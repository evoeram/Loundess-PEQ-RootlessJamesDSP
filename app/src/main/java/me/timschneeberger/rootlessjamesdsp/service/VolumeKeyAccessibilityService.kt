package me.timschneeberger.rootlessjamesdsp.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import org.koin.core.context.GlobalContext
import timber.log.Timber
import java.lang.ref.WeakReference

/**
 * AccessibilityService that intercepts hardware volume buttons globally (in background)
 * and provides fine-grained volume control by adjusting JamesDSP internal gain
 * in small steps (0.5 dB), instead of the coarse system stream volume steps.
 *
 * When enabled:
 * - Volume UP/DOWN change JamesDSP internal volume by [STEP_DB] dB
 * - The system media volume is kept at a fixed level (user-configurable target)
 * - This gives ~200 steps across a 100 dB range, vs ~15 system steps
 *
 * The service communicates volume changes to the DSP engine via broadcast,
 * which the AudioProcessorService picks up to call setLoudnessCorrectionVolume().
 *
 * Requires the user to enable this accessibility service in system settings.
 */
class VolumeKeyAccessibilityService : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        /** Volume step in dB per single key press (first press, no hold). */
        private const val STEP_DB = 0.5

        /** Maximum internal volume in dB. */
        private const val MAX_VOLUME_DB = 0.0

        /** Minimum internal volume in dB (full attenuation). */
        private const val MIN_VOLUME_DB = -60.0

        /** Time window (ms) after which a key press is considered a new press, not a continuation. */
        private const val HOLD_RESET_MS = 400L

        const val ACTION_VOLUME_CHANGED = "me.timschneeberger.rootlessjamesdsp.SMOOTH_VOLUME_CHANGED"
        const val EXTRA_VOLUME_DB = "volume_db"
    }

    private var currentVolumeDb = 0.0
    private var isSmoothVolumeEnabled = false
    private var preferences: Preferences.App? = null
    private var overlay: VolumeOverlay? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Hold-tracking state for accelerated volume ramp
    private var holdDirection = 0       // +1 = up, -1 = down, 0 = idle
    private var holdStartTime = 0L      // SystemClock uptime when hold began
    private var holdLastTickTime = 0L   // last accelerated tick
    private var holdTickInterval = 0L   // current interval between ticks (ms)

    override fun onServiceConnected() {
        super.onServiceConnected()
        Timber.i("VolumeKeyAccessibilityService connected")

        // Get preferences from Koin
        preferences = try {
            GlobalContext.get().get<Preferences.App>()
        } catch (e: Exception) {
            Timber.w(e, "Failed to get Preferences from Koin")
            null
        }

        loadSettings()
        // Register for preference changes so smooth volume can be toggled without reconnecting
        preferences?.registerOnSharedPreferenceChangeListener(this)
        // Use WeakReference to self in the callback so the overlay
        // doesn't keep the service alive after onDestroy.
        val weakThis = WeakReference(this)
        overlay = VolumeOverlay(this) { deltaDb ->
            weakThis.get()?.adjustVolume(deltaDb)
        }
    }

    private fun loadSettings() {
        val prefs = preferences ?: return
        isSmoothVolumeEnabled = prefs.get(R.string.key_smooth_volume_enabled)
        currentVolumeDb = prefs.get<Float>(R.string.key_smooth_volume_db).toDouble()
        Timber.i("Smooth volume enabled=$isSmoothVolumeEnabled, currentVolume=${currentVolumeDb}dB")
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            getString(R.string.key_smooth_volume_enabled),
            getString(R.string.key_smooth_volume_db) -> {
                loadSettings()
                Timber.i("Smooth volume settings reloaded: enabled=$isSmoothVolumeEnabled, vol=${currentVolumeDb}dB")
            }
        }
    }

    private fun configureService() {
        // No-op: XML config handles flagRequestFilterKeyEvents.
        // Setting serviceInfo at runtime causes bind/unbind loops.
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't process accessibility events, only key events
    }

    override fun onInterrupt() {
        Timber.w("VolumeKeyAccessibilityService interrupted")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val action = event.action
        val keyCode = event.keyCode
        val actionStr = when (action) {
            KeyEvent.ACTION_DOWN -> "DOWN"
            KeyEvent.ACTION_UP -> "UP"
            KeyEvent.ACTION_MULTIPLE -> "MULTIPLE"
            else -> "OTHER($action)"
        }
        val keyStr = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> "VOL_UP"
            KeyEvent.KEYCODE_VOLUME_DOWN -> "VOL_DOWN"
            else -> "KEY($keyCode)"
        }
        Timber.d("onKeyEvent: key=%s action=%s repeat=%d smoothEnabled=%b",
            keyStr, actionStr, event.repeatCount, isSmoothVolumeEnabled)

        if (!isSmoothVolumeEnabled) {
            Timber.d("onKeyEvent: smooth volume disabled, not consuming")
            return false
        }

        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                val direction = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) +1 else -1

                if (action == KeyEvent.ACTION_DOWN) {
                    val now = android.os.SystemClock.uptimeMillis()

                    if (event.repeatCount == 0) {
                        Timber.d("onKeyEvent: FIRST PRESS, starting ramp. dir=%d", direction)
                        stopHoldRamp()
                        adjustVolume(direction * STEP_DB)
                        holdDirection = direction
                        holdStartTime = now
                        holdLastTickTime = now
                        holdTickInterval = 300L
                        startHoldRamp()
                    }
                    // Note: AccessibilityService does NOT deliver repeated ACTION_DOWN
                    // events for held keys (unlike Activity.onKeyDown). The ramp is
                    // driven solely by our Handler, and stopped by ACTION_UP.
                    return true
                }

                if (action == KeyEvent.ACTION_UP) {
                    Timber.d("onKeyEvent: ACTION_UP, stopping ramp")
                    stopHoldRamp()
                    holdDirection = 0
                    return true
                }

                Timber.d("onKeyEvent: action=%s for vol key, consuming", actionStr)
                return true
            }
        }

        Timber.d("onKeyEvent: non-volume key, not consuming")
        return false
    }

    private val holdRampRunnable = object : Runnable {
        override fun run() {
            if (holdDirection == 0) {
                Timber.d("holdRamp: holdDirection=0, stopping")
                return
            }

            val now = android.os.SystemClock.uptimeMillis()
            val heldFor = now - holdStartTime

            val (stepDb, interval) = computeRampParams(heldFor)
            Timber.d("holdRamp: dir=%d heldFor=%dms step=%.1f interval=%dms",
                holdDirection, heldFor, stepDb, interval)

            adjustVolume(holdDirection * stepDb)

            holdLastTickTime = now
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
     *
     * Progression:
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
        val oldVolume = currentVolumeDb
        val newVolume = (currentVolumeDb + deltaDb)
            .coerceIn(MIN_VOLUME_DB, MAX_VOLUME_DB)

        if (newVolume == currentVolumeDb) {
            Timber.d("adjustVolume: delta=%.1f dB, vol already at limit %.1f->%.1f (clamped, no-op)",
                deltaDb, oldVolume, newVolume)
            return
        }

        currentVolumeDb = newVolume
        Timber.d("adjustVolume: delta=%.1f dB, %.1f -> %.1f dB", deltaDb, oldVolume, currentVolumeDb)

        // Persist the volume
        preferences?.set(R.string.key_smooth_volume_db, currentVolumeDb.toFloat())

        // Broadcast volume change to DSP engine (applies to AudioTrack.setVolume)
        val intent = Intent(ACTION_VOLUME_CHANGED).apply {
            setPackage(packageName)
            putExtra(EXTRA_VOLUME_DB, currentVolumeDb)
        }
        sendLocalBroadcast(intent)

        Timber.d("adjustVolume: broadcast sent, vol=%.1f dB", currentVolumeDb)

        // Show volume overlay HUD
        overlay?.show(currentVolumeDb)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // Remove overlay synchronously to prevent Context leak.
        // The AccessibilityService framework holds a Binder reference
        // (IAccessibilityServiceClientWrapper.mContext) that can keep
        // the destroyed service context alive if the overlay view is
        // still attached to the WindowManager.
        stopHoldRamp()
        holdDirection = 0
        overlay?.hide()
        overlay = null
        preferences?.unregisterOnSharedPreferenceChangeListener(this)
        preferences = null
        Timber.i("VolumeKeyAccessibilityService unbound")
        return false
    }

    override fun onDestroy() {
        stopHoldRamp()
        holdDirection = 0
        overlay?.hide()
        overlay = null
        preferences?.unregisterOnSharedPreferenceChangeListener(this)
        preferences = null
        Timber.i("VolumeKeyAccessibilityService destroyed")
        super.onDestroy()
    }
}
