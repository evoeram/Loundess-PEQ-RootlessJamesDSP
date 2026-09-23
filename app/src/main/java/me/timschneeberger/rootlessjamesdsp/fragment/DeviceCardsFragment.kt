package me.timschneeberger.rootlessjamesdsp.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.adapter.DeviceCardAdapter
import me.timschneeberger.rootlessjamesdsp.databinding.FragmentDeviceCardsBinding
import me.timschneeberger.rootlessjamesdsp.model.DeviceCard
import me.timschneeberger.rootlessjamesdsp.utils.DevicePresetManager
import me.timschneeberger.rootlessjamesdsp.utils.PresetOverlayManager
import me.timschneeberger.rootlessjamesdsp.utils.RoutingObserver
import org.koin.android.ext.android.inject
import timber.log.Timber

/**
 * Фрагмент-раздел "Устройства" на главном экране.
 *
 * Заменяет собой DeviceProfilesCardFragment.
 * Показывает список всех известных устройств в виде карточек.
 * Активное устройство подсвечивается красной линией сбоку.
 * Тап на карточку открывает диалог выбора пресета.
 *
 * Список устройств и их preset-assignment сохраняются постоянно в SharedPreferences.
 * При перезапуске приложения все ранее выбранные пресеты восстанавливаются.
 */
class DeviceCardsFragment : Fragment(), RoutingObserver.RoutingChangedCallback {

    private val routingObserver: RoutingObserver by inject()
    private val devicePresetManager: DevicePresetManager by inject()
    private val presetOverlayManager: PresetOverlayManager by inject()

    private var _binding: FragmentDeviceCardsBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: DeviceCardAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentDeviceCardsBinding.inflate(layoutInflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = DeviceCardAdapter { deviceCard -> onDeviceCardClick(deviceCard) }
        binding.devicesRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.devicesRecycler.adapter = adapter

        // Отключаем change-анимации (ITEM_ANIMATOR_CHANGE_ANIMATIONS).
        // При смене активного устройства карточка мигрирует между типами
        // (Active → Other и наоборот). Change-анимация в этом случае
        // вызывает краш "Two different ViewHolders have the same change ID".
        (binding.devicesRecycler.itemAnimator as? androidx.recyclerview.widget.DefaultItemAnimator)
            ?.supportsChangeAnimations = false

        routingObserver.registerOnRoutingChangeListener(this)

        // Первичная загрузка сохранённых устройств
        refreshList()
    }

    override fun onDestroyView() {
        routingObserver.unregisterOnRoutingChangeListener(this)
        _binding = null
        super.onDestroyView()
    }

    override fun onRoutingDeviceChanged(device: RoutingObserver.Device?) {
        if (device != null) {
            // Регистрируем устройство постоянно в DevicePresetManager
            devicePresetManager.registerDevice(device)
            Timber.d("Device registered: ${device.id}")
        }
        refreshList()
    }

    /**
     * Обновить список карточек устройств.
     * Источник данных — DevicePresetManager.getAllDevices() (постоянное хранилище).
     * Активное устройство берётся из routingObserver.currentDevice.
     *
     * Адаптер сам разбивает список на активные (всегда видны) и неактивные
     * (свернуты под заголовком «Другие устройства» по умолчанию).
     */
    private fun refreshList() {
        val activeDevice = routingObserver.currentDevice

        // Регистрируем активное устройство, если ещё не сохранено
        activeDevice?.let { devicePresetManager.registerDevice(it) }

        // Загружаем все сохранённые устройства из постоянного хранилища
        val savedDevices = devicePresetManager.getAllDevices()

        // Строим карточки
        val cards = savedDevices.map { info ->
            // Определяем тип устройства из строки group
            val group = try {
                RoutingObserver.DeviceGroup.valueOf(info.group)
            } catch (e: IllegalArgumentException) {
                RoutingObserver.DeviceGroup.OTHER
            }

            DeviceCard(
                name = info.name,
                id = info.id,
                group = group,
                uuid = info.uuid,
                presetAssignment = devicePresetManager.getPresetAssignment(info.id),
                isActive = activeDevice != null && info.id == activeDevice.id,
            )
        }.sortedByDescending { it.isActive } // активное первым

        if (cards.isEmpty()) {
            binding.devicesEmpty.visibility = View.VISIBLE
            binding.devicesRecycler.visibility = View.GONE
        } else {
            binding.devicesEmpty.visibility = View.GONE
            binding.devicesRecycler.visibility = View.VISIBLE
        }

        // Передаём полный список — адаптер сам решает что показать
        adapter.submitDeviceList(cards)
    }

    /**
     * Обработать тап на карточку устройства.
     * Открывает диалог выбора пресета: "Нет", "Спросить при подключении", или конкретный пресет.
     */
    private fun onDeviceCardClick(deviceCard: DeviceCard) {
        // Показываем overlay-диалог напрямую через WindowManager
        // refreshList вызывается после применения пресета, а не сразу
        presetOverlayManager.show(deviceCard.id, deviceCard.name, persist = true) {
            // Колбэк после выбора пресета — обновляем список
            refreshList()
        }
    }

    override fun onResume() {
        super.onResume()
        // Обновить список после возврата из PresetSelectionActivity
        refreshList()
    }

    companion object {
        fun newInstance(): DeviceCardsFragment = DeviceCardsFragment()
    }
}
