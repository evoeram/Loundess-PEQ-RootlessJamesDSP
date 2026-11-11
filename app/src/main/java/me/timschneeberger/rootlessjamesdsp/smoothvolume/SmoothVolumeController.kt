package me.timschneeberger.rootlessjamesdsp.smoothvolume

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import timber.log.Timber

/**
 * Single source of truth for the Smooth Volume gain.
 *
 * Every input path (hardware keys via the accessibility service, the floating
 * HUD, the in-app slider, the Quick Settings tile) calls into this controller;
 * the controller owns the persisted value, broadcasts the effective gain to the
 * audio services (root native / rootless capture / Direct Mode) and exposes a
 * [StateFlow] that UI surfaces collect. The audio services never rebroadcast,
 * so no loops are possible, and identical values are never re-applied.
 *
 * Mute is a distinct state: the user's gain is kept in [SmoothVolumeState.gainDb]
 * (and persisted), while the applied gain is silence. Mute is never merged with
 * the -60 dB step, and unmute restores [SmoothVolumeState.previousGainDb].
 *
 * Disabling Smooth Volume broadcasts an explicit 0 dB reset so no stale gain
 * stays applied in any of the three audio paths.
 */
class SmoothVolumeController(
    private val context: Context,
    private val preferences: Preferences.App,
) {
    companion object {
        /** Broadcast consumed by Root/RootlessAudioProcessorService. */
        const val ACTION_VOLUME_CHANGED = "me.timschneeberger.rootlessjamesdsp.SMOOTH_VOLUME_CHANGED"
        const val EXTRA_VOLUME_DB = "volume_db"
        const val EXTRA_VOLUME_MUTED = "volume_muted"
        const val EXTRA_VOLUME_ENABLED = "volume_enabled"

        /** Applied gain for mute: effectively silent, but distinct from the -60 dB user step. */
        const val SILENCE_DB = -96.0

        /** Max rate at which gain updates are pushed to the audio paths (~40/s). */
        private const val AUDIO_THROTTLE_MS = 25L
    }

    data class SmoothVolumeState(
        val enabled: Boolean,
        val gainDb: Double,
        val previousGainDb: Double,
        val muted: Boolean,
        val hudVisible: Boolean,
        val hudAvailable: Boolean,
        val accessibilityAvailable: Boolean,
        val overlayPermissionAvailable: Boolean,
        val dspActive: Boolean,
        val lastChangeSource: VolumeSource,
    )

    private val _state = MutableStateFlow(
        SmoothVolumeState(
            enabled = preferences.get(R.string.key_smooth_volume_enabled),
            gainDb = SmoothVolumeMath.snap(preferences.get<Float>(R.string.key_smooth_volume_db).toDouble()),
            previousGainDb = SmoothVolumeMath.snap(preferences.get<Float>(R.string.key_smooth_volume_db).toDouble()),
            muted = preferences.get(R.string.key_smooth_volume_muted),
            hudVisible = false,
            hudAvailable = false,
            accessibilityAvailable = false,
            overlayPermissionAvailable = false,
            dspActive = false,
            lastChangeSource = VolumeSource.SYSTEM,
        )
    )
    val state: StateFlow<SmoothVolumeState> = _state.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())

    // Audio broadcast throttle: coalesce rapid gesture updates, always send a
    // trailing update so the final gesture value is never lost.
    private var lastBroadcastAt = 0L
    private var broadcastPending = false
    private val flushBroadcastRunnable = Runnable {
        broadcastPending = false
        sendAudioBroadcast()
    }

    init {
        // Normalize any out-of-range persisted value once.
        if (_state.value.gainDb != preferences.get<Float>(R.string.key_smooth_volume_db).toDouble()) {
            preferences.set(R.string.key_smooth_volume_db, _state.value.gainDb.toFloat())
        }
        setOverlayPermissionAvailable(Settings.canDrawOverlays(context))
        // Track DSP service liveness so the card/HUD never claim ACTIVE when
        // no audio path is applying the gain.
        context.registerLocalReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    Constants.ACTION_SERVICE_STARTED -> setDspActive(true)
                    Constants.ACTION_SERVICE_STOPPED -> setDspActive(false)
                }
            }
        }, IntentFilter().apply {
            addAction(Constants.ACTION_SERVICE_STARTED)
            addAction(Constants.ACTION_SERVICE_STOPPED)
        })

        // The master switch can also be flipped from the (legacy) settings
        // screen, which writes the preference directly; keep the state in sync
        // and make sure the disable path still resets the applied gain.
        val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == context.getString(R.string.key_smooth_volume_enabled)) {
                val prefEnabled = preferences.get<Boolean>(R.string.key_smooth_volume_enabled)
                if (prefEnabled != _state.value.enabled) setEnabled(prefEnabled)
            }
        }
        preferences.preferences.registerOnSharedPreferenceChangeListener(prefListener)
        Timber.i("SmoothVolumeController init: enabled=%b, gain=%.1f dB, muted=%b",
            _state.value.enabled, _state.value.gainDb, _state.value.muted)
    }

    //region Availability flags (set by services / UI)

    fun setAccessibilityAvailable(available: Boolean) {
        updateState { it.copy(accessibilityAvailable = available) }
        recomputeHudAvailability()
    }

    fun setOverlayPermissionAvailable(available: Boolean) {
        updateState { it.copy(overlayPermissionAvailable = available) }
        recomputeHudAvailability()
    }

    fun setDspActive(active: Boolean) {
        updateState { it.copy(dspActive = active) }
    }

    fun setHudVisible(visible: Boolean) {
        updateState { it.copy(hudVisible = visible) }
    }

    private fun recomputeHudAvailability() {
        val s = _state.value
        updateState { it.copy(hudAvailable = it.accessibilityAvailable || it.overlayPermissionAvailable) }
        if (s.hudAvailable != _state.value.hudAvailable) {
            Timber.d("HUD availability changed: %b (accessibility=%b, overlay=%b)",
                _state.value.hudAvailable, _state.value.accessibilityAvailable, _state.value.overlayPermissionAvailable)
        }
    }

    //endregion

    //region Gain control

    fun setGain(db: Double, source: VolumeSource) {
        val snapped = SmoothVolumeMath.snap(db)
        val old = _state.value
        if (!old.enabled) {
            Timber.d("setGain ignored (disabled): %.1f dB from %s", snapped, source)
            return
        }
        if (SmoothVolumeMath.sameStep(old.gainDb, snapped) && !old.muted) return
        if (old.muted) {
            // Adjusting the gain while muted switches to unmuted at the new value
            updateState { it.copy(gainDb = snapped, previousGainDb = snapped, muted = false, lastChangeSource = source) }
        }
        else {
            updateState { it.copy(gainDb = snapped, previousGainDb = it.gainDb, lastChangeSource = source) }
        }
        persistGain()
        pushToAudio()
        Timber.d("setGain: %.1f dB (was %.1f) from %s", snapped, old.gainDb, source)
    }

    fun adjustGain(deltaDb: Double, source: VolumeSource) {
        setGain(_state.value.gainDb + deltaDb, source)
    }

    fun mute(source: VolumeSource) {
        val old = _state.value
        if (!old.enabled || old.muted) return
        updateState { it.copy(muted = true, lastChangeSource = source) }
        preferences.set(R.string.key_smooth_volume_muted, true)
        pushToAudio()
        Timber.i("Muted at %.1f dB", old.gainDb)
    }

    fun unmute(source: VolumeSource) {
        val old = _state.value
        if (!old.enabled || !old.muted) return
        updateState { it.copy(muted = false, lastChangeSource = source) }
        preferences.set(R.string.key_smooth_volume_muted, false)
        pushToAudio()
        Timber.i("Unmuted, restored %.1f dB", old.gainDb)
    }

    fun toggleMute(source: VolumeSource) {
        if (_state.value.muted) unmute(source) else mute(source)
    }

    /** Reset to 0 dB and clear mute. */
    fun reset(source: VolumeSource) {
        val old = _state.value
        if (!old.enabled) {
            Timber.d("reset ignored (disabled)")
            return
        }
        updateState { it.copy(gainDb = SmoothVolumeMath.DEFAULT_DB, previousGainDb = SmoothVolumeMath.DEFAULT_DB, muted = false, lastChangeSource = source) }
        preferences.set(R.string.key_smooth_volume_muted, false)
        persistGain()
        pushToAudio()
        Timber.i("Reset to 0 dB")
    }

    /**
     * Master switch. Turning the feature OFF broadcasts an explicit 0 dB
     * (unmuted) reset so no stale gain remains applied in any audio path;
     * the persisted gain is also reset, per spec. Turning it ON re-applies
     * the (now 0 dB) gain.
     */
    fun setEnabled(enabled: Boolean) {
        val old = _state.value
        if (old.enabled == enabled) return
        updateState {
            it.copy(
                enabled = enabled,
                gainDb = if (enabled) it.gainDb else SmoothVolumeMath.DEFAULT_DB,
                previousGainDb = if (enabled) it.previousGainDb else SmoothVolumeMath.DEFAULT_DB,
                muted = if (enabled) it.muted else false,
                lastChangeSource = VolumeSource.SYSTEM,
            )
        }
        preferences.set(R.string.key_smooth_volume_enabled, enabled)
        preferences.set(R.string.key_smooth_volume_muted, _state.value.muted)
        persistGain()
        sendAudioBroadcast() // immediate, not throttled: disable must be a guaranteed reset
        Timber.i("Smooth volume enabled=%b (gain reset to 0 dB on disable)", enabled)
    }

    //endregion

    //region Internals

    private fun updateState(reducer: (SmoothVolumeState) -> SmoothVolumeState) {
        _state.value = reducer(_state.value)
    }

    private fun persistGain() {
        preferences.set(R.string.key_smooth_volume_db, _state.value.gainDb.toFloat())
    }

    /**
     * Push the current state to the audio paths at most every [AUDIO_THROTTLE_MS],
     * with a trailing send. Safe to call at gesture rate (60 Hz+); the DSP sees
     * ~40 updates/s max and always the final value.
     */
    private fun pushToAudio() {
        val now = SystemClock.uptimeMillis()
        val elapsed = now - lastBroadcastAt
        if (elapsed >= AUDIO_THROTTLE_MS) {
            lastBroadcastAt = now
            mainHandler.removeCallbacks(flushBroadcastRunnable)
            broadcastPending = false
            sendAudioBroadcast()
        }
        else if (!broadcastPending) {
            broadcastPending = true
            mainHandler.postDelayed(flushBroadcastRunnable, AUDIO_THROTTLE_MS - elapsed)
        }
        // else: a trailing send is already scheduled; it will read the latest state
    }

    private fun sendAudioBroadcast() {
        val s = _state.value
        val intent = Intent(ACTION_VOLUME_CHANGED).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_VOLUME_DB, s.gainDb)
            putExtra(EXTRA_VOLUME_MUTED, s.muted)
            putExtra(EXTRA_VOLUME_ENABLED, s.enabled)
        }
        context.sendLocalBroadcast(intent)
    }

    //endregion
}
