package me.timschneeberger.rootlessjamesdsp.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

/**
 * Mirrors the math in the ViPER extras C modules (fet_compressor.c,
 * viper_clarity.c, field_surround.c, viper_agc.c, cure.c). The native modules
 * are authoritative for audio; these tests pin the math contracts.
 */
class ViperExtrasMathTest {

    private fun linear(db: Double): Double = 10.0.pow(db / 20.0)

    // ── FET compressor ──

    @Test
    fun fetIsTransparentBelowThreshold() {
        val gain = ViperExtrasMath.fetGainLin(linear(-30.0), -18.0, 4.0, 0.0)
        assertEquals(1.0, gain, 1e-9)
    }

    @Test
    fun fetCutFollowsLogSlope() {
        // 0 dBFS with -20 dB threshold at 4:1: 20 dB over → 15 dB cut.
        val gain = ViperExtrasMath.fetGainLin(linear(0.0), -20.0, 4.0, 0.0)
        assertEquals(linear(-15.0), gain, 1e-9)
    }

    @Test
    fun fetHarderRatioCutsMore() {
        val g4 = ViperExtrasMath.fetGainLin(linear(0.0), -20.0, 4.0, 0.0)
        val g10 = ViperExtrasMath.fetGainLin(linear(0.0), -20.0, 10.0, 0.0)
        // 20 dB over: 4:1 → -15 dB, 10:1 → -18 dB.
        assertEquals(-15.0, 20.0 * Math.log10(g4), 1e-6)
        assertEquals(-18.0, 20.0 * Math.log10(g10), 1e-6)
    }

    @Test
    fun fetMakeupMultiplies() {
        val makeupDb = 20.0 * Math.log10(2.0)
        val gain = ViperExtrasMath.fetGainLin(linear(0.0), -20.0, 4.0, makeupDb)
        // -15 dB cut, then a ×2 makeup: exactly 2 × 10^(-15/20).
        assertEquals(2.0 * linear(-15.0), gain, 1e-9)
    }

    @Test
    fun fetBallisticsCoefficient() {
        // 5 ms at 48 kHz = 240 samples: coef = 1 - e^(-1/240).
        assertEquals(1.0 - kotlin.math.exp(-1.0 / 240.0),
            ViperExtrasMath.ballisticsCoef(48000.0, 5.0), 1e-12)
        // Sub-sample times are floored at 1 sample.
        assertEquals(1.0 - kotlin.math.exp(-1.0),
            ViperExtrasMath.ballisticsCoef(48000.0, 0.001), 1e-12)
    }

    // ── ViPER Clarity ──

    @Test
    fun claritySharpScalesLinearlyWithGain() {
        assertEquals(0.6, ViperExtrasMath.claritySharp(6.0), 1e-12)
        assertEquals(0.0, ViperExtrasMath.claritySharp(0.0), 1e-12)
    }

    @Test
    fun clarityShelfGainDependsOnMode() {
        assertEquals(3.0, ViperExtrasMath.clarityShelfGainDb(6.0, 1), 1e-12)
        assertEquals(4.8, ViperExtrasMath.clarityShelfGainDb(6.0, 2), 1e-12)
    }

    @Test
    fun clarityShelfIsRbjHighShelf() {
        // High shelf: unity at DC, total designed gain at Nyquist.
        // Mode 1 designs the shelf with half the user gain (6 dB → 3 dB).
        val coeffs = ViperExtrasMath.clarityShelf(48000.0, ViperExtrasMath.clarityShelfGainDb(6.0, 1))
        assertEquals(1.0, ViperExtrasMath.biquadGainAt(coeffs, nyquist = false), 1e-9)
        assertEquals(linear(3.0), ViperExtrasMath.biquadGainAt(coeffs, nyquist = true), 1e-6)
    }

    @Test
    fun clarityShelfStable() {
        // Poles inside the unit circle: 1 + a1 + a2 > 0, 1 - a1 + a2 > 0, 1 - a2 > 0.
        for (sr in doubleArrayOf(44100.0, 48000.0, 96000.0)) {
            for (gainDb in doubleArrayOf(0.0, 4.8, 14.0)) {
                val (b0, b1, b2, a1, a2) = ViperExtrasMath.clarityShelf(sr, gainDb)
                assertTrue(1.0 + a1 + a2 > 0.0)
                assertTrue(1.0 - a1 + a2 > 0.0)
                assertTrue(1.0 - a2 > 0.0)
                // Coefficients are finite and normalised.
                assertTrue(listOf(b0, b1, b2, a1, a2).all { it.isFinite() })
            }
        }
    }

    // ── Field Surround ──

    @Test
    fun surroundIsTransparentAtNeutralSettings() {
        // strength 0 / mid 50 → sideGain 1.0, midGain 1.0.
        val (l2, r2) = ViperExtrasMath.surroundSample(0.7, -0.2, 0.0, 50.0)
        assertEquals(0.7, l2, 1e-12)
        assertEquals(-0.2, r2, 1e-12)
    }

    @Test
    fun surroundWidensSideContent() {
        // Pure side signal L = -R grows by sideGain; mono stays mono.
        val (lOut, rOut) = ViperExtrasMath.surroundSample(0.5, -0.5, 100.0, 50.0)
        assertEquals(1.5, lOut, 1e-12) // sideGain = 3, side = ±0.5
        assertEquals(-1.5, rOut, 1e-12)

        val (monoL, monoR) = ViperExtrasMath.surroundSample(0.4, 0.4, 100.0, 50.0)
        assertEquals(0.4, monoL, 1e-12)
        assertEquals(0.4, monoR, 1e-12)
    }

    @Test
    fun surroundMidControlAttenuatesCenter() {
        // mid 0 → midGain 0.5: mono content halved.
        val (monoL, _) = ViperExtrasMath.surroundSample(0.4, 0.4, 0.0, 0.0)
        assertEquals(0.2, monoL, 1e-12)
    }

    // ── AGC ──

    @Test
    fun agcDesiredGainClampedToMaxBoost() {
        // env 0.05, target 30% → desired 6.0, but 12 dB cap is ~3.98.
        assertEquals(ViperExtrasMath.agcMaxGain(12.0),
            ViperExtrasMath.agcDesiredGain(0.05, 30.0, 12.0), 1e-9)
    }

    @Test
    fun agcDesiredGainFloored() {
        // Very loud signal: 10% target at env 0.9 → 0.111, floored at 0.2.
        assertEquals(0.2, ViperExtrasMath.agcDesiredGain(0.9, 10.0, 12.0), 1e-9)
    }

    @Test
    fun agcDesiredGainUnclampedInRange() {
        // env 0.1, target 30% → desired 3.0 < 12 dB cap.
        assertEquals(3.0, ViperExtrasMath.agcDesiredGain(0.1, 30.0, 12.0), 1e-9)
    }

    @Test
    fun agcTimeConstantsAreSlow() {
        // ~300 ms envelope and ~1.5 s gain glide at 48 kHz.
        assertEquals(1.0 - kotlin.math.exp(-1.0 / 14400.0),
            ViperExtrasMath.agcEnvCoef(48000.0), 1e-12)
        assertEquals(1.0 - kotlin.math.exp(-1.0 / 72000.0),
            ViperExtrasMath.agcGainCoef(48000.0), 1e-12)
    }

    // ── Cure ──

    @Test
    fun cureFeedAndNormPerLevel() {
        for (level in 0..2) {
            val feed = ViperExtrasMath.cureFeed(level)
            val norm = ViperExtrasMath.cureNorm(level)
            assertEquals(1.0 / (1.0 + feed), norm, 1e-12)
        }
        assertEquals(0.30, ViperExtrasMath.cureFeed(0), 1e-12)
        assertEquals(0.40, ViperExtrasMath.cureFeed(1), 1e-12)
        assertEquals(0.55, ViperExtrasMath.cureFeed(2), 1e-12)
    }

    @Test
    fun cureCutoffPerLevel() {
        assertEquals(700.0, ViperExtrasMath.cureCutoffHz(0), 1e-12)
        assertEquals(700.0, ViperExtrasMath.cureCutoffHz(1), 1e-12)
        assertEquals(650.0, ViperExtrasMath.cureCutoffHz(2), 1e-12)
    }

    @Test
    fun cureMonoStaysUnityAtDc() {
        // Mono content low-passes to itself, so DC gain = (1+feed)/(1+feed) = 1.
        val lpCoef = ViperExtrasMath.cureLpCoef(48000.0, 0)
        val feed = ViperExtrasMath.cureFeed(0)
        val norm = ViperExtrasMath.cureNorm(0)
        // Steady state of the one-pole at constant input x: lpz → x.
        val x = 0.5
        var lpz = 0.0
        repeat(100000) { lpz += lpCoef * (x - lpz) }
        val out = (x + feed * lpz) * norm
        assertEquals(x, out, 1e-6)
    }

    @Test
    fun cureCrossfeedIsLowPassed() {
        // High-frequency content on the right channel must be attenuated
        // before reaching the left ear: |H| at Nyquist ≈ feed * small / norm... simply < feed.
        val lpCoef = ViperExtrasMath.cureLpCoef(48000.0, 0)
        val feed = ViperExtrasMath.cureFeed(0)
        val norm = ViperExtrasMath.cureNorm(0)
        // Steady-state amplitude of a one-pole fed by alternating ±x: gain = coef/(2 - coef).
        val x = 0.5
        var lpz = 0.0
        repeat(10000) { lpz += lpCoef * (if (it % 2 == 0) x else -x - lpz) }
        val hfGain = abs(lpz) / x
        assertTrue(hfGain < feed)
        // The crossfed path is also normalized, so it cannot exceed unity:
        assertTrue(feed * hfGain * norm < 1.0)
    }
}
