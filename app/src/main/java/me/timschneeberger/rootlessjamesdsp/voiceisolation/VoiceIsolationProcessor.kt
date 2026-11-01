package me.timschneeberger.rootlessjamesdsp.voiceisolation

import android.content.Context
import timber.log.Timber
import kotlin.math.log10

/**
 * Block-level Voice Isolation stage for the rootless capture loop.
 *
 * The enhancer core works on 16 kHz mono hops, so each stereo channel gets its own
 * decimator -> hop loop -> interpolator chain. The dry estimate used by the mixer is taken after
 * the decimator, so voice and background are sample-aligned and the subtraction that synthesises
 * the background does not comb-filter the voice.
 */
class VoiceIsolationProcessor(context: Context) : AutoCloseable {
    private val model: ByteArray? = runCatching {
        context.assets.open(GtcrnProcessor.MODEL_ASSET).use { it.readBytes() }
    }.onFailure { Timber.e(it, "VoiceIsolation: model asset ${GtcrnProcessor.MODEL_ASSET} unavailable") }.getOrNull()

    @Volatile
    private var pipeline: Pipeline? = null

    @Volatile
    var settings: VoiceIsolationSettings = VoiceIsolationSettings()

    @Volatile
    var enabled: Boolean = false
        set(value) {
            if (value != field) {
                field = value
                if (value) pipeline?.reset()
            }
        }

    val available: Boolean get() = model != null

    /** Adds [latencyMs] to the block it processes; a block always yields exactly its own frame count. */
    val latencyMs: Float
        get() = pipeline?.latencyMs ?: 0f

    fun configure(sampleRate: Int) {
        if (sampleRate <= 0) return
        val current = pipeline
        if (current != null && current.sampleRate == sampleRate) return

        synchronized(this) {
            if (pipeline?.sampleRate == sampleRate) return
            val created = runCatching { Pipeline(sampleRate, requireNotNull(model)) }
                .onFailure { Timber.e(it, "VoiceIsolation: pipeline for $sampleRate Hz failed to build") }
                .getOrNull()
            pipeline?.close()
            pipeline = created
            if (created != null) {
                Timber.i("VoiceIsolation: pipeline ready at ${sampleRate}Hz, latency %.1f ms".format(created.latencyMs))
            } else {
                enabled = false
            }
        }
    }

    fun processFloat(buffer: FloatArray, frames: Int, offset: Int = 0) {
        if (!enabled) return
        val chain = pipeline ?: return
        try {
            chain.process(buffer, frames, offset, settings)
        } catch (e: Exception) {
            enabled = false
            Timber.e(e, "VoiceIsolation: disabled after a processing failure")
        }
    }

    fun processShort(buffer: ShortArray, frames: Int, offset: Int = 0) {
        if (!enabled) return
        val chain = pipeline ?: return
        val samples = frames * 2
        try {
            val scratch = chain.scratch(samples)
            PcmConverter.toFloatRange(buffer, scratch, offset, samples)
            chain.process(scratch, frames, 0, settings)
            PcmConverter.toShortRange(scratch, buffer, offset, samples)
        } catch (e: Exception) {
            enabled = false
            Timber.e(e, "VoiceIsolation: disabled after a processing failure")
        }
    }

    fun inferenceMs(): Float = pipeline?.maxInferenceMs() ?: 0f

    /** Real-time budget of one enhancer hop (16 ms at 16 kHz). */
    fun hopBudgetMs(): Float = StftProcessor.HOP_SIZE * 1000f / GtcrnProcessor.SAMPLE_RATE

    fun speechDetected(): Boolean = pipeline?.channels?.any { it.mixer.speechDetected } ?: false

    fun effectiveReduction(): Float = pipeline?.channels?.maxOfOrNull { it.mixer.effectiveReduction } ?: 0f

    /**
     * Peak output amplitude in dBFS since the last call. This is what tells a stage that mixes
     * audio apart from one that only burns CPU while writing silence.
     */
    fun takeOutputLevelDb(): Float {
        val chain = pipeline ?: return SILENCE_DB
        val peak = chain.outputPeak
        chain.outputPeak = 0f
        return if (peak <= 0f) SILENCE_DB else 20f * log10(peak)
    }

    override fun close() {
        synchronized(this) {
            pipeline?.close()
            pipeline = null
        }
    }

    private class Pipeline(val sampleRate: Int, modelBytes: ByteArray) : AutoCloseable {
        val channels = arrayOf(Channel(sampleRate, modelBytes), Channel(sampleRate, modelBytes))
        val latencyMs: Float = channels.first().latencyMs
        var outputPeak = 0f

        private var scratch: FloatArray = FloatArray(0)

        fun scratch(size: Int): FloatArray {
            if (scratch.size < size) scratch = FloatArray(size)
            return scratch
        }

        fun process(buffer: FloatArray, frames: Int, offset: Int, settings: VoiceIsolationSettings) {
            for (channel in channels.indices) {
                channels[channel].process(buffer, channel, frames, offset, settings)
            }
            var index = offset
            val last = offset + frames * 2
            while (index < last) {
                val sample = if (buffer[index] < 0f) -buffer[index] else buffer[index]
                if (sample > outputPeak) outputPeak = sample
                index++
            }
        }

        fun maxInferenceMs(): Float = channels.maxOf { it.model.inferenceMs() }

        fun reset() {
            channels.forEach { it.reset() }
        }

        override fun close() {
            channels.forEach { it.close() }
        }
    }

    private class Channel(sampleRate: Int, modelBytes: ByteArray) : AutoCloseable {
        val model = GtcrnProcessor(modelBytes)
        val mixer = IsolationIntensityMixer(StftProcessor.HOP_SIZE)
        private val framing = HopPipeline(sampleRate) { dry, mixed, settings ->
            val isolated = model.process(dry)
            mixer.mix(dry, isolated, mixed, settings)
        }

        val latencyMs: Float get() = framing.latencyMs

        fun process(buffer: FloatArray, channel: Int, frames: Int, offset: Int, settings: VoiceIsolationSettings) =
            framing.process(buffer, channel, frames, offset, settings)

        fun reset() {
            framing.reset()
            model.reset()
            mixer.reset()
        }

        override fun close() {
            model.close()
        }
    }

    companion object {
        private const val SILENCE_DB = -120f
    }
}
