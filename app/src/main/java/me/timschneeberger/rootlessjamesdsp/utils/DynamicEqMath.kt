package me.timschneeberger.rootlessjamesdsp.utils

import me.timschneeberger.rootlessjamesdsp.model.DynamicEqMode
import kotlin.math.log2

/**
 * Kotlin mirror of the native gain computer in dynamicEq.c.
 *
 * The C module is the one that processes audio; this copy exists so the
 * dynamics math can be unit-tested without the NDK. Keep the two in sync.
 */
object DynamicEqMath {
    /** Detector floor: silence must not send the log to -inf. */
    const val ENV_FLOOR = 1e-6

    /** One-pole coefficient for a time constant in milliseconds. */
    fun timeCoef(ms: Double, sampleRate: Double): Double {
        if (ms <= 0.0 || sampleRate <= 0.0) return 0.0
        return Math.exp(-1.0 / ((ms * 0.001) * sampleRate))
    }

    /** Attack when the magnitude rises, release when it falls. */
    fun envStep(env: Double, magnitude: Double, attackCoef: Double, releaseCoef: Double): Double {
        val c = if (magnitude > env) attackCoef else releaseCoef
        return magnitude + c * (env - magnitude)
    }

    /**
     * Band gain in dB for a detector level [env] (linear).
     * Compressor cuts above the threshold, expander boosts below it;
     * both are clamped by [rangeDb] in the sign the user asked for.
     */
    fun gainComputerDb(
        env: Double,
        thresholdDb: Double,
        ratio: Double,
        rangeDb: Double,
        mode: DynamicEqMode
    ): Double {
        val r = if (ratio < 1.0) 1.0 else ratio
        if (rangeDb == 0.0) return 0.0

        val level = if (env > ENV_FLOOR) env else ENV_FLOOR
        val envDb = 6.020599913279624 * log2(level)
        val over = envDb - thresholdDb
        var target = 0.0

        if (mode == DynamicEqMode.COMPRESS) {
            if (over > 0.0) target = -over * (1.0 - 1.0 / r)
        } else {
            if (over < 0.0) target = -over * (1.0 - 1.0 / r)
        }

        return if (rangeDb < 0.0) target.coerceIn(rangeDb, 0.0) else target.coerceIn(0.0, rangeDb)
    }
}
