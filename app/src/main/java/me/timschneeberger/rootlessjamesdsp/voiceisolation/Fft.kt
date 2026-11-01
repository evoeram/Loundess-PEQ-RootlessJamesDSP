package me.timschneeberger.rootlessjamesdsp.voiceisolation

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Complex DFT of one fixed length.
 *
 * Power-of-two lengths transform in place with a radix-2 Cooley-Tukey pass. The 48 kHz enhancers
 * ask for a 960-point transform, which is not a power of two, so those lengths go through
 * Bluestein's chirp transform: the DFT becomes a convolution that is evaluated with two radix-2
 * transforms of the next power of two. The result is the exact 960-point DFT, which padding the
 * frame to 1024 would not be.
 */
internal class Fft(val size: Int) {
    private val powerOfTwo = size and (size - 1) == 0
    private val workSize = if (powerOfTwo) size else nextPowerOfTwo(2 * size - 1)
    private val workReal = FloatArray(workSize)
    private val workImaginary = FloatArray(workSize)

    /** chirp[n] = exp(-i*pi*n^2/size); only meaningful for the Bluestein path. */
    private val chirpReal = if (powerOfTwo) FloatArray(0) else FloatArray(size) { n ->
        cos(-PI * n * n / size).toFloat()
    }
    private val chirpImaginary = if (powerOfTwo) FloatArray(0) else FloatArray(size) { n ->
        sin(-PI * n * n / size).toFloat()
    }

    /** DFT of the negated chirp kernel, precomputed once for the Bluestein path. */
    private val kernelReal: FloatArray
    private val kernelImaginary: FloatArray

    init {
        if (powerOfTwo) {
            kernelReal = FloatArray(0)
            kernelImaginary = FloatArray(0)
        } else {
            val real = FloatArray(workSize)
            val imaginary = FloatArray(workSize)
            for (m in 0..size) {
                val angle = PI * m * m / size
                real[m] = cos(angle).toFloat()
                imaginary[m] = sin(angle).toFloat()
            }
            // The convolution kernel has to be defined for negative offsets as well; it is even.
            for (m in 1 until size) {
                real[workSize - m] = real[m]
                imaginary[workSize - m] = imaginary[m]
            }
            radix2(real, imaginary, workSize, inverse = false)
            kernelReal = real
            kernelImaginary = imaginary
        }
    }

    /**
     * Transforms [real]/[imaginary] of [size] samples in place. The inverse is normalised by 1/size,
     * matching the unnormalised forward pass.
     */
    fun transform(real: FloatArray, imaginary: FloatArray, inverse: Boolean) {
        if (powerOfTwo) {
            radix2(real, imaginary, size, inverse)
            return
        }
        if (inverse) {
            // IDFT(X) = conj(chirp * DFT_M(DFT_M(chirp * conj(X)) * K)) / size, i.e. the forward
            // pass applied to conj(X) with the output chirp conjugated as well.
            for (n in 0 until size) {
                workReal[n] = real[n] * chirpReal[n] + imaginary[n] * chirpImaginary[n]
                workImaginary[n] = real[n] * chirpImaginary[n] - imaginary[n] * chirpReal[n]
            }
            bluestein()
            val scale = 1f / size
            for (k in 0 until size) {
                val transformedReal = workReal[k]
                val transformedImaginary = workImaginary[k]
                real[k] = (transformedReal * chirpReal[k] - transformedImaginary * chirpImaginary[k]) * scale
                imaginary[k] = -(transformedReal * chirpImaginary[k] + transformedImaginary * chirpReal[k]) * scale
            }
            return
        }
        for (n in 0 until size) {
            workReal[n] = real[n] * chirpReal[n] - imaginary[n] * chirpImaginary[n]
            workImaginary[n] = real[n] * chirpImaginary[n] + imaginary[n] * chirpReal[n]
        }
        bluestein()
        for (k in 0 until size) {
            real[k] = workReal[k] * chirpReal[k] - workImaginary[k] * chirpImaginary[k]
            imaginary[k] = workReal[k] * chirpImaginary[k] + workImaginary[k] * chirpReal[k]
        }
    }

    private fun bluestein() {
        for (n in size until workSize) {
            workReal[n] = 0f
            workImaginary[n] = 0f
        }
        radix2(workReal, workImaginary, workSize, inverse = false)
        for (index in 0 until workSize) {
            val real = workReal[index]
            val imaginary = workImaginary[index]
            workReal[index] = real * kernelReal[index] - imaginary * kernelImaginary[index]
            workImaginary[index] = real * kernelImaginary[index] + imaginary * kernelReal[index]
        }
        radix2(workReal, workImaginary, workSize, inverse = true)
    }

    private fun radix2(real: FloatArray, imaginary: FloatArray, length: Int, inverse: Boolean) {
        var reversed = 0
        for (current in 1 until length) {
            var bit = length shr 1
            while (reversed and bit != 0) {
                reversed = reversed xor bit
                bit = bit shr 1
            }
            reversed = reversed xor bit
            if (current < reversed) {
                val realValue = real[current]
                real[current] = real[reversed]
                real[reversed] = realValue

                val imaginaryValue = imaginary[current]
                imaginary[current] = imaginary[reversed]
                imaginary[reversed] = imaginaryValue
            }
        }

        var stage = 2
        while (stage <= length) {
            val angle = (if (inverse) 2.0 else -2.0) * PI / stage
            val stepReal = cos(angle).toFloat()
            val stepImaginary = sin(angle).toFloat()

            var offset = 0
            while (offset < length) {
                var weightReal = 1f
                var weightImaginary = 0f
                for (index in 0 until stage / 2) {
                    val oddIndex = offset + index + stage / 2
                    val oddReal = weightReal * real[oddIndex] - weightImaginary * imaginary[oddIndex]
                    val oddImaginary = weightReal * imaginary[oddIndex] + weightImaginary * real[oddIndex]
                    val evenIndex = offset + index
                    val evenReal = real[evenIndex]
                    val evenImaginary = imaginary[evenIndex]

                    real[evenIndex] = evenReal + oddReal
                    imaginary[evenIndex] = evenImaginary + oddImaginary
                    real[oddIndex] = evenReal - oddReal
                    imaginary[oddIndex] = evenImaginary - oddImaginary

                    val nextWeightReal = weightReal * stepReal - weightImaginary * stepImaginary
                    weightImaginary = weightReal * stepImaginary + weightImaginary * stepReal
                    weightReal = nextWeightReal
                }
                offset += stage
            }
            stage = stage shl 1
        }

        if (inverse) {
            val scale = 1f / length
            for (index in 0 until length) {
                real[index] *= scale
                imaginary[index] *= scale
            }
        }
    }

    private fun nextPowerOfTwo(minimum: Int): Int {
        var candidate = 1
        while (candidate < minimum) candidate = candidate shl 1
        return candidate
    }
}
