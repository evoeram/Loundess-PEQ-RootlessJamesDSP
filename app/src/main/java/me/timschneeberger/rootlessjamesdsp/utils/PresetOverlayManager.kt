package me.timschneeberger.rootlessjamesdsp.utils

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import me.timschneeberger.rootlessjamesdsp.R
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber

/**
 * Менеджер overlay-диалога выбора пресета.
 *
 * Показывает dialog поверх ЛЮБОГО приложения напрямую через WindowManager
 * (TYPE_APPLICATION_OVERLAY), без запуска Activity.
 *
 * Это позволяет работать из фонового процесса (ProfileManager/BroadcastReceiver),
 * где Android блокирует запуск Activity (BAL_BLOCK на Android 14+).
 *
 * Требует разрешение SYSTEM_ALERT_WINDOW.
 */
class PresetOverlayManager : KoinComponent {

    private val context: Context by inject()
    private val devicePresetManager: DevicePresetManager by inject()
    private val routingObserver: RoutingObserver by inject()

    private var overlayView: View? = null

    /**
     * Проверка наличия разрешения SYSTEM_ALERT_WINDOW.
     */
    private val canDrawOverlays: Boolean
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }

    /**
     * Показать overlay-диалог выбора пресета для устройства.
     *
     * @param deviceId идентификатор устройства
     * @param deviceName отображаемое имя устройства
     * @param persist true → выбор сохраняется постоянно (из главного меню);
     *                false → только для текущей сессии, при реконнекте сбросится (автоконнект)
     * @param onPresetApplied колбэк после выбора и применения пресета (вызывается в главном потоке)
     * @return true если overlay показан, false если нет разрешения
     */
    fun show(deviceId: String, deviceName: String, persist: Boolean = false, onPresetApplied: (() -> Unit)? = null): Boolean {
        if (!canDrawOverlays) {
            Timber.w("PresetOverlayManager: SYSTEM_ALERT_WINDOW not granted, cannot show overlay")
            return false
        }

        // Если уже показан — закрываем предыдущий
        dismiss()

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        val dialogView = createDialogView(deviceId, deviceName, persist, onPresetApplied) {
            // Колбэк при закрытии
            dismiss()
        }

        overlayView = dialogView
        windowManager.addView(dialogView, params)
        Timber.d("PresetOverlayManager: overlay shown for device '$deviceId'")
        return true
    }

    /**
     * Закрыть overlay-диалог.
     */
    fun dismiss() {
        overlayView?.let {
            try {
                val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                windowManager.removeView(it)
            } catch (e: Exception) {
                Timber.w("PresetOverlayManager: failed to remove overlay view: ${e.message}")
            }
            overlayView = null
        }
    }

    /**
     * Проверить, показан ли overlay сейчас.
     */
    val isShowing: Boolean
        get() = overlayView != null

    /**
     * Создать View для overlay-диалога.
     */
    private fun createDialogView(
        deviceId: String,
        deviceName: String,
        persist: Boolean,
        onPresetApplied: (() -> Unit)?,
        onClose: () -> Unit,
    ): View {
        // Оборачиваем context в тему Theme.RootlessJamesDSP (Material3)
        // MaterialCardView/MaterialButton требуют Material3 тему,
        // а application context её не имеет.
        val themedContext = ContextThemeWrapper(context, R.style.Theme_RootlessJamesDSP)

        val currentAssignment = devicePresetManager.getPresetAssignment(deviceId)
        val presets = devicePresetManager.listAvailablePresets()

        // Корневой контейнер — MaterialCardView
        val cardView = MaterialCardView(themedContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(48, 0, 48, 0)
            }
            radius = 28f
            cardElevation = 16f
            setCardBackgroundColor(getColorFromAttr(themedContext, com.google.android.material.R.attr.colorSurface))
        }

        val container = LinearLayout(themedContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 40, 48, 40)
        }

        // Заголовок
        val title = TextView(themedContext).apply {
            text = deviceName.ifBlank { themedContext.getString(R.string.preset_select_title) }
            textSize = 20f
            setTextColor(getColorFromAttr(themedContext, com.google.android.material.R.attr.colorOnSurface))
            setPadding(0, 0, 0, 8)
        }
        container.addView(title)

        // Подзаголовок
        val subtitle = TextView(themedContext).apply {
            text = themedContext.getString(R.string.preset_select_subtitle)
            textSize = 14f
            setTextColor(getColorFromAttr(themedContext, com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, 0, 0, 24)
        }
        container.addView(subtitle)

        // RadioGroup
        val radioGroup = RadioGroup(themedContext).apply {
            orientation = RadioGroup.VERTICAL
        }

        // При persist=true (тап на карточку в главном меню) показываем
        // "Нет (по умолчанию)" и "Спросить при подключении" — это настройки
        // постоянного поведения устройства.
        // При persist=false (автоподключение, popup) показываем "Нет (по умолчанию)"
        // и пресеты — без "Спросить при подключении", чтобы не создавать впечатление,
        // что выбор пресета меняет постоянное поведение при следующих подключениях.
        if (persist) {
            // "Нет (по умолчанию)"
            val noneRadio = RadioButton(themedContext).apply {
                text = themedContext.getString(R.string.preset_select_none)
                id = VIEW_ID_NONE
                isChecked = currentAssignment == DevicePresetManager.VALUE_NONE
            }
            radioGroup.addView(noneRadio)

            // "Спросить при подключении"
            val askRadio = RadioButton(themedContext).apply {
                text = themedContext.getString(R.string.preset_select_ask)
                id = VIEW_ID_ASK
                isChecked = currentAssignment == DevicePresetManager.VALUE_ASK
            }
            radioGroup.addView(askRadio)
        } else {
            // Popup при автоподключении: только "Нет (по умолчанию)" без "Спросить"
            val noneRadio = RadioButton(themedContext).apply {
                text = themedContext.getString(R.string.preset_select_none)
                id = VIEW_ID_NONE
                isChecked = currentAssignment == DevicePresetManager.VALUE_NONE
            }
            radioGroup.addView(noneRadio)
        }

        // Разделитель
        val divider = View(themedContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { setMargins(0, 16, 0, 16) }
            setBackgroundColor(0x33808080)
        }
        radioGroup.addView(divider)

        // Пресеты (расширение .tar убирается для отображения)
        presets.forEachIndexed { index, presetName ->
            val radio = RadioButton(themedContext).apply {
                text = presetName.removeSuffix(".tar")
                id = VIEW_ID_PRESET_BASE + index
                isChecked = currentAssignment == presetName
            }
            radioGroup.addView(radio)
        }

        container.addView(radioGroup)

        // Кнопка "Применить"
        val applyButton = MaterialButton(themedContext).apply {
            text = themedContext.getString(R.string.preset_select_apply)
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

                // persist=true (из меню) → пишем в permanent (сохраняется навсегда).
                // persist=false (автоконнект) → пишем в temp (только сессия, при реконнекте сбросится).
                if (persist) {
                    devicePresetManager.setPresetAssignment(deviceId, newAssignment)
                } else {
                    devicePresetManager.setTempAssignment(deviceId, newAssignment)
                }

                // Применяем пресет к звуку ТОЛЬКО если устройство активное.
                // Выбор пресета для неактивного устройства в меню не должен менять текущий звук.
                val isActive = routingObserver.currentDevice?.id == deviceId
                if (isActive && newAssignment != DevicePresetManager.VALUE_ASK) {
                    devicePresetManager.applyPreset(
                        if (newAssignment == DevicePresetManager.VALUE_NONE) null else newAssignment
                    )
                }

                // Уведомляем колбэк о применении пресета
                onPresetApplied?.invoke()

                onClose()
            }
        }
        container.addView(applyButton)

        // Кнопка "Отмена"
        val cancelButton = MaterialButton(themedContext).apply {
            text = themedContext.getString(R.string.peq_cancel)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 8, 0, 0) }
            setOnClickListener { onClose() }
        }
        container.addView(cancelButton)

        cardView.addView(container)

        // Касание вне карточки (но внутри overlay-окна) закрывает диалог.
        // FLAG_WATCH_OUTSIDE_TOUCH отправляет MotionEvent.ACTION_OUTSIDE при тапе мимо view.
        cardView.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_OUTSIDE) {
                onClose()
                true
            } else {
                false
            }
        }

        return cardView
    }

    /**
     * Получить цвет из атрибута темы.
     */
    private fun getColorFromAttr(ctx: Context, attr: Int): Int {
        val typedValue = TypedValue()
        ctx.theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    companion object {
        private const val VIEW_ID_NONE = 1000
        private const val VIEW_ID_ASK = 1001
        private const val VIEW_ID_PRESET_BASE = 2000
    }
}
