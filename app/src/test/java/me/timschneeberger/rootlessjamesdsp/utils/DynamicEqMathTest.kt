package me.timschneeberger.rootlessjamesdsp.utils

import me.timschneeberger.rootlessjamesdsp.model.DynamicEqMode
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

/**
 * Mirrors the gain computer and envelope in dynamicEq.c. The native module
 * is authoritative for audio; these tests pin the math contract.
 */
class DynamicEqMathTest {

    private fun linear(db: Double): Double = 10.0.pow(db / 20.0)

    // ── Gain computer ──

    @Test
    fun compressorCutsAboveThreshold() {
        // 0 dBFS with a -20 dB threshold and 4:1 ratio: 20 dB over → 15 dB cut.
        val gain = DynamicEqMath.gainComputerDb(
            linear(0.0), thresholdDb = -20.0, ratio = 4.0, rangeDb = -30.0,
            mode = DynamicEqMode.COMPRESS
        )
        assertEquals(-15.0, gain, 0.01)
    }

    @Test
    fun compressorIsTransparentBelowThreshold() {
        val gain = DynamicEqMath.gainComputerDb(
            linear(-30.0), thresholdDb = -20.0, ratio = 4.0, rangeDb = -30.0,
            mode = DynamicEqMode.COMPRESS
        )
        assertEquals(0.0, gain, 0.0001)
    }

    @Test
    fun compressorCutIsClampedToNegativeRange() {
        // 40 dB over at 10:1 would be 36 dB; range limits it to 8 dB.
        val gain = DynamicEqMath.gainComputerDb(
            linear(20.0), thresholdDb = -20.0, ratio = 10.0, rangeDb = -8.0,
            mode = DynamicEqMode.COMPRESS
        )
        assertEquals(-8.0, gain, 0.0001)
    }

    @Test
    fun expanderBoostsBelowThreshold() {
        // -30 dBFS with a -20 dB threshold and 2:1 ratio: 10 dB under → +5 dB.
        val gain = DynamicEqMath.gainComputerDb(
            linear(-30.0), thresholdDb = -20.0, ratio = 2.0, rangeDb = 12.0,
            mode = DynamicEqMode.EXPAND
        )
        assertEquals(5.0, gain, 0.01)
    }

    @Test
    fun expanderIsTransparentAboveThreshold() {
        val gain = DynamicEqMath.gainComputerDb(
            linear(0.0), thresholdDb = -20.0, ratio = 2.0, rangeDb = 12.0,
            mode = DynamicEqMode.EXPAND
        )
        assertEquals(0.0, gain, 0.0001)
    }

    @Test
    fun silenceDoesNotProduceNaNOrInfinity() {
        for (mode in DynamicEqMode.entries) {
            val gain = DynamicEqMath.gainComputerDb(
                0.0, thresholdDb = -20.0, ratio = 4.0, rangeDb = 12.0, mode = mode
            )
            assertFalse("gain is NaN for $mode", gain.isNaN())
            assertFalse("gain is infinite for $mode", gain.isInfinite())
        }
    }

    @Test
    fun expanderAtSilenceIsClampedToRange() {
        // The -120 dB detector floor is far below the threshold, so the
        // expander requests its maximum boost and the range clamps it.
        val gain = DynamicEqMath.gainComputerDb(
            0.0, thresholdDb = -20.0, ratio = 2.0, rangeDb = 12.0,
            mode = DynamicEqMode.EXPAND
        )
        assertEquals(12.0, gain, 0.0001)
    }

    @Test
    fun zeroRangeDisablesTheBand() {
        val gain = DynamicEqMath.gainComputerDb(
            linear(0.0), thresholdDb = -20.0, ratio = 4.0, rangeDb = 0.0,
            mode = DynamicEqMode.COMPRESS
        )
        assertEquals(0.0, gain, 0.0)
    }

    // ── Envelope / time constants ──

    @Test
    fun zeroTimeConstantMeansNoSmoothing() {
        assertEquals(0.0, DynamicEqMath.timeCoef(0.0, 48000.0), 0.0)
        assertEquals(0.0, DynamicEqMath.timeCoef(10.0, 0.0), 0.0)
    }

    @Test
    fun timeConstantMatchesOnePoleFormula() {
        val coef = DynamicEqMath.timeCoef(10.0, 48000.0)
        assertEquals(Math.exp(-1.0 / 480.0), coef, 1e-12)
    }

    @Test
    fun envelopeConvergesToSteadyState() {
        val attack = DynamicEqMath.timeCoef(5.0, 48000.0)
        val release = DynamicEqMath.timeCoef(100.0, 48000.0)

        var env = 0.0
        repeat(48000) {
            env = DynamicEqMath.envStep(env, 1.0, attack, release)
        }
        assertEquals(1.0, env, 1e-4)

        repeat(48000) {
            env = DynamicEqMath.envStep(env, 0.0, attack, release)
        }
        assertEquals(0.0, env, 1e-4)
        assertFalse(env.isNaN())
        assertFalse(env.isInfinite())
    }

    @Test
    fun attackIsFasterThanRelease() {
        val attack = DynamicEqMath.timeCoef(5.0, 48000.0)
        val release = DynamicEqMath.timeCoef(200.0, 48000.0)

        var rising = 0.0
        var falling = 1.0
        repeat(480) {
            rising = DynamicEqMath.envStep(rising, 1.0, attack, release)
            falling = DynamicEqMath.envStep(falling, 0.0, attack, release)
        }

        // After 10 ms, the attack has covered far more of the way up than
        // the release has covered downward.
        assertTrue(rising > 1.0 - falling)
    }
}
