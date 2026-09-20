package me.timschneeberger.rootlessjamesdsp.activity

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.timschneeberger.rootlessjamesdsp.MainApplication
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.model.preset.Preset
import me.timschneeberger.rootlessjamesdsp.utils.ProfileManager
import me.timschneeberger.rootlessjamesdsp.utils.RoutingObserver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.toast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import timber.log.Timber
import java.io.File

/**
 * Прозрачная Activity для выбора пресета при подключении нового аудиоустройства.
 * Запускается из ProfileManager, когда подключённое устройство (USB DAC / USB-наушники)
 * соответствует включённым в настройках группам. Работает даже если приложение закрыто.
 *
 * Логика:
 * 1. Показывает диалог со списком доступных пресетов (.tar файлы из Presets/)
 * 2. При выборе — применяет пресет к текущему профилю устройства
 * 3. При пропуске (Skip) — ничего не делает, остаётся текущущий профиль
 * 4. Закрывается автоматически после выбора
 */
class PresetSelectionActivity : AppCompatActivity() {

    private fun getProfileManager(): ProfileManager {
        return (applicationContext as MainApplication).profileManager
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "Unknown device"
        val deviceGroup = intent.getStringExtra(EXTRA_DEVICE_GROUP) ?: "USB"
        val isUsbDac = intent.getBooleanExtra(EXTRA_IS_USB_DAC, false)

        Timber.i("PresetSelectionActivity: device='$deviceName', group=$deviceGroup, isUsbDac=$isUsbDac")

        // Получаем список доступных пресетов из внешней папки Presets/
        val presets = getAvailablePresets()

        if(presets.isEmpty()) {
            // Нет пресетов — показываем уведомление и закрываемся
            showNotification(deviceName)
            toast(getString(R.string.preset_select_dialog_title) + ": " + deviceName)
            finish()
            return
        }

        val displayNames = presets.map { it.displayName }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.preset_select_dialog_title) + " — " + deviceName)
            .setItems(displayNames) { _, which ->
                val selected = presets[which]
                applyPreset(selected)
                finish()
            }
            .setNegativeButton(R.string.preset_select_skip) { _, _ ->
                Timber.d("Preset selection skipped by user")
                finish()
            }
            .setOnCancelListener { finish() }
            .show()
    }

    /** Получить список .tar пресетов из папки Presets/ */
    private fun getAvailablePresets(): List<PresetItem> {
        val externalDir = getExternalFilesDir(null)
        Timber.i("getAvailablePresets: getExternalFilesDir(null)=${externalDir?.path}")
        
        if(externalDir == null) {
            Timber.w("getAvailablePresets: getExternalFilesDir(null) returned null")
            return emptyList()
        }
        
        val presetsDir = File(externalDir.path, "Presets")
        Timber.i("getAvailablePresets: presetsDir=${presetsDir.path}, exists=${presetsDir.exists()}, isDirectory=${presetsDir.isDirectory}")
        
        if(!presetsDir.exists() || !presetsDir.isDirectory) {
            Timber.w("getAvailablePresets: Presets directory not found at ${presetsDir.path}")
            return emptyList()
        }

        val files = presetsDir.listFiles { file -> file.isFile && file.extension == "tar" }
        Timber.i("getAvailablePresets: found ${files?.size ?: 0} .tar files")

        return files
            ?.sortedBy { it.nameWithoutExtension }
            ?.map { PresetItem(it.nameWithoutExtension, it) }
            ?: emptyList()
    }

    /** Применить выбранный пресет к текущему профилю устройства */
    private fun applyPreset(item: PresetItem) {
        try {
            Timber.i("Applying preset '${item.displayName}' to current device profile")

            // 1. Загружаем пресет в shared_prefs (текущий активный профиль)
            //    Preset.load() распаковывает dsp_*.xml из .tar в shared_prefs/
            Preset(item.file.name, item.file.parentFile).load()

            // 2. Сохраняем обновлённые shared_prefs в профиль устройства
            //    storeCurrentProfile() → store(activeProfile) → Preset.save()
            //    читает shared_prefs/ и упаковывает в files/profiles/<id>/profile.tar
            getProfileManager().storeCurrentProfile()

            // 3. Уведомляем аудио-движок о смене настроек (local broadcast)
            sendLocalBroadcast(Intent(me.timschneeberger.rootlessjamesdsp.utils.Constants.ACTION_PREFERENCES_UPDATED))

            toast(getString(R.string.preset_select_apply) + ": " + item.displayName)
        } catch(ex: Exception) {
            Timber.e(ex, "Failed to apply preset: ${item.displayName}")
            toast("Error: ${ex.message}")
        }
    }

    /** Показать уведомление, если нет пресетов */
    private fun showNotification(deviceName: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Preset selection",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_baseline_equalizer_24dp)
            .setContentTitle(getString(R.string.preset_select_notification_title))
            .setContentText("$deviceName — ${getString(R.string.preset_select_notification_text)}")
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /** Элемент списка пресетов */
    private data class PresetItem(val displayName: String, val file: File)

    companion object {
        private const val CHANNEL_ID = "preset_selection"
        private const val NOTIFICATION_ID = 9100

        const val EXTRA_DEVICE_NAME = "device_name"
        const val EXTRA_DEVICE_GROUP = "device_group"
        const val EXTRA_IS_USB_DAC = "is_usb_dac"

        /**
         * Запустить Activity выбора пресета для указанного устройства.
         * Вызывается из ProfileManager.
         */
        fun start(context: Context, device: RoutingObserver.Device) {
            val intent = Intent(context, PresetSelectionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_DEVICE_NAME, device.name)
                putExtra(EXTRA_DEVICE_GROUP, device.group.name)
                putExtra(EXTRA_IS_USB_DAC, device.isUsbDac)
            }
            context.startActivity(intent)
        }
    }
}
