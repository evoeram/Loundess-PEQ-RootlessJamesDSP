package me.timschneeberger.rootlessjamesdsp.activity

import android.app.Activity
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.DevicePresetManager
import org.koin.android.ext.android.inject

/**
 * Активность выбора пресета для устройства.
 *
 * Показывает system overlay window (TYPE_APPLICATION_OVERLAY) поверх ЛЮБОГО приложения.
 * Работает в любом месте системы: на главном экране, в другом приложении, в браузере и т.д.
 *
 * Требует разрешение SYSTEM_ALERT_WINDOW (проверяется через canDrawOverlays).
 * Если разрешение отсутствует — fallback на обычный MaterialAlertDialog.
 *
 * Показывает список вариантов:
 * 1. "Нет (по умолчанию)" — сброс DSP при подключении
 * 2. "Спросить при подключении" — каждый раз показывать этот overlay
 * 3..N. Конкретные пресеты из папки Presets/ (MyPreset.tar, ...)
 *
 * Может вызываться как из DeviceCardsFragment (тап на карточку),
 * так и из ProfileManager (при подключении устройства с assignment="ask").
 */
class PresetSelectionActivity : Activity() {

    private val devicePresetManager: DevicePresetManager by inject()

    private var deviceId: String? = null
    private var deviceName: String? = null
    private var overlayView: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID)
        deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME)

        if (deviceId == null) {
            finish()
            return
        }

        // Проверяем разрешение на отрисовку поверх других приложений
        if (canDrawOverlays) {
            showOverlayDialog()
        } else {
            // Нет разрешения — fallback на обычный диалог
            showDialogFallback()
        }
    }

    /**
     * Проверка наличия разрешения SYSTEM_ALERT_WINDOW.
     */
    private val canDrawOverlays: Boolean
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }

    /**
     * Показать overlay-диалог поверх любого приложения.
     * Использует TYPE_APPLICATION_OVERLAY (API 26+) или TYPE_PHONE (старее).
     */
    private fun showOverlayDialog() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            LayoutParams.TYPE_PHONE
        }

        // Параметры окна: центрированный, с отступами по бокам
        val params = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT,
            layoutType,
            LayoutParams.FLAG_NOT_TOUCH_MODAL or LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        // Создаём View диалога
        val dialogView = createOverlayDialogView {
            // Колбэк при закрытии
            try {
                windowManager.removeView(overlayView)
            } catch (e: Exception) { /* уже удалено */ }
            finish()
        }

        overlayView = dialogView
        windowManager.addView(dialogView, params)
    }

    /**
     * Fallback: показать как обычный MaterialAlertDialog, если нет разрешения overlay.
     */
    private fun showDialogFallback() {
        val currentAssignment = devicePresetManager.getPresetAssignment(deviceId!!)
        val presets = devicePresetManager.listAvailablePresets()

        val items = mutableListOf<String>()
        items.add(getString(R.string.preset_select_none))
        items.add(getString(R.string.preset_select_ask))
        items.addAll(presets)

        val checkedIndex = when (currentAssignment) {
            DevicePresetManager.VALUE_NONE -> 0
            DevicePresetManager.VALUE_ASK -> 1
            else -> {
                val idx = presets.indexOfFirst { it == currentAssignment }
                if (idx >= 0) idx + 2 else 0
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(deviceName ?: getString(R.string.preset_select_title))
            .setMessage(getString(R.string.preset_select_subtitle))
            .setSingleChoiceItems(items.toTypedArray(), checkedIndex) { dialog, which ->
                val newAssignment = when (which) {
                    0 -> DevicePresetManager.VALUE_NONE
                    1 -> DevicePresetManager.VALUE_ASK
                    else -> presets[which - 2]
                }

                devicePresetManager.setPresetAssignment(deviceId!!, newAssignment)

                if (newAssignment != DevicePresetManager.VALUE_ASK) {
                    devicePresetManager.applyPreset(
                        if (newAssignment == DevicePresetManager.VALUE_NONE) null else newAssignment
                    )
                }

                dialog.dismiss()
                finish()
            }
            .setOnCancelListener { finish() }
            .show()
    }

    /**
     * Создать View для overlay-диалога.
     * @param onClose колбэк при закрытии диалога
     */
    private fun createOverlayDialogView(onClose: () -> Unit): View {
        val currentAssignment = devicePresetManager.getPresetAssignment(deviceId!!)
        val presets = devicePresetManager.listAvailablePresets()

        // Корневой контейнер — MaterialCardView
        val cardView = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(48, 0, 48, 0)
            }
            radius = 28f
            cardElevation = 16f
            // Фон карточки — цвет surface из темы
            setCardBackgroundColor(getColorFromAttr(com.google.android.material.R.attr.colorSurface))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 40, 48, 40)
        }

        // Заголовок
        val title = TextView(this).apply {
            text = deviceName ?: getString(R.string.preset_select_title)
            textSize = 20f
            setTextColor(getColorFromAttr(com.google.android.material.R.attr.colorOnSurface))
            setPadding(0, 0, 0, 8)
        }
        container.addView(title)

        // Подзаголовок
        val subtitle = TextView(this).apply {
            text = getString(R.string.preset_select_subtitle)
            textSize = 14f
            setTextColor(getColorFromAttr(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, 0, 0, 24)
        }
        container.addView(subtitle)

        // RadioGroup
        val radioGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }

        // "Нет (по умолчанию)"
        val noneRadio = RadioButton(this).apply {
            text = getString(R.string.preset_select_none)
            id = VIEW_ID_NONE
            isChecked = currentAssignment == DevicePresetManager.VALUE_NONE
        }
        radioGroup.addView(noneRadio)

        // "Спросить при подключении"
        val askRadio = RadioButton(this).apply {
            text = getString(R.string.preset_select_ask)
            id = VIEW_ID_ASK
            isChecked = currentAssignment == DevicePresetManager.VALUE_ASK
        }
        radioGroup.addView(askRadio)

        // Разделитель
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { setMargins(0, 16, 0, 16) }
            setBackgroundColor(0x33808080)
        }
        radioGroup.addView(divider)

        // Пресеты
        presets.forEachIndexed { index, presetName ->
            val radio = RadioButton(this).apply {
                text = presetName
                id = VIEW_ID_PRESET_BASE + index
                isChecked = currentAssignment == presetName
            }
            radioGroup.addView(radio)
        }

        container.addView(radioGroup)

        // Кнопка "Применить"
        val applyButton = MaterialButton(this).apply {
            text = getString(R.string.preset_select_apply)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 32, 0, 0) }
            setOnClickListener {
                val selectedId = radioGroup.checkedRadioButtonId
                val newAssignment = when (selectedId) {
                    VIEW_ID_NONE -> DevicePresetManager.VALUE_NONE
                    VIEW_ID_ASK -> DevicePresetManager.VALUE_ASK
                    in VIEW_ID_PRESET_BASE..Int.MAX_VALUE -> {
                        val presetIndex = selectedId - VIEW_ID_PRESET_BASE
                        presets.getOrElse(presetIndex) { DevicePresetManager.VALUE_NONE }
                    }
                    else -> DevicePresetManager.VALUE_NONE
                }

                devicePresetManager.setPresetAssignment(deviceId!!, newAssignment)

                if (newAssignment != DevicePresetManager.VALUE_ASK) {
                    devicePresetManager.applyPreset(
                        if (newAssignment == DevicePresetManager.VALUE_NONE) null else newAssignment
                    )
                }

                onClose()
            }
        }
        container.addView(applyButton)

        // Кнопка "Отмена"
        val cancelButton = MaterialButton(this).apply {
            text = getString(R.string.peq_cancel)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 8, 0, 0) }
            setOnClickListener { onClose() }
        }
        container.addView(cancelButton)

        cardView.addView(container)
        return cardView
    }

    /**
     * Получить цвет из атрибута темы.
     */
    private fun getColorFromAttr(attr: Int): Int {
        val typedValue = TypedValue()
        theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    override fun onDestroy() {
        // Убираем overlay при уничтожении
        overlayView?.let {
            try {
                val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                windowManager.removeView(it)
            } catch (e: Exception) { /* уже удалено */ }
            overlayView = null
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_DEVICE_ID = "extra_device_id"
        const val EXTRA_DEVICE_NAME = "extra_device_name"
        const val EXTRA_DEVICE_GROUP = "extra_device_group"

        private const val VIEW_ID_NONE = 1000
        private const val VIEW_ID_ASK = 1001
        private const val VIEW_ID_PRESET_BASE = 2000
    }
}
