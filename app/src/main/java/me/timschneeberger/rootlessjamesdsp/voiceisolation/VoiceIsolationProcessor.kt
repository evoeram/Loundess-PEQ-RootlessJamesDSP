package me.timschneeberger.rootlessjamesdsp.voiceisolation

import android.content.Context
import timber.log.Timber
import kotlin.math.log10

/**
 * Block-level Voice Isolation stage for the rootless capture loop.
 *
 * Any [EnhancerBackend] runs inside the same chain: each stereo channel gets its own
 * decimator -> hop loop -> interpolator, followed by the mix that reconstructs the background.
 * The dry estimate used by the mixer is taken after the decimator, so voice and background are
 * sample-aligned and the subtraction that synthesises the background does not comb-filter the voice.
 */
class VoiceIsolationProcessor(private val context: Context) : AutoCloseable {
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

    /** Model the running pipeline was built for; null while nothing is loaded. */
    @Volatile
    var model: EnhancerModel? = null
        private set

    /** Adds [latencyMs] to the block it processes; a block always yields exactly its own frame count. */
    val latencyMs: Float
        get() = pipeline?.latencyMs ?: 0f

    /** Key of the model actually running, or null when the stage is inactive. */
    val activeModelKey: String?
        get() = pipeline?.modelKey

    /** Real-time budget of one enhancer hop, in milliseconds; 0 while inactive. */
    fun hopBudgetMs(): Float = pipeline?.hopBudgetMs ?: 0f

    /**
     * (Re)builds the pipeline for [sampleRate] and [model]. Returns false when the assets are
     * missing or the graphs refuse to load, in which case the stage stays off.
     */
    fun configure(sampleRate: Int, model: EnhancerModel): Boolean {
        if (sampleRate <= 0) return false
        val current = pipeline
        if (current != null && current.sampleRate == sampleRate && current.modelKey == model.key) {
            this.model = model
            enabled = true
            return true
        }

        return synchronized(this) {
            if (pipeline?.sampleRate == sampleRate && pipeline?.modelKey == model.key) {
                enabled = true
                return true
            }
            val created = runCatching { buildPipeline(sampleRate, model) }
                .onFailure { Timber.e(it, "VoiceIsolation: pipeline for $sampleRate Hz / ${model.key} failed to build") }
                .getOrNull()
            pipeline?.close()
            pipeline = created
            this.model = if (created != null) model else null
            enabled = created != null
            if (created != null) {
                Timber.i(
                    "VoiceIsolation: ${model.key} ready at ${sampleRate}Hz, " +
                        "${created.hopSize} samples/${created.modelSampleRate}Hz hop, " +
                        "latency %.1f ms".format(created.latencyMs)
                )
            }
            created != null
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
            model = null
            enabled = false
        }
    }

    private var cachedKey: String? = null
    private var cachedBytes: List<ByteArray>? = null

    private fun buildPipeline(sampleRate: Int, model: EnhancerModel): Pipeline {
        val bytes = loadAssets(model)
            ?: throw IllegalStateException("Voice Isolation model ${model.key} is not installed")
        return Pipeline(sampleRate, model, bytes)
    }

    /** Reads the model files, keeping the last set so switching back does not re-read them. */
    private fun loadAssets(model: EnhancerModel): List<ByteArray>? {
        if (model.key == cachedKey && cachedBytes?.size == model.assets.size) return cachedBytes
        val loaded = model.assets.map { name ->
            runCatching {
                context.assets.open(name).use { it.readBytes() }
            }.onFailure { Timber.e(it, "VoiceIsolation: model asset $name unavailable") }.getOrNull()
                ?: return null
        }
        cachedKey = model.key
        cachedBytes = loaded
        return loaded
    }

    private class Pipeline(
        val sampleRate: Int,
        model: EnhancerModel,
        modelBytes: List<ByteArray>,
    ) : AutoCloseable {
        val modelKey: String = model.key
        val channels = arrayOf(
            Channel(sampleRate, model, modelBytes),
            Channel(sampleRate, model, modelBytes),
        )
        val hopSize: Int = channels.first().backend.hopSize
        val modelSampleRate: Int = channels.first().backend.modelSampleRate
        val hopBudgetMs: Float = hopSize * 1000f / modelSampleRate
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

        fun maxInferenceMs(): Float = channels.maxOf { it.backend.inferenceMs() }

        fun reset() {
            channels.forEach { it.reset() }
        }

        override fun close() {
            channels.forEach { it.close() }
        }
    }

    private class Channel(sampleRate: Int, model: EnhancerModel, modelBytes: List<ByteArray>) : AutoCloseable {
        val backend: EnhancerBackend = model.create(modelBytes)
        val mixer = IsolationIntensityMixer(backend.hopSize, backend.modelSampleRate)
        private val isolated = FloatArray(backend.hopSize)

        /**
         * Dry frames kept waiting for a backend that answers with older audio; see
         * [EnhancerBackend.dryDelayHops]. Slot `step % size` always holds the hop from `size` hops
         * back, which is what the enhancer just returned.
         */
        private val dryDelay = Array(backend.dryDelayHops) { FloatArray(backend.hopSize) }
        private var dryStep = 0

        private val framing = HopPipeline(sampleRate, backend.modelSampleRate, backend.hopSize) { dry, mixed, settings ->
            backend.process(dry, isolated)
            val aligned = if (dryDelay.isEmpty()) dry else dryDelay[dryStep % dryDelay.size]
            mixer.mix(aligned, isolated, mixed, settings)
            if (aligned !== dry) dry.copyInto(aligned)
            dryStep++
        }

        /** Beyond what [HopPipeline] accounts for, only a lookahead model adds to the delay. */
        val extraLatencyMs: Float = backend.dryDelayHops * 1000f / backend.modelSampleRate

        val latencyMs: Float get() = framing.latencyMs + extraLatencyMs

        fun process(buffer: FloatArray, channel: Int, frames: Int, offset: Int, settings: VoiceIsolationSettings) =
            framing.process(buffer, channel, frames, offset, settings)

        fun reset() {
            framing.reset()
            backend.reset()
            mixer.reset()
            dryDelay.forEach { it.fill(0f) }
            dryStep = 0
        }

        override fun close() {
            backend.close()
        }
    }

    companion object {
        private const val SILENCE_DB = -120f
    }
}
