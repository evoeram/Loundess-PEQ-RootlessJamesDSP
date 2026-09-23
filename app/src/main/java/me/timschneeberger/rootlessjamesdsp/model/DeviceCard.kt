package me.timschneeberger.rootlessjamesdsp.model

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
     */
    fun getPresetAssignmentDisplay(): String = when (presetAssignment) {
        "none" -> "Нет (по умолчанию)"
        "ask" -> "Спросить при подключении"
        else -> presetAssignment.removeSuffix(".tar")
    }

    /**
     * True если пресет назначен (не "none" и не "ask").
     */
    val hasPresetAssigned: Boolean
        get() = presetAssignment != "none" && presetAssignment != "ask"
}
