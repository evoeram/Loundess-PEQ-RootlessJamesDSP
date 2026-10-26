package me.timschneeberger.rootlessjamesdsp.model

import java.io.Serializable
import java.util.UUID

/** Dynamics direction of a Dynamic EQ band. */
enum class DynamicEqMode(val code: Int) {
    COMPRESS(0),
    EXPAND(1);

    companion object {
        fun fromCode(code: Int) = entries.firstOrNull { it.code == code } ?: COMPRESS
    }
}

/** Channel component a Dynamic EQ band operates on. */
enum class DynamicEqMsMode(val code: Int) {
    STEREO(0),
    MID(1),
    SIDE(2);

    companion object {
        fun fromCode(code: Int) = entries.firstOrNull { it.code == code } ?: STEREO
    }
}

/**
 * A Dynamic EQ band: a peaking filter whose gain is driven by the level of
 * its own frequency region.
 *
 * [uuid] is excluded from equals/hashCode so that two bands with the same
 * audio parameters compare as equal regardless of identity.
 */
class DynamicEqBand(
    val frequency: Double,
    val q: Double,
    val thresholdDb: Double,
    val ratio: Double,
    val attackMs: Double,
    val releaseMs: Double,
    val rangeDb: Double,
    val mode: DynamicEqMode = DynamicEqMode.COMPRESS,
    val uuid: UUID = UUID.randomUUID()
) : Serializable {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DynamicEqBand) return false
        return frequency == other.frequency &&
                q == other.q &&
                thresholdDb == other.thresholdDb &&
                ratio == other.ratio &&
                attackMs == other.attackMs &&
                releaseMs == other.releaseMs &&
                rangeDb == other.rangeDb &&
                mode == other.mode
    }

    override fun hashCode(): Int {
        var result = frequency.hashCode()
        result = 31 * result + q.hashCode()
        result = 31 * result + thresholdDb.hashCode()
        result = 31 * result + ratio.hashCode()
        result = 31 * result + attackMs.hashCode()
        result = 31 * result + releaseMs.hashCode()
        result = 31 * result + rangeDb.hashCode()
        result = 31 * result + mode.hashCode()
        return result
    }

    override fun toString(): String =
        "DynamicEqBand(frequency=$frequency, q=$q, thresholdDb=$thresholdDb, ratio=$ratio, " +
                "attackMs=$attackMs, releaseMs=$releaseMs, rangeDb=$rangeDb, mode=$mode, uuid=$uuid)"
}
