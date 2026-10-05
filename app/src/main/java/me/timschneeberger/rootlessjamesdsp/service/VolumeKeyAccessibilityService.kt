package me.timschneeberger.rootlessjamesdsp.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import org.koin.core.context.GlobalContext
import timber.log.Timber

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
class VolumeKeyAccessibilityService : AccessibilityService() {

    private var lastVolumeDownTime = 0L
    private var volumeDownRepeatCount = 0
    private var lastVolumeUpTime = 0L
    private var volumeUpRepeatCount = 0

    companion object {
        /** Volume step in dB per key press. */
        private const val STEP_DB = 0.5

        /** Maximum internal volume in dB. */
        private const val MAX_VOLUME_DB = 0.0

        /** Minimum internal volume in dB (full attenuation). */
        private const val MIN_VOLUME_DB = -60.0

        /** Time window (ms) within which a key event is considered a repeat. */
        private const val REPEAT_WINDOW_MS = 400L

        const val ACTION_VOLUME_CHANGED = "me.timschneeberger.rootlessjamesdsp.SMOOTH_VOLUME_CHANGED"
        const val EXTRA_VOLUME_DB = "volume_db"
    }

    private var currentVolumeDb = 0.0
    private var isSmoothVolumeEnabled = false
    private var preferences: Preferences.App? = null
    private var overlay: me.timschneeberger.rootlessjamesdsp.service.VolumeOverlay? = null

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
        overlay = VolumeOverlay(this)
    }

    private fun loadSettings() {
        val prefs = preferences ?: return
        isSmoothVolumeEnabled = prefs.get(R.string.key_smooth_volume_enabled)
        currentVolumeDb = prefs.get<Float>(R.string.key_smooth_volume_db).toDouble()
        Timber.i("Smooth volume enabled=$isSmoothVolumeEnabled, currentVolume=${currentVolumeDb}dB")
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
        if (!isSmoothVolumeEnabled) return false

        val action = event.action
        val keyCode = event.keyCode

        if (action != KeyEvent.ACTION_DOWN) return false

        val now = System.currentTimeMillis()

        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                // Track repeats manually: if events come quickly, count as held
                if (now - lastVolumeUpTime < REPEAT_WINDOW_MS) {
                    volumeUpRepeatCount++
                } else {
                    volumeUpRepeatCount = 0
                }
                lastVolumeUpTime = now

                val step = computeStep(volumeUpRepeatCount)
                adjustVolume(+step)
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (now - lastVolumeDownTime < REPEAT_WINDOW_MS) {
                    volumeDownRepeatCount++
                } else {
                    volumeDownRepeatCount = 0
                }
                lastVolumeDownTime = now

                val step = computeStep(volumeDownRepeatCount)
                adjustVolume(-step)
                return true
            }
        }

        return false
    }

    private fun computeStep(repeatCount: Int): Double {
        // Accelerated adjustment when button is held:
        // 0 (first press) = 0.5 dB
        // 1-3 = 1.0 dB
        // 4-9 = 2.0 dB
        // 10+ = 3.0 dB
        return when (repeatCount) {
            0 -> STEP_DB
            in 1..3 -> STEP_DB * 2
            in 4..9 -> STEP_DB * 4
            else -> STEP_DB * 6
        }
    }

    private fun adjustVolume(deltaDb: Double) {
        val newVolume = (currentVolumeDb + deltaDb)
            .coerceIn(MIN_VOLUME_DB, MAX_VOLUME_DB)

        if (newVolume == currentVolumeDb) return

        currentVolumeDb = newVolume

        // Persist the volume
        preferences?.set(R.string.key_smooth_volume_db, currentVolumeDb.toFloat())

        // Broadcast volume change to DSP engine (applies to AudioTrack.setVolume)
        val intent = Intent(ACTION_VOLUME_CHANGED).apply {
            setPackage(packageName)
            putExtra(EXTRA_VOLUME_DB, currentVolumeDb)
        }
        sendLocalBroadcast(intent)

        Timber.i("Smooth volume broadcast sent: ${currentVolumeDb}dB")

        // Show volume overlay HUD
        overlay?.show(currentVolumeDb)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        overlay?.hide()
        overlay = null
        Timber.i("VolumeKeyAccessibilityService unbound")
        return false
    }
}
