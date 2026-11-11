package me.timschneeberger.rootlessjamesdsp.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.smoothvolume.SmoothVolumeController
import me.timschneeberger.rootlessjamesdsp.smoothvolume.SmoothVolumeHudManager
import me.timschneeberger.rootlessjamesdsp.smoothvolume.VolumeSource
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import org.koin.core.Koin
import org.koin.core.context.GlobalContext
import timber.log.Timber

/**
 * AccessibilityService that intercepts hardware volume buttons globally (in background)
 * and provides fine-grained volume control by adjusting JamesDSP internal gain
 * in small steps (0.5 dB), instead of the coarse system stream volume steps.
 *
 * When enabled:
 * - Volume UP/DOWN change JamesDSP internal volume by [STEP_DB] dB
 * - The system media volume is kept at a fixed level (a consequence of the
 *   intercepted keys being consumed)
 * - This gives ~200 steps across a 100 dB range, vs ~15 system steps
 *
 * All gain changes go through [SmoothVolumeController] (the single state
 * source); the controller persists the value and notifies the audio services
 * and every HUD. This service additionally hosts the floating Mini-HUD as a
 * TYPE_ACCESSIBILITY_OVERLAY window when the HUD is enabled in settings.
 *
 * Requires the user to enable this accessibility service in system settings.
 */
class VolumeKeyAccessibilityService : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        /** Volume step in dB per single key press (first press, no hold). */
        private const val STEP_DB = 0.5

        /**
         * Time window (ms) after which a key press is considered a new press, not a continuation.
         * (Range/step limits live in SmoothVolumeMath; the ramp multiplies [STEP_DB].)
         */
        private const val HOLD_RESET_MS = 400L
    }

    private var isSmoothVolumeEnabled = false
    private var preferences: Preferences.App? = null
    private var controller: SmoothVolumeController? = null
    private var hudManager: SmoothVolumeHudManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Hold-tracking state for accelerated volume ramp
    private var holdDirection = 0       // +1 = up, -1 = down, 0 = idle
    private var holdStartTime = 0L      // SystemClock uptime when hold began
    private var holdTickInterval = 0L   // current interval between ticks (ms)

    override fun onServiceConnected() {
        super.onServiceConnected()
        Timber.i("VolumeKeyAccessibilityService connected")

        val koin: Koin = GlobalContext.get()
        preferences = try {
            koin.get<Preferences.App>()
        } catch (e: Exception) {
            Timber.w(e, "Failed to get Preferences from Koin")
            null
        }
        controller = try {
            koin.get<SmoothVolumeController>()
        } catch (e: Exception) {
            Timber.w(e, "Failed to get SmoothVolumeController from Koin")
            null
        }
        hudManager = try {
            koin.get<SmoothVolumeHudManager>()
        } catch (e: Exception) {
            Timber.w(e, "Failed to get SmoothVolumeHudManager from Koin")
            null
        }

        loadSettings()
        preferences?.registerOnSharedPreferenceChangeListener(this)

        // This service is now the HUD host (TYPE_ACCESSIBILITY_OVERLAY); it
        // replaces any TYPE_APPLICATION_OVERLAY window owned by the fallback
        // service, which stops itself once it sees accessibilityAvailable=true.
        controller?.setAccessibilityAvailable(true)
        attachHud()

        // The fallback overlay host must not run while we own the HUD window
        startFallbackServiceIfIdle()
    }

    private fun loadSettings() {
        isSmoothVolumeEnabled = preferences?.get(R.string.key_smooth_volume_enabled) ?: false
        Timber.i("Smooth volume enabled=%b", isSmoothVolumeEnabled)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            getString(R.string.key_smooth_volume_enabled) -> loadSettings()
            getString(R.string.key_smooth_volume_hud_enabled) -> attachHud()
        }
    }

    private fun attachHud() {
        val manager = hudManager ?: return
        val hudEnabled = preferences?.get(R.string.key_smooth_volume_hud_enabled) ?: true
        if (hudEnabled) {
            manager.attach(this, useAccessibilityOverlay = true)
        }
        else {
            manager.detach()
        }
    }

    /**
     * Start the fallback overlay host only when it actually has work to do
     * (HUD enabled, no accessibility-based host active, permission granted).
     * The service stops itself when none of those hold.
     */
    private fun startFallbackServiceIfIdle() {
        val hudEnabled = preferences?.get(R.string.key_smooth_volume_hud_enabled) ?: true
        if (hudEnabled && hudManager?.isAttached != true) {
            try {
                startService(Intent(this, SmoothVolumeHudService::class.java))
            } catch (e: Exception) {
                Timber.w(e, "Could not start fallback HUD host")
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't process accessibility events, only key events
    }

    override fun onInterrupt() {
        Timber.w("VolumeKeyAccessibilityService interrupted")
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Screen rotation / size change: re-anchor and clamp the HUD
        hudManager?.reclamp()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // Detach the overlay synchronously to prevent Context leaks.
        // The AccessibilityService framework holds a Binder reference
        // (IAccessibilityServiceClientWrapper.mContext) that can keep
        // the destroyed service context alive if the overlay view is
        // still attached to the WindowManager.
        stopHoldRamp()
        holdDirection = 0
        hudManager?.detach()
        controller?.setAccessibilityAvailable(false)
        preferences?.unregisterOnSharedPreferenceChangeListener(this)
        preferences = null
        Timber.i("VolumeKeyAccessibilityService unbound")
        return false
    }

    override fun onDestroy() {
        stopHoldRamp()
        holdDirection = 0
        hudManager?.detach()
        controller?.setAccessibilityAvailable(false)
        preferences?.unregisterOnSharedPreferenceChangeListener(this)
        preferences = null
        Timber.i("VolumeKeyAccessibilityService destroyed")
        super.onDestroy()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!isSmoothVolumeEnabled) {
            return false
        }

        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                val direction = if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) +1 else -1

                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (event.repeatCount == 0) {
                        Timber.d("onKeyEvent: first press, starting ramp. dir=%d", direction)
                        stopHoldRamp()
                        adjustVolume(direction * STEP_DB)
                        holdDirection = direction
                        holdStartTime = android.os.SystemClock.uptimeMillis()
                        holdTickInterval = 300L
                        startHoldRamp()
                    }
                    // Note: AccessibilityService does NOT deliver repeated ACTION_DOWN
                    // events for held keys (unlike Activity.onKeyDown). The ramp is
                    // driven solely by our Handler, and stopped by ACTION_UP.
                    return true
                }

                if (event.action == KeyEvent.ACTION_UP) {
                    Timber.d("onKeyEvent: ACTION_UP, stopping ramp")
                    stopHoldRamp()
                    holdDirection = 0
                    return true
                }

                return true
            }
        }

        return false
    }

    private val holdRampRunnable = object : Runnable {
        override fun run() {
            if (holdDirection == 0) {
                return
            }

            val heldFor = android.os.SystemClock.uptimeMillis() - holdStartTime
            val (stepDb, interval) = computeRampParams(heldFor)
            adjustVolume(holdDirection * stepDb)

            holdTickInterval = interval
            mainHandler.postDelayed(this, interval)
        }
    }

    private fun startHoldRamp() {
        mainHandler.removeCallbacks(holdRampRunnable)
        // First accelerated tick after initial interval
        mainHandler.postDelayed(holdRampRunnable, holdTickInterval)
    }

    private fun stopHoldRamp() {
        mainHandler.removeCallbacks(holdRampRunnable)
    }

    /**
     * Compute (stepDb, intervalMs) based on how long the button has been held.
     * This accelerated ramp is part of the UX contract and must be preserved:
     *
     *   0–500ms:   0.5 dB / 300ms  — gentle, precise start
     *   500–1500ms: 1.0 dB / 200ms  — moderate
     *   1500–3000ms: 2.0 dB / 150ms — fast
     *   3000–5000ms: 4.0 dB / 120ms — very fast
     *   5000ms+:    8.0 dB / 100ms  — sweep
     */
    private fun computeRampParams(heldForMs: Long): Pair<Double, Long> {
        return when {
            heldForMs < 500   -> STEP_DB * 1  to 300L
            heldForMs < 1500  -> STEP_DB * 2  to 200L
            heldForMs < 3000  -> STEP_DB * 4  to 150L
            heldForMs < 5000  -> STEP_DB * 8  to 120L
            else              -> STEP_DB * 16 to 100L
        }
    }

    private fun adjustVolume(deltaDb: Double) {
        controller?.adjustGain(deltaDb, VolumeSource.HARDWARE_KEY)
            ?: Timber.w("adjustVolume: controller unavailable, change dropped")
    }
}
