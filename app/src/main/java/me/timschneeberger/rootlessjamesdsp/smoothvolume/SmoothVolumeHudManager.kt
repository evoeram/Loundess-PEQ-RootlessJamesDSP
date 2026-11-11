package me.timschneeberger.rootlessjamesdsp.smoothvolume

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.timschneeberger.rootlessjamesdsp.activity.MainActivity
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.service.VolumeKeyAccessibilityService
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import timber.log.Timber

/**
 * Owns the single HUD overlay window and its lifecycle.
 *
 * The window is created once per attach and reused for every update (only the
 * view content/position change); hosts are:
 *  - [VolumeKeyAccessibilityService] with TYPE_ACCESSIBILITY_OVERLAY when the
 *    accessibility service is connected (no overlay permission needed);
 *  - [me.timschneeberger.rootlessjamesdsp.service.SmoothVolumeHudService] with
 *    TYPE_APPLICATION_OVERLAY when SYSTEM_ALERT_WINDOW is granted but the
 *    accessibility service is off.
 *
 * The manager collects [SmoothVolumeController.state] while attached, applies
 * the show-on-* filters, auto-hide timer and position clamping, and persists
 * the custom position in PREF_VAR.
 */
class SmoothVolumeHudManager(
    private val controller: SmoothVolumeController,
    private val prefsApp: Preferences.App,
    hostContext: Context,
) {
    companion object {
        private const val VAR_POS_X = "smooth_volume_hud_pos_x"
        private const val VAR_POS_Y = "smooth_volume_hud_pos_y"
    }

    // Raw PREF_VAR handle for the custom position coordinates (same approach
    // as SwitchPreferenceGroup's collapse state: PREF_VAR never reaches the
    // DSP engine sync and holds no-default bookkeeping values).
    private val varPrefs = hostContext.getSharedPreferences(me.timschneeberger.rootlessjamesdsp.utils.Constants.PREF_VAR, Context.MODE_PRIVATE)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var attachedContext: Context? = null
    private var windowManager: WindowManager? = null
    private var view: SmoothVolumeHudView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var stateJob: kotlinx.coroutines.Job? = null

    private var hideRunnable = Runnable { hide(false) }
    private var shownForSource: VolumeSource = VolumeSource.SYSTEM
    private var stateCache: SmoothVolumeController.SmoothVolumeState? = null

    init {
        // Live-apply HUD appearance/behavior settings: the host services only
        // listen to the enable/disable keys; every other smooth_volume_hud_*
        // change must reconfigure (and reposition) the attached window here.
        prefsApp.preferences.registerOnSharedPreferenceChangeListener(
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == null || !key.startsWith("smooth_volume_hud_")) return@OnSharedPreferenceChangeListener
                mainHandler.post {
                    if (view != null) reconfigure()
                }
            })
    }

    val isAttached: Boolean get() = view != null

    /**
     * True when the currently attached window belongs to the fallback host
     * (TYPE_APPLICATION_OVERLAY). The accessibility host's window must never
     * be detached by the fallback service (see SmoothVolumeHudService.stop).
     */
    val isAttachedAsApplicationOverlay: Boolean
        get() = view != null && layoutParams?.type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    /**
     * Attach the HUD window. Returns false (and does nothing) when the HUD is
     * disabled in settings. An existing window of a different type is replaced.
     */
    fun attach(hostContext: Context, useAccessibilityOverlay: Boolean): Boolean {
        val config = SmoothVolumeHudConfig.fromPreferences(prefsApp)
        if (!config.enabled) {
            Timber.d("HUD attach skipped: disabled in settings")
            return false
        }
        if (!useAccessibilityOverlay && !Settings.canDrawOverlays(hostContext)) {
            Timber.w("HUD attach skipped: no overlay permission")
            return false
        }

        if (view != null && attachedContext === hostContext && sameWindowType(useAccessibilityOverlay)) {
            return true // already attached with the right window
        }
        detach()

        val wm = hostContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val hudView = SmoothVolumeHudView(hostContext, config)
        val (w, h) = hudView.measurePanel()

        val type = if (useAccessibilityOverlay)
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        val params = WindowManager.LayoutParams(
            w, h,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, // starts hidden
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        hudView.callbacks = object : SmoothVolumeHudView.Callbacks {
            override fun onGainRequested(db: Double) = controller.setGain(db, VolumeSource.HUD)
            override fun onAdjustRequested(deltaDb: Double) = controller.adjustGain(deltaDb, VolumeSource.HUD)
            override fun onMuteRequested() = controller.mute(VolumeSource.HUD)
            override fun onUnmuteRequested() = controller.unmute(VolumeSource.HUD)
            override fun onResetRequested() = controller.reset(VolumeSource.HUD)
            override fun onOpenAppRequested() {
                val intent = Intent(hostContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                hostContext.startActivity(intent)
            }
            override fun onCollapseRequested() {
                hudView.expanded = false
                if (config.style == HudStyle.EXPANDED) hide(true) // always-expanded style: collapse hides
                else scheduleAutoHide()
            }
            override fun onExpandStateChanged(expanded: Boolean) = resizeWindow()
            override fun onMoved(x: Int, y: Int) {
                val lp = layoutParams ?: return
                lp.x = x
                lp.y = y
                clampPosition()
                runCatching { wm.updateViewLayout(hudView, lp) }
                    .onFailure { Timber.w(it, "HUD updateViewLayout failed") }
            }
            override fun onMoveEnded() {
                layoutParams?.let { lp ->
                    varPrefs.edit().putInt(VAR_POS_X, lp.x).putInt(VAR_POS_Y, lp.y).apply()
                }
            }
        }

        runCatching {
            wm.addView(hudView, params)
        }.onFailure {
            Timber.w(it, "HUD addView failed")
            return false
        }

        attachedContext = hostContext.applicationContext
        windowManager = wm
        view = hudView
        layoutParams = params
        hudView.visibility = View.INVISIBLE
        computePresetPosition(config)
        clampPosition()

        stateCache = controller.state.value
        hudView.gainDb = stateCache!!.gainDb
        hudView.muted = stateCache!!.muted

        // Collect controller state: keep the view in sync, trigger show per
        // show-on-* filters.
        stateJob = scope.launch {
            controller.state.collect { onStateChanged(it) }
        }

        controller.setHudVisible(false)
        Timber.i("HUD attached (accessibilityOverlay=%b, %dx%d @ %d,%d)",
            useAccessibilityOverlay, params.width, params.height, params.x, params.y)
        return true
    }

    private fun sameWindowType(useAccessibilityOverlay: Boolean): Boolean {
        val type = layoutParams?.type ?: return false
        return if (useAccessibilityOverlay)
            type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else
            type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    }

    fun detach() {
        if (view == null) return
        Timber.i("HUD detached (was applicationOverlay=%b)", layoutParams?.type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        stateJob?.cancel()
        stateJob = null
        mainHandler.removeCallbacks(hideRunnable)
        view?.let { v ->
            runCatching { windowManager?.removeView(v) }
                .onFailure { Timber.w(it, "HUD removeView failed") }
        }
        view = null
        layoutParams = null
        windowManager = null
        attachedContext = null
        controller.setHudVisible(false)
    }

    /** Re-apply preset/persisted position and clamp (rotation, resolution change). */
    fun reclamp() {
        val config = SmoothVolumeHudConfig.fromPreferences(prefsApp)
        computePresetPosition(config)
        clampPosition()
        view?.let { v -> layoutParams?.let { runCatching { windowManager?.updateViewLayout(v, it) } } }
    }

    fun reconfigure() {
        val config = SmoothVolumeHudConfig.fromPreferences(prefsApp)
        view?.reconfigure(config)
        resizeWindow()
    }

    /** Resize/reposition the window to the view's current preferred panel size. */
    private fun resizeWindow() {
        val hud = view ?: return
        val lp = layoutParams ?: return
        val (w, h) = hud.measurePanel()
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
        }
        computePresetPosition(SmoothVolumeHudConfig.fromPreferences(prefsApp))
        clampPosition()
        runCatching { windowManager?.updateViewLayout(hud, lp) }
            .onFailure { Timber.w(it, "HUD resize updateViewLayout failed") }
    }

    private fun onStateChanged(state: SmoothVolumeController.SmoothVolumeState) {
        val old = stateCache
        stateCache = state
        val hud = view ?: return
        hud.gainDb = state.gainDb
        hud.muted = state.muted

        if (!state.enabled) {
            hide(true)
            return
        }

        val changed = old == null ||
            old.gainDb != state.gainDb || old.muted != state.muted
        if (!changed) return

        val source = state.lastChangeSource
        shownForSource = source
        val config = SmoothVolumeHudConfig.fromPreferences(prefsApp)
        val shouldShow = when (source) {
            VolumeSource.HARDWARE_KEY -> config.showOnHardwareKeys
            VolumeSource.HUD -> config.showOnHudInteraction
            VolumeSource.APP, VolumeSource.QUICK_SETTINGS, VolumeSource.SYSTEM -> config.showOnAppVolumeChanges
        }
        if (shouldShow) show()
    }

    private fun show() {
        val hud = view ?: return
        val lp = layoutParams ?: return
        Timber.d("HUD show @ %d,%d %dx%d", lp.x, lp.y, lp.width, lp.height)
        val config = SmoothVolumeHudConfig.fromPreferences(prefsApp)
        hud.visibility = View.VISIBLE
        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        runCatching { windowManager?.updateViewLayout(hud, lp) }
            .onFailure { Timber.w(it, "HUD show updateViewLayout failed") }
        controller.setHudVisible(true)
        scheduleAutoHide()
    }

    private fun hide(permanent: Boolean) {
        val hud = view ?: return
        val lp = layoutParams ?: return
        Timber.d("HUD hide (permanent=%b)", permanent)
        mainHandler.removeCallbacks(hideRunnable)
        hud.visibility = View.INVISIBLE
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { windowManager?.updateViewLayout(hud, lp) }
            .onFailure { Timber.w(it, "HUD hide updateViewLayout failed") }
        controller.setHudVisible(false)
    }

    private fun scheduleAutoHide() {
        mainHandler.removeCallbacks(hideRunnable)
        val seconds = SmoothVolumeHudConfig.fromPreferences(prefsApp).autoHide.seconds
        if (seconds > 0) {
            mainHandler.postDelayed(hideRunnable, seconds * 1000L)
        }
    }

    /** Position for the configured preset; CUSTOM uses persisted coordinates. */
    private fun computePresetPosition(config: SmoothVolumeHudConfig) {
        val lp = layoutParams ?: return
        val metrics = attachedContext?.resources?.displayMetrics ?: return
        val margin = dp(12f).toInt()
        val vw = lp.width
        val vh = lp.height
        val verticalHud = config.orientation == HudOrientation.VERTICAL
        when (config.position) {
            HudPosition.LEFT -> { lp.x = margin; lp.y = (metrics.heightPixels - vh) / 2 }
            HudPosition.RIGHT -> { lp.x = metrics.widthPixels - vw - margin; lp.y = (metrics.heightPixels - vh) / 2 }
            HudPosition.TOP -> { lp.x = (metrics.widthPixels - vw) / 2; lp.y = statusBarHeight() + margin }
            HudPosition.BOTTOM -> { lp.x = (metrics.widthPixels - vw) / 2; lp.y = metrics.heightPixels - vh - navBarHeight() - margin }
            HudPosition.CUSTOM -> {
                lp.x = varPrefs.getInt(VAR_POS_X, margin)
                lp.y = varPrefs.getInt(VAR_POS_Y, (metrics.heightPixels - vh) / 2)
            }
        }
        // Cross-orientation sanity: a vertical HUD at top/bottom still needs an x,
        // a horizontal HUD at left/right still needs a y.
        if (verticalHud && config.position == HudPosition.TOP || !verticalHud && config.position == HudPosition.LEFT)
            lp.x = margin
        if (verticalHud && config.position == HudPosition.BOTTOM || !verticalHud && config.position == HudPosition.RIGHT) {
            lp.x = metrics.widthPixels - vw - margin
            if (!verticalHud) lp.y = metrics.heightPixels - vh - navBarHeight() - margin
        }
    }

    private fun clampPosition() {
        val lp = layoutParams ?: return
        val metrics = attachedContext?.resources?.displayMetrics ?: return
        val inset = statusBarHeight()
        lp.x = lp.x.coerceIn(0, (metrics.widthPixels - lp.width).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(inset, (metrics.heightPixels - lp.height - navBarHeight()).coerceAtLeast(inset))
    }

    private fun statusBarHeight(): Int {
        val res = attachedContext?.resources ?: return 0
        val id = res.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) res.getDimensionPixelSize(id) else dp(24f).toInt()
    }

    private fun navBarHeight(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = attachedContext?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            @Suppress("DEPRECATION")
            val insets = wm?.currentWindowMetrics?.windowInsets
                ?.getInsets(android.view.WindowInsets.Type.navigationBars())
            insets?.bottom ?: dp(24f).toInt()
        } else dp(24f).toInt()
    }

    private fun dp(v: Float): Int {
        val metrics = attachedContext?.resources?.displayMetrics ?: return v.toInt()
        return (v * metrics.density).toInt()
    }
}
