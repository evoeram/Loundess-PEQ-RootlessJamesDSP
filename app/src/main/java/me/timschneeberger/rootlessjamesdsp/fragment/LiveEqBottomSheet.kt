package me.timschneeberger.rootlessjamesdsp.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.google.android.material.slider.Slider
import me.timschneeberger.rootlessjamesdsp.databinding.FragmentLiveEqBinding
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqBand
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqBandList
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqChannel
import timber.log.Timber
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.*
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Bottom sheet для интерактивной настройки полос параметрического эквалайзера.
 *
 * Позволяет выбирать полосу через чипы и регулировать частоту, усиление и Q
 * с помощью слайдеров. Изменения применяются в реальном времени:
 * - [onLiveUpdate] вызывается при каждом сдвиге слайдера (визуальная обратная связь);
 * - [onCommit] вызывается при отпускании слайдера (запись в источник + движок).
 *
 * Частота и Q отображаются в логарифмической шкале на слайдерах 0..100.
 */
class LiveEqBottomSheet : BottomSheetDialogFragment() {

    private lateinit var binding: FragmentLiveEqBinding

    // Исходный список полос; изменения записываются в него только при отпускании слайдера
    private lateinit var source: ParametricEqBandList
    // Рабочая копия, которую редактируют слайдеры (чтобы не сохранять на каждый шаг)
    private val bands = ParametricEqBandList()
    private var preampDb: Double = 0.0

    // Вызывается при каждом изменении слайдера для мгновенной визуальной обратной связи
    private var onLiveUpdate: ((ParametricEqBandList) -> Unit)? = null

    // Вызывается после записи изменённых полос обратно в список редактора
    private var onCommit: (() -> Unit)? = null

    private var selectedIndex: Int = -1

    // Защита от циклов обратной связи при программмной установке значений слайдеров
    private var isUpdatingSliders = false

    private val df = DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.ENGLISH))

    // ── Преобразование логарифмической шкалы слайдеров ──────────────────────

    /** Частота (Гц) → позиция слайдера 0..100 (логарифмическая шкала 20..20000 Гц) */
    private fun freqToSlider(hz: Double): Float =
        ((ln(hz) - LN_FREQ_MIN) / (LN_FREQ_MAX - LN_FREQ_MIN) * 100.0)
            .toFloat().coerceIn(0f, 100f)

    /** Позиция слайдера 0..100 → частота (Гц), логарифмическая шкала */
    private fun sliderToFreq(pos: Float): Double =
        exp(LN_FREQ_MIN + pos / 100.0 * (LN_FREQ_MAX - LN_FREQ_MIN))

    /** Q → позиция слайдера 0..100 (логарифмическая шкала 0.1..30) */
    private fun qToSlider(q: Double): Float =
        ((ln(q.coerceAtLeast(0.001)) - LN_Q_MIN) / (LN_Q_MAX - LN_Q_MIN) * 100.0)
            .toFloat().coerceIn(0f, 100f)

    /** Позиция слайдера 0..100 → Q, логарифмическая шкала */
    private fun sliderToQ(pos: Float): Double =
        exp(LN_Q_MIN + pos / 100.0 * (LN_Q_MAX - LN_Q_MIN))

    // ── Жизненный цикл фрагмента ────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Список полос и колбэки не восстанавливаются при пересоздании системой
        if (!::source.isInitialized) {
            dismissAllowingStateLoss()
            return
        }
        bands.clear()
        bands.addAll(source)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentLiveEqBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (!::source.isInitialized)
            return

        // Первичная отрисовка поверхности АЧХ
        binding.liveEqSurface.setBands(bands, preampDb)

        // Построение чипов — по одному на полосу
        buildBandChips()

        // Форматтеры для всплывающих меток слайдеров
        binding.freqSlider.setLabelFormatter { pos ->
            "${sliderToFreq(pos).roundToInt()} Hz"
        }
        binding.gainSlider.setLabelFormatter { pos ->
            "${df.format(pos)} dB"
        }
        binding.qSlider.setLabelFormatter { pos ->
            df.format(sliderToQ(pos))
        }

        // Слушатели изменений слайдеров — обновляют полосу и пушят визуальную обратную связь
        val freqChangeListener = Slider.OnChangeListener { _, value, fromUser ->
            if (!fromUser || isUpdatingSliders || selectedIndex < 0) return@OnChangeListener
            val band = bands[selectedIndex]
            val newFreq = sliderToFreq(value)
            Timber.d("LiveEQ freqSlider: pos=$value → ${newFreq.roundToInt()} Hz")
            bands[selectedIndex] = ParametricEqBand(newFreq, band.gain, band.q, band.filterType, band.channel, band.uuid)
            refreshChipLabel(selectedIndex)
            onLiveUpdate?.invoke(bands)
            binding.liveEqSurface.setBands(bands, preampDb)
        }
        val gainChangeListener = Slider.OnChangeListener { _, value, fromUser ->
            if (!fromUser || isUpdatingSliders || selectedIndex < 0) return@OnChangeListener
            val band = bands[selectedIndex]
            Timber.d("LiveEQ gainSlider: ${value} dB")
            bands[selectedIndex] = ParametricEqBand(band.frequency, value.toDouble(), band.q, band.filterType, band.channel, band.uuid)
            onLiveUpdate?.invoke(bands)
            binding.liveEqSurface.setBands(bands, preampDb)
        }
        val qChangeListener = Slider.OnChangeListener { _, value, fromUser ->
            if (!fromUser || isUpdatingSliders || selectedIndex < 0) return@OnChangeListener
            val band = bands[selectedIndex]
            val newQ = sliderToQ(value).coerceAtLeast(0.1)
            Timber.d("LiveEQ qSlider: pos=$value → Q=$newQ")
            bands[selectedIndex] = ParametricEqBand(band.frequency, band.gain, newQ, band.filterType, band.channel, band.uuid)
            onLiveUpdate?.invoke(bands)
            binding.liveEqSurface.setBands(bands, preampDb)
        }

        binding.freqSlider.addOnChangeListener(freqChangeListener)
        binding.gainSlider.addOnChangeListener(gainChangeListener)
        binding.qSlider.addOnChangeListener(qChangeListener)

        // Запись обратно в редактор только когда пользователь отпускает палец
        val touchListener = object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                commitChanges()
            }
        }
        binding.freqSlider.addOnSliderTouchListener(touchListener)
        binding.gainSlider.addOnSliderTouchListener(touchListener)
        binding.qSlider.addOnSliderTouchListener(touchListener)

        // Переключатель канала: L / L+R / R
        binding.channelLeft.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !isUpdatingSliders && selectedIndex >= 0) {
                updateChannel(ParametricEqChannel.LEFT)
            }
        }
        binding.channelBoth.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !isUpdatingSliders && selectedIndex >= 0) {
                updateChannel(ParametricEqChannel.LEFT_RIGHT)
            }
        }
        binding.channelRight.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !isUpdatingSliders && selectedIndex >= 0) {
                updateChannel(ParametricEqChannel.RIGHT)
            }
        }
    }

    override fun onDestroyView() {
        // Покрывает свайп-закрытие в середине перетаскивания
        if (::source.isInitialized)
            commitChanges()
        super.onDestroyView()
    }

    /**
     * Записывает изменённые полосы обратно в [source] и вызывает [onCommit].
     * Сравнение через equals (uuid исключён) — сохраняются только реальные изменения параметров.
     */
    private fun commitChanges() {
        var changed = false
        for (i in bands.indices) {
            if (i < source.size && source[i] != bands[i]) {
                source[i] = bands[i]
                changed = true
            }
        }
        if (changed)
            onCommit?.invoke()
    }

    // ── Чипы выбора полосы ──────────────────────────────────────────────────

    /** Создаёт по одному чипу на каждую полосу */
    private fun buildBandChips() {
        binding.bandChips.removeAllViews()
        for ((i, band) in bands.withIndex()) {
            val chip = Chip(requireContext()).apply {
                id = View.generateViewId()
                text = chipLabel(i, band)
                isCheckable = true
                isCheckedIconVisible = false
            }
            chip.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selectBand(i)
            }
            binding.bandChips.addView(chip)
        }
    }

    /** Текстовая метка чипа: «N: тип частотаHz» */
    private fun chipLabel(index: Int, band: ParametricEqBand): String {
        val freqStr = if (band.frequency >= 1000.0)
            "${df.format(band.frequency / 1000.0)}k"
        else
            "${band.frequency.roundToInt()}"
        return "${index + 1}: ${band.filterType.displayLabel} ${freqStr}Hz"
    }

    /** Обновляет текст чипа после изменения частоты */
    private fun refreshChipLabel(index: Int) {
        val chip = binding.bandChips.getChildAt(index) as? Chip ?: return
        chip.text = chipLabel(index, bands[index])
    }

    /**
     * Выбирает полосу для редактирования: устанавливает значения слайдеров
     * и переключателя канала из параметров выбранной полосы.
     */
    private fun selectBand(index: Int) {
        selectedIndex = index
        val band = bands[index]

        isUpdatingSliders = true
        binding.freqSlider.value = freqToSlider(band.frequency)
        // Слайдер отклоняет значения не на шаге 0.1 dB (например импортированные 2.25 dB)
        binding.gainSlider.value = ((band.gain * 10).roundToInt() / 10f).coerceIn(-30f, 30f)
        binding.qSlider.value = qToSlider(band.q)
        // Устанавливаем переключатель канала
        when (band.channel) {
            ParametricEqChannel.LEFT -> binding.channelLeft.isChecked = true
            ParametricEqChannel.LEFT_RIGHT -> binding.channelBoth.isChecked = true
            ParametricEqChannel.RIGHT -> binding.channelRight.isChecked = true
        }
        isUpdatingSliders = false

        binding.liveEqHint.isVisible = false
        binding.slidersSection.isVisible = true
    }

    /** Обновляет канал выбранной полосы и применяет изменения в реальном времени */
    private fun updateChannel(channel: ParametricEqChannel) {
        val band = bands[selectedIndex]
        Timber.d("LiveEQ channelChips: ${band.channel} → $channel")
        bands[selectedIndex] = ParametricEqBand(band.frequency, band.gain, band.q, band.filterType, channel, band.uuid)
        onLiveUpdate?.invoke(bands)
        binding.liveEqSurface.setBands(bands, preampDb)
        commitChanges()
    }

    // ── Публичный API ────────────────────────────────────────────────────────

    companion object {
        // Логарифмические границы для слайдеров
        private val LN_FREQ_MIN = ln(20.0)
        private val LN_FREQ_MAX = ln(20000.0)
        private val LN_Q_MIN = ln(0.1)
        private val LN_Q_MAX = ln(30.0)

        /**
         * Создаёт экземпляр [LiveEqBottomSheet].
         *
         * @param bands список полос эквалайзера (редактируется на месте при отпускании слайдера)
         * @param preampDb текущее усиление предусилителя для отрисовки АЧХ
         * @param onLiveUpdate колбэк при каждом сдвиге слайдера (визуальная обратная связь)
         * @param onCommit колбэк после записи изменённых полос обратно в [bands]
         */
        fun newInstance(
            bands: ParametricEqBandList,
            preampDb: Double,
            onLiveUpdate: (ParametricEqBandList) -> Unit,
            onCommit: () -> Unit,
        ): LiveEqBottomSheet {
            return LiveEqBottomSheet().apply {
                this.source = bands
                this.preampDb = preampDb
                this.onLiveUpdate = onLiveUpdate
                this.onCommit = onCommit
            }
        }
    }
}
