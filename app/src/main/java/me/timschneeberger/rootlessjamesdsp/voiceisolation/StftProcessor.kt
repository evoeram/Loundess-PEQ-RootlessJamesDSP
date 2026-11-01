package me.timschneeberger.rootlessjamesdsp.voiceisolation

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Window shapes the enhancers specify. Both are power-complementary at 50% overlap. */
internal enum class WindowType {
    SqrtHann,
    Vorbis,
    ;

    fun build(size: Int): FloatArray = when (this) {
        SqrtHann -> FloatArray(size) { index ->
            sqrt(0.5 - 0.5 * cos(2.0 * PI * index / size)).toFloat()
        }
        // Nested sine window used by the DPDFNet exports: s = sin(0.5*pi*(n+0.5)/half).
        Vorbis -> {
            val half = size / 2.0
            FloatArray(size) { index ->
                val s = sin(0.5 * PI * (index + 0.5) / half)
                sin(0.5 * PI * s * s).toFloat()
            }
        }
    }
}

/**
 * Sliding short-time Fourier transform pair: [fftSize] samples analysed with a [hopSize] advance and
 * an overlap-add synthesis. Perfect reconstruction needs a window whose squared magnitudes sum to
 * one over consecutive hops, which both [WindowType]s satisfy at hop = fftSize/2.
 *
 * The spectrum is stored split: real bins first, then the imaginary ones. Frames are analysed
 * without look-ahead — the newest [hopSize] samples occupy the second half of the window — so each
 * hop costs exactly one hop of delay.
 */
internal class StftProcessor(
    val fftSize: Int,
    val hopSize: Int,
    windowType: WindowType,
) {
    val binCount = fftSize / 2 + 1
    val spectrumSize = binCount * 2

    private val window = windowType.build(fftSize)
    private val analysisBuffer = FloatArray(fftSize)
    private val overlapBuffer = FloatArray(fftSize)
    private val real = FloatArray(fftSize)
    private val imaginary = FloatArray(fftSize)
    private val fft = Fft(fftSize)

    init {
        require(hopSize > 0 && hopSize <= fftSize)
        require(analysisBuffer.size == window.size)
    }

    fun analyze(input: FloatArray, spectrum: FloatArray) {
        require(input.size == hopSize)
        require(spectrum.size == spectrumSize)

        analysisBuffer.copyInto(
            destination = analysisBuffer,
            destinationOffset = 0,
            startIndex = hopSize,
            endIndex = fftSize,
        )
        input.copyInto(analysisBuffer, destinationOffset = fftSize - hopSize)

        for (index in 0 until fftSize) {
            real[index] = analysisBuffer[index] * window[index]
            imaginary[index] = 0f
        }
        fft.transform(real, imaginary, inverse = false)

        for (bin in 0 until binCount) {
            spectrum[bin] = real[bin]
            spectrum[bin + binCount] = imaginary[bin]
        }
    }

    fun synthesize(spectrum: FloatArray, output: FloatArray) {
        require(spectrum.size == spectrumSize)
        require(output.size == hopSize)

        for (bin in 0 until binCount) {
            real[bin] = spectrum[bin]
            imaginary[bin] = spectrum[bin + binCount]
        }
        for (bin in 1 until binCount - 1) {
            real[fftSize - bin] = real[bin]
            imaginary[fftSize - bin] = -imaginary[bin]
        }
        fft.transform(real, imaginary, inverse = true)

        for (index in 0 until fftSize) {
            overlapBuffer[index] += real[index] * window[index]
        }
        overlapBuffer.copyInto(output, endIndex = hopSize)
        overlapBuffer.copyInto(
            destination = overlapBuffer,
            destinationOffset = 0,
            startIndex = hopSize,
            endIndex = fftSize,
        )
        overlapBuffer.fill(0f, fromIndex = fftSize - hopSize)
    }

    fun reset() {
        analysisBuffer.fill(0f)
        overlapBuffer.fill(0f)
        real.fill(0f)
        imaginary.fill(0f)
    }
}
