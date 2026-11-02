package me.timschneeberger.rootlessjamesdsp.utils

import me.timschneeberger.rootlessjamesdsp.utils.LoudnessCurveCalculator.SubsonicParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Regression tests for BUG-05 (UI side): the subsonic high-pass response must
 * follow the shared C/C++/Kotlin spec — floor(order/2) 2nd-order RBJ biquads
 * plus one first-order RC stage for odd orders, 6 dB/oct per order — and must
 * be visible even when the loudness correction itself is neutral.
 *
 * Each expected value is computed analytically from the RBJ/RC transfer
 * functions at the probed grid frequency, so the test validates the
 * implementation against independent math rather than hardcoded magic numbers.
 */
class LoudnessCurveSubsonicTest {

    private val fc = 80.0
    private val q = 0.707

    /** Subsonic-only curve at neutral loudness (volDiff == 0). */
    private fun subsonicCurve(order: Int, points: Int = 512): LoudnessCurveCalculator.CurveResult =
        LoudnessCurveCalculator.compute(
            mode = 0,
            referenceLevel = 60.0,
            referenceOffset = 0.0,
            attenuation = 1.0,
            volumeDb = 60.0,           // == referenceLevel → neutral loudness
            subsonic = SubsonicParams(enable = true, freq = fc, order = order, qFactor = q),
            numPoints = points,
        )

    /** Analytic attenuation of the specified cascade at frequency [f] (dB). */
    private fun expectedAttenuationDb(f: Double, order: Int): Double {
        val w = f / fc
        var db = 0.0
        repeat(order / 2) {
            val w2 = w * w
            val mag2 = w2 * w2 / (w2 * w2 + w2 * (1.0 / (q * q) - 2.0) + 1.0)
            db += 10.0 * log10(mag2)
        }
        if (order % 2 == 1) {
            val mag = w / sqrt(1.0 + w * w)
            db += 20.0 * log10(mag)
        }
        return db
    }

    /** Index of the grid frequency closest to [freq]. */
    private fun nearestIndex(freqs: DoubleArray, freq: Double): Int =
        freqs.indices.minByOrNull { kotlin.math.abs(it.let { i -> freqs[i] } - freq) } ?: 0

    @Test
    fun `subsonic filter is visible at neutral loudness`() {
        val result = subsonicCurve(order = 4)
        // Curve is normalized to a 0 dB peak at the top of the passband…
        assertEquals(0.0, result.gains.maxOrNull()!!, 0.5)
        // …and the stopband at the lowest displayed frequency is far below it.
        assertTrue("expected deep attenuation at 20 Hz, got ${result.gains[0]}",
            result.gains[0] < -10.0)
    }

    @Test
    fun `attenuation at cutoff matches analytic cascade for orders 1 to 8`() {
        for (order in 1..8) {
            val result = subsonicCurve(order)
            val idx = nearestIndex(result.frequencies, fc)
            val probed = result.frequencies[idx]
            // Grid density guarantees a point within ~3% of fc
            assertTrue("no grid point near fc", kotlin.math.abs(probed - fc) / fc < 0.03)
            assertEquals("order $order at ${"%.2f".format(probed)} Hz",
                expectedAttenuationDb(probed, order), result.gains[idx], 0.3)
        }
    }

    @Test
    fun `slope below cutoff is 6 dB per octave per order`() {
        // Probe two octaves below fc (20 Hz on this grid).
        for (order in 1..4) {
            val result = subsonicCurve(order)
            val expected = expectedAttenuationDb(result.frequencies[0], order)
            assertEquals("order $order at ${"%.2f".format(result.frequencies[0])} Hz",
                expected, result.gains[0], 0.3)
        }
    }

    @Test
    fun `odd order is one first-order stage not an extra biquad`() {
        // BUG-05 failure mode: ceil(order/2) biquads turned order 1 into a
        // 2nd-order filter, and the UI stacked another first-order stage on
        // top. Correct order-1 behaviour is exactly one RC stage.
        val order1 = subsonicCurve(order = 1)
        val idx1 = nearestIndex(order1.frequencies, fc)
        assertEquals(expectedAttenuationDb(order1.frequencies[idx1], 1),
            order1.gains[idx1], 0.3)

        val order3 = subsonicCurve(order = 3)
        val idx3 = nearestIndex(order3.frequencies, fc)
        assertEquals(expectedAttenuationDb(order3.frequencies[idx3], 3),
            order3.gains[idx3], 0.3)
    }
}
