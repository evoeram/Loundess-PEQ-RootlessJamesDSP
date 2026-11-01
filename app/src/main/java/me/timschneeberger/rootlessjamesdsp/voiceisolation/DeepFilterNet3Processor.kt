package me.timschneeberger.rootlessjamesdsp.voiceisolation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Streaming DeepFilterNet3 speech enhancer (https://github.com/Rikorose/DeepFilterNet,
 * MIT/Apache-2.0, weights from huggingface.co/bitsydarel/deepfilternet3-onnx).
 *
 * Two stages share one 960-point STFT: an ERB gain stage that attenuates every FFT bin of the band a
 * gain was estimated for, and a deep-filter stage that replaces the lowest 96 bins with a five-frame
 * linear combination. Three graphs run per hop -- encoder, ERB decoder, DF decoder -- and every
 * convolution history frame and recurrent state travels through the `stream_*` inputs the bundled
 * assets were re-exported to expose, because upstream hides them in `Pad` nodes and GRU state.
 * [DeepFilterNet3GraphTest] is the proof that the re-export still computes what it did before.
 *
 * The exported network is causal, which costs it two frames of lookahead: the gains computed from
 * hop `t` belong to frame `t-2` and the DF coefficients span frames `t-4..t` around that same frame,
 * so the enhanced audio leaves [dryDelayHops] hops later than the STFT-only backends answer.
 *
 * Gating follows the reference: below -10 dB of estimated local SNR the frame is muted, above 30 dB
 * nothing is applied, and above 20 dB the DF stage is left out. Leaving a decoder out freezes its
 * state too, which is what the reference does by never calling it.
 */
internal class DeepFilterNet3Processor(models: List<ByteArray>) : EnhancerBackend {
    private val environment = OrtEnvironment.getEnvironment()
    private val encoder = newSession(models[ENCODER])
    private val erbDecoder = newSession(models[ERB_DECODER])
    private val dfDecoder = newSession(models[DF_DECODER])

    private val stft = StftProcessor(FFT_SIZE, HOP_SIZE, WindowType.Vorbis)
    private val bins = stft.binCount
    private val erbWidths = erbBandWidths()

    private val analysis = FloatArray(stft.spectrumSize)
    private val frames = Array(DF_ORDER) { FloatArray(stft.spectrumSize) }
    private val enhanced = FloatArray(stft.spectrumSize)
    private val muted = FloatArray(stft.spectrumSize)

    private val erbNorm = initMeanNorm()
    private val specNorm = initUnitNorm()
    private val featErb = FloatArray(ERB_BANDS)
    private val featSpec = FloatArray(DF_BINS * SPEC_PLANES)
    private val erbHistory = Array(2) { FloatArray(ERB_BANDS) }
    private val specHistory = Array(2) { FloatArray(DF_BINS * SPEC_PLANES) }

    private val encFeatErb = Input(1, 1, 1, ERB_BANDS)
    private val encFeatSpec = Input(1, SPEC_PLANES, 1, DF_BINS)
    private val encErbPast1 = Input(1, 1, 1, ERB_BANDS)
    private val encErbPast2 = Input(1, 1, 1, ERB_BANDS)
    private val encSpecPast1 = Input(1, SPEC_PLANES, 1, DF_BINS)
    private val encSpecPast2 = Input(1, SPEC_PLANES, 1, DF_BINS)
    private val encHidden = Input(1, 1, HIDDEN)

    private val erbEmbedding = Input(1, 1, EMBEDDING)
    private val erbE0 = Input(1, CONV_CHANNELS, 1, ERB_BANDS)
    private val erbE1 = Input(1, CONV_CHANNELS, 1, ERB_BANDS / 2)
    private val erbE2 = Input(1, CONV_CHANNELS, 1, ERB_BANDS / 4)
    private val erbE3 = Input(1, CONV_CHANNELS, 1, ERB_BANDS / 4)
    private val erbHidden1 = Input(1, 1, HIDDEN)
    private val erbHidden2 = Input(1, 1, HIDDEN)

    private val dfEmbedding = Input(1, 1, EMBEDDING)
    private val dfC0 = Input(1, CONV_CHANNELS, 1, DF_BINS)
    private val dfHidden1 = Input(1, 1, HIDDEN)
    private val dfHidden2 = Input(1, 1, HIDDEN)
    private val dfC0Past = Array(DF_ORDER - 1) { Input(1, CONV_CHANNELS, 1, DF_BINS) }
    private val dfC0History = Array(DF_ORDER - 1) { FloatArray(CONV_CHANNELS * DF_BINS) }

    private val allInputs = listOf(
        encFeatErb, encFeatSpec, encErbPast1, encErbPast2, encSpecPast1, encSpecPast2, encHidden,
        erbEmbedding, erbE0, erbE1, erbE2, erbE3, erbHidden1, erbHidden2,
        dfEmbedding, dfC0, dfHidden1, dfHidden2,
    ) + dfC0Past.toList()

    private val encoderInputs = mapOf(
        "feat_erb" to encFeatErb.tensor,
        "feat_spec" to encFeatSpec.tensor,
        "stream_erb_conv0_in1" to encErbPast1.tensor,
        "stream_erb_conv0_in2" to encErbPast2.tensor,
        "stream_df_conv0_in1" to encSpecPast1.tensor,
        "stream_df_conv0_in2" to encSpecPast2.tensor,
        "stream_h1_in" to encHidden.tensor,
    )
    private val erbInputs = mapOf(
        "emb" to erbEmbedding.tensor,
        "e3" to erbE3.tensor,
        "e2" to erbE2.tensor,
        "e1" to erbE1.tensor,
        "e0" to erbE0.tensor,
        "stream_h1_in" to erbHidden1.tensor,
        "stream_h2_in" to erbHidden2.tensor,
    )
    private val dfInputs = mapOf(
        "emb" to dfEmbedding.tensor,
        "c0" to dfC0.tensor,
        "stream_h1_in" to dfHidden1.tensor,
        "stream_h2_in" to dfHidden2.tensor,
        "stream_df_convp_in1" to dfC0Past[0].tensor,
        "stream_df_convp_in2" to dfC0Past[1].tensor,
        "stream_df_convp_in3" to dfC0Past[2].tensor,
        "stream_df_convp_in4" to dfC0Past[3].tensor,
    )

    private val embedding = FloatArray(EMBEDDING)
    private val featureMap0 = FloatArray(CONV_CHANNELS * ERB_BANDS)
    private val featureMap1 = FloatArray(CONV_CHANNELS * ERB_BANDS / 2)
    private val featureMap2 = FloatArray(CONV_CHANNELS * ERB_BANDS / 4)
    private val featureMap3 = FloatArray(CONV_CHANNELS * ERB_BANDS / 4)
    private val convPath = FloatArray(CONV_CHANNELS * DF_BINS)
    private val snrEstimate = FloatArray(1)
    private val gains = FloatArray(ERB_BANDS)
    private val coefficients = FloatArray(DF_BINS * DF_ORDER * SPEC_PLANES)

    private var hops = 0
    private var dfRuns = 0
    private var skipCounter = 0
    private var smoothedInferenceMs = 0f

    override val modelSampleRate = SAMPLE_RATE
    override val hopSize = HOP_SIZE
    override val dryDelayHops = LOOKAHEAD

    init {
        require(models.size == 3) { "DeepFilterNet3 is three graphs, got ${models.size}" }
        require(encoder.inputNames == encoderInputs.keys) {
            "Encoder lacks the streaming inputs: expected ${encoderInputs.keys}, found ${encoder.inputNames}"
        }
        require(erbDecoder.inputNames == erbInputs.keys) {
            "ERB decoder lacks the streaming inputs: found ${erbDecoder.inputNames}"
        }
        require(dfDecoder.inputNames == dfInputs.keys) {
            "DF decoder lacks the streaming inputs: found ${dfDecoder.inputNames}"
        }
    }

    override fun process(input: FloatArray, output: FloatArray) {
        require(input.size == hopSize && output.size == hopSize)

        stft.analyze(input, analysis)
        analysis.copyInto(frames[hops % DF_ORDER])
        val hop = hops++

        skipCounter = if (meanSquare(input) < SILENCE_POWER) skipCounter + 1 else 0
        if (skipCounter > MAX_SKIPPED_HOPS) {
            // Nothing is fed to the networks, so no state moves -- but the STFT keeps running, or
            // the gap would slide the enhanced voice out of alignment with the dry one.
            stft.synthesize(muted, output)
            return
        }

        val startedAt = System.nanoTime()
        computeFeatures()
        val stages = stages(runEncoder())

        // The gains estimated from hop `t` belong to frame t-LOOKAHEAD, whose slot is still untouched.
        frames[(hop + DF_ORDER - LOOKAHEAD) % DF_ORDER].copyInto(enhanced)
        if (stages.applyZeros) {
            enhanced.fill(0f)
        } else if (stages.applyGains) {
            runErbDecoder()
            applyErbGains()
        }
        if (stages.applyDf) {
            runDfDecoder()
            applyDeepFilter(hop)
        }
        stft.synthesize(enhanced, output)

        val elapsed = (System.nanoTime() - startedAt) / 1_000_000f
        smoothedInferenceMs = if (smoothedInferenceMs == 0f) {
            elapsed
        } else {
            smoothedInferenceMs * 0.9f + elapsed * 0.1f
        }
    }

    override fun inferenceMs(): Float = smoothedInferenceMs

    override fun reset() {
        stft.reset()
        frames.forEach { it.fill(0f) }
        erbHistory.forEach { it.fill(0f) }
        specHistory.forEach { it.fill(0f) }
        dfC0History.forEach { it.fill(0f) }
        initMeanNorm().copyInto(erbNorm)
        initUnitNorm().copyInto(specNorm)
        allInputs.forEach(Input::clear)
        hops = 0
        dfRuns = 0
        skipCounter = 0
        smoothedInferenceMs = 0f
    }

    override fun close() {
        allInputs.forEach(Input::close)
        encoder.close()
        erbDecoder.close()
        dfDecoder.close()
    }

    /** ERB band energies and the unit-normalised low bins of the hop that was just analysed. */
    private fun computeFeatures() {
        var bin = 0
        for (band in 0 until ERB_BANDS) {
            val width = erbWidths[band]
            var power = 0f
            for (offset in 0 until width) {
                val re = analysis[bin + offset] * WNORM
                val imaginary = analysis[bins + bin + offset] * WNORM
                power += re * re + imaginary * imaginary
            }
            val decibels = log10(power / width + 1e-10f) * 10f
            erbNorm[band] = decibels * (1f - NORM_ALPHA) + erbNorm[band] * NORM_ALPHA
            featErb[band] = (decibels - erbNorm[band]) / NORM_SCALE
            bin += width
        }

        for (lowBin in 0 until DF_BINS) {
            val re = analysis[lowBin] * WNORM
            val imaginary = analysis[bins + lowBin] * WNORM
            specNorm[lowBin] = sqrt(re * re + imaginary * imaginary) * (1f - NORM_ALPHA) +
                specNorm[lowBin] * NORM_ALPHA
            val scale = 1f / sqrt(specNorm[lowBin])
            featSpec[lowBin] = re * scale
            featSpec[DF_BINS + lowBin] = imaginary * scale
        }
    }

    private fun runEncoder(): Float {
        encFeatErb.set(featErb)
        encFeatSpec.set(featSpec)
        encErbPast1.set(erbHistory[0])
        encErbPast2.set(erbHistory[1])
        encSpecPast1.set(specHistory[0])
        encSpecPast2.set(specHistory[1])

        encoder.run(encoderInputs).use { result ->
            result.read("lsnr", snrEstimate)
            result.read("emb", embedding)
            result.read("e0", featureMap0)
            result.read("e1", featureMap1)
            result.read("e2", featureMap2)
            result.read("e3", featureMap3)
            result.read("c0", convPath)
            encHidden.store(result.value("stream_h1_out"))
        }

        erbHistory[1].copyInto(erbHistory[0])
        featErb.copyInto(erbHistory[1])
        specHistory[1].copyInto(specHistory[0])
        featSpec.copyInto(specHistory[1])
        return snrEstimate[0]
    }

    private fun runErbDecoder() {
        erbEmbedding.set(embedding)
        erbE0.set(featureMap0)
        erbE1.set(featureMap1)
        erbE2.set(featureMap2)
        erbE3.set(featureMap3)
        erbDecoder.run(erbInputs).use { result ->
            result.read("m", gains)
            erbHidden1.store(result.value("stream_h1_out"))
            erbHidden2.store(result.value("stream_h2_out"))
        }
    }

    private fun runDfDecoder() {
        dfEmbedding.set(embedding)
        dfC0.set(convPath)
        // Slot `f % (DF_ORDER - 1)` holds the c0 of DF run `f`, so the four past frames of this run
        // start at the slot the new frame is about to take.
        for (past in 0 until DF_ORDER - 1) {
            dfC0Past[past].set(dfC0History[(dfRuns + past) % (DF_ORDER - 1)])
        }
        dfDecoder.run(dfInputs).use { result ->
            result.read("coefs", coefficients)
            dfHidden1.store(result.value("stream_h1_out"))
            dfHidden2.store(result.value("stream_h2_out"))
        }
        convPath.copyInto(dfC0History[dfRuns % (DF_ORDER - 1)])
        dfRuns++
    }

    /** Every bin of a band takes its band's gain, the replication libDF calls `apply_mask`. */
    private fun applyErbGains() {
        var bin = 0
        for (band in 0 until ERB_BANDS) {
            val gain = gains[band]
            val width = erbWidths[band]
            for (offset in 0 until width) {
                enhanced[bin + offset] *= gain
                enhanced[bins + bin + offset] *= gain
            }
            bin += width
        }
    }

    /**
     * Replaces the lowest [DF_BINS] bins of the target frame with a filtering over the five analysed
     * frames around it. Tap 0 is the oldest frame, so the target sits in the middle of the window.
     */
    private fun applyDeepFilter(hop: Int) {
        for (bin in 0 until DF_BINS) {
            enhanced[bin] = 0f
            enhanced[bins + bin] = 0f
        }
        val oldest = (hop + 1) % DF_ORDER // tap 0 reads frame hop-(DF_ORDER-1), which is this slot
        for (tap in 0 until DF_ORDER) {
            val source = frames[(oldest + tap) % DF_ORDER]
            var coefficient = tap * SPEC_PLANES
            for (bin in 0 until DF_BINS) {
                val re = source[bin]
                val imaginary = source[bins + bin]
                val filterRe = coefficients[coefficient]
                val filterIm = coefficients[coefficient + 1]
                enhanced[bin] += re * filterRe - imaginary * filterIm
                enhanced[bins + bin] += re * filterIm + imaginary * filterRe
                coefficient += DF_ORDER * SPEC_PLANES
            }
        }
    }

    private fun stages(lsnr: Float): Stages = when {
        lsnr < MIN_DB_THRESH -> Stages(applyGains = false, applyZeros = true, applyDf = false)
        lsnr > MAX_DB_ERB_THRESH -> Stages(applyGains = false, applyZeros = false, applyDf = false)
        else -> Stages(applyGains = true, applyZeros = false, applyDf = lsnr <= MAX_DB_DF_THRESH)
    }

    private class Stages(val applyGains: Boolean, val applyZeros: Boolean, val applyDf: Boolean)

    /** Port of libDF's `erb_fb`: how many FFT bins each ERB band covers, lowest band first. */
    private fun erbBandWidths(): IntArray {
        val freqWidth = SAMPLE_RATE.toFloat() / FFT_SIZE
        val low = freq2erb(0f)
        val step = (freq2erb(SAMPLE_RATE / 2f) - low) / ERB_BANDS
        val widths = IntArray(ERB_BANDS)
        var previous = 0
        var borrowed = 0
        for (band in 0 until ERB_BANDS) {
            val edge = (erb2freq(low + (band + 1) * step) / freqWidth).roundToInt()
            var count = edge - previous - borrowed
            if (count < MIN_BAND_FREQS) {
                borrowed = MIN_BAND_FREQS - count
                count = MIN_BAND_FREQS
            } else {
                borrowed = 0
            }
            widths[band] = count
            previous = edge
        }
        widths[ERB_BANDS - 1]++
        val tooLarge = widths.sum() - bins
        if (tooLarge > 0) widths[ERB_BANDS - 1] -= tooLarge
        require(widths.sum() == bins) { "ERB bands cover ${widths.sum()} of $bins bins" }
        return widths
    }

    private fun freq2erb(frequency: Float): Float = ERB_RATE * ln(1f + frequency / (ERB_CORNER * ERB_RATE))

    private fun erb2freq(band: Float): Float =
        ERB_CORNER * ERB_RATE * (exp((band / ERB_RATE).toDouble()).toFloat() - 1f)

    private fun meanSquare(input: FloatArray): Float {
        var power = 0f
        for (sample in input) power += sample * sample
        return power / input.size
    }

    private fun initMeanNorm(): FloatArray =
        FloatArray(ERB_BANDS) { MEAN_NORM_MIN + it * (MEAN_NORM_MAX - MEAN_NORM_MIN) / (ERB_BANDS - 1) }

    private fun initUnitNorm(): FloatArray =
        FloatArray(DF_BINS) { UNIT_NORM_MIN + it * (UNIT_NORM_MAX - UNIT_NORM_MIN) / (DF_BINS - 1) }

    private fun newSession(model: ByteArray): OrtSession {
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        return options.use { environment.createSession(model, it) }
    }

    /**
     * A graph input that outlives every run. The tensor wraps the buffer, so refilling the buffer is
     * all the next run sees -- and storing a recurrent state back into the buffer it was read from
     * turns that output into the following hop's input without a copy.
     */
    private inner class Input(vararg shape: Int) : AutoCloseable {
        private val size = shape.reduce { total, dimension -> total * dimension }
        private val buffer: FloatBuffer = ByteBuffer
            .allocateDirect(size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        val tensor: OnnxTensor = OnnxTensor.createTensor(
            environment,
            buffer,
            shape.map { it.toLong() }.toLongArray(),
        )

        fun set(values: FloatArray) {
            require(values.size == size) { "Input of $size given ${values.size} values" }
            for (index in 0 until size) buffer.put(index, values[index])
        }

        fun store(value: OnnxTensor) {
            val source = value.floatBuffer
            require(source.remaining() == size) { "State of $size came back as ${source.remaining()}" }
            var index = 0
            while (source.hasRemaining()) buffer.put(index++, source.get())
        }

        fun clear() {
            for (index in 0 until size) buffer.put(index, 0f)
        }

        override fun close() = tensor.close()
    }

    private fun OrtSession.Result.value(name: String): OnnxTensor {
        val found = get(name)
        check(found.isPresent) { "DeepFilterNet3 graph has no output $name" }
        return found.get() as OnnxTensor
    }

    private fun OrtSession.Result.read(name: String, destination: FloatArray) {
        val source = value(name).floatBuffer
        source.rewind()
        require(source.remaining() == destination.size) {
            "Output $name holds ${source.remaining()} floats, expected ${destination.size}"
        }
        source.get(destination)
    }

    companion object {
        private const val SAMPLE_RATE = 48_000
        private const val FFT_SIZE = 960
        private const val HOP_SIZE = 480
        private const val ENCODER = 0
        private const val ERB_DECODER = 1
        private const val DF_DECODER = 2

        private const val ERB_BANDS = 32
        private const val DF_BINS = 96
        private const val SPEC_PLANES = 2
        private const val DF_ORDER = 5
        private const val LOOKAHEAD = 2
        private const val MIN_BAND_FREQS = 2
        private const val CONV_CHANNELS = 64
        private const val HIDDEN = 256
        private const val EMBEDDING = CONV_CHANNELS * ERB_BANDS / 4

        private const val ERB_RATE = 9.265f
        private const val ERB_CORNER = 24.7f

        /** libDF scales the analysed spectrum by `1 / (fft^2 / (2 * hop))` before measuring anything. */
        private const val WNORM = 1f / (FFT_SIZE * FFT_SIZE / (2 * HOP_SIZE)).toFloat()

        /** exp(-480/48000 / tau=1) rounded to three digits, the way libDF's `calc_norm_alpha` does. */
        private const val NORM_ALPHA = 0.99f
        private const val NORM_SCALE = 40f

        private const val MEAN_NORM_MIN = -60f
        private const val MEAN_NORM_MAX = -90f
        private const val UNIT_NORM_MIN = 0.001f
        private const val UNIT_NORM_MAX = 0.0001f

        private const val MIN_DB_THRESH = -10f
        private const val MAX_DB_ERB_THRESH = 30f
        private const val MAX_DB_DF_THRESH = 20f

        /** Mean square, not its root: the reference cuts on the same power measure. */
        private const val SILENCE_POWER = 1e-7f
        private const val MAX_SKIPPED_HOPS = 5
    }
}
