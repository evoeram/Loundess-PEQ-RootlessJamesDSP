package me.timschneeberger.rootlessjamesdsp.model

import android.os.Bundle
import androidx.databinding.ObservableArrayList
import me.timschneeberger.rootlessjamesdsp.utils.extensions.CompatExtensions.getSerializableAs
import timber.log.Timber
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import java.util.UUID

/**
 * Band list for Dynamic EQ.
 *
 * Internal serialization format for SharedPreferences:
 * "DEQ: freq q threshold ratio attack release range mode; ..."
 * Bands beyond [MAX_BANDS] are dropped on load, matching the native limit.
 */
class DynamicEqBandList : ObservableArrayList<DynamicEqBand>() {
    private val dfFreq = DecimalFormat("0", DecimalFormatSymbols.getInstance(Locale.ENGLISH)).apply {
        maximumFractionDigits = 2
    }
    private val dfQ = DecimalFormat("0", DecimalFormatSymbols.getInstance(Locale.ENGLISH)).apply {
        maximumFractionDigits = 4
    }
    private val dfDb = DecimalFormat("0", DecimalFormatSymbols.getInstance(Locale.ENGLISH)).apply {
        maximumFractionDigits = 2
    }
    private val dfRatio = DecimalFormat("0", DecimalFormatSymbols.getInstance(Locale.ENGLISH)).apply {
        maximumFractionDigits = 3
    }
    private val dfTime = DecimalFormat("0", DecimalFormatSymbols.getInstance(Locale.ENGLISH)).apply {
        maximumFractionDigits = 3
    }

    fun serialize(): String {
        val sb = StringBuilder("DEQ: ")
        for (band in this) {
            sb.append(
                "${dfFreq.format(band.frequency)} ${dfQ.format(band.q)} " +
                        "${dfDb.format(band.thresholdDb)} ${dfRatio.format(band.ratio)} " +
                        "${dfTime.format(band.attackMs)} ${dfTime.format(band.releaseMs)} " +
                        "${dfDb.format(band.rangeDb)} ${band.mode.code}; "
            )
        }
        return sb.toString()
    }

    fun deserialize(str: String) {
        this.clear()

        str.replace("DEQ:", "")
            .replace("\n", " ")
            .split(";")
            .map { it.trim() }
            .filter(String::isNotBlank)
            .forEach { s ->
                if (size >= MAX_BANDS) return@forEach
                val parts = s.split(" ").filter(String::isNotBlank)
                val freq = parts.getOrNull(0)?.toDoubleOrNull()
                val q = parts.getOrNull(1)?.toDoubleOrNull()
                val threshold = parts.getOrNull(2)?.toDoubleOrNull()
                val ratio = parts.getOrNull(3)?.toDoubleOrNull()
                val attack = parts.getOrNull(4)?.toDoubleOrNull()
                val release = parts.getOrNull(5)?.toDoubleOrNull()
                val range = parts.getOrNull(6)?.toDoubleOrNull()
                val mode = parts.getOrNull(7)?.toIntOrNull()

                if (freq != null && q != null && threshold != null && ratio != null &&
                    attack != null && release != null && range != null && mode != null
                ) {
                    this.add(
                        DynamicEqBand(
                            freq, q, threshold, ratio, attack, release, range,
                            DynamicEqMode.fromCode(mode)
                        )
                    )
                } else {
                    Timber.d("DynamicEqBandList: skipping malformed band '$s'")
                }
            }
    }

    /**
     * Native/AudioEffect payload: [frequency, q, thresholdDb, ratio, attackMs,
     * releaseMs, rangeDb, mode] per band, max [MAX_BANDS] bands.
     */
    fun toNativeConfig(): FloatArray {
        val count = minOf(size, MAX_BANDS)
        val data = FloatArray(count * VALUES_PER_BAND)
        for (i in 0 until count) {
            val band = this[i]
            val base = i * VALUES_PER_BAND
            data[base + 0] = band.frequency.toFloat()
            data[base + 1] = band.q.toFloat()
            data[base + 2] = band.thresholdDb.toFloat()
            data[base + 3] = band.ratio.toFloat()
            data[base + 4] = band.attackMs.toFloat()
            data[base + 5] = band.releaseMs.toFloat()
            data[base + 6] = band.rangeDb.toFloat()
            data[base + 7] = band.mode.code.toFloat()
        }
        return data
    }

    fun fromBundle(bundle: Bundle) {
        this.clear()

        val freq = bundle.getDoubleArray(STATE_FREQ) ?: return
        val q = bundle.getDoubleArray(STATE_Q) ?: return
        val threshold = bundle.getDoubleArray(STATE_THRESHOLD) ?: return
        val ratio = bundle.getDoubleArray(STATE_RATIO) ?: return
        val attack = bundle.getDoubleArray(STATE_ATTACK) ?: return
        val release = bundle.getDoubleArray(STATE_RELEASE) ?: return
        val range = bundle.getDoubleArray(STATE_RANGE) ?: return
        val modes = bundle.getIntArray(STATE_MODE) ?: return
        val uuids = bundle.getSerializableAs<Array<UUID>>(STATE_UUID)

        val count = minOf(
            freq.size, q.size, threshold.size, ratio.size,
            attack.size, release.size, range.size, modes.size, MAX_BANDS
        )
        for (i in 0 until count) {
            this.add(
                DynamicEqBand(
                    freq[i], q[i], threshold[i], ratio[i], attack[i], release[i], range[i],
                    DynamicEqMode.fromCode(modes[i]),
                    uuids?.getOrNull(i) ?: UUID.randomUUID()
                )
            )
        }
    }

    fun toBundle(): Bundle {
        val bundle = Bundle()
        val freqArr = DoubleArray(size)
        val qArr = DoubleArray(size)
        val thresholdArr = DoubleArray(size)
        val ratioArr = DoubleArray(size)
        val attackArr = DoubleArray(size)
        val releaseArr = DoubleArray(size)
        val rangeArr = DoubleArray(size)
        val modeArr = IntArray(size)
        val uuidArr = arrayListOf<UUID>()

        for ((i, band) in this.withIndex()) {
            freqArr[i] = band.frequency
            qArr[i] = band.q
            thresholdArr[i] = band.thresholdDb
            ratioArr[i] = band.ratio
            attackArr[i] = band.attackMs
            releaseArr[i] = band.releaseMs
            rangeArr[i] = band.rangeDb
            modeArr[i] = band.mode.code
            uuidArr.add(band.uuid)
        }

        bundle.putDoubleArray(STATE_FREQ, freqArr)
        bundle.putDoubleArray(STATE_Q, qArr)
        bundle.putDoubleArray(STATE_THRESHOLD, thresholdArr)
        bundle.putDoubleArray(STATE_RATIO, ratioArr)
        bundle.putDoubleArray(STATE_ATTACK, attackArr)
        bundle.putDoubleArray(STATE_RELEASE, releaseArr)
        bundle.putDoubleArray(STATE_RANGE, rangeArr)
        bundle.putIntArray(STATE_MODE, modeArr)
        bundle.putSerializable(STATE_UUID, uuidArr.toTypedArray())
        return bundle
    }

    companion object {
        const val MAX_BANDS = 8
        const val VALUES_PER_BAND = 8

        private const val STATE_FREQ = "deq_freq"
        private const val STATE_Q = "deq_q"
        private const val STATE_THRESHOLD = "deq_threshold"
        private const val STATE_RATIO = "deq_ratio"
        private const val STATE_ATTACK = "deq_attack"
        private const val STATE_RELEASE = "deq_release"
        private const val STATE_RANGE = "deq_range"
        private const val STATE_MODE = "deq_mode"
        private const val STATE_UUID = "deq_uuid"
    }
}
