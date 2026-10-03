package me.timschneeberger.rootlessjamesdsp.preference

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.util.AttributeSet
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.interop.PreferenceCache
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.LoudnessCurveCalculator
import me.timschneeberger.rootlessjamesdsp.view.LoudnessSurface

/**
 * Preference that embeds a real-time loudness correction curve visualization.
 * Listens to loudness preference changes AND system volume changes (when
 * auto-volume is enabled) and redraws the curve.
 */
class LoudnessGraphPreference : Preference, SharedPreferences.OnSharedPreferenceChangeListener {

    private var surface: LoudnessSurface? = null
    private val loudnessPrefs = PreferenceCache.getPreferences(context, Constants.PREF_LOUDNESS)
    private var volumeReceiverRegistered = false

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
        this(context, attrs, defStyleAttr, 0)

    constructor(context: Context, attrs: AttributeSet?) :
        this(context, attrs, androidx.preference.R.attr.preferenceStyle)

    constructor(context: Context) : this(context, null)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) :
        super(context, attrs, defStyleAttr, defStyleRes) {
        layoutResource = R.layout.preference_loudness_graph
    }

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == VOLUME_CHANGED_ACTION) {
                postUpdate()
            }
        }
    }

    override fun onAttached() {
        super.onAttached()
        loudnessPrefs.registerOnSharedPreferenceChangeListener(this)
        registerVolumeReceiver()
    }

    override fun onDetached() {
        loudnessPrefs.unregisterOnSharedPreferenceChangeListener(this)
        unregisterVolumeReceiver()
        super.onDetached()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        surface = holder.findViewById(R.id.loudness_graph_surface) as? LoudnessSurface
        updateGraph()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        // Re-register volume receiver in case auto_volume setting changed
        registerVolumeReceiver()
        postUpdate()
    }

    private fun registerVolumeReceiver() {
        val autoVolume = loudnessPrefs.getBoolean(
            context.getString(R.string.key_loudness_auto_volume), false)
        val enabled = loudnessPrefs.getBoolean(
            context.getString(R.string.key_loudness_enable), false)

        if (enabled && autoVolume && !volumeReceiverRegistered) {
            val filter = IntentFilter(VOLUME_CHANGED_ACTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(volumeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(volumeReceiver, filter)
            }
            volumeReceiverRegistered = true
        } else if ((!enabled || !autoVolume) && volumeReceiverRegistered) {
            unregisterVolumeReceiver()
        }
    }

    private fun unregisterVolumeReceiver() {
        if (volumeReceiverRegistered) {
            try {
                context.unregisterReceiver(volumeReceiver)
            } catch (_: Exception) {}
            volumeReceiverRegistered = false
        }
    }

    private fun postUpdate() {
        surface?.post { updateGraph() }
    }

    private fun updateGraph() {
        val s = surface ?: return
        val prefs = loudnessPrefs

        val enabled = prefs.getBoolean(context.getString(R.string.key_loudness_enable), false)
        if (!enabled) {
            s.clearCurve()
            return
        }

        val mode = prefs.getString(context.getString(R.string.key_loudness_mode), "0")?.toIntOrNull() ?: 0
        val refLevel = prefs.getFloat(context.getString(R.string.key_loudness_reference_level), 0f).toDouble()
        val refOffset = prefs.getFloat(context.getString(R.string.key_loudness_reference_offset), 0f).toDouble()
        val attenuation = prefs.getFloat(context.getString(R.string.key_loudness_attenuation), 100f).toDouble() / 100.0
        val autoVolume = prefs.getBoolean(context.getString(R.string.key_loudness_auto_volume), false)

        val volume = if (autoVolume) {
            getSystemVolumeDb()
        } else {
            prefs.getFloat(context.getString(R.string.key_loudness_volume), 0f).toDouble()
        }

        val tuning = LoudnessCurveCalculator.TuningParams(
            lsFreq = prefs.getFloat(context.getString(R.string.key_loudness_ls_freq), 75f).toDouble(),
            lsSlope = prefs.getFloat(context.getString(R.string.key_loudness_ls_slope), 52f).toDouble() / 100.0,
            lsRatio = prefs.getFloat(context.getString(R.string.key_loudness_ls_ratio), 55f).toDouble() / 100.0,
            hsFreq = prefs.getFloat(context.getString(R.string.key_loudness_hs_freq), 10000f).toDouble(),
            hsSlope = prefs.getFloat(context.getString(R.string.key_loudness_hs_slope), 90f).toDouble() / 100.0,
            hsRatio = prefs.getFloat(context.getString(R.string.key_loudness_hs_ratio), 225f).toDouble() / 1000.0,
            isoBasePhon = prefs.getFloat(context.getString(R.string.key_loudness_iso_base_phon), 80f).toDouble(),
            isoQ = prefs.getFloat(context.getString(R.string.key_loudness_iso_q), 432f).toDouble() / 100.0
        )

        val result = LoudnessCurveCalculator.compute(mode, refLevel, refOffset, attenuation, volume, tuning)
        s.setCurve(result.frequencies, result.gains, result.preampDb)
    }

    private fun getSystemVolumeDb(): Double {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val maxVol = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            val curVol = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            if (maxVol <= 0) 0.0
            else if (curVol <= 0) -60.0
            else 20.0 * kotlin.math.log10((curVol.toDouble() / maxVol).coerceIn(0.001, 1.0))
        } catch (_: Exception) {
            loudnessPrefs.getFloat(context.getString(R.string.key_loudness_volume), 0f).toDouble()
        }
    }

    companion object {
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
    }
}
