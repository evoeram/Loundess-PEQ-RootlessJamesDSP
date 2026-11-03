package me.timschneeberger.rootlessjamesdsp.voiceisolation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Streaming DPDFNet speech enhancer (https://github.com/ceva-ip/DPDFNet, Apache-2.0).
 *
 * The exported graph is a single hop: one complex spectrum frame in, one enhanced frame out, plus a
 * flat recurrent state that carries the ERB and spectral normalisation statistics. Everything the
 * pipeline needs — rate, window, hop, bin count, state size and the initial statistics — is read
 * from the model's own ONNX metadata, so both 48 kHz checkpoints configure themselves.
 *
 * The reference script analyses the whole file with a centred, reflect-padded STFT and throws away
 * the first four hops, which is offline warm-up for the cold normalisation state. Here the state is
 * seeded from the metadata instead and the sliding window runs with one hop of delay.
 */
internal class DpdfNetProcessor(model: ByteArray) : EnhancerBackend {
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val metadata: Map<String, String>

    private val stft: StftProcessor
    private val stateSize: Int
    private val initialState: FloatArray

    private val stateBuffer: FloatBuffer
    private val specBuffer: FloatBuffer
    private val specTensor: OnnxTensor
    private val stateTensor: OnnxTensor
    private val inputs: Map<String, OnnxTensor>
    private val specName: String
    private val stateName: String
    private val inputSpectrum: FloatArray
    private val enhancedSpectrum: FloatArray
    private val interleaved: FloatArray

    private var smoothedInferenceMs = 0f

    override val modelSampleRate: Int
    override val hopSize: Int

    init {
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = options.use { environment.createSession(model, it) }
        metadata = session.metadata.customMetadata

        require(metadata["model_type"] == "dpdfnet") {
            "Not a DPDFNet model: metadata says ${metadata["model_type"]}"
        }
        val sampleRate = metadata.integer("sample_rate")
        val fftSize = metadata.integer("n_fft")
        val hopLength = metadata.integer("hop_length")
        val windowLength = metadata.integer("window_length")
        val freqBins = metadata.integer("freq_bins")
        require(windowLength == fftSize) {
            "DPDFNet expects a $windowLength-sample window inside a $fftSize-point transform; " +
                "only the equal case is supported"
        }
        require(hopLength * 2 == fftSize) { "DPDFNet hop $hopLength must be half of n_fft $fftSize" }
        require(freqBins == fftSize / 2 + 1) { "DPDFNet bin count $freqBins does not fit n_fft $fftSize" }
        modelSampleRate = sampleRate
        hopSize = hopLength

        stft = StftProcessor(fftSize, hopLength, windowType(metadata["window_type"]))
        inputSpectrum = FloatArray(stft.spectrumSize)
        enhancedSpectrum = FloatArray(stft.spectrumSize)
        interleaved = FloatArray(stft.spectrumSize)

        stateSize = metadata.integer("state_size")
        val erbNormStateSize = metadata.integer("erb_norm_state_size")
        val specNormStateSize = metadata.integer("spec_norm_state_size")
        val erbNormInit = metadata.floats("erb_norm_init")
        val specNormInit = metadata.floats("spec_norm_init")
        require(erbNormInit.size == erbNormStateSize && specNormInit.size == specNormStateSize) {
            "DPDFNet state sizes ($erbNormStateSize+$specNormStateSize) do not match " +
                "the init vectors (${erbNormInit.size}+${specNormInit.size})"
        }
        require(stateSize >= erbNormStateSize + specNormStateSize) {
            "DPDFNet state of $stateSize cannot hold the normalisation seeds"
        }
        initialState = FloatArray(stateSize)
        erbNormInit.copyInto(initialState, 0)
        specNormInit.copyInto(initialState, erbNormStateSize)

        stateBuffer = directFloatBuffer(stateSize)
        specBuffer = directFloatBuffer(stft.spectrumSize)
        loadInitialState()

        val inputNames = session.inputNames.toList()
        require(inputNames.size == 2) { "Expected (spec, state) inputs, got $inputNames" }
        specName = inputNames[0]
        stateName = inputNames[1]
        specTensor = OnnxTensor.createTensor(environment, specBuffer, longArrayOf(1, 1, freqBins.toLong(), 2))
        stateTensor = OnnxTensor.createTensor(environment, stateBuffer, longArrayOf(stateSize.toLong()))
        inputs = mapOf(specName to specTensor, stateName to stateTensor)
    }

    override fun process(input: FloatArray, output: FloatArray) {
        require(input.size == hopSize && output.size == hopSize)
        stft.analyze(input, inputSpectrum)

        specBuffer.clear()
        for (bin in 0 until stft.binCount) {
            specBuffer.put(inputSpectrum[bin])
            specBuffer.put(inputSpectrum[bin + stft.binCount])
        }
        specBuffer.rewind()

        val startedAt = System.nanoTime()
        session.run(inputs).use { result ->
            readOutput(result, 0, enhancedSpectrum)
            readOutput(result, 1, stateBuffer)
        }
        val inferenceMs = (System.nanoTime() - startedAt) / 1_000_000f
        smoothedInferenceMs = if (smoothedInferenceMs == 0f) {
            inferenceMs
        } else {
            smoothedInferenceMs * 0.9f + inferenceMs * 0.1f
        }

        // The graph emits interleaved re/im; the transform wants the split layout.
        enhancedSpectrum.copyInto(interleaved)
        for (bin in 0 until stft.binCount) {
            enhancedSpectrum[bin] = interleaved[bin * 2]
            enhancedSpectrum[bin + stft.binCount] = interleaved[bin * 2 + 1]
        }
        stft.synthesize(enhancedSpectrum, output)
    }

    override fun inferenceMs(): Float = smoothedInferenceMs

    override fun reset() {
        loadInitialState()
        stft.reset()
        smoothedInferenceMs = 0f
    }

    override fun close() {
        specTensor.close()
        stateTensor.close()
        session.close()
    }

    private fun loadInitialState() {
        stateBuffer.clear()
        stateBuffer.put(initialState)
        stateBuffer.rewind()
    }

    private fun readOutput(result: OrtSession.Result, index: Int, destination: FloatArray) {
        val tensor = outputTensor(result, index)
        val source = tensor.floatBuffer
        source.rewind()
        source.get(destination)
    }

    private fun readOutput(result: OrtSession.Result, index: Int, destination: FloatBuffer) {
        val tensor = outputTensor(result, index)
        val source = tensor.floatBuffer
        source.rewind()
        destination.clear()
        destination.put(source)
        destination.rewind()
    }

    private fun outputTensor(result: OrtSession.Result, index: Int): OnnxTensor =
        result.get(index) as OnnxTensor

    private fun Map<String, String>.integer(key: String): Int =
        checkNotNull(this[key]) { "DPDFNet model is missing metadata: $key" }.trim().toInt()

    private fun Map<String, String>.floats(key: String): FloatArray =
        checkNotNull(this[key]) { "DPDFNet model is missing metadata: $key" }
            .split(',')
            .filter { it.isNotBlank() }
            .map { it.trim().toFloat() }
            .toFloatArray()

    private fun windowType(name: String?): WindowType = when (name) {
        "vorbis" -> WindowType.Vorbis
        "hann", "sqrt_hann" -> WindowType.SqrtHann
        else -> error("Unsupported DPDFNet window: $name")
    }

    private fun directFloatBuffer(size: Int): FloatBuffer =
        ByteBuffer.allocateDirect(size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
}
