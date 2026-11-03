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
                if (value) synchronized(this) { pipeline?.reset() }
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

    // The lock serialises processing against configure()/close(): without it a model switch could
    // free the native ONNX session while the audio thread is still inside OrtSession.run.
    fun processFloat(buffer: FloatArray, frames: Int, offset: Int = 0) {
        if (!enabled) return
        try {
            synchronized(this) {
                pipeline?.process(buffer, frames, offset, settings)
            }
        } catch (e: Exception) {
            enabled = false
            Timber.e(e, "VoiceIsolation: disabled after a processing failure")
        }
    }

    fun processShort(buffer: ShortArray, frames: Int, offset: Int = 0) {
        if (!enabled) return
        val samples = frames * 2
        try {
            synchronized(this) {
                val chain = pipeline ?: return
                val scratch = chain.scratch(samples)
                PcmConverter.toFloatRange(buffer, scratch, offset, samples)
                chain.process(scratch, frames, 0, settings)
                PcmConverter.toShortRange(scratch, buffer, offset, samples)
            }
        } catch (e: Exception) {
            enabled = false
            Timber.e(e, "VoiceIsolation: disabled after a processing failure")
        }
    }

    fun inferenceMs(): Float = pipeline?.maxInferenceMs() ?: 0f

    /** Output blocks replaced by silence because the enhancer could not keep up, so far. */
    fun underruns(): Long = pipeline?.underruns() ?: 0L

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
        val latencyMs: Float get() = channels.first().latencyMs
        var outputPeak = 0f

        private var scratch: FloatArray = FloatArray(0)

        // The two channels are independent chains sharing nothing but the interleaved buffer, so one
        // of them runs on a worker while the caller does the other. Sequential stereo costs two
        // inferences per hop and pushed the 48 kHz models to ~1.6x their real-time budget.
        //
        // The handshake is a lock + epoch, not ExecutorService.submit() (OPT-03): a per-block
        // FutureTask is a steady allocation source on the audio thread, and every GC then risks a
        // "Waiting for a blocking GC" pause mid-block — audible as a micro-stutter whenever any
        // other thread (UI in particular) allocates. This path performs no per-block allocations;
        // the worker wakes only when a block arrives.
        private val workerLock = Object()
        private var blockSeq = 0L
        private var doneSeq = 0L
        private var workerFailure: Exception? = null

        @Volatile
        private var workerClosed = false

        private var taskBuffer: FloatArray? = null
        private var taskFrames = 0
        private var taskOffset = 0
        private var taskSettings: VoiceIsolationSettings? = null

        private val worker: Thread = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            var lastSeq = 0L
            while (true) {
                var buffer: FloatArray? = null
                var frames = 0
                var offset = 0
                var settings: VoiceIsolationSettings? = null
                synchronized(workerLock) {
                    while (!workerClosed && blockSeq == lastSeq) {
                        try {
                            workerLock.wait()
                        } catch (e: InterruptedException) {
                            workerClosed = true
                        }
                    }
                    if (workerClosed) return@synchronized
                    lastSeq = blockSeq
                    buffer = taskBuffer
                    frames = taskFrames
                    offset = taskOffset
                    settings = taskSettings
                }
                if (buffer == null) break
                try {
                    channels[0].process(buffer, 0, frames, offset, settings!!)
                } catch (e: Exception) {
                    // Проваливаем ошибку в caller через workerFailure; doneSeq всё равно
                    // выставляем, чтобы ожидающий блок проснулся и не завис
                    synchronized(workerLock) { workerFailure = e }
                }
                synchronized(workerLock) {
                    doneSeq = lastSeq
                    workerLock.notifyAll()
                }
            }
        }.apply {
            isDaemon = true
            name = "voice-isolation-channel"
            start()
        }

        fun scratch(size: Int): FloatArray {
            if (scratch.size < size) scratch = FloatArray(size)
            return scratch
        }

        fun process(buffer: FloatArray, frames: Int, offset: Int, settings: VoiceIsolationSettings) {
            val lock = workerLock
            val mySeq: Long
            synchronized(lock) {
                taskBuffer = buffer
                taskFrames = frames
                taskOffset = offset
                taskSettings = settings
                mySeq = ++blockSeq
                lock.notifyAll()
            }
            // Канал 1 обрабатываем на caller-потоке параллельно с worker, который берёт канал 0;
            // последовательное исполнение удвоило бы стоимость блока и превысило бы бюджет.
            var failure: Exception? = null
            try {
                channels[1].process(buffer, 1, frames, offset, settings)
            } catch (e: Exception) {
                failure = e
            }
            var interrupted = false
            synchronized(lock) {
                // Даже при собственном сбое канала 1 дожидаемся worker: иначе следующий блок
                // перезапишет поля задачи, пока worker ещё внутри нативного инференса
                while (workerFailure == null && doneSeq < mySeq && !workerClosed) {
                    try {
                        lock.wait()
                    } catch (e: InterruptedException) {
                        // Остановка capture-потока извне (BUG-02): прерываем ожидание
                        // и пропускаем блок — не сбой обработки
                        Thread.currentThread().interrupt()
                        interrupted = true
                        break
                    }
                }
                if (failure == null) failure = workerFailure
                workerFailure = null
                taskBuffer = null
                taskSettings = null
            }
            when {
                failure != null -> throw failure
                interrupted -> return
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

        fun underruns(): Long = channels.sumOf { it.underruns() }

        fun reset() {
            channels.forEach { it.reset() }
        }

        override fun close() {
            // BUG-02: снимаем флаг и будим поток из wait(); join с тем же таймаутом,
            // что раньше был у awaitTermination()
            synchronized(workerLock) {
                workerClosed = true
                workerLock.notifyAll()
            }
            worker.join(200)
            if (worker.isAlive) {
                worker.interrupt()
            }
            channels.forEach { it.close() }
        }
    }

    private class Channel(sampleRate: Int, model: EnhancerModel, modelBytes: List<ByteArray>) : AutoCloseable {
        val backend: EnhancerBackend = model.create(modelBytes)
        val mixer = IsolationIntensityMixer(
            backend.hopSize,
            backend.modelSampleRate,
            initialDelayHops = 1 + backend.dryDelayHops,
        )
        private val isolated = FloatArray(backend.hopSize)

        private val framing = HopPipeline(sampleRate, backend.modelSampleRate, backend.hopSize) { dry, mixed, settings ->
            backend.process(dry, isolated)
            mixer.mix(dry, isolated, mixed, settings)
        }

        /** HopPipeline includes the STFT hop; the mixer reports any additional measured delay. */
        val extraLatencyMs: Float
            get() = mixerExtraLatencyMs(mixer.detectedDelayHops, backend.hopSize, backend.modelSampleRate)

        val latencyMs: Float get() = framing.latencyMs + extraLatencyMs

        fun process(buffer: FloatArray, channel: Int, frames: Int, offset: Int, settings: VoiceIsolationSettings) =
            framing.process(buffer, channel, frames, offset, settings)

        fun underruns(): Long = framing.underruns

        fun reset() {
            framing.reset()
            backend.reset()
            mixer.reset()
        }

        override fun close() {
            backend.close()
        }
    }

    companion object {
        private const val SILENCE_DB = -120f
    }
}

/**
 * Additional latency beyond the STFT hop reported by the mixer, in milliseconds.
 * [detectedDelayHops] counts hops of [hopSize] samples at [modelSampleRate], so a hop
 * is `hopSize * 1000 / modelSampleRate` ms — not `1000 / modelSampleRate` ms (BUG-08).
 * One hop is already accounted for by `HopPipeline.latencyMs`, hence the `-1`.
 */
internal fun mixerExtraLatencyMs(detectedDelayHops: Int, hopSize: Int, modelSampleRate: Int): Float =
    (detectedDelayHops - 1).coerceAtLeast(0) * hopSize * 1000f / modelSampleRate
