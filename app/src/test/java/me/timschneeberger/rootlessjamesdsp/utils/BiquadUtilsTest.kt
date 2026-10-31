package me.timschneeberger.rootlessjamesdsp.utils

import me.timschneeberger.rootlessjamesdsp.model.ParametricEqBand
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqFilterType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Unit tests for [BiquadUtils] stability analysis and input validation
 * (port of the DDCToolbox Biquad stability check).
 */
class BiquadUtilsTest {

    private val sr = 48000.0

    // ---- Stability of ordinary RBJ filters ----

    @Test
    fun `rbj filters are stable at common sample rates`() {
        val sampleRates = doubleArrayOf(44100.0, 48000.0, 96000.0)
        val types = ParametricEqFilterType.entries.filter { it != ParametricEqFilterType.PREAMP }
        for (sampleRate in sampleRates) {
            for (type in types) {
                for (f in doubleArrayOf(100.0, 1000.0, 10000.0)) {
                    for (q in doubleArrayOf(0.5, 0.707, 1.41, 7.0)) {
                        val coeffs = BiquadUtils.computeCoefficients(f, 6.0, q, type, sampleRate)
                        val radius = BiquadUtils.poleRadius(coeffs)
                        // Narrow legitimate filters (high Q at low f) mathematically sit
                        // close to the unit circle; only strict stability is required here
                        assertTrue(
                            "sr=$sampleRate type=$type f=$f q=$q radius=$radius",
                            radius < 1.0
                        )
                        assertTrue(BiquadUtils.isStable(coeffs))
                    }
                }
            }
        }
    }

    @Test
    fun `pole radius matches closed form for constructed coefficients`() {
        fun coeffs(a0: Double, a1: Double, a2: Double) = BiquadUtils.BiquadCoefficients(
            1.0, 0.0, 0.0, a0, a1, a2
        )

        // z^2 - 4 = 0 -> roots ±2 (real, outside)
        val real = coeffs(1.0, 0.0, -4.0)
        assertEquals(2.0, BiquadUtils.poleRadius(real), 1e-12)
        assertFalse(BiquadUtils.isStable(real))

        // z^2 + 4 = 0 -> roots ±2j (complex, outside)
        val complex = coeffs(1.0, 0.0, 4.0)
        assertEquals(2.0, BiquadUtils.poleRadius(complex), 1e-12)
        assertFalse(BiquadUtils.isStable(complex))

        // z^2 - 2z + 1 = 0 -> double pole exactly on the unit circle
        val unit = coeffs(1.0, -2.0, 1.0)
        assertEquals(1.0, BiquadUtils.poleRadius(unit), 1e-12)
        assertFalse("pole on the unit circle is not stable", BiquadUtils.isStable(unit))

        // z^2 + 0.5z + 0.9 = 0 -> complex pair with modulus sqrt(0.9), inside
        val inside = coeffs(1.0, 0.5, 0.9)
        assertEquals(sqrt(0.9), BiquadUtils.poleRadius(inside), 1e-12)
        assertTrue(BiquadUtils.isStable(inside))

        // Pole-free (PREAMP shape): a1 = a2 = 0
        assertEquals(0.0, BiquadUtils.poleRadius(BiquadUtils.UNITY), 1e-12)
        assertTrue(BiquadUtils.isStable(BiquadUtils.UNITY))

        // Degenerate and non-finite denominators are reported as unstable
        val zeroA0 = coeffs(0.0, 1.0, 1.0)
        assertEquals(Double.POSITIVE_INFINITY, BiquadUtils.poleRadius(zeroA0), 0.0)
        assertFalse(BiquadUtils.isStable(zeroA0))
        val nanCoeff = coeffs(1.0, Double.NaN, 0.5)
        assertEquals(Double.POSITIVE_INFINITY, BiquadUtils.poleRadius(nanCoeff), 0.0)
        assertFalse(BiquadUtils.isStable(nanCoeff))
    }

    @Test
    fun `peaking near nyquist with high q has pole near unity`() {
        // 23900 Hz at 48 kHz is a valid input frequency (< 23976 Hz) but sin(w) is
        // tiny, so even a moderate Q puts the pole radius above the warning threshold
        val band = ParametricEqBand(23900.0, 12.0, 10.0, ParametricEqFilterType.PEAKING)
        val coeffs = BiquadUtils.computeCoefficients(band.frequency, band.gain, band.q, band.filterType, sr)
        val radius = BiquadUtils.poleRadius(coeffs)

        assertTrue("expected radius > 0.999, got $radius", radius > BiquadUtils.NEAR_UNITY_POLE_RADIUS)
        assertTrue("formally stable ($radius < 1)", radius < 1.0)
        assertFalse("near-unity pole must be flagged", BiquadUtils.isBandStable(band, sr))

        // Ordinary band stays clear of the threshold
        val calm = ParametricEqBand(1000.0, 12.0, 7.0, ParametricEqFilterType.PEAKING)
        val calmRadius = BiquadUtils.poleRadius(
            BiquadUtils.computeCoefficients(calm.frequency, calm.gain, calm.q, calm.filterType, sr)
        )
        assertTrue("ordinary band must not trigger the warning (radius=$calmRadius)", calmRadius <= BiquadUtils.NEAR_UNITY_POLE_RADIUS)
        assertTrue(BiquadUtils.isBandStable(calm, sr))
    }

    // ---- computeCoefficients input validation ----

    @Test
    fun `computeCoefficients falls back to unity for invalid inputs`() {
        val boundary = sr * 0.5 * 0.999
        val invalid = listOf(
            // frequency domain
            Triple(0.0, 0.0, 1.41),
            Triple(-100.0, 0.0, 1.41),
            Triple(Double.NaN, 0.0, 1.41),
            Triple(Double.POSITIVE_INFINITY, 0.0, 1.41),
            Triple(24000.0, 0.0, 1.41),
            Triple(30000.0, 0.0, 1.41),
            Triple(boundary, 0.0, 1.41),
            // q domain
            Triple(1000.0, 0.0, 0.0),
            Triple(1000.0, 0.0, -1.0),
            Triple(1000.0, 0.0, Double.NaN),
            Triple(1000.0, 0.0, Double.POSITIVE_INFINITY),
            // non-finite gain
            Triple(1000.0, Double.NaN, 1.41),
            Triple(1000.0, Double.POSITIVE_INFINITY, 1.41),
            Triple(1000.0, Double.NEGATIVE_INFINITY, 1.41),
        )
        for ((f, g, q) in invalid) {
            assertEquals(
                "f=$f g=$g q=$q must fall back to unity",
                BiquadUtils.UNITY,
                BiquadUtils.computeCoefficients(f, g, q, ParametricEqFilterType.PEAKING, sr)
            )
        }

        for (badSr in doubleArrayOf(0.0, -48000.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(
                "sr=$badSr must fall back to unity",
                BiquadUtils.UNITY,
                BiquadUtils.computeCoefficients(1000.0, 0.0, 1.41, ParametricEqFilterType.PEAKING, badSr)
            )
        }
    }

    @Test
    fun `frequency just below the nyquist guard is accepted`() {
        val boundary = sr * 0.5 * 0.999
        val coeffs = BiquadUtils.computeCoefficients(boundary - 0.1, 0.0, 1.41, ParametricEqFilterType.PEAKING, sr)
        assertNotEquals(BiquadUtils.UNITY, coeffs)
        assertTrue(
            listOf(coeffs.b0, coeffs.b1, coeffs.b2, coeffs.a0, coeffs.a1, coeffs.a2).all { it.isFinite() }
        )
        assertTrue(BiquadUtils.isStable(coeffs))
    }

    @Test
    fun `absurd gain falls back instead of producing infinite coefficients`() {
        // 10^(1e6/40) overflows to Infinity in the RBJ formulas
        assertEquals(
            BiquadUtils.UNITY,
            BiquadUtils.computeCoefficients(1000.0, 1.0e6, 1.41, ParametricEqFilterType.PEAKING, sr)
        )
    }

    @Test
    fun `preamp keeps its gain regardless of frequency and q`() {
        val coeffs = BiquadUtils.computeCoefficients(0.0, -3.0, 0.0, ParametricEqFilterType.PREAMP, sr)
        assertEquals(10.0.pow(-3.0 / 20.0), coeffs.b0, 1e-9)
        assertEquals(1.0, coeffs.a0, 0.0)
        assertTrue(BiquadUtils.isBandStable(ParametricEqBand(0.0, -3.0, 0.0, ParametricEqFilterType.PREAMP), sr))
    }

    @Test
    fun `isBandStable flags out-of-domain and near-unity bands`() {
        // Above the Nyquist guard / non-positive / non-finite frequency
        assertFalse(BiquadUtils.isBandStable(ParametricEqBand(999999999.0, 3.0, 1.41, ParametricEqFilterType.PEAKING), sr))
        assertFalse(BiquadUtils.isBandStable(ParametricEqBand(0.0, 3.0, 1.41, ParametricEqFilterType.PEAKING), sr))
        assertFalse(BiquadUtils.isBandStable(ParametricEqBand(Double.NaN, 0.0, 1.41, ParametricEqFilterType.PEAKING), sr))
        // Non-positive Q
        assertFalse(BiquadUtils.isBandStable(ParametricEqBand(1000.0, 3.0, 0.0, ParametricEqFilterType.PEAKING), sr))
        // Near-unity pole (see dedicated test above)
        assertFalse(BiquadUtils.isBandStable(ParametricEqBand(23900.0, 12.0, 10.0, ParametricEqFilterType.PEAKING), sr))

        // Healthy bands
        assertTrue(BiquadUtils.isBandStable(ParametricEqBand(1000.0, 3.0, 1.41, ParametricEqFilterType.PEAKING), sr))
        assertTrue(BiquadUtils.isBandStable(ParametricEqBand(20000.0, 3.0, 1.41, ParametricEqFilterType.PEAKING), sr))
    }

    @Test
    fun `unity fallback renders as flat 0 dB`() {
        assertEquals(0.0, BiquadUtils.magnitudeResponse(BiquadUtils.UNITY, 1000.0, sr), 1e-12)
    }

    // ---- Butterworth cutoff cascade (port of RootlessViPER4Android 5ea9281, P6.2) ----

    @Test
    fun `cutoff expands into one section per 12 dB of slope`() {
        // gain < 6 on a cutoff is the absence of a chosen slope -> 48 dB/oct default
        assertEquals(4, BiquadUtils.cascadeFor(
            ParametricEqBand(1000.0, 0.0, 0.707, ParametricEqFilterType.LOW_PASS), sr).size)
        assertEquals(2, BiquadUtils.cascadeFor(
            ParametricEqBand(1000.0, 24.0, 0.707, ParametricEqFilterType.HIGH_PASS), sr).size)
        assertEquals(8, BiquadUtils.cascadeFor(
            ParametricEqBand(1000.0, 96.0, 0.707, ParametricEqFilterType.LOW_PASS), sr).size)
    }

    @Test
    fun `peaking and shelving bands stay single sections`() {
        val peak = ParametricEqBand(1000.0, 6.0, 2.0, ParametricEqFilterType.PEAKING)
        val sections = BiquadUtils.cascadeFor(peak, sr)
        assertEquals(1, sections.size)
        assertEquals(
            BiquadUtils.computeCoefficients(1000.0, 6.0, 2.0, ParametricEqFilterType.PEAKING, sr),
            sections[0]
        )
    }

    @Test
    fun `cutoff sections run at zero gain with butterworth qs, user q on the last`() {
        val q = 1.2
        val sections = BiquadUtils.cascadeFor(
            ParametricEqBand(1000.0, 0.0, q, ParametricEqFilterType.LOW_PASS), sr)
        val stages = 4
        assertEquals(stages, sections.size)
        for ((k, c) in sections.withIndex()) {
            var expectedQ = 1.0 / (2.0 * cos((2.0 * k + 1.0) * PI / (4.0 * stages)))
            if (k == stages - 1) expectedQ *= q / 0.70710678
            assertEquals(
                BiquadUtils.computeCoefficients(1000.0, 0.0, expectedQ, ParametricEqFilterType.LOW_PASS, sr),
                c
            )
        }
    }

    @Test
    fun `cascade rolls off at the requested slope and is -3 dB at the corner`() {
        val fc = 1000.0
        val sections = BiquadUtils.cascadeFor(
            ParametricEqBand(fc, 0.0, 0.707, ParametricEqFilterType.LOW_PASS), sr)
        fun dbAt(f: Double) = sections.fold(0.0) { acc, c -> acc + BiquadUtils.magnitudeResponse(c, f, sr) }

        // Butterworth: |H| = -3.0103 dB at the corner (RBJ discretization: ~0.1 dB off)
        assertEquals(-3.0103, dbAt(fc), 0.1)

        // Stopband slope 48 dB/octave, one to two octaves above the corner;
        // a single biquad would only manage 12
        assertEquals(48.0, dbAt(fc * 2) - dbAt(fc * 4), 3.0)

        // Two octaves further: an 8-pole rolloff is ~96 dB down at fc*4
        assertTrue(dbAt(fc * 4) < -90.0)

        // Passband (below the corner) stays flat instead of drooping toward the corner
        assertTrue(dbAt(fc / 8) > -1.0)
    }
}
