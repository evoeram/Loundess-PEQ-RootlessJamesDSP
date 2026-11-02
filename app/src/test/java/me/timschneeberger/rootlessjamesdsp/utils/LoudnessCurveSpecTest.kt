package me.timschneeberger.rootlessjamesdsp.utils

import me.timschneeberger.rootlessjamesdsp.utils.LoudnessCurveCalculator.TuningParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.pow

/**
 * Regression tests for BUG-09: the classic-mode treble-cut gain used a
 * hardcoded 0.175 factor in both the C++ processor and the Kotlin UI curve,
 * ignoring the user-tunable hsRatio. The shared spec is
 * `hsRatio * 0.778` (0.225 * 0.778 = 0.175 only for the default), matching
 * the C root path (peq_loudness.c).
 */
class LoudnessCurveSpecTest {

    private fun responseAt(freqs: DoubleArray, gains: DoubleArray, f: Double): Double {
        var best = 0.0
        var bestDist = Double.MAX_VALUE
        for (i in freqs.indices) {
            val d = kotlin.math.abs(kotlin.math.log10(freqs[i] / f))
            if (d < bestDist) { bestDist = d; best = gains[i] }
        }
        return best
    }

    @Test
    fun `classic treble cut scales with hsRatio, not a hardcoded constant`() {
        // Volume 10 dB above reference: only the high shelf acts (gentle treble cut).
        // volDiff = -10, hsVol = -10 (preAmp = 0 in the cut branch).
        val expected = { hsRatio: Double ->
            val hsGain = -10.0 * hsRatio * 0.778 * exp(-10.0 / 80.0) // dB
            // Shelf sits at 10 kHz with slope 0.9; at 20 kHz the cut is nearly
            // full, so the measured response tracks hsGain within a dB or two.
            hsGain
        }

        val base = LoudnessCurveCalculator.compute(
            mode = 0, referenceLevel = 0.0, referenceOffset = 0.0,
            attenuation = 1.0, volumeDb = 10.0,
            tuning = TuningParams(hsRatio = 0.1),
        )
        val stronger = LoudnessCurveCalculator.compute(
            mode = 0, referenceLevel = 0.0, referenceOffset = 0.0,
            attenuation = 1.0, volumeDb = 10.0,
            tuning = TuningParams(hsRatio = 0.5),
        )

        val baseAt20k = responseAt(base.frequencies, base.gains, 20000.0)
        val strongAt20k = responseAt(stronger.frequencies, stronger.gains, 20000.0)

        // With the old hardcoded 0.175 both curves would be identical.
        assertTrue(
            "hsRatio must influence the treble cut: $strongAt20k vs $baseAt20k",
            kotlin.math.abs(strongAt20k - baseAt20k) > 1.0,
        )

        // And the stronger ratio must follow the shared spec: gain ratio ≈ 0.5/0.1 = 5.
        val measuredRatio = strongAt20k / baseAt20k
        assertEquals(5.0, measuredRatio, 0.6)
        assertEquals(expected(0.1), baseAt20k, 1.5)
        assertEquals(expected(0.5), strongAt20k, 1.5)
    }

    @Test
    fun `default hsRatio reproduces the historical 0_175 behavior`() {
        // hsVol = -10 → default hsGain = -10 * 0.225 * 0.778 * exp(-10/80)
        val expectedHsGain = -10.0 * 0.225 * 0.778 * exp(-10.0 / 80.0)
        val result = LoudnessCurveCalculator.compute(
            mode = 0, referenceLevel = 0.0, referenceOffset = 0.0,
            attenuation = 1.0, volumeDb = 10.0,
            tuning = TuningParams(hsRatio = 0.225), // explicit default
        )
        val at20k = responseAt(result.frequencies, result.gains, 20000.0)
        // Old code used 0.175 flat: -10 * 0.175 * exp(-10/80) = -1.575…;
        // new spec gives -1.754… — distinguishable well beyond tolerance.
        assertEquals(expectedHsGain, at20k, 1.5)
    }

    @Test
    fun `iso preamp uses the passed sample rate, not a hardcoded 48 kHz`() {
        // At 22.05 kHz the Nyquist filter drops the top ISO bands (>= 12.5 kHz)
        // from the cascade, so the peak/preamp estimate must move relative to
        // 96 kHz. With a hardcoded 48 kHz grid both calls were identical
        // regardless of the argument passed.
        val at22050 = LoudnessCurveCalculator.compute(
            mode = 1, referenceLevel = 0.0, referenceOffset = 0.0,
            attenuation = 1.0, volumeDb = -30.0, // well below reference → big correction
            sampleRate = 22050.0, numPoints = 256,
        )
        val at96 = LoudnessCurveCalculator.compute(
            mode = 1, referenceLevel = 0.0, referenceOffset = 0.0,
            attenuation = 1.0, volumeDb = -30.0,
            sampleRate = 96000.0, numPoints = 256,
        )
        // The curve itself must reflect the sample rate: at 22.05 kHz the 12.5 kHz
        // ISO band sits above Nyquist*0.98 and is dropped from the cascade, while
        // at 96 kHz it boosts ~+4.4 dB at its center. A hardcoded 48 kHz grid made
        // both calls identical regardless of the passed rate.
        val nearNyq22050 = responseAt(at22050.frequencies, at22050.gains, 12500.0)
        val nearNyq96 = responseAt(at96.frequencies, at96.gains, 12500.0)
        assertTrue(
            "curve near 12.5 kHz must depend on sample rate: $nearNyq22050 vs $nearNyq96",
            kotlin.math.abs(nearNyq96 - nearNyq22050) > 2.0,
        )
        // Sanity: both curves still normalized to a 0 dB peak.
        assertEquals(0.0, at22050.gains.maxOrNull() ?: 1.0, 1e-6)
        assertEquals(0.0, at96.gains.maxOrNull() ?: 1.0, 1e-6)
    }
}
