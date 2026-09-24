package me.timschneeberger.rootlessjamesdsp.interop

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import me.timschneeberger.rootlessjamesdsp.interop.structure.EelVmVariable
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import timber.log.Timber
import java.util.Timer
import kotlin.concurrent.schedule
import kotlin.math.log10

class JamesDspLocalEngine(context: Context, callbacks: JamesDspWrapper.JamesDspCallbacks? = null) : JamesDspBaseEngine(context, callbacks) {
    var handle: JamesDspHandle = JamesDspWrapper.alloc(callbacks ?: DummyCallbacks())

    override var sampleRate: Float
        set(value) {
            super.sampleRate = value
            JamesDspWrapper.setSamplingRate(handle, value, false)
            context.sendLocalBroadcast(Intent(Constants.ACTION_SAMPLE_RATE_UPDATED))
        }
        get() = super.sampleRate
    override var enabled: Boolean = true

    init {
        if(BenchmarkManager.hasBenchmarksCached())
            BenchmarkManager.loadBenchmarksFromCache()
    }

    override fun close() {
        setLoudnessAutoVolume(false)

        val oldHandle = handle
        handle = 0

        // Make sure ongoing async calls to native have enough time to finish
        Timer().schedule(100) {
            JamesDspWrapper.free(oldHandle)
            Timber.d("Handle $oldHandle has been freed")
        }
    }

    // Processing
    fun processInt16(input: ShortArray, output: ShortArray, offset: Int = -1, length: Int = -1)
    {
        if(!enabled || handle == 0L)
        {
            if(offset < 0 && length < 0) {
                input.copyInto(output)
            }
            else {
                input.copyInto(output, 0, offset, offset + length)
            }
        }
        else {
            JamesDspWrapper.processInt16(handle, input, output, offset, length)
        }
    }

    fun processInt32(input: IntArray, output: IntArray, offset: Int = -1, length: Int = -1)
    {
        if(!enabled || handle == 0L)
        {
            if(offset < 0 && length < 0) {
                input.copyInto(output)
            }
            else {
                input.copyInto(output, 0, offset, offset + length)
            }
        }
        else {
            JamesDspWrapper.processInt32(handle, input, output, offset, length)
        }
    }

    fun processFloat(input: FloatArray, output: FloatArray, offset: Int = -1, length: Int = -1)
    {
        if(!enabled || handle == 0L)
        {
            if(offset < 0 && length < 0) {
                input.copyInto(output)
            }
            else {
                input.copyInto(output, 0, offset, offset + length)
            }
        }
        else {
            JamesDspWrapper.processFloat(handle, input, output, offset, length)
        }
    }

    // Effect config
    override fun setOutputControl(threshold: Float, release: Float, postGain: Float): Boolean {
        return JamesDspWrapper.setLimiter(handle, threshold, release) and JamesDspWrapper.setPostGain(handle, postGain)
    }

    override fun setReverb(enable: Boolean, preset: Int): Boolean
    {
        return JamesDspWrapper.setReverb(handle, enable, preset)
    }

    override fun setCrossfeed(enable: Boolean, mode: Int): Boolean
    {
        return JamesDspWrapper.setCrossfeed(handle, enable, mode, 0, 0)
    }

    override fun setCrossfeedCustom(enable: Boolean, fcut: Int, feed: Int): Boolean
    {
        return JamesDspWrapper.setCrossfeed(handle, enable, 99, fcut, feed)
    }

    override fun setBassBoost(enable: Boolean, maxGain: Float): Boolean
    {
        return JamesDspWrapper.setBassBoost(handle, enable, maxGain)
    }

    override fun setStereoEnhancement(enable: Boolean, level: Float): Boolean
    {
        return JamesDspWrapper.setStereoEnhancement(handle, enable, level)
    }

    override fun setVacuumTube(enable: Boolean, level: Float): Boolean
    {
        return JamesDspWrapper.setVacuumTube(handle, enable, level)
    }

    override fun setMultiEqualizerInternal(
        enable: Boolean,
        filterType: Int,
        interpolationMode: Int,
        bands: DoubleArray
    ): Boolean {
        return JamesDspWrapper.setMultiEqualizer(handle, enable, filterType, interpolationMode, bands)
    }

    override fun setCompanderInternal(
        enable: Boolean,
        timeConstant: Float,
        granularity: Int,
        tfTransforms: Int,
        bands: DoubleArray
    ): Boolean {
        return JamesDspWrapper.setCompander(handle, enable, timeConstant, granularity, tfTransforms, bands)
    }

    override fun setVdcInternal(enable: Boolean, vdc: String): Boolean {
        return JamesDspWrapper.setVdc(handle, enable, vdc)
    }

    override fun setConvolverInternal(
        enable: Boolean,
        impulseResponse: FloatArray,
        irChannels: Int,
        irFrames: Int,
        irCrc: Int,
        irSampleRate: Int,
    ): Boolean {
        return JamesDspWrapper.setConvolver(handle, enable, impulseResponse, irChannels, irFrames)
    }

    override fun setGraphicEqInternal(enable: Boolean, bands: String): Boolean {
        // Передаём EQ-кривую в AndroidEq (Movie Mode) — поддерживает per-channel стерео
        // AndroidEq.setCurve парсит строку (включая STEREO_GRAPHIC_EQ_SPLIT) и запускает refit
        if (me.timschneeberger.rootlessjamesdsp.utils.SdkCheck.isPie)
            me.timschneeberger.rootlessjamesdsp.androideq.AndroidEq.setCurve(enable, bands)

        // Native JamesDspWrapper не понимает стерео payload с CHANNEL_SPLIT —
        // передаём только левую кривую (или моно, если кривые одинаковы).
        val nativeBands = bands.substringBefore(JamesDspBaseEngine.STEREO_GRAPHIC_EQ_SPLIT)
        return JamesDspWrapper.setGraphicEq(handle, enable, nativeBands)
    }

    override fun setLiveprogInternal(enable: Boolean, name: String, script: String): Boolean {
        return JamesDspWrapper.setLiveprog(handle, enable, name, script)
    }

    override fun setParametricEqCascade(
        enable: Boolean,
        sampleRate: Double,
        preampDb: Double,
        bands: List<me.timschneeberger.rootlessjamesdsp.model.ParametricEqBand>
    ): Boolean {
        // Biquad coefficients depend on sample rate; applying with sr=0 produces
        // invalid coefficients (division by zero) and silences audio. Skip until
        // a valid sample rate is known — re-applied when sample rate arrives.
        if (enable && bands.isNotEmpty() && sampleRate <= 0.0) {
            Timber.w("setParametricEqCascade: skipping, sample rate not yet known ($sampleRate)")
            return false
        }

        if (!enable || bands.isEmpty()) {
            return JamesDspWrapper.setParametricEq(
                handle, false, sampleRate, preampDb,
                DoubleArray(0), DoubleArray(0), DoubleArray(0),
                IntArray(0), IntArray(0)
            )
        }

        val freq = DoubleArray(bands.size)
        val gain = DoubleArray(bands.size)
        val q = DoubleArray(bands.size)
        val type = IntArray(bands.size)
        val chan = IntArray(bands.size)

        for ((i, band) in bands.withIndex()) {
            freq[i] = band.frequency
            gain[i] = band.gain
            q[i] = band.q
            type[i] = band.filterType.code
            chan[i] = band.channel.code
        }

        return JamesDspWrapper.setParametricEq(
            handle, true, sampleRate, preampDb,
            freq, gain, q, type, chan
        )
    }

    // Feature support
    override fun supportsEelVmAccess(): Boolean { return true }
    override fun supportsCustomCrossfeed(): Boolean { return true }
    override fun supportsParametricEqCascade(): Boolean {
        // В Movie Mode нативный PEQ-каскад недоступен — нет capture loop.
        // Fallback: PEQ мёрджится в GraphicEQ → AndroidEq (DynamicsProcessing).
        return !me.timschneeberger.rootlessjamesdsp.androideq.AndroidEq.isEnabled
    }
    override fun supportsLoudnessCorrection(): Boolean { return true }

    // Loudness correction
    override fun setLoudnessCorrection(
        enable: Boolean,
        sampleRate: Double,
        referenceLevel: Double,
        referenceOffset: Double,
        attenuation: Double,
        currentVolumeDb: Double
    ): Boolean {
        // Biquad coefficients depend on sample rate; applying with sr=0 produces
        // invalid coefficients (division by zero) and silences audio. Skip until
        // a valid sample rate is known — re-applied when sample rate arrives.
        if (enable && sampleRate <= 0.0) {
            Timber.w("setLoudnessCorrection: skipping, sample rate not yet known ($sampleRate)")
            return false
        }

        return JamesDspWrapper.setLoudnessCorrection(
            handle, enable, sampleRate,
            referenceLevel, referenceOffset, attenuation, currentVolumeDb
        )
    }

    override fun setLoudnessCorrectionVolume(currentVolumeDb: Double): Boolean {
        if (handle == 0L) return false
        return JamesDspWrapper.setLoudnessCorrectionVolume(handle, currentVolumeDb)
    }

    // ---- Auto system volume tracking ----

    private var volumeReceiverRegistered = false
    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == VOLUME_CHANGED_ACTION) {
                val volDb = getCurrentMediaVolumeDb()
                JamesDspWrapper.setLoudnessCorrectionVolume(handle, volDb)
            }
        }
    }

    override fun setLoudnessAutoVolume(enable: Boolean) {
        if (enable && !volumeReceiverRegistered) {
            val filter = IntentFilter(VOLUME_CHANGED_ACTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(volumeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(volumeReceiver, filter)
            }
            volumeReceiverRegistered = true
            // Push initial volume
            val volDb = getCurrentMediaVolumeDb()
            JamesDspWrapper.setLoudnessCorrectionVolume(handle, volDb)
            Timber.d("Loudness auto-volume tracking enabled (vol=%.1f dB)", volDb)
        } else if (!enable && volumeReceiverRegistered) {
            try {
                context.unregisterReceiver(volumeReceiver)
            } catch (e: Exception) {
                Timber.w(e, "Failed to unregister volume receiver")
            }
            volumeReceiverRegistered = false
            Timber.d("Loudness auto-volume tracking disabled")
        }
    }

    override fun getCurrentMediaVolumeDb(): Double {
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (maxVol <= 0) return 0.0
        if (curVol <= 0) return -60.0 // mute → maximum correction
        val ratio = curVol.toDouble() / maxVol.toDouble()
        return 20.0 * log10(ratio.coerceIn(0.001, 1.0))
    }

    // EEL VM utilities
    override fun enumerateEelVariables(): ArrayList<EelVmVariable>
    {
        return JamesDspWrapper.enumerateEelVariables(handle)
    }

    override fun manipulateEelVariable(name: String, value: Float): Boolean
    {
        return JamesDspWrapper.manipulateEelVariable(handle, name, value)
    }

    override fun freezeLiveprogExecution(freeze: Boolean)
    {
        JamesDspWrapper.freezeLiveprogExecution(handle, freeze)
    }

    companion object {
        // AudioManager.VOLUME_CHANGED_ACTION is a hidden API
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
    }
}
