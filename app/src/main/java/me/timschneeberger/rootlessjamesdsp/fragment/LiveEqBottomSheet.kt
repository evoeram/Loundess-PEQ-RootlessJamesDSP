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
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqFilterType
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
    // Колбэк для обновления preamp в реальном времени
    private var onPreampUpdate: ((Double) -> Unit)? = null
    // Auto Preamp: автоматически вычислять preamp = -max(positive gain)
    private var autoPreampEnabled: Boolean = false

    // Вызывается при каждом изменении слайдера для мгновенной визуальной обратной связи
    private var onLiveUpdate: ((ParametricEqBandList) -> Unit)? = null

    // Вызывается после записи изменённых полос обратно в список редактора
    private var onCommit: (() -> Unit)? = null

    // ── Оверлеи для Squig Live: measurement (L, R), target, corrected FR ──
    // Эти данные передаются из SquigLiveFragment для отображения на графике Live EQ.
    // В PEQ-редакторе они null — график показывает только filter response.
    private var overlayMeasurementFreqs: FloatArray? = null
    private var overlayMeasurementSpl: FloatArray? = null
    private var overlayMeasurementRFreqs: FloatArray? = null
    private var overlayMeasurementRSpl: FloatArray? = null
    private var overlayTargetFreqs: FloatArray? = null
    private var overlayTargetSpl: FloatArray? = null
    // Колбэк для обновления corrected FR в реальном времени (Squig Live)
    private var onCorrectedUpdate: ((ParametricEqBandList, Double) -> Unit)? = null

    private var selectedIndex: Int = -1

    // Защита от циклов обратной связи при программмной установке значений слайдеров
    private var isUpdatingSliders = false
    // Защита от циклов при программмной установке значений полей ввода
    private var isUpdatingInputs = false

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
        applyOverlays()

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
            // Синхронизация поля ввода
            isUpdatingInputs = true
            binding.freqInput.setText(newFreq.roundToInt().toString())
            isUpdatingInputs = false
            onLiveUpdate?.invoke(bands)
            onCorrectedUpdate?.invoke(bands, preampDb)
            binding.liveEqSurface.setBands(bands, preampDb)
            applyOverlays()
        }
        val gainChangeListener = Slider.OnChangeListener { _, value, fromUser ->
            if (!fromUser || isUpdatingSliders || selectedIndex < 0) return@OnChangeListener
            val band = bands[selectedIndex]
            Timber.d("LiveEQ gainSlider: ${value} dB")
            bands[selectedIndex] = ParametricEqBand(band.frequency, value.toDouble(), band.q, band.filterType, band.channel, band.uuid)
            // Синхронизация поля ввода
            isUpdatingInputs = true
            binding.gainInput.setText(df.format(value.toDouble()))
            isUpdatingInputs = false
            // Auto Preamp: пересчитать preamp при изменении gain
            if (autoPreampEnabled) applyAutoPreamp()
            onLiveUpdate?.invoke(bands)
            onCorrectedUpdate?.invoke(bands, preampDb)
            binding.liveEqSurface.setBands(bands, preampDb)
            applyOverlays()
        }
        val qChangeListener = Slider.OnChangeListener { _, value, fromUser ->
            if (!fromUser || isUpdatingSliders || selectedIndex < 0) return@OnChangeListener
            val band = bands[selectedIndex]
            val newQ = sliderToQ(value).coerceAtLeast(0.1)
            Timber.d("LiveEQ qSlider: pos=$value → Q=$newQ")
            bands[selectedIndex] = ParametricEqBand(band.frequency, band.gain, newQ, band.filterType, band.channel, band.uuid)
            // Синхронизация поля ввода
            isUpdatingInputs = true
            binding.qInput.setText(df.format(newQ))
            isUpdatingInputs = false
            onLiveUpdate?.invoke(bands)
            onCorrectedUpdate?.invoke(bands, preampDb)
            binding.liveEqSurface.setBands(bands, preampDb)
            applyOverlays()
        }

        binding.freqSlider.addOnChangeListener(freqChangeListener)
        binding.gainSlider.addOnChangeListener(gainChangeListener)
        binding.qSlider.addOnChangeListener(qChangeListener)

        // Preamp слайдер: меняет preamp в реальном времени
        binding.preampSlider.value = preampDb.toFloat().coerceIn(-30f, 30f)
        binding.preampSlider.setLabelFormatter { pos ->
            "${df.format(pos)} dB"
        }
        binding.preampSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || isUpdatingSliders) return@addOnChangeListener
            // Если AutoPreamp включён — слайдер preamp отключён, игнорируем
            if (autoPreampEnabled) return@addOnChangeListener
            preampDb = value.toDouble()
            Timber.d("LiveEQ preampSlider: $preampDb dB")
            // Синхронизация поля ввода
            isUpdatingInputs = true
            binding.preampInput.setText(df.format(preampDb))
            isUpdatingInputs = false
            onPreampUpdate?.invoke(preampDb)
            onLiveUpdate?.invoke(bands)
            onCorrectedUpdate?.invoke(bands, preampDb)
            binding.liveEqSurface.setBands(bands, preampDb)
            applyOverlays()
        }

        // Auto Preamp switch: при включении — автоматически вычислять preamp = -max(positive gain)
        binding.autoPreampSwitch.setOnCheckedChangeListener { _, isChecked ->
            autoPreampEnabled = isChecked
            Timber.d("LiveEQ autoPreamp: $isChecked")
            // Отключаем ручной preamp слайдер и поле ввода когда AutoPreamp включён
            binding.preampSlider.isEnabled = !isChecked
            binding.preampInput.isEnabled = !isChecked
            if (isChecked) {
                applyAutoPreamp()
            }
        }

        // ── Поля точного ввода: freq, gain, q, preamp ──
        // Частота: ввод в Гц, преобразуется в позицию слайдера
        binding.freqInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                val hz = binding.freqInput.text?.toString()?.toDoubleOrNull()
                if (hz != null && hz in 20.0..20000.0 && selectedIndex >= 0) {
                    val band = bands[selectedIndex]
                    bands[selectedIndex] = ParametricEqBand(hz, band.gain, band.q, band.filterType, band.channel, band.uuid)
                    isUpdatingSliders = true
                    binding.freqSlider.value = freqToSlider(hz)
                    isUpdatingSliders = false
                    refreshChipLabel(selectedIndex)
                    onLiveUpdate?.invoke(bands)
                    onCorrectedUpdate?.invoke(bands, preampDb)
                    binding.liveEqSurface.setBands(bands, preampDb)
                    applyOverlays()
                    commitChanges()
                }
                true
            } else false
        }
        // Gain: ввод в dB
        binding.gainInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                val db = binding.gainInput.text?.toString()?.toDoubleOrNull()
                if (db != null && db in -30.0..30.0 && selectedIndex >= 0) {
                    val band = bands[selectedIndex]
                    bands[selectedIndex] = ParametricEqBand(band.frequency, db, band.q, band.filterType, band.channel, band.uuid)
                    isUpdatingSliders = true
                    binding.gainSlider.value = db.toFloat()
                    isUpdatingSliders = false
                    if (autoPreampEnabled) applyAutoPreamp()
                    onLiveUpdate?.invoke(bands)
                    onCorrectedUpdate?.invoke(bands, preampDb)
                    binding.liveEqSurface.setBands(bands, preampDb)
                    applyOverlays()
                    commitChanges()
                }
                true
            } else false
        }
        // Q: ввод точного значения Q
        binding.qInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                val qVal = binding.qInput.text?.toString()?.toDoubleOrNull()
                if (qVal != null && qVal in 0.1..30.0 && selectedIndex >= 0) {
                    val band = bands[selectedIndex]
                    bands[selectedIndex] = ParametricEqBand(band.frequency, band.gain, qVal, band.filterType, band.channel, band.uuid)
                    isUpdatingSliders = true
                    binding.qSlider.value = qToSlider(qVal)
                    isUpdatingSliders = false
                    onLiveUpdate?.invoke(bands)
                    onCorrectedUpdate?.invoke(bands, preampDb)
                    binding.liveEqSurface.setBands(bands, preampDb)
                    applyOverlays()
                    commitChanges()
                }
                true
            } else false
        }
        // Preamp: ввод в dB (отключён если AutoPreamp включён)
        binding.preampInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                if (autoPreampEnabled) return@setOnEditorActionListener true
                val db = binding.preampInput.text?.toString()?.toDoubleOrNull()
                if (db != null && db in -30.0..30.0) {
                    preampDb = db
                    isUpdatingSliders = true
                    binding.preampSlider.value = db.toFloat()
                    isUpdatingSliders = false
                    onPreampUpdate?.invoke(preampDb)
                    onLiveUpdate?.invoke(bands)
                    onCorrectedUpdate?.invoke(bands, preampDb)
                    binding.liveEqSurface.setBands(bands, preampDb)
                    applyOverlays()
                    commitChanges()
                }
                true
            } else false
        }

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
        binding.preampSlider.addOnSliderTouchListener(touchListener)

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

        // Переключатель типа фильтра
        binding.filterTypePeaking.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !isUpdatingSliders && selectedIndex >= 0) {
                updateFilterType(ParametricEqFilterType.PEAKING)
            }
        }
        binding.filterTypeLowShelf.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !isUpdatingSliders && selectedIndex >= 0) {
                updateFilterType(ParametricEqFilterType.LOW_SHELF)
            }
        }
        binding.filterTypeHighShelf.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !isUpdatingSliders && selectedIndex >= 0) {
                updateFilterType(ParametricEqFilterType.HIGH_SHELF)
            }
        }
        binding.filterTypeLowPass.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !isUpdatingSliders && selectedIndex >= 0) {
                updateFilterType(ParametricEqFilterType.LOW_PASS)
            }
        }
        binding.filterTypeHighPass.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !isUpdatingSliders && selectedIndex >= 0) {
                updateFilterType(ParametricEqFilterType.HIGH_PASS)
            }
        }
        binding.filterTypeBandPass.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !isUpdatingSliders && selectedIndex >= 0) {
                updateFilterType(ParametricEqFilterType.BAND_PASS)
            }
        }
        binding.filterTypeNotch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !isUpdatingSliders && selectedIndex >= 0) {
                updateFilterType(ParametricEqFilterType.NOTCH)
            }
        }

        // Кнопка Add: добавляет новую полосу (PK, 1000 Hz, 0 dB, Q=1.0)
        binding.addBandButton.setOnClickListener {
            if (bands.size >= MAX_BANDS) return@setOnClickListener
            val newBand = ParametricEqBand(1000.0, 0.0, 1.0, ParametricEqFilterType.PEAKING, ParametricEqChannel.LEFT_RIGHT)
            bands.add(newBand)
            Timber.d("LiveEQ addBand: добавлена полоса ${bands.size}")
            rebuildAfterBandChange()
            // Выбираем новую полосу
            selectBand(bands.size - 1)
            binding.bandChips.getChildAt(bands.size - 1)?.let { (it as? Chip)?.isChecked = true }
        }

        // Кнопка Delete: удаляет выбранную полосу
        binding.deleteBandButton.setOnClickListener {
            if (selectedIndex < 0 || selectedIndex >= bands.size) return@setOnClickListener
            Timber.d("LiveEQ deleteBand: удалена полоса $selectedIndex")
            bands.removeAt(selectedIndex)
            selectedIndex = -1
            rebuildAfterBandChange()
            // Сброс UI
            binding.liveEqHint.isVisible = true
            binding.slidersSection.isVisible = false
            binding.deleteBandButton.isEnabled = false
        }

        // Обновить состояние кнопок
        updateButtonStates()
    }

    override fun onDestroyView() {
        // Покрывает свайп-закрытие в середине перетаскивания
        if (::source.isInitialized)
            commitChanges()
        super.onDestroyView()
    }

    /**
     * Записывает изменённые полосы обратно в [source] и вызывает [onCommit].
     * Полная синхронизация: добавление/удаление полос также отражается в source.
     */
    private fun commitChanges() {
        var changed = false
        // Если количество полос изменилось — полная пересинхронизация
        if (source.size != bands.size) {
            source.clear()
            source.addAll(bands)
            changed = true
        } else {
            for (i in bands.indices) {
                if (source[i] != bands[i]) {
                    source[i] = bands[i]
                    changed = true
                }
            }
        }
        if (changed)
            onCommit?.invoke()
    }

    // ── Чипы выбора полосы ──────────────────────────────────────────────────

    /**
     * Сортировка полос: сначала LS (low-shelf), потом PK (peaking) по частоте, потом HS (high-shelf).
     * Вызывается перед buildBandChips и после add/delete.
     */
    private fun sortBands() {
        val sorted = bands.sortedWith(compareBy(
            { band ->
                when (band.filterType) {
                    ParametricEqFilterType.LOW_SHELF -> 0
                    ParametricEqFilterType.PEAKING -> 1
                    ParametricEqFilterType.HIGH_SHELF -> 2
                    else -> 3 // Прочие типы — в конце
                }
            },
            { it.frequency }
        ))
        bands.clear()
        bands.addAll(sorted)
    }

    /** Создаёт по одному чипу на каждую полосу */
    private fun buildBandChips() {
        sortBands()
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
        binding.gainSlider.value = band.gain.toFloat().coerceIn(-30f, 30f)
        binding.qSlider.value = qToSlider(band.q)
        // Синхронизация полей точного ввода
        isUpdatingInputs = true
        binding.freqInput.setText(band.frequency.roundToInt().toString())
        binding.gainInput.setText(df.format(band.gain))
        binding.qInput.setText(df.format(band.q))
        binding.preampInput.setText(df.format(preampDb))
        isUpdatingInputs = false
        // Устанавливаем переключатель канала
        when (band.channel) {
            ParametricEqChannel.LEFT -> binding.channelLeft.isChecked = true
            ParametricEqChannel.LEFT_RIGHT -> binding.channelBoth.isChecked = true
            ParametricEqChannel.RIGHT -> binding.channelRight.isChecked = true
        }
        // Устанавливаем переключатель типа фильтра
        when (band.filterType) {
            ParametricEqFilterType.PEAKING -> binding.filterTypePeaking.isChecked = true
            ParametricEqFilterType.LOW_SHELF -> binding.filterTypeLowShelf.isChecked = true
            ParametricEqFilterType.HIGH_SHELF -> binding.filterTypeHighShelf.isChecked = true
            ParametricEqFilterType.LOW_PASS -> binding.filterTypeLowPass.isChecked = true
            ParametricEqFilterType.HIGH_PASS -> binding.filterTypeHighPass.isChecked = true
            ParametricEqFilterType.BAND_PASS -> binding.filterTypeBandPass.isChecked = true
            ParametricEqFilterType.NOTCH -> binding.filterTypeNotch.isChecked = true
            ParametricEqFilterType.ALL_PASS, ParametricEqFilterType.PREAMP -> binding.filterTypePeaking.isChecked = true
        }
        isUpdatingSliders = false

        binding.liveEqHint.isVisible = false
        binding.slidersSection.isVisible = true
        updateButtonStates()
    }

    /** Обновляет канал выбранной полосы и применяет изменения в реальном времени */
    private fun updateChannel(channel: ParametricEqChannel) {
        val band = bands[selectedIndex]
        Timber.d("LiveEQ channelChips: ${band.channel} → $channel")
        bands[selectedIndex] = ParametricEqBand(band.frequency, band.gain, band.q, band.filterType, channel, band.uuid)
        onLiveUpdate?.invoke(bands)
        onCorrectedUpdate?.invoke(bands, preampDb)
        binding.liveEqSurface.setBands(bands, preampDb)
        applyOverlays()
        commitChanges()
    }

    /** Обновляет тип фильтра выбранной полосы и применяет изменения в реальном времени */
    private fun updateFilterType(filterType: ParametricEqFilterType) {
        val band = bands[selectedIndex]
        Timber.d("LiveEQ filterType: ${band.filterType} → $filterType")
        bands[selectedIndex] = ParametricEqBand(band.frequency, band.gain, band.q, filterType, band.channel, band.uuid)
        refreshChipLabel(selectedIndex)
        onLiveUpdate?.invoke(bands)
        onCorrectedUpdate?.invoke(bands, preampDb)
        binding.liveEqSurface.setBands(bands, preampDb)
        applyOverlays()
        commitChanges()
    }

    // ── Публичный API ────────────────────────────────────────────────────────

    /**
     * Применяет оверлеи (measurement L/R, target) на поверхность Live EQ.
     * Вызывается после каждого setBands для обновления оверлеев.
     * Также вычисляет и отображает corrected FR, если measurement доступен.
     */
    private fun applyOverlays() {
        val surface = binding.liveEqSurface
        // Measurement L
        val mFreqs = overlayMeasurementFreqs
        val mSpl = overlayMeasurementSpl
        if (mFreqs != null && mSpl != null && mFreqs.isNotEmpty()) {
            surface.setMeasurementData(mFreqs, mSpl)
        } else {
            surface.clearMeasurementData()
        }
        // Measurement R
        val mRFreqs = overlayMeasurementRFreqs
        val mRSpl = overlayMeasurementRSpl
        if (mRFreqs != null && mRSpl != null && mRFreqs.isNotEmpty()) {
            surface.setMeasurementDataR(mRFreqs, mRSpl)
        } else {
            surface.clearMeasurementDataR()
        }
        // Target — сдвигаем на preamp для совпадения с corrected FR
        val tFreqs = overlayTargetFreqs
        val tSpl = overlayTargetSpl
        if (tFreqs != null && tSpl != null && tFreqs.isNotEmpty()) {
            val shiftedSpl = FloatArray(tSpl.size) { i -> (tSpl[i] + preampDb).toFloat() }
            surface.setTargetCurve(tFreqs, shiftedSpl)
        } else {
            surface.clearTargetCurve()
        }
        // Corrected FR: вычисляем, если есть measurement
        if (mFreqs != null && mSpl != null && mFreqs.isNotEmpty() && bands.isNotEmpty()) {
            val calculator = me.timschneeberger.rootlessjamesdsp.utils.ParametricEqResponseCalculator()
            val filterResponse = calculator.compute(bands.toList(), preampDb)
            // corrected = measurement + filter response + preamp
            val correctedSpl = FloatArray(mFreqs.size) { i ->
                val f = mFreqs[i].toDouble()
                // Интерполяция filter response на частотах measurement
                val evalFreqs = filterResponse.frequencies
                val evalResp = filterResponse.leftResponseDb
                var lo = 0
                var hi = evalFreqs.size - 1
                while (hi - lo > 1) {
                    val mid = (lo + hi) / 2
                    if (evalFreqs[mid] <= f) lo = mid else hi = mid
                }
                val t = if (evalFreqs[hi] != evalFreqs[lo])
                    (f - evalFreqs[lo]) / (evalFreqs[hi] - evalFreqs[lo])
                else 0.0
                val filterAtF = evalResp[lo] + t * (evalResp[hi] - evalResp[lo])
                (mSpl[i] + filterAtF + preampDb).toFloat()
            }
            surface.setCorrectedFR(mFreqs, correctedSpl)
        } else if (mFreqs != null && mSpl != null && mFreqs.isNotEmpty()) {
            // No bands — corrected = measurement + preamp only
            val correctedSpl = FloatArray(mFreqs.size) { i ->
                (mSpl[i] + preampDb).toFloat()
            }
            surface.setCorrectedFR(mFreqs, correctedSpl)
        } else {
            surface.clearCorrectedFR()
        }
    }

    /**
     * Перестраивает чипы, график и коммитит изменения после добавления/удаления полосы.
     */
    private fun rebuildAfterBandChange() {
        buildBandChips()
        // Auto Preamp: пересчитать preamp после добавления/удаления полосы
        if (autoPreampEnabled) applyAutoPreamp()
        binding.liveEqSurface.setBands(bands, preampDb)
        applyOverlays()
        onLiveUpdate?.invoke(bands)
        onCorrectedUpdate?.invoke(bands, preampDb)
        commitChanges()
        updateButtonStates()
    }

    /**
     * Auto Preamp: вычисляет preamp из реальной суммарной АЧХ каскада фильтров.
     *
     * Берёт -max(leftResponse, rightResponse) по всем точкам частот, а не
     * -max(gain отдельной полосы). Это корректно для пограничных случаев:
     * - несколько положительных полос складываются в пик выше любой отдельной
     * - shelf с большим |gain| и низкой Q имеет пик не на f0, а в широкой зоне
     * - отрицательный shelf (gain < 0) может давать положительный горб выше f0
     *
     * Если пик ≤ 0 dB — preamp = 0.
     * Обновляет preampDb, слайдер, колбэки и график.
     */
    private fun applyAutoPreamp() {
        val newPreamp = if (bands.isEmpty()) {
            0.0
        } else {
            val calculator = me.timschneeberger.rootlessjamesdsp.utils.ParametricEqResponseCalculator()
            val filterResponse = calculator.compute(bands.toList(), 0.0)
            val maxPeak = maxOf(
                filterResponse.leftResponseDb.maxOrNull() ?: 0.0,
                filterResponse.rightResponseDb.maxOrNull() ?: 0.0
            )
            if (maxPeak > 0.0) -maxPeak else 0.0
        }
        if (newPreamp == preampDb) return
        preampDb = newPreamp
        Timber.d("LiveEQ autoPreamp computed: $preampDb dB (from real cascaded response peak)")
        // Обновляем слайдер (без триггера слушателя)
        isUpdatingSliders = true
        binding.preampSlider.value = preampDb.toFloat().coerceIn(-30f, 30f)
        isUpdatingSliders = false
        // Обновляем поле ввода preamp
        isUpdatingInputs = true
        binding.preampInput.setText(df.format(preampDb))
        isUpdatingInputs = false
        // Уведомляем колбэки
        onPreampUpdate?.invoke(preampDb)
        onLiveUpdate?.invoke(bands)
        onCorrectedUpdate?.invoke(bands, preampDb)
        binding.liveEqSurface.setBands(bands, preampDb)
        applyOverlays()
    }

    /** Обновляет enabled-состояние кнопок Add/Delete */
    private fun updateButtonStates() {
        binding.addBandButton.isEnabled = bands.size < MAX_BANDS
        binding.deleteBandButton.isEnabled = selectedIndex >= 0 && bands.size > 0
    }

    // ── Добавление/удаление полос ──────────────────────────────────────────

    companion object {
        // Логарифмические границы для слайдеров
        private val LN_FREQ_MIN = ln(20.0)
        private val LN_FREQ_MAX = ln(20000.0)
        private val LN_Q_MIN = ln(0.1)
        private val LN_Q_MAX = ln(30.0)
        private const val MAX_BANDS = 64

        /**
         * Создаёт экземпляр [LiveEqBottomSheet].
         *
         * @param bands список полос эквалайзера (редактируется на месте при отпускании слайдера)
         * @param preampDb текущее усиление предусилителя для отрисовки АЧХ
         * @param onLiveUpdate колбэк при каждом сдвиге слайдера (визуальная обратная связь)
         * @param onCommit колбэк после записи изменённых полос обратно в [bands]
         * @param onPreampUpdate колбэк при изменении preamp слайдера
         * @param overlayMeasurementFreqs частоты измерения L (для оверлея на графике, Squig Live)
         * @param overlayMeasurementSpl SPL измерения L
         * @param overlayMeasurementRFreqs частоты измерения R
         * @param overlayMeasurementRSpl SPL измерения R
         * @param overlayTargetFreqs частоты целевой кривой
         * @param overlayTargetSpl SPL целевой кривой
         * @param onCorrectedUpdate колбэк для обновления corrected FR (Squig Live)
         */
        fun newInstance(
            bands: ParametricEqBandList,
            preampDb: Double,
            onLiveUpdate: (ParametricEqBandList) -> Unit,
            onCommit: () -> Unit,
            onPreampUpdate: ((Double) -> Unit)? = null,
            overlayMeasurementFreqs: FloatArray? = null,
            overlayMeasurementSpl: FloatArray? = null,
            overlayMeasurementRFreqs: FloatArray? = null,
            overlayMeasurementRSpl: FloatArray? = null,
            overlayTargetFreqs: FloatArray? = null,
            overlayTargetSpl: FloatArray? = null,
            onCorrectedUpdate: ((ParametricEqBandList, Double) -> Unit)? = null,
        ): LiveEqBottomSheet {
            return LiveEqBottomSheet().apply {
                this.source = bands
                this.preampDb = preampDb
                this.onLiveUpdate = onLiveUpdate
                this.onCommit = onCommit
                this.onPreampUpdate = onPreampUpdate
                this.overlayMeasurementFreqs = overlayMeasurementFreqs
                this.overlayMeasurementSpl = overlayMeasurementSpl
                this.overlayMeasurementRFreqs = overlayMeasurementRFreqs
                this.overlayMeasurementRSpl = overlayMeasurementRSpl
                this.overlayTargetFreqs = overlayTargetFreqs
                this.overlayTargetSpl = overlayTargetSpl
                this.onCorrectedUpdate = onCorrectedUpdate
            }
        }
    }
}
