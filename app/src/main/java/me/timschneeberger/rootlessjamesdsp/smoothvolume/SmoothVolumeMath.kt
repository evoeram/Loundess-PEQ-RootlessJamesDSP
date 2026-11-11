package me.timschneeberger.rootlessjamesdsp.smoothvolume

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Pure math for the Smooth Volume feature. No Android dependencies, unit tested.
 *
 * Range: -60..0 dB, default 0 dB, step 0.5 dB. Everything is dB-linear;
 * the [position] domain (0..1) maps linearly onto the dB scale — it is a
 * slider position, never a "percent volume".
 */
object SmoothVolumeMath {
    const val MIN_DB = -60.0
    const val MAX_DB = 0.0
    const val DEFAULT_DB = 0.0
    const val STEP_DB = 0.5

    /** Clamp to the supported gain range. */
    fun clamp(db: Double): Double = db.coerceIn(MIN_DB, MAX_DB)

    /** Round to the nearest 0.5 dB step (half up). */
    fun roundToStep(db: Double): Double = kotlin.math.round(db / STEP_DB) * STEP_DB

    /** Round to step, then clamp. Canonical entry point for any user input. */
    fun snap(db: Double): Double = clamp(roundToStep(db))

    /**
     * Apply a delta to [db] and snap the result. Returns the unchanged value
     * when the delta does not move off a limit (callers use this to detect no-ops).
     */
    fun adjust(db: Double, deltaDb: Double): Double {
        val target = snap(db + deltaDb)
        // A sub-step delta (e.g. a tiny drag) must not round to a value that
        // differs from the current one in the wrong direction; snap() already
        // guarantees |result - (db + delta)| <= STEP/2, which is the desired
        // quantized behavior.
        return target
    }

    /** dB -> linear gain factor (10^(dB/20)). */
    fun dbToLinear(db: Double): Double = 10.0.pow(db / 20.0)

    /** dB -> slider position 0..1 (dB-linear). */
    fun dbToPosition(db: Double): Double = ((clamp(db) - MIN_DB) / (MAX_DB - MIN_DB))

    /** Slider position 0..1 -> dB, snapped. */
    fun positionToDb(position: Double): Double = snap(MIN_DB + position * (MAX_DB - MIN_DB))

    /** Format like the HUD/app show it: "-3.5 dB" / "0.0 dB" (one decimal, trimmed). */
    fun formatDb(db: Double): String {
        val rounded = roundToStep(db)
        val text = if (rounded == rounded.roundToInt().toDouble())
            rounded.roundToInt().toString()
        else
            rounded.toString()
        return "$text dB"
    }

    /** True when two snapped values are the same step (identity check for change detection). */
    fun sameStep(a: Double, b: Double): Boolean = abs(a - b) < STEP_DB / 2.0
}
