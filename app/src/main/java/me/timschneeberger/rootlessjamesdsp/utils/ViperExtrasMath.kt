package me.timschneeberger.rootlessjamesdsp.utils

import kotlin.math.exp
import kotlin.math.pow

/**
 * Kotlin mirror of the native math in the ViPER extras modules
 * (fet_compressor.c, viper_clarity.c, field_surround.c, viper_agc.c, cure.c).
 *
 * The C modules process the audio; this copy exists so the math contracts
 * can be unit-tested without the NDK. Keep the two in sync.
 */
object ViperExtrasMath {

    // ── FET compressor (fet_compressor.c) ──

    /** Log-domain gain computer: slope = 1 - 1/ratio, transparent below threshold. */
    fun fetGainLin(env: Double, thresholdDb: Double, ratio: Double, makeupDb: Double): Double {
        val slope = 1.0 - 1.0 / ratio
        val thrLin = 10.0.pow(thresholdDb / 20.0)
        var gain = 1.0
        if (env > thrLin && env > 1e-8) {
            val overDb = 20.0 * Math.log10(env / thrLin)
            gain = 10.0.pow(-overDb * slope / 20.0)
        }
        return gain * 10.0.pow(makeupDb / 20.0)
    }

    /** One-pole coefficient for a time in milliseconds, floored at 1 sample. */
    fun ballisticsCoef(sampleRate: Double, ms: Double): Double {
        var samples = sampleRate * ms * 0.001
        if (samples < 1.0) samples = 1.0
        return 1.0 - exp(-1.0 / samples)
    }

    // ── ViPER Clarity (viper_clarity.c) ──

    const val CLARITY_SHELF_FREQ = 4500.0

    /** Derivative-sharpening strength; reaches 1 + 2*sharp at Nyquist. */
    fun claritySharp(gainDb: Double): Double = gainDb * 0.10

    fun clarityShelfGainDb(gainDb: Double, mode: Int): Double =
        gainDb * (if (mode == 2) 0.8 else 0.5)

    /** RBJ high shelf, S = 0.9 (as in the source's vcShelf). */
    fun clarityShelf(sampleRate: Double, gainDb: Double): DoubleArray {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * Math.PI * CLARITY_SHELF_FREQ / sampleRate
        val cw = kotlin.math.cos(w0)
        val sw = kotlin.math.sin(w0)
        val alpha = sw / 2.0 * kotlin.math.sqrt((a + 1.0 / a) * (1.0 / 0.9 - 1.0) + 2.0)
        val sq = 2.0 * kotlin.math.sqrt(a) * alpha
        val a0 = (a + 1.0) - (a - 1.0) * cw + sq
        return doubleArrayOf(
            (a * ((a + 1.0) + (a - 1.0) * cw + sq)) / a0,
            (-2.0 * a * ((a - 1.0) + (a + 1.0) * cw)) / a0,
            (a * ((a + 1.0) + (a - 1.0) * cw - sq)) / a0,
            (2.0 * ((a - 1.0) - (a + 1.0) * cw)) / a0,
            ((a + 1.0) - (a - 1.0) * cw - sq) / a0
        )
    }

    /** |H| of the biquad at DC (z=1) and Nyquist (z=-1). */
    fun biquadGainAt(coeffs: DoubleArray, nyquist: Boolean): Double {
        val (b0, b1, b2, a1, a2) = coeffs
        val s = if (nyquist) -1.0 else 1.0
        val num = b0 + b1 * s + b2
        val den = 1.0 + a1 * s + a2
        return kotlin.math.abs(num / den)
    }

    // ── Field Surround (field_surround.c) ──

    fun surroundSideGain(strengthPct: Double): Double = 1.0 + strengthPct * 0.02
    fun surroundMidGain(midImagePct: Double): Double = 0.5 + midImagePct * 0.01

    fun surroundSample(l: Double, r: Double, strengthPct: Double, midImagePct: Double): Pair<Double, Double> {
        val mid = (l + r) * 0.5 * surroundMidGain(midImagePct)
        val side = (l - r) * 0.5 * surroundSideGain(strengthPct)
        return (mid + side) to (mid - side)
    }

    // ── AGC (viper_agc.c) ──

    const val AGC_ENV_FLOOR = 0.002
    const val AGC_GAIN_MIN = 0.2

    fun agcTarget(targetPct: Double): Double = targetPct * 0.01
    fun agcMaxGain(maxBoostDb: Double): Double = 10.0.pow(maxBoostDb / 20.0)
    fun agcEnvCoef(sampleRate: Double): Double = 1.0 - exp(-1.0 / (0.3 * sampleRate))
    fun agcGainCoef(sampleRate: Double): Double = 1.0 - exp(-1.0 / (1.5 * sampleRate))

    /** Desired gain for a detector level, clamped to [0.2, maxGain]. */
    fun agcDesiredGain(env: Double, targetPct: Double, maxBoostDb: Double): Double {
        var desired = agcTarget(targetPct) / env
        val maxGain = agcMaxGain(maxBoostDb)
        if (desired > maxGain) desired = maxGain
        if (desired < AGC_GAIN_MIN) desired = AGC_GAIN_MIN
        return desired
    }

    // ── Cure (cure.c) ──

    fun cureCutoffHz(level: Int): Double = if (level == 2) 650.0 else 700.0
    fun cureFeed(level: Int): Double = when (level) {
        1 -> 0.40
        2 -> 0.55
        else -> 0.30
    }

    fun cureLpCoef(sampleRate: Double, level: Int): Double =
        1.0 - exp(-2.0 * Math.PI * cureCutoffHz(level) / sampleRate)

    fun cureNorm(level: Int): Double = 1.0 / (1.0 + cureFeed(level))
}
