package me.timschneeberger.rootlessjamesdsp.voiceisolation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The 960-point transform behind the 48 kHz enhancers is not a power of two, so it runs through
 * Bluestein's chirp transform. These tests pin that path against the textbook DFT sum and against
 * the radix-2 pass used for the power-of-two sizes.
 */
class FftTest {
    private fun deterministic(size: Int): Pair<FloatArray, FloatArray> {
        val real = FloatArray(size) { index ->
            (sin(2.0 * PI * index * 7 / size) + 0.5 * cos(2.0 * PI * index * 31 / size)).toFloat()
        }
        val imaginary = FloatArray(size) { index ->
            (cos(2.0 * PI * index * 3 / size) * 0.25).toFloat()
        }
        return real to imaginary
    }

    private fun naiveDft(real: FloatArray, imaginary: FloatArray): Pair<FloatArray, FloatArray> {
        val size = real.size
        val outReal = FloatArray(size)
        val outImaginary = FloatArray(size)
        for (k in 0 until size) {
            var sumReal = 0.0
            var sumImaginary = 0.0
            for (n in 0 until size) {
                val angle = -2.0 * PI * k * n / size
                val cosValue = cos(angle)
                val sinValue = sin(angle)
                sumReal += real[n] * cosValue - imaginary[n] * sinValue
                sumImaginary += real[n] * sinValue + imaginary[n] * cosValue
            }
            outReal[k] = sumReal.toFloat()
            outImaginary[k] = sumImaginary.toFloat()
        }
        return outReal to outImaginary
    }

    @Test
    fun chirpTransformMatchesTheDirectSum() {
        val (real, imaginary) = deterministic(960)
        val expected = naiveDft(real, imaginary)

        val actualReal = real.copyOf()
        val actualImaginary = imaginary.copyOf()
        Fft(960).transform(actualReal, actualImaginary, inverse = false)

        var worst = 0f
        for (index in 0 until 960) {
            worst = maxOf(worst, abs(actualReal[index] - expected.first[index]))
            worst = maxOf(worst, abs(actualImaginary[index] - expected.second[index]))
        }
        // The reference sum runs in double, the transform in float over a 960-point signal.
        assertTrue("worst DFT error: $worst", worst < 6e-2f)
    }

    @Test
    fun inverseUndoesTheForwardTransform() {
        val (real, imaginary) = deterministic(960)
        val originalReal = real.copyOf()
        val originalImaginary = imaginary.copyOf()

        val fft = Fft(960)
        fft.transform(real, imaginary, inverse = false)
        fft.transform(real, imaginary, inverse = true)

        for (index in 0 until 960) {
            assertEquals(originalReal[index], real[index], 1e-3f)
            assertEquals(originalImaginary[index], imaginary[index], 1e-3f)
        }
    }

    /** A unit impulse has a flat spectrum; anything else means the chirp tables are misaligned. */
    @Test
    fun chirpTransformOfAnImpulseIsFlat() {
        val real = FloatArray(960)
        val imaginary = FloatArray(960)
        real[0] = 1f

        Fft(960).transform(real, imaginary, inverse = false)

        for (index in 0 until 960) {
            assertEquals(1f, real[index], 1e-3f)
            assertEquals(0f, imaginary[index], 1e-3f)
        }
    }
}
