package me.timschneeberger.rootlessjamesdsp.model

import java.io.Serializable
import java.util.*

enum class ParametricEqFilterType(val code: Int, val apoLabel: String, val displayLabel: String) {
    PEAKING(0, "PK", "PK"),
    LOW_SHELF(1, "LSC", "LS"),
    HIGH_SHELF(2, "HSC", "HS"),
    LOW_PASS(3, "LP", "LP"),
    HIGH_PASS(4, "HP", "HP"),
    BAND_PASS(5, "BP", "BP"),
    NOTCH(6, "NO", "NO"),
    ALL_PASS(7, "AP", "AP"),
    PREAMP(8, "PRE", "Pre");

    /** Whether this filter type is a flat gain stage with no frequency/Q dependence. */
    val isFlatGain: Boolean get() = this == PREAMP

    companion object {
        fun fromCode(code: Int) = entries.firstOrNull { it.code == code } ?: PEAKING
        fun fromApoLabel(label: String) = when (label.uppercase()) {
            "PK" -> PEAKING
            "LSC", "LS" -> LOW_SHELF
            "HSC", "HS" -> HIGH_SHELF
            "LP" -> LOW_PASS
            "HP" -> HIGH_PASS
            "BP" -> BAND_PASS
            "NO" -> NOTCH
            "AP" -> ALL_PASS
            "PRE" -> PREAMP
            else -> null
        }
    }
}

/**
 * Channel routing for a parametric EQ band.
 * Controls which channels the filter is applied to.
 */
enum class ParametricEqChannel(val code: Int, val apoLabel: String, val displayLabel: String) {
    /** Apply to both L and R channels */
    LEFT_RIGHT(0, "", "L+R"),
    /** Apply to left channel only; right passes through */
    LEFT(1, "L", "L"),
    /** Apply to right channel only; left passes through */
    RIGHT(2, "R", "R");

    companion object {
        fun fromCode(code: Int) = entries.firstOrNull { it.code == code } ?: LEFT_RIGHT
        fun fromApoLabel(label: String?) = when (label?.uppercase()) {
            "L" -> LEFT
            "R" -> RIGHT
            else -> LEFT_RIGHT
        }
    }
}

/**
 * A parametric EQ band definition.
 *
 * [uuid] is excluded from equals/hashCode so that two bands with the
 * same audio parameters compare as equal regardless of identity.
 *
 * @param channel controls which channels (L+R, L, R) this band applies to
 */
class ParametricEqBand(
    val frequency: Double,
    val gain: Double,
    val q: Double,
    val filterType: ParametricEqFilterType = ParametricEqFilterType.PEAKING,
    val channel: ParametricEqChannel = ParametricEqChannel.LEFT_RIGHT,
    val uuid: UUID = UUID.randomUUID()
) : Serializable {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ParametricEqBand) return false
        return frequency == other.frequency &&
                gain == other.gain &&
                q == other.q &&
                filterType == other.filterType &&
                channel == other.channel
    }

    override fun hashCode(): Int {
        var result = frequency.hashCode()
        result = 31 * result + gain.hashCode()
        result = 31 * result + q.hashCode()
        result = 31 * result + filterType.hashCode()
        result = 31 * result + channel.hashCode()
        return result
    }

    override fun toString(): String =
        "ParametricEqBand(frequency=$frequency, gain=$gain, q=$q, filterType=$filterType, channel=$channel, uuid=$uuid)"
}
