package me.timschneeberger.rootlessjamesdsp.utils

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Computes the loudness correction frequency response curve for visualization.
 * Mirrors the C++ LoudnessCorrectionProcessor logic for both modes:
 *  - Mode 0 (Classic): Fletcher-Munson two-shelf heuristic
 *  - Mode 1 (ISO 226:2023): 29-band contour-based correction
 *
 * Returns a pair: (frequencies Hz, gains dB) sampled at the given resolution.
 */
object LoudnessCurveCalculator {

    // ISO 226:2023 Table 1: 29 one-third-octave frequencies (Hz)
    private val isoFreqs = doubleArrayOf(
        20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0,
        200.0, 250.0, 315.0, 400.0, 500.0, 630.0, 800.0, 1000.0, 1250.0, 1600.0,
        2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0, 8000.0, 10000.0, 12500.0
    )

    // ISO 226:2023 Table 1: exponent α_f
    private val isoAf = doubleArrayOf(
        0.635, 0.602, 0.569, 0.537, 0.509, 0.482, 0.456, 0.433, 0.412, 0.391,
        0.373, 0.357, 0.343, 0.330, 0.320, 0.311, 0.303, 0.300, 0.295, 0.292,
        0.290, 0.290, 0.289, 0.289, 0.289, 0.293, 0.303, 0.323, 0.354
    )

    // ISO 226:2023 Table 1: L_U (dB)
    private val isoLu = doubleArrayOf(
        -31.5, -27.2, -23.1, -19.3, -16.1, -13.1, -10.4, -8.2, -6.3, -4.6,
        -3.2, -2.1, -1.2, -0.5, 0.0, 0.4, 0.5, 0.0, -2.7, -4.2,
        -1.2, 1.4, 2.3, 1.0, -2.3, -7.2, -11.2, -10.9, -3.5
    )

    // ISO 226:2023 Table 1: T_f (dB)
    private val isoTf = doubleArrayOf(
        78.1, 68.7, 59.5, 51.1, 44.0, 37.5, 31.5, 26.5, 22.1, 17.9,
        14.4, 11.4, 8.6, 6.2, 4.4, 3.0, 2.2, 2.4, 3.5, 1.7,
        -1.3, -4.2, -6.0, -5.4, -1.5, 6.0, 12.6, 13.9, 12.3
    )

    private const val BASE_PHON = 80.0

    data class CurveResult(
        val frequencies: DoubleArray,
        val gains: DoubleArray,
        val preampDb: Double
    )

    /**
     * Compute the loudness correction curve.
     *
     * @param mode 0 = classic, 1 = ISO 226:2023
     * @param referenceLevel user reference level (dB)
     * @param referenceOffset user reference offset (dB)
     * @param attenuation correction strength [0..2]
     * @param volumeDb current playback volume (dB)
     * @param numPoints resolution of the output curve
     * @return curve result with frequencies and gains in dB
     */
    fun compute(
        mode: Int,
        referenceLevel: Double,
        referenceOffset: Double,
        attenuation: Double,
        volumeDb: Double,
        numPoints: Int = 200
    ): CurveResult {
        val att = attenuation.coerceIn(0.0, 2.0)
        val volDiff = referenceLevel - referenceOffset - volumeDb

        if (abs(volDiff) < 0.01) {
            // No correction needed — flat line at 0 dB
            val freqs = logSpace(20.0, 20000.0, numPoints)
            val gains = DoubleArray(numPoints) { 0.0 }
            return CurveResult(freqs, gains, 0.0)
        }

        return if (mode == 1) {
            computeIso226(volDiff, att, numPoints)
        } else {
            computeClassic(volDiff, att, numPoints)
        }
    }

    // ---- Classic mode (Fletcher-Munson two-shelf) ----

    private fun computeClassic(volDiff: Double, att: Double, numPoints: Int): CurveResult {
        val freqs = logSpace(20.0, 20000.0, numPoints)
        val gains = DoubleArray(numPoints)

        // Low shelf params (mirrors C++ getLowShelfParams)
        val lsFreq = 75.0
        val lsS = 0.52
        var lsGain = 0.0
        var preAmp = 0.0

        if (volDiff > 0.0) {
            lsGain = volDiff * 0.55 / (1.0 - 0.55) * att
            preAmp = -lsGain
        } else if (volDiff < 0.0) {
            lsGain = volDiff * 0.55 * kotlin.math.exp(volDiff / 90.0) * att
        }

        // High shelf params (mirrors C++ getHighShelfParams)
        // C++ calls getHighShelfParams(volume + preAmp), which inside computes
        // volDiff = referenceLevel - referenceOffset - (volume + preAmp)
        //          = volDiff_original - preAmp
        val hsFreq = 10000.0
        val hsS = 0.9
        val hsVol = volDiff - preAmp
        var hsGain = 0.0

        if (hsVol > 0.0) {
            hsGain = hsVol * 0.225 * kotlin.math.exp(-hsVol / 100.0) * att
        } else if (hsVol < 0.0) {
            hsGain = hsVol * 0.175 * kotlin.math.exp(hsVol / 80.0) * att
        }

        // Approximate the combined shelf response at each frequency
        for (i in freqs.indices) {
            val f = freqs[i]
            val lsResponse = lowShelfResponse(f, lsFreq, lsGain)
            val hsResponse = highShelfResponse(f, hsFreq, hsGain)
            gains[i] = lsResponse + hsResponse + preAmp
        }

        return CurveResult(freqs, gains, preAmp)
    }

    /**
     * Approximate low shelf filter magnitude response at frequency f.
     * Below shelfFreq: gain approaches lsGain. Above: approaches 0 dB.
     * Transition width ≈ 1.2 octaves, matching a real biquad shelf with S≈0.5.
     */
    private fun lowShelfResponse(f: Double, shelfFreq: Double, gain: Double): Double {
        if (abs(gain) < 0.001) return 0.0
        val ratio = log10(f / shelfFreq)
        val t = 1.0 / (1.0 + 10.0.pow(ratio / 0.2))
        return gain * t
    }

    /**
     * Approximate high shelf filter magnitude response at frequency f.
     * Above shelfFreq: gain approaches hsGain. Below: approaches 0 dB.
     * Transition width ≈ 1.2 octaves, matching a real biquad shelf with S≈0.9.
     */
    private fun highShelfResponse(f: Double, shelfFreq: Double, gain: Double): Double {
        if (abs(gain) < 0.001) return 0.0
        val ratio = log10(f / shelfFreq)
        val t = 1.0 / (1.0 + 10.0.pow(-ratio / 0.2))
        return gain * t
    }

    // ---- ISO 226:2023 mode ----

    private fun computeIso226(volDiff: Double, att: Double, numPoints: Int): CurveResult {
        val refPhon = BASE_PHON
        val curPhon = (BASE_PHON - volDiff).coerceIn(20.0, 90.0)

        // Compute gains at 29 ISO frequencies
        val isoGains = DoubleArray(isoFreqs.size)
        var maxGain = 0.0
        for (i in isoFreqs.indices) {
            val refSpl = splFromPhon(isoAf[i], isoLu[i], isoTf[i], refPhon)
            val curSpl = splFromPhon(isoAf[i], isoLu[i], isoTf[i], curPhon)
            val gain = (curSpl - refSpl + (refPhon - curPhon)) * att
            isoGains[i] = gain
            if (gain > maxGain) maxGain = gain
        }

        val preAmpDb = -maxGain * att

        // Interpolate to a smooth curve from 20 Hz to 20 kHz.
        // ISO data only goes to 12.5 kHz — extrapolate beyond that
        // using the slope of the last two bands (log-linear).
        val freqs = logSpace(20.0, 20000.0, numPoints)
        val gains = DoubleArray(numPoints) { i ->
            interpolateGainExtrapolated(freqs[i], isoFreqs, isoGains) + preAmpDb
        }

        return CurveResult(freqs, gains, preAmpDb)
    }

    private fun splFromPhon(af: Double, lu: Double, tf: Double, lN: Double): Double {
        val term1 = (4e-10).pow(0.3 - af) *
            (10.0.pow(0.03 * lN) - 10.0.pow(0.072))
        val term2 = 10.0.pow(af * (tf + lu) / 10.0)
        val inner = term1 + term2
        if (inner <= 0.0) return 0.0
        return (10.0 / af) * log10(inner) - lu
    }

    private fun interpolateGainExtrapolated(freq: Double, freqs: DoubleArray, gains: DoubleArray): Double {
        if (freqs.isEmpty()) return 0.0
        if (freqs.size == 1) return gains[0]

        // Below first point: hold first value
        if (freq <= freqs[0]) return gains[0]

        // Above last point: extrapolate using slope of last two points
        if (freq >= freqs[freqs.size - 1]) {
            val n = freqs.size
            val logF = log10(freq)
            val logF0 = log10(freqs[n - 2])
            val logF1 = log10(freqs[n - 1])
            if (logF1 <= logF0) return gains[n - 1]
            val slope = (gains[n - 1] - gains[n - 2]) / (logF1 - logF0)
            return gains[n - 1] + slope * (logF - logF1)
        }

        // Between points: log-linear interpolation
        for (i in 0 until freqs.size - 1) {
            if (freq in freqs[i]..freqs[i + 1]) {
                val logF = log10(freq)
                val logF0 = log10(freqs[i])
                val logF1 = log10(freqs[i + 1])
                if (logF1 <= logF0) return gains[i]
                val t = (logF - logF0) / (logF1 - logF0)
                return gains[i] + t * (gains[i + 1] - gains[i])
            }
        }
        return 0.0
    }

    private fun logSpace(min: Double, max: Double, n: Int): DoubleArray {
        val logMin = log10(min)
        val logMax = log10(max)
        return DoubleArray(n) { i ->
            10.0.pow(logMin + (logMax - logMin) * i.toDouble() / (n - 1))
        }
    }
}
