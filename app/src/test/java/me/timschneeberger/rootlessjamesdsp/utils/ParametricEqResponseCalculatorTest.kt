package me.timschneeberger.rootlessjamesdsp.utils

import me.timschneeberger.rootlessjamesdsp.model.ParametricEqBand
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqChannel
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqFilterType
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/**
 * Unit tests for [ParametricEqResponseCalculator].
 *
 * Verifies independent L/R frequency-response computation,
 * cascade behavior, edge cases, and numerical stability.
 */
class ParametricEqResponseCalculatorTest {

    private val sampleRate = 48000.0
    private val tolerance = 0.5 // dB tolerance for peak approximation

    private fun makeCalculator(numPoints: Int = 256): ParametricEqResponseCalculator =
        ParametricEqResponseCalculator(sampleRate = sampleRate, numPoints = numPoints)

    private fun band(
        freq: Double, gain: Double, q: Double = 1.41,
        type: ParametricEqFilterType = ParametricEqFilterType.PEAKING,
        channel: ParametricEqChannel = ParametricEqChannel.LEFT_RIGHT
    ) = ParametricEqBand(freq, gain, q, type, channel)

    /** Find the response value closest to the given frequency. */
    private fun responseAt(freqs: DoubleArray, gains: DoubleArray, targetFreq: Double): Double {
        var bestIdx = 0
        var bestDist = Double.MAX_VALUE
        for (i in freqs.indices) {
            val dist = abs(freqs[i] - targetFreq)
            if (dist < bestDist) {
                bestDist = dist
                bestIdx = i
            }
        }
        return gains[bestIdx]
    }

    // ── Test 1: Empty EQ → 0 dB everywhere ──

    @Test
    fun emptyEq_returnsZeroDb() {
        val calc = makeCalculator()
        val result = calc.compute(emptyList())

        assertEquals(256, result.frequencies.size)
        result.leftResponseDb.forEach { assertEquals(0.0, it, 0.001) }
        result.rightResponseDb.forEach { assertEquals(0.0, it, 0.001) }
    }

    // ── Test 2: L +3.5 dB peak at 1 kHz → L ≈ +3.5 near 1 kHz ──

    @Test
    fun leftOnlyPeak_positiveGain() {
        val calc = makeCalculator()
        val bands = listOf(band(1000.0, 3.5, 1.41, channel = ParametricEqChannel.LEFT))
        val result = calc.compute(bands)

        val leftAt1k = responseAt(result.frequencies, result.leftResponseDb, 1000.0)
        val rightAt1k = responseAt(result.frequencies, result.rightResponseDb, 1000.0)

        assertEquals(3.5, leftAt1k, tolerance)
        assertEquals(0.0, rightAt1k, 0.01) // R unaffected
    }

    // ── Test 3: R -3.5 dB peak at 1 kHz → R ≈ -3.5 near 1 kHz ──

    @Test
    fun rightOnlyPeak_negativeGain() {
        val calc = makeCalculator()
        val bands = listOf(band(1000.0, -3.5, 1.41, channel = ParametricEqChannel.RIGHT))
        val result = calc.compute(bands)

        val leftAt1k = responseAt(result.frequencies, result.leftResponseDb, 1000.0)
        val rightAt1k = responseAt(result.frequencies, result.rightResponseDb, 1000.0)

        assertEquals(0.0, leftAt1k, 0.01) // L unaffected
        assertEquals(-3.5, rightAt1k, tolerance)
    }

    // ── Test 4: Independent channels: L +3.5, R -3.5 ──

    @Test
    fun independentChannels_remainIndependent() {
        val calc = makeCalculator()
        val bands = listOf(
            band(1000.0, 3.5, 1.41, channel = ParametricEqChannel.LEFT),
            band(1000.0, -3.5, 1.41, channel = ParametricEqChannel.RIGHT)
        )
        val result = calc.compute(bands)

        val leftAt1k = responseAt(result.frequencies, result.leftResponseDb, 1000.0)
        val rightAt1k = responseAt(result.frequencies, result.rightResponseDb, 1000.0)

        assertEquals(3.5, leftAt1k, tolerance)
        assertEquals(-3.5, rightAt1k, tolerance)

        // Verify independence: near the peak, L and R must differ significantly.
        // Find all points within 1 octave of 1 kHz and verify L > 0 and R < 0
        var checkedNearPeak = false
        for (i in result.frequencies.indices) {
            val f = result.frequencies[i]
            if (f > 500.0 && f < 2000.0) {
                assertTrue("L must be positive near peak at $f Hz", result.leftResponseDb[i] > 0.5)
                assertTrue("R must be negative near peak at $f Hz", result.rightResponseDb[i] < -0.5)
                checkedNearPeak = true
            }
        }
        assertTrue("Must have checked at least one point near peak", checkedNearPeak)
    }

    // ── Test 5: Identical L/R → curves match ──

    @Test
    fun identicalChannels_curvesMatch() {
        val calc = makeCalculator()
        val bands = listOf(
            band(1000.0, 3.5, 1.41, channel = ParametricEqChannel.LEFT_RIGHT)
        )
        val result = calc.compute(bands)

        for (i in result.frequencies.indices) {
            assertEquals(
                "L and R must match at freq ${result.frequencies[i]}",
                result.leftResponseDb[i], result.rightResponseDb[i], 0.001
            )
        }
    }

    // ── Test 6: Multiple cascaded bands ──

    @Test
    fun multipleCascadedBands() {
        val calc = makeCalculator()
        val bands = listOf(
            band(100.0, 5.0, 0.7, channel = ParametricEqChannel.LEFT_RIGHT),
            band(1000.0, -3.0, 1.41, channel = ParametricEqChannel.LEFT_RIGHT),
            band(5000.0, 4.0, 2.0, channel = ParametricEqChannel.LEFT_RIGHT)
        )
        val result = calc.compute(bands)

        // At 100 Hz, first band dominates → roughly +5 dB
        val at100 = responseAt(result.frequencies, result.leftResponseDb, 100.0)
        assertEquals(5.0, at100, 1.5)

        // At 1 kHz, second band → roughly -3 dB
        val at1k = responseAt(result.frequencies, result.leftResponseDb, 1000.0)
        assertEquals(-3.0, at1k, 1.5)

        // At 5 kHz, third band → roughly +4 dB
        val at5k = responseAt(result.frequencies, result.leftResponseDb, 5000.0)
        assertEquals(4.0, at5k, 1.5)

        // L and R identical (all BOTH)
        for (i in result.frequencies.indices) {
            assertEquals(result.leftResponseDb[i], result.rightResponseDb[i], 0.001)
        }
    }

    // ── Test 7: Different sample rates ──

    @Test
    fun differentSampleRates() {
        for (sr in doubleArrayOf(44100.0, 48000.0, 96000.0)) {
            val calc = ParametricEqResponseCalculator(sampleRate = sr, numPoints = 128)
            val bands = listOf(band(1000.0, 3.0, 1.41))
            val result = calc.compute(bands)

            val at1k = responseAt(result.frequencies, result.leftResponseDb, 1000.0)
            assertEquals("At sample rate $sr", 3.0, at1k, tolerance)

            // No NaN/Infinity
            result.leftResponseDb.forEach {
                assertTrue("NaN at sr=$sr", !it.isNaN())
                assertTrue("Infinity at sr=$sr", !it.isInfinite())
            }
        }
    }

    // ── Test 8: No NaN / Infinity ──

    @Test
    fun noNaNOrInfinity() {
        val calc = makeCalculator()
        // Extreme values
        val bands = listOf(
            band(20.0, 30.0, 24.0, channel = ParametricEqChannel.LEFT_RIGHT),
            band(20000.0, -30.0, 0.1, channel = ParametricEqChannel.LEFT),
            band(1.0, 12.0, 0.1, channel = ParametricEqChannel.RIGHT)
        )
        val result = calc.compute(bands)

        result.frequencies.forEach { assertTrue(!it.isNaN()); assertTrue(!it.isInfinite()) }
        result.leftResponseDb.forEach { assertTrue(!it.isNaN()); assertTrue(!it.isInfinite()) }
        result.rightResponseDb.forEach { assertTrue(!it.isNaN()); assertTrue(!it.isInfinite()) }
    }

    // ── Test 9: Logarithmic frequency spacing ──

    @Test
    fun logarithmicFrequencySpacing() {
        val calc = makeCalculator(numPoints = 64)
        val freqs = calc.logSpacedFrequencies()

        assertEquals(64, freqs.size)
        assertEquals(20.0, freqs.first(), 0.1)
        assertEquals(20000.0, freqs.last(), 0.1)

        // Check that spacing is logarithmic (ratio between consecutive points is constant)
        for (i in 1 until freqs.size) {
            val ratio = freqs[i] / freqs[i - 1]
            val expectedRatio = (20000.0 / 20.0).pow(1.0 / 63)
            assertEquals(expectedRatio, ratio, 0.01)
        }
    }

    // ── Test 10: 0 dB is the neutral reference ──

    @Test
    fun zeroDbIsNeutralReference() {
        val calc = makeCalculator()

        // With no bands, response is exactly 0 dB
        val empty = calc.compute(emptyList())
        empty.leftResponseDb.forEach { assertEquals(0.0, it, 0.001) }
        empty.rightResponseDb.forEach { assertEquals(0.0, it, 0.001) }

        // With a 0 dB gain band, response is still 0 dB everywhere
        val zeroGainBand = listOf(band(1000.0, 0.0, 1.41))
        val zeroResult = calc.compute(zeroGainBand)
        zeroResult.leftResponseDb.forEach { assertEquals(0.0, it, 0.01) }
        zeroResult.rightResponseDb.forEach { assertEquals(0.0, it, 0.01) }
    }

    // ── Test 11: Preamp is NOT included in response (applied by view layer) ──

    @Test
    fun preampNotIncludedInResponse() {
        val calc = makeCalculator()
        val bands = listOf(
            band(1000.0, 3.0, 1.41, channel = ParametricEqChannel.LEFT),
            band(1000.0, -3.0, 1.41, channel = ParametricEqChannel.RIGHT)
        )
        // compute() returns filter-only response, preamp is applied separately by the view
        val result = calc.compute(bands, preampDb = -6.0)

        val leftAt1k = responseAt(result.frequencies, result.leftResponseDb, 1000.0)
        val rightAt1k = responseAt(result.frequencies, result.rightResponseDb, 1000.0)

        // Filter response only, no preamp
        assertEquals(3.0, leftAt1k, tolerance)
        assertEquals(-3.0, rightAt1k, tolerance)

        // At frequencies far from the peak, response ≈ 0 dB (no preamp)
        val leftAt20k = responseAt(result.frequencies, result.leftResponseDb, 20000.0)
        val rightAt20k = responseAt(result.frequencies, result.rightResponseDb, 20000.0)
        assertEquals(0.0, leftAt20k, 0.1)
        assertEquals(0.0, rightAt20k, 0.1)
    }

    // ── Test 12: All filter types produce valid output ──

    @Test
    fun allFilterTypesProduceValidOutput() {
        val calc = makeCalculator(numPoints = 64)

        for (type in ParametricEqFilterType.entries) {
            val bands = listOf(band(1000.0, 3.0, 1.41, type = type))
            val result = calc.compute(bands)

            result.leftResponseDb.forEach {
                assertTrue("NaN for $type", !it.isNaN())
                assertTrue("Infinity for $type", !it.isInfinite())
            }
        }
    }

    // ── Test 13: Extreme Q doesn't break computation ──

    @Test
    fun extremeQDoesNotBreak() {
        val calc = makeCalculator(numPoints = 128)

        for (q in doubleArrayOf(0.1, 0.5, 10.0, 24.0)) {
            val bands = listOf(band(1000.0, 6.0, q))
            val result = calc.compute(bands)

            result.leftResponseDb.forEach {
                assertTrue("NaN at Q=$q", !it.isNaN())
                assertTrue("Infinity at Q=$q", !it.isInfinite())
            }
        }
    }

    // ── Test 14: Frequencies near Nyquist ──

    @Test
    fun frequenciesNearNyquist() {
        val calc = ParametricEqResponseCalculator(
            sampleRate = 48000.0, minFreq = 20.0, maxFreq = 20000.0, numPoints = 128
        )
        // 20 kHz is below Nyquist (24 kHz) for 48 kHz sample rate
        val bands = listOf(band(19000.0, 6.0, 1.0))
        val result = calc.compute(bands)

        result.leftResponseDb.forEach {
            assertTrue(!it.isNaN())
            assertTrue(!it.isInfinite())
        }
    }

    // ── Test 15: Mixed BOTH + L-only + R-only ──

    @Test
    fun mixedChannelModes() {
        val calc = makeCalculator()
        val bands = listOf(
            band(100.0, 5.0, 0.7, channel = ParametricEqChannel.LEFT_RIGHT),
            band(1000.0, 3.0, 1.41, channel = ParametricEqChannel.LEFT),
            band(5000.0, -4.0, 2.0, channel = ParametricEqChannel.RIGHT)
        )
        val result = calc.compute(bands)

        // L at 100 Hz: BOTH band → +5
        val lAt100 = responseAt(result.frequencies, result.leftResponseDb, 100.0)
        assertEquals(5.0, lAt100, 1.5)

        // R at 100 Hz: BOTH band → +5
        val rAt100 = responseAt(result.frequencies, result.rightResponseDb, 100.0)
        assertEquals(5.0, rAt100, 1.5)

        // L at 1 kHz: BOTH (0 at 1k) + L-only (+3) ≈ +3
        val lAt1k = responseAt(result.frequencies, result.leftResponseDb, 1000.0)
        assertEquals(3.0, lAt1k, 1.5)

        // R at 1 kHz: BOTH (0 at 1k) + no R-only → 0
        val rAt1k = responseAt(result.frequencies, result.rightResponseDb, 1000.0)
        assertEquals(0.0, rAt1k, 0.5)

        // R at 5 kHz: BOTH (0 at 5k) + R-only (-4) ≈ -4
        val rAt5k = responseAt(result.frequencies, result.rightResponseDb, 5000.0)
        assertEquals(-4.0, rAt5k, 1.5)

        // L at 5 kHz: BOTH (0 at 5k) + no R-only → 0
        val lAt5k = responseAt(result.frequencies, result.leftResponseDb, 5000.0)
        assertEquals(0.0, lAt5k, 0.5)
    }
}
