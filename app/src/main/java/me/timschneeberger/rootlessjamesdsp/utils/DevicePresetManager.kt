package me.timschneeberger.rootlessjamesdsp.utils

import android.content.Context
import android.content.Intent
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.model.preset.Preset
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.restoreDspSettings
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ensureIsDirectory
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ensureIsFile
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.io.File

/**
 * Менеджер пресетов для устройств.
 *
 * Связывает каждое устройство (по его profile id) с пресетом из папки Presets/.
 * Возможные значения: "none" (дефолт), "ask" (спросить при подключении),
 * или имя файла пресета ("MyPreset.tar").
 *
 * Хранилище: SharedPreferences "device_preset_map", ключ = profile id, значение = preset assignment.
 */
class DevicePresetManager : KoinComponent {

    private val context: Context by inject()
    private val prefs: Preferences.App by inject()

    /**
     * Permanent-хранилище: назначение, выставленное пользователем вручную в главном меню.
     * Значение "ask" → при каждом коннекте показывается overlay.
     */
    private val storage by lazy {
        context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Temp-хранилище: назначение для текущей сессии (выбрано через overlay при автоконнекте).
     * Сбрасывается = permanent при каждом коннекте/реконнекте.
     * Меню читает из temp, чтобы показать выбранный в overlay пресет.
     * При реконнекте temp = permanent → если permanent = "ask", overlay показывается снова.
     */
    private val tempStorage by lazy {
        context.getSharedPreferences(SHARED_PREFS_NAME_TEMP, Context.MODE_PRIVATE)
    }

    /** Имя SharedPreferences для метаданных известных устройств */
    private val deviceStore by lazy {
        context.getSharedPreferences(DEVICE_STORE_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Сохранить метаданные устройства постоянно.
     * Вызывается при обнаружении нового устройства.
     */
    fun registerDevice(device: RoutingObserver.Device) {
        val info = DeviceInfo(
            name = device.name,
            id = device.id,
            group = device.group.name,
            uuid = device.id
        )
        val json = Json.encodeToString(info)
        deviceStore.edit().putString(device.id, json).apply()
        Timber.d("DevicePresetManager: registered device '${device.id}' ($json)")
    }

    /**
     * Получить все сохранённые устройства.
     * @return список метаданных устройств
     */
    fun getAllDevices(): List<DeviceInfo> {
        return deviceStore.all.values.mapNotNull { v ->
            try {
                Json.decodeFromString<DeviceInfo>(v as String)
            } catch (e: Exception) {
                Timber.e("Failed to parse device info: $v")
                null
            }
        }.sortedBy { it.name }
    }

    /**
     * Удалить устройство из сохранённых.
     */
    fun unregisterDevice(deviceId: String) {
        deviceStore.edit().remove(deviceId).apply()
        storage.edit().remove(deviceId).apply()
        tempStorage.edit().remove(deviceId).apply()
    }

    /**
     * Сохранённые метаданные устройства.
     */
    @Serializable
    data class DeviceInfo(
        val name: String,
        val id: String,
        val group: String,
        val uuid: String,
    )

    /**
     * Получить назначенный пресет для устройства (из permanent-хранилища).
     * Читает ГЛАВНОЕ МЕНЮ — всегда показывает то, что выставлено вручную.
     * Попап не меняет это значение.
     * @param profileId идентификатор профиля устройства (из RoutingObserver.Device.id)
     * @return "none", "ask", или имя файла пресета ("MyPreset.tar")
     */
    fun getPresetAssignment(profileId: String): String {
        return storage.getString(profileId, VALUE_NONE) ?: VALUE_NONE
    }

    /**
     * Получить temp-назначение (то, что выбрано в попапе для текущей сессии).
     * Используется только внутри handleDeviceChange.
     */
    fun getTempAssignment(profileId: String): String {
        return tempStorage.getString(profileId, storage.getString(profileId, VALUE_NONE)) ?: VALUE_NONE
    }

    /**
     * Назначить пресет для устройства постоянно (из главного меню).
     * Пишет ТОЛЬКО в permanent. Попап не использует этот метод.
     * @param profileId идентификатор профиля устройства
     * @param value "none", "ask", или имя файла пресета
     */
    fun setPresetAssignment(profileId: String, value: String) {
        Timber.d("DevicePresetManager: setPresetAssignment(profileId='$profileId', value='$value')")
        storage.edit().putString(profileId, value).apply()
    }

    /**
     * Назначить пресет только для текущей сессии (из overlay-диалога при автоконнекте).
     * Permanent не меняется — при реконнекте temp сбросится обратно.
     * @param profileId идентификатор профиля устройства
     * @param value "none", "ask", или имя файла пресета
     */
    fun setTempAssignment(profileId: String, value: String) {
        Timber.d("DevicePresetManager: setTempAssignment(profileId='$profileId', value='$value')")
        tempStorage.edit().putString(profileId, value).apply()
    }

    /**
     * Проверяет, нужно ли спросить пользователя при подключении устройства.
     */
    fun shouldAskForPreset(profileId: String): Boolean {
        return getPresetAssignment(profileId) == VALUE_ASK
    }

    /**
     * Список доступных пресетов (.tar) из папки Presets/.
     * @return массив имён файлов пресетов
     */
    fun listAvailablePresets(): Array<String> {
        val presetsDir = File(context.getExternalFilesDir(null), PRESETS_DIR)
        return presetsDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".tar") }
            ?.map { it.name }
            ?.sorted()
            ?.toTypedArray() ?: emptyArray()
    }

    /**
     * Применить пресет к текущему активному устройству.
     * Загружает .tar в shared_prefs иbroadcast'ит обновление.
     * @param presetFileName имя файла пресета ("MyPreset.tar") или null для "none"
     * @return true если успешно
     */
    fun applyPreset(presetFileName: String?): Boolean {
        if (presetFileName == null || presetFileName == VALUE_NONE) {
            Timber.d("DevicePresetManager: applyPreset — restoring defaults (none)")
            context.restoreDspSettings()
            return true
        }

        return try {
            val presetsDir = File(context.getExternalFilesDir(null), PRESETS_DIR)
            val presetFile = File(presetsDir, presetFileName)
            if (!presetFile.exists()) {
                Timber.e("DevicePresetManager: preset file not found: ${presetFile.absolutePath}")
                return false
            }

            Timber.d("DevicePresetManager: loading preset '$presetFileName'")
            Preset(presetFileName, presetsDir).load()
            true
        } catch (ex: Exception) {
            Timber.e("DevicePresetManager: failed to load preset '$presetFileName'")
            Timber.i(ex)
            false
        }
    }

    /**
     * Обработать смену устройства: автоматически применить пресет или показать диалог.
     * Вызывается из ProfileManager при смене активного устройства.
     *
     * @param device новое активное устройство
     * @return [PresetAction] — что нужно сделать
     */
    fun handleDeviceChange(device: RoutingObserver.Device): PresetAction {
        // Сброс temp = permanent при каждом коннекте/реконнекте.
        // Если permanent = "ask", то temp становится "ask" → overlay показывается снова (безусловно).
        val permanent = getPresetAssignment(device.id)
        tempStorage.edit().putString(device.id, permanent).apply()

        val assignment = getTempAssignment(device.id)
        Timber.d("DevicePresetManager: handleDeviceChange(device='${device.id}', permanent='$permanent', temp='$assignment')")

        return when (assignment) {
            VALUE_NONE -> {
                // Дефолт — сброс настроек
                applyPreset(null)
                PresetAction.AppliedNone
            }
            VALUE_ASK -> {
                // Спросить — показать попап
                PresetAction.AskUser
            }
            else -> {
                // Применить конкретный пресет
                val success = applyPreset(assignment)
                if (success) PresetAction.AppliedPreset(assignment) else PresetAction.Failed
            }
        }
    }

    /** Результат обработки смены устройства */
    sealed class PresetAction {
        /** Применён дефолт (none) */
        object AppliedNone : PresetAction()
        /** Нужно спросить пользователя (показать попап) */
        object AskUser : PresetAction()
        /** Успешно применён пресет */
        data class AppliedPreset(val presetName: String) : PresetAction()
        /** Ошибка при применении */
        object Failed : PresetAction()
    }

    companion object {
        const val SHARED_PREFS_NAME = "device_preset_map"
        const val SHARED_PREFS_NAME_TEMP = "device_preset_map_temp"
        const val DEVICE_STORE_NAME = "device_registry"
        const val PRESETS_DIR = "Presets"

        /** Значения назначения пресета */
        const val VALUE_NONE = "none"
        const val VALUE_ASK = "ask"
    }
}
