package me.timschneeberger.rootlessjamesdsp.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.databinding.ItemDeviceCardBinding
import me.timschneeberger.rootlessjamesdsp.model.DeviceCard
import me.timschneeberger.rootlessjamesdsp.utils.RoutingObserver

/**
 * Adapter для списка карточек устройств на главном экране.
 *
 * Отображает каждое известное устройство с его типом, именем, UUID и назначенным пресетом.
 * Активное устройство подсвечивается цветной линией слева.
 * Тап на карточку открывает диалог выбора пресета.
 */
class DeviceCardAdapter(
    private val onItemClick: (DeviceCard) -> Unit,
) : ListAdapter<DeviceCard, DeviceCardAdapter.DeviceCardViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceCardViewHolder {
        val binding = ItemDeviceCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return DeviceCardViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DeviceCardViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

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

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<DeviceCard>() {
            override fun areItemsTheSame(old: DeviceCard, new: DeviceCard): Boolean =
                old.id == new.id

            override fun areContentsTheSame(old: DeviceCard, new: DeviceCard): Boolean =
                old == new
        }
    }
}
