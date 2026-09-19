package me.timschneeberger.rootlessjamesdsp.model

import android.os.Bundle
import androidx.databinding.ObservableArrayList
import me.timschneeberger.rootlessjamesdsp.utils.extensions.CompatExtensions.getSerializableAs
import timber.log.Timber
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.*

/**
 * Result of parsing an EqualizerAPO format string.
 * @param skippedFilters number of unsupported filter lines that were skipped
 * @param preampDb the parsed Preamp value in dB (0.0 if not present)
 */
data class ApoImportResult(
    val skippedFilters: Int,
    val preampDb: Double
)

class ParametricEqBandList : ObservableArrayList<ParametricEqBand>() {
    private val dfFreq = DecimalFormat("0", DecimalFormatSymbols.getInstance(Locale.ENGLISH))
    private val dfGain = DecimalFormat("0", DecimalFormatSymbols.getInstance(Locale.ENGLISH))
    private val dfQ = DecimalFormat("0", DecimalFormatSymbols.getInstance(Locale.ENGLISH))

    init {
        dfFreq.maximumFractionDigits = 2
        dfGain.maximumFractionDigits = 6
        dfQ.maximumFractionDigits = 4
    }

    /**
     * Internal serialization format for SharedPreferences.
     * Format: "PEQ: freq gain q type chan; freq gain q type chan; ..."
     * chan: 0=both, 1=left, 2=right (omitted for backwards compat defaults to 0)
     */
    fun serialize(): String {
        val sb = StringBuilder("PEQ: ")
        for (band in this) {
            sb.append("${dfFreq.format(band.frequency)} ${dfGain.format(band.gain)} ${dfQ.format(band.q)} ${band.filterType.code} ${band.channelMode.code}; ")
        }
        return sb.toString()
    }

    /**
     * Parse the internal serialization format.
     * Supports both old 4-field format (freq gain q type) and new 5-field (freq gain q type chan).
     */
    fun deserialize(str: String) {
        this.clear()

        str.replace("PEQ:", "")
            .replace("\n", " ")
            .split(";")
            .map { it.trim() }
            .filter(String::isNotBlank)
            .forEach { s ->
                val parts = s.split(" ").filter(String::isNotBlank)
                val freq = parts.getOrNull(0)?.toDoubleOrNull()
                val gain = parts.getOrNull(1)?.toDoubleOrNull()
                val q = parts.getOrNull(2)?.toDoubleOrNull()
                val type = parts.getOrNull(3)?.toIntOrNull()
                val chan = parts.getOrNull(4)?.toIntOrNull() ?: 0 // default BOTH

                if (freq != null && gain != null && q != null && type != null) {
                    this.add(ParametricEqBand(freq, gain, q, ParametricEqFilterType.fromCode(type), ParametricEqChannelMode.fromCode(chan)))
                }
            }
    }

    /**
     * Export to EqualizerAPO format with per-channel support.
     *
     * Groups filters by channel and emits Channel directives:
     *   Preamp: -1.0 dB
     *   Channel: ALL
     *   Filter 1: ON LSC Fc 260 Hz Gain -6.0 dB Q 0.600
     *   Channel: L
     *   Filter 2: ON PK Fc 3440 Hz Gain -0.9 dB Q 3.870
     *   Channel: R
     *   Filter 3: ON PK Fc 2840 Hz Gain 1.0 dB Q 2.390
     *
     * If all bands are BOTH (L+R), no Channel directives are emitted
     * (backwards compatible with single-channel export).
     */
    fun toApoString(preampDb: Double = 0.0): String {
        val sb = StringBuilder()
        sb.appendLine("Preamp: ${dfGain.format(preampDb)} dB")

        val hasPerChannel = this.any { it.channelMode != ParametricEqChannelMode.BOTH }

        if (!hasPerChannel) {
            // All BOTH: simple format without Channel directives
            for ((i, band) in this.withIndex()) {
                sb.appendLine(
                    "Filter ${i + 1}: ON ${band.filterType.apoLabel} Fc ${dfFreq.format(band.frequency)} Hz Gain ${dfGain.format(band.gain)} dB Q ${dfQ.format(band.q)}"
                )
            }
        } else {
            // Per-channel: group by channelMode and emit Channel directives
            var filterIndex = 1
            var currentChannel: ParametricEqChannelMode? = null

            for (band in this) {
                if (band.channelMode != currentChannel) {
                    currentChannel = band.channelMode
                    val channelLabel = when (band.channelMode) {
                        ParametricEqChannelMode.BOTH -> "ALL"
                        ParametricEqChannelMode.LEFT_ONLY -> "L"
                        ParametricEqChannelMode.RIGHT_ONLY -> "R"
                    }
                    sb.appendLine("Channel: $channelLabel")
                }
                sb.appendLine(
                    "Filter ${filterIndex}: ON ${band.filterType.apoLabel} Fc ${dfFreq.format(band.frequency)} Hz Gain ${dfGain.format(band.gain)} dB Q ${dfQ.format(band.q)}"
                )
                filterIndex++
            }
        }

        return sb.toString()
    }

    /**
     * Parse EqualizerAPO format with per-channel support.
     *
     * Supports lines like:
     *   Preamp: -3 dB
     *   Channel: ALL
     *   Filter 1: ON PK Fc 1000 Hz Gain 3.0 dB Q 1.41
     *   Channel: L
     *   Filter 2: ON LSC Fc 100 Hz Gain 5.0 dB Q 0.71
     *   Channel: R
     *   Filter 3: ON PK Fc 2000 Hz Gain -2.0 dB Q 1.0
     *
     * The Channel directive sets the channel mode for all subsequent filters
     * until a new Channel directive is encountered. If no Channel directive
     * is present, filters default to BOTH (L+R).
     *
     * Returns [ApoImportResult] with the parsed preamp and number of skipped filters.
     */
    fun fromApoString(text: String): ApoImportResult {
        this.clear()
        var skipped = 0
        var preampDb = 0.0
        var currentChannel = ParametricEqChannelMode.BOTH

        val filterRegex = Regex(
            """Filter\s+\d+:\s+ON\s+(\S+)\s+Fc\s+([\d.]+)\s+Hz\s+Gain\s+([-\d.]+)\s+dB\s+Q\s+([\d.]+)""",
            RegexOption.IGNORE_CASE
        )
        val preampRegex = Regex(
            """Preamp:\s*([-\d.]+)\s*dB""",
            RegexOption.IGNORE_CASE
        )
        val channelRegex = Regex(
            """Channel:\s*([A-Za-z+]+)""",
            RegexOption.IGNORE_CASE
        )

        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith("#")) {
                continue
            }

            // Parse preamp line
            if (trimmed.startsWith("Preamp", ignoreCase = true)) {
                val preampMatch = preampRegex.find(trimmed)
                if (preampMatch != null) {
                    preampDb = preampMatch.groupValues[1].toDoubleOrNull() ?: 0.0
                    // Clamp to valid range
                    preampDb = preampDb.coerceIn(-30.0, 0.0)
                } else {
                    Timber.d("fromApoString: could not parse preamp line: $trimmed")
                }
                continue
            }

            // Parse Channel directive
            if (trimmed.startsWith("Channel", ignoreCase = true)) {
                val channelMatch = channelRegex.find(trimmed)
                if (channelMatch != null) {
                    val channelStr = channelMatch.groupValues[1].uppercase()
                    currentChannel = when (channelStr) {
                        "ALL", "BOTH", "L+R" -> ParametricEqChannelMode.BOTH
                        "L", "LEFT" -> ParametricEqChannelMode.LEFT_ONLY
                        "R", "RIGHT" -> ParametricEqChannelMode.RIGHT_ONLY
                        else -> {
                            Timber.d("fromApoString: unknown channel '$channelStr', defaulting to BOTH")
                            ParametricEqChannelMode.BOTH
                        }
                    }
                }
                continue
            }

            val match = filterRegex.find(trimmed)
            if (match == null) {
                Timber.d("fromApoString: skipping unrecognized line: $trimmed")
                continue
            }

            val typeStr = match.groupValues[1]
            val freq = match.groupValues[2].toDoubleOrNull() ?: continue
            val gain = match.groupValues[3].toDoubleOrNull() ?: continue
            val q = match.groupValues[4].toDoubleOrNull() ?: continue

            val filterType = ParametricEqFilterType.fromApoLabel(typeStr)
            if (filterType == null) {
                Timber.d("fromApoString: unsupported filter type '$typeStr', skipping")
                skipped++
                continue
            }

            this.add(ParametricEqBand(freq, gain, q, filterType, currentChannel))
        }

        return ApoImportResult(skippedFilters = skipped, preampDb = preampDb)
    }

    fun fromBundle(bundle: Bundle) {
        this.clear()

        val freq = bundle.getDoubleArray(STATE_FREQ) ?: return
        val gain = bundle.getDoubleArray(STATE_GAIN) ?: return
        val q = bundle.getDoubleArray(STATE_Q) ?: return
        val types = bundle.getIntArray(STATE_TYPE) ?: return
        val chans = bundle.getIntArray(STATE_CHAN) // optional, default to BOTH
        val uuids = bundle.getSerializableAs<Array<UUID>>(STATE_UUID)

        val count = minOf(freq.size, gain.size, q.size, types.size)
        for (i in 0 until count) {
            this.add(
                ParametricEqBand(
                    freq[i], gain[i], q[i],
                    ParametricEqFilterType.fromCode(types[i]),
                    ParametricEqChannelMode.fromCode(chans?.getOrNull(i) ?: 0),
                    uuids?.getOrNull(i) ?: UUID.randomUUID()
                )
            )
        }
    }

    fun toBundle(): Bundle {
        val bundle = Bundle()
        val freqArr = DoubleArray(this.size)
        val gainArr = DoubleArray(this.size)
        val qArr = DoubleArray(this.size)
        val typeArr = IntArray(this.size)
        val chanArr = IntArray(this.size)
        val uuidArr = arrayListOf<UUID>()

        for ((i, band) in this.withIndex()) {
            freqArr[i] = band.frequency
            gainArr[i] = band.gain
            qArr[i] = band.q
            typeArr[i] = band.filterType.code
            chanArr[i] = band.channelMode.code
            uuidArr.add(band.uuid)
        }

        bundle.putDoubleArray(STATE_FREQ, freqArr)
        bundle.putDoubleArray(STATE_GAIN, gainArr)
        bundle.putDoubleArray(STATE_Q, qArr)
        bundle.putIntArray(STATE_TYPE, typeArr)
        bundle.putIntArray(STATE_CHAN, chanArr)
        bundle.putSerializable(STATE_UUID, uuidArr.toTypedArray())
        return bundle
    }

    companion object {
        private const val STATE_FREQ = "peq_freq"
        private const val STATE_GAIN = "peq_gain"
        private const val STATE_Q = "peq_q"
        private const val STATE_TYPE = "peq_type"
        private const val STATE_CHAN = "peq_chan"
        private const val STATE_UUID = "peq_uuid"
    }
}
