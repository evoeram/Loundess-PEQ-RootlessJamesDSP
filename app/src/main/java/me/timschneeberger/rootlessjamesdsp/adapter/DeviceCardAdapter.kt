package me.timschneeberger.rootlessjamesdsp.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.databinding.ItemDeviceCardBinding
import me.timschneeberger.rootlessjamesdsp.databinding.ItemDeviceCardHeaderBinding
import me.timschneeberger.rootlessjamesdsp.model.DeviceCard
import me.timschneeberger.rootlessjamesdsp.utils.RoutingObserver

/**
 * Adapter для списка карточек устройств на главном экране.
 *
 * Показывает активное устройство всегда развёрнутым.
 * Неактивные устройства скрыты под сворачиваемым заголовком
 * «Другие устройства (N)» и по умолчанию свёрнуты.
 *
 * Тап на карточку открывает диалог выбора пресета.
 * Тап на заголовок разворачивает/сворачивает список неактивных устройств.
 */
class DeviceCardAdapter(
    private val onItemClick: (DeviceCard) -> Unit,
) : ListAdapter<DeviceCardAdapter.Item, RecyclerView.ViewHolder>(DIFF_CALLBACK) {

    /** Элементы списка — карточка устройства или сворачиваемый заголовок. */
    sealed class Item {
        abstract val stableId: Long

        /** Карточка активного устройства. */
        data class ActiveDeviceCard(val card: DeviceCard) : Item() {
            // Стабильный ID на основе ID устройства.
            // Важно: ActiveDeviceCard и OtherDeviceCard для одного и того же
            // устройства имеют РАЗНЫЕ stableId, чтобы DiffUtil корректно
            // отрабатывал смену статуса (remove old + insert new, а не change).
            override val stableId: Long = STABLE_ID_ACTIVE_PREFIX or card.id.hashCode().toLong()
        }

        /** Сворачиваемый заголовок «Другие устройства (N)». */
        data class OtherHeader(val count: Int) : Item() {
            override val stableId: Long = STABLE_ID_HEADER
        }

        /** Карточка неактивного устройства. */
        data class OtherDeviceCard(val card: DeviceCard) : Item() {
            override val stableId: Long = STABLE_ID_OTHER_PREFIX or card.id.hashCode().toLong()
        }
    }

    /** Состояние сворачивания списка неактивных устройств. */
    private var otherExpanded = false

    /** Последний полный список устройств (до фильтрации по expand-состоянию). */
    private var lastFullList: List<DeviceCard> = emptyList()

    init {
        // Стабильные ID необходимы, чтобы RecyclerView не путал ViewHolder'ы
        // при быстрой смене списка (например, при переключении устройства).
        setHasStableIds(true)
    }

    companion object {
        private const val TYPE_ACTIVE = 0
        private const val TYPE_HEADER = 1
        private const val TYPE_OTHER = 2

        // Префиксы для стабильных ID, чтобы гарантировать уникальность
        // между активными и неактивными карточками одного и того же устройства.
        private const val STABLE_ID_ACTIVE_PREFIX: Long = 0x10_0000_0000L
        private const val STABLE_ID_OTHER_PREFIX: Long  = 0x20_0000_0000L
        private const val STABLE_ID_HEADER: Long        = 0x30_0000_0000L

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Item>() {
            override fun areItemsTheSame(old: Item, new: Item): Boolean =
                old.stableId == new.stableId

            override fun areContentsTheSame(old: Item, new: Item): Boolean = old == new

            // Отключаем change-анимации: при смене статуса устройства
            // (active ↔ other) DiffUtil видит remove + insert, а не change.
            // Это предотвращает краш "Two different ViewHolders have the same change ID".
            override fun getChangePayload(oldItem: Item, newItem: Item): Any? = null
        }
    }

    override fun getItemId(position: Int): Long = getItem(position).stableId

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is Item.ActiveDeviceCard -> TYPE_ACTIVE
        is Item.OtherHeader -> TYPE_HEADER
        is Item.OtherDeviceCard -> TYPE_OTHER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_ACTIVE, TYPE_OTHER -> DeviceCardViewHolder(
                ItemDeviceCardBinding.inflate(inflater, parent, false)
            )
            TYPE_HEADER -> OtherHeaderViewHolder(
                ItemDeviceCardHeaderBinding.inflate(inflater, parent, false)
            )
            else -> throw IllegalArgumentException("Unknown viewType: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is Item.ActiveDeviceCard -> (holder as DeviceCardViewHolder).bind(item.card)
            is Item.OtherDeviceCard -> (holder as DeviceCardViewHolder).bind(item.card)
            is Item.OtherHeader -> (holder as OtherHeaderViewHolder).bind(item.count, otherExpanded)
        }
    }

    /**
     * Передать полный список устройств в адаптер.
     * Адаптер сам разбивает его на активные / неактивные и вставляет заголовок.
     */
    fun submitDeviceList(cards: List<DeviceCard>) {
        lastFullList = cards
        rebuildList()
    }

    /** Перестроить видимые элементы из lastFullList с учётом otherExpanded. */
    private fun rebuildList() {
        // Дедупликация по id на случай, если во входном списке есть дубликаты
        // (misdetection типа устройства и т.п.).
        val deduped = lastFullList.distinctBy { it.id }
        val active = deduped.filter { it.isActive }
        val other = deduped.filter { !it.isActive }

        val items = mutableListOf<Item>()
        active.forEach { items.add(Item.ActiveDeviceCard(it)) }
        if (other.isNotEmpty()) {
            // Только один заголовок, независимо от количества неактивных устройств
            items.add(Item.OtherHeader(other.size))
            if (otherExpanded) {
                other.forEach { items.add(Item.OtherDeviceCard(it)) }
            }
        }
        submitList(items)
    }

    /** Переключить сворачивание неактивных устройств. */
    fun toggleOtherExpanded() {
        otherExpanded = !otherExpanded
        rebuildList()
    }

    // ── ViewHolder для карточки устройства ──────────────────────────

    inner class DeviceCardViewHolder(
        private val binding: ItemDeviceCardBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(device: DeviceCard) {
            val ctx: Context = binding.root.context

            // Имя устройства
            binding.deviceName.text = device.name

            // Тип устройства + UUID
            val groupLabel = ctx.getString(device.group.nameRes)
            binding.deviceType.text = "${device.uuid} • $groupLabel"
            binding.devicePreset.text = device.getPresetAssignmentDisplay(ctx)

            // Иконка по типу устройства
            binding.deviceIcon.setImageDrawable(
                ContextCompat.getDrawable(ctx, getIconForGroup(device.group))
            )

            // Подсветка активного устройства — цветная линия слева
            val accentColor = ContextCompat.getColor(ctx, R.color.accent_device_active)
            val outlineColor = ContextCompat.getColor(ctx, R.color.device_outline_default)
            binding.activeIndicator.setBackgroundColor(
                if (device.isActive) accentColor else outlineColor
            )

            // Подсветка всей карточки для активного
            binding.root.strokeWidth = if (device.isActive) 2 else 0

            // Тап на карточку
            binding.root.setOnClickListener { onItemClick(device) }
        }

        /** Иконка для типа устройства */
        private fun getIconForGroup(group: RoutingObserver.DeviceGroup): Int = when (group) {
            RoutingObserver.DeviceGroup.ANALOG -> R.drawable.ic_twotone_headphones_24dp
            RoutingObserver.DeviceGroup.BLUETOOTH -> R.drawable.ic_twotone_bluetooth_24dp
            RoutingObserver.DeviceGroup.HDMI -> R.drawable.ic_twotone_settings_input_hdmi_24dp
            RoutingObserver.DeviceGroup.SPEAKER -> R.drawable.ic_twotone_speaker_24dp
            RoutingObserver.DeviceGroup.USB -> R.drawable.ic_twotone_usb_24dp
            RoutingObserver.DeviceGroup.OTHER -> R.drawable.ic_twotone_device_unknown_24dp
        }
    }

    // ── ViewHolder для заголовка «Другие устройства» ────────────────

    inner class OtherHeaderViewHolder(
        private val binding: ItemDeviceCardHeaderBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(count: Int, expanded: Boolean) {
            val ctx = binding.root.context
            binding.headerTitle.text = ctx.getString(R.string.devices_other_header, count)

            // Стрелка: развёрнуто — вниз, свёрнуто — вправо
            binding.headerArrow.setImageResource(
                if (expanded) R.drawable.ic_baseline_keyboard_arrow_down_24dp
                else R.drawable.ic_twotone_chevron_right_24dp
            )

            // Тап по заголовку — перестраиваем список через rebuildList()
            binding.root.setOnClickListener {
                toggleOtherExpanded()
            }
        }
    }
}
