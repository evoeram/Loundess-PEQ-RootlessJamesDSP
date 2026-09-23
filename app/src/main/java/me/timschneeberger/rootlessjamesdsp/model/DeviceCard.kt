package me.timschneeberger.rootlessjamesdsp.model

import android.content.Context
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.RoutingObserver

/**
 * Data-класс карточки устройства для раздела "Устройства" на главном экране.
 *
 * @param name отображаемое имя (например "Sony WH-1000XM4 (Bluetooth)")
 * @param id уникальный идентификатор (profile id из RoutingObserver.Device.id)
 * @param group тип устройства (SPEAKER, USB, BLUETOOTH, HDMI, ANALOG, OTHER)
 * @param uuid уникальный UUID устройства (MAC для BT, productName+address для USB, и т.д.)
 * @param presetAssignment текущий назначенный пресет ("none", "ask", "MyPreset.tar")
 * @param isActive активно ли устройство в данный момент
 */
data class DeviceCard(
    val name: String,
    val id: String,
    val group: RoutingObserver.DeviceGroup,
    val uuid: String,
    val presetAssignment: String,
    val isActive: Boolean,
) {
    /**
     * Человеко-читаемое описание назначения пресета.
     * @param ctx контекст для доступа к строковым ресурсам
     */
    fun getPresetAssignmentDisplay(ctx: Context): String = when (presetAssignment) {
        "none" -> ctx.getString(R.string.preset_select_none)
        "ask" -> ctx.getString(R.string.preset_select_ask)
        else -> presetAssignment.removeSuffix(".tar")
    }

    /**
     * True если пресет назначен (не "none" и не "ask").
     */
    val hasPresetAssigned: Boolean
        get() = presetAssignment != "none" && presetAssignment != "ask"
}
