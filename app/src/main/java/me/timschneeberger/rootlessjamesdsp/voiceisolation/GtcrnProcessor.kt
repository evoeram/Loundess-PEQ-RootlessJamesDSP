package me.timschneeberger.rootlessjamesdsp.voiceisolation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs

/**
 * Streaming GTCRN speech enhancer: STFT in, enhanced voice spectrum out, with the model's recurrent
 * caches held between frames. Ported from Voice Isolation for Android
 * (https://github.com/sk2andy/android-realtime-voice-isolation, MIT).
 *
 * The model emits the enhanced voice only; background suppression is done downstream by
 * [IsolationIntensityMixer].
 */
internal class GtcrnProcessor(model: ByteArray) : EnhancerBackend {
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val stft = StftProcessor(FFT_SIZE, HOP_SIZE, WindowType.SqrtHann)

    private val mixBuffer = directFloatBuffer(SPECTRUM_SIZE)
    private val convolutionCache = directFloatBuffer(CONVOLUTION_CACHE_SIZE)
    private val attentionCache = directFloatBuffer(ATTENTION_CACHE_SIZE)
    private val recurrentCache = directFloatBuffer(RECURRENT_CACHE_SIZE)

    private val mixTensor: OnnxTensor
    private val convolutionTensor: OnnxTensor
    private val attentionTensor: OnnxTensor
    private val recurrentTensor: OnnxTensor
    private val inputs: Map<String, OnnxTensor>

    private val inputSpectrum = FloatArray(SPECTRUM_SIZE)
    private val enhancedSpectrum = FloatArray(SPECTRUM_SIZE)
    private val interleavedSpectrum = FloatArray(SPECTRUM_SIZE)

    private var smoothedInferenceMs = 0f

    override val modelSampleRate: Int = SAMPLE_RATE
    override val hopSize: Int = HOP_SIZE

    init {
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = options.use { environment.createSession(model, it) }

        mixTensor = OnnxTensor.createTensor(environment, mixBuffer, MIX_SHAPE)
        convolutionTensor = OnnxTensor.createTensor(environment, convolutionCache, CONVOLUTION_SHAPE)
        attentionTensor = OnnxTensor.createTensor(environment, attentionCache, ATTENTION_SHAPE)
        recurrentTensor = OnnxTensor.createTensor(environment, recurrentCache, RECURRENT_SHAPE)
        inputs = mapOf(
            "mix" to mixTensor,
            "conv_cache" to convolutionTensor,
            "tra_cache" to attentionTensor,
            "inter_cache" to recurrentTensor,
        )
    }

    override fun process(input: FloatArray, output: FloatArray) {
        require(input.size == HOP_SIZE && output.size == HOP_SIZE)
        stft.analyze(input, inputSpectrum)

        mixBuffer.clear()
        for (bin in 0 until BIN_COUNT) {
            mixBuffer.put(inputSpectrum[bin])
            mixBuffer.put(inputSpectrum[bin + BIN_COUNT])
        }
        mixBuffer.rewind()

        val startedAt = System.nanoTime()
        session.run(inputs).use { result ->
            copyTensor(result, "enh", interleavedSpectrum)
            copyTensor(result, "conv_cache_out", convolutionCache)
            copyTensor(result, "tra_cache_out", attentionCache)
            copyTensor(result, "inter_cache_out", recurrentCache)
        }
        trackInferenceTime(startedAt)

        for (bin in 0 until BIN_COUNT) {
            enhancedSpectrum[bin] = interleavedSpectrum[bin * 2]
            enhancedSpectrum[bin + BIN_COUNT] = interleavedSpectrum[bin * 2 + 1]
        }
        stft.synthesize(enhancedSpectrum, output)
        limit(output)
    }

    override fun inferenceMs(): Float = smoothedInferenceMs

    override fun reset() {
        clearBuffer(convolutionCache)
        clearBuffer(attentionCache)
        clearBuffer(recurrentCache)
        stft.reset()
        smoothedInferenceMs = 0f
    }

    override fun close() {
        mixTensor.close()
        convolutionTensor.close()
        attentionTensor.close()
        recurrentTensor.close()
        session.close()
    }

    private fun trackInferenceTime(startedAtNanos: Long) {
        val inferenceMs = (System.nanoTime() - startedAtNanos) / 1_000_000f
        smoothedInferenceMs = if (smoothedInferenceMs == 0f) {
            inferenceMs
        } else {
            smoothedInferenceMs * 0.9f + inferenceMs * 0.1f
        }
    }

    private fun copyTensor(result: OrtSession.Result, name: String, destination: FloatArray) {
        val tensor = result.get(name).orElseThrow {
            IllegalStateException("Missing ONNX output: $name")
        } as OnnxTensor
        val source = tensor.floatBuffer
        source.rewind()
        source.get(destination)
    }

    private fun copyTensor(result: OrtSession.Result, name: String, destination: FloatBuffer) {
        val tensor = result.get(name).orElseThrow {
            IllegalStateException("Missing ONNX output: $name")
        } as OnnxTensor
        val source = tensor.floatBuffer
        source.rewind()
        destination.clear()
        destination.put(source)
        destination.rewind()
    }

    private fun limit(frame: FloatArray) {
        var peak = 0f
        for (sample in frame) peak = maxOf(peak, abs(sample))
        if (peak > 0.98f) {
            val scale = 0.98f / peak
            for (index in frame.indices) frame[index] *= scale
        }
    }

    private fun directFloatBuffer(size: Int): FloatBuffer =
        ByteBuffer.allocateDirect(size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

    private fun clearBuffer(buffer: FloatBuffer) {
        buffer.clear()
        while (buffer.hasRemaining()) buffer.put(0f)
        buffer.rewind()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val FFT_SIZE = 512
        const val HOP_SIZE = 256
        const val BIN_COUNT = FFT_SIZE / 2 + 1
        const val SPECTRUM_SIZE = BIN_COUNT * 2
        internal const val MODEL_ASSET = "gtcrn.onnx"
        private const val CONVOLUTION_CACHE_SIZE = 2 * 1 * 16 * 16 * 33
        private const val ATTENTION_CACHE_SIZE = 2 * 3 * 1 * 1 * 16
        private const val RECURRENT_CACHE_SIZE = 2 * 1 * 33 * 16

        private val MIX_SHAPE = longArrayOf(1, 257, 1, 2)
        private val CONVOLUTION_SHAPE = longArrayOf(2, 1, 16, 16, 33)
        private val ATTENTION_SHAPE = longArrayOf(2, 3, 1, 1, 16)
        private val RECURRENT_SHAPE = longArrayOf(2, 1, 33, 16)
    }
}
