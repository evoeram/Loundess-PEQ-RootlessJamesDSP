package me.timschneeberger.rootlessjamesdsp.utils

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

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
     * @param tuning user-tunable shelf/contour parameters (null = defaults)
     * @param numPoints resolution of the output curve
     * @return curve result with frequencies and gains in dB
     */
    fun compute(
        mode: Int,
        referenceLevel: Double,
        referenceOffset: Double,
        attenuation: Double,
        volumeDb: Double,
        tuning: TuningParams? = null,
        subsonic: SubsonicParams? = null,
        numPoints: Int = 200
    ): CurveResult {
        val t = tuning ?: TuningParams()
        val att = attenuation.coerceIn(0.0, 2.0)
        val volDiff = referenceLevel - referenceOffset - volumeDb

        // Build subsonic HP response curve (applied regardless of volDiff)
        val subsonicResponse = if (subsonic?.enable == true) {
            buildSubsonicResponse(subsonic, numPoints)
        } else null

        if (abs(volDiff) < 0.01) {
            // No loudness correction needed — but still show subsonic filter if enabled
            val freqs = logSpace(20.0, 20000.0, numPoints)
            val gains = if (subsonicResponse != null) subsonicResponse.copyOf() else DoubleArray(numPoints) { 0.0 }
            // Bring peak to 0.0 dB
            val maxGain0 = gains.maxOrNull() ?: 0.0
            val correction0 = -maxGain0
            if (abs(correction0) > 0.001) {
                for (i in gains.indices) gains[i] += correction0
            }
            return CurveResult(freqs, gains, correction0)
        }

        val result = if (mode == 1) {
            computeIso226(volDiff, att, t, numPoints)
        } else {
            computeClassic(volDiff, att, t, numPoints)
        }

        // Apply subsonic filter response on top of loudness curve
        if (subsonicResponse != null) {
            for (i in result.gains.indices) {
                result.gains[i] += subsonicResponse[i]
            }
        }

        // Recompute preamp: find actual peak of the combined curve (LS + HS + subsonic)
        // and bring it to 0.0 dB. This accounts for subsonic filter, LS/HS slope and Q,
        // and any filter interactions that the initial preamp estimate didn't cover.
        val maxGain = result.gains.maxOrNull() ?: 0.0
        val correction = -maxGain
        if (abs(correction) > 0.001) {
            for (i in result.gains.indices) {
                result.gains[i] += correction
            }
        }

        return result.copy(preampDb = result.preampDb + correction)
    }

    /** Subsonic (infrasonic) high-pass filter parameters. */
    data class SubsonicParams(
        val enable: Boolean = false,
        val freq: Double = 20.0,
        val order: Int = 4,        // 1–8
        val qFactor: Double = 0.707
    )

    /**
     * Build the frequency response (dB) of a cascaded high-pass filter.
     * Each 2nd-order stage uses an RBJ biquad HP response.
     * Total attenuation at DC = order × 6 dB.
     */
    private fun buildSubsonicResponse(s: SubsonicParams, numPoints: Int): DoubleArray {
        val freqs = logSpace(20.0, 20000.0, numPoints)
        val response = DoubleArray(numPoints)
        val numStages = (s.order + 1) / 2 // ceil(order/2)
        val f = s.freq.coerceIn(1.0, 20000.0)
        val q = s.qFactor.coerceIn(0.1, 10.0)

        for (i in freqs.indices) {
            var gainDb = 0.0
            for (stage in 0 until numStages) {
                gainDb += highPassBiquadResponseDb(freqs[i], f, q)
            }
            // For odd order, add a 1st-order RC response (−6 dB/oct)
            if (s.order % 2 == 1) {
                gainDb += firstOrderHighPassDb(freqs[i], f)
            }
            response[i] = gainDb
        }
        return response
    }

    /** RBJ 2nd-order high-pass biquad magnitude response in dB. */
    private fun highPassBiquadResponseDb(f: Double, fc: Double, q: Double): Double {
        val ratio = f / fc
        if (ratio <= 0.0) return -120.0
        // w = f/fc; normalized biquad HP transfer function magnitude
        val w = ratio
        val w2 = w * w
        val q2 = q * q
        // |H(jw)|^2 for RBJ HP: (w^4) / (w^4 + w^2*(1/Q^2 - 2) + 1)
        val num = w2 * w2
        val den = w2 * w2 + w2 * (1.0 / q2 - 2.0) + 1.0
        if (den <= 0.0) return 0.0
        val mag = num / den
        return if (mag > 0.0) 10.0 * log10(mag) else -120.0
    }

    /** 1st-order RC high-pass magnitude response in dB. */
    private fun firstOrderHighPassDb(f: Double, fc: Double): Double {
        val ratio = f / fc
        if (ratio <= 0.0) return -120.0
        val mag = ratio / sqrt(1.0 + ratio * ratio)
        return if (mag > 0.0) 20.0 * log10(mag) else -120.0
    }

    /** User-tunable shelf/contour parameters. Defaults match original hardcoded values. */
    data class TuningParams(
        val lsFreq: Double = 75.0,
        val lsSlope: Double = 0.52,
        val lsRatio: Double = 0.55,
        val hsFreq: Double = 10000.0,
        val hsSlope: Double = 0.90,
        val hsRatio: Double = 0.225,
        val isoBasePhon: Double = 80.0,
        val isoQ: Double = 4.318
    )

    // ---- Classic mode (Fletcher-Munson two-shelf) ----

    private fun computeClassic(volDiff: Double, att: Double, t: TuningParams, numPoints: Int): CurveResult {
        val freqs = logSpace(20.0, 20000.0, numPoints)
        val gains = DoubleArray(numPoints)

        // Low shelf params (mirrors C++ getLowShelfParams)
        val lsFreq = t.lsFreq
        val lsS = t.lsSlope
        var lsGain = 0.0
        var preAmp = 0.0

        if (volDiff > 0.0) {
            lsGain = volDiff * t.lsRatio / (1.0 - t.lsRatio) * att
            preAmp = -lsGain
        } else if (volDiff < 0.0) {
            lsGain = volDiff * t.lsRatio * kotlin.math.exp(volDiff / 90.0) * att
        }

        // High shelf params (mirrors C++ getHighShelfParams)
        val hsFreq = t.hsFreq
        val hsS = t.hsSlope
        val hsVol = volDiff - preAmp
        var hsGain = 0.0

        if (hsVol > 0.0) {
            hsGain = hsVol * t.hsRatio * kotlin.math.exp(-hsVol / 100.0) * att
        } else if (hsVol < 0.0) {
            hsGain = hsVol * 0.175 * kotlin.math.exp(hsVol / 80.0) * att
        }

        // Approximate the combined shelf response at each frequency
        for (i in freqs.indices) {
            val f = freqs[i]
            val lsResponse = lowShelfResponse(f, lsFreq, lsGain, lsS)
            val hsResponse = highShelfResponse(f, hsFreq, hsGain, hsS)
            gains[i] = lsResponse + hsResponse + preAmp
        }

        return CurveResult(freqs, gains, preAmp)
    }

    /**
     * Approximate low shelf filter magnitude response at frequency f.
     * Below shelfFreq: gain approaches lsGain. Above: approaches 0 dB.
     * Transition width derived from the S slope parameter, matching a real
     * biquad shelf where smaller S = steeper transition.
     */
    private fun lowShelfResponse(f: Double, shelfFreq: Double, gain: Double, slope: Double): Double {
        if (abs(gain) < 0.001) return 0.0
        val ratio = log10(f / shelfFreq)
        // slope ≈ 0.5 → ~1 octave transition; slope ≈ 1.0 → ~2 octaves
        val transitionWidth = (0.2 + slope * 0.2).coerceIn(0.05, 0.6)
        val t = 1.0 / (1.0 + 10.0.pow(ratio / transitionWidth))
        return gain * t
    }

    /**
     * Approximate high shelf filter magnitude response at frequency f.
     * Above shelfFreq: gain approaches hsGain. Below: approaches 0 dB.
     * Transition width derived from the S slope parameter, matching a real
     * biquad shelf where smaller S = steeper transition.
     */
    private fun highShelfResponse(f: Double, shelfFreq: Double, gain: Double, slope: Double): Double {
        if (abs(gain) < 0.001) return 0.0
        val ratio = log10(f / shelfFreq)
        val transitionWidth = (0.2 + slope * 0.2).coerceIn(0.05, 0.6)
        val t = 1.0 / (1.0 + 10.0.pow(-ratio / transitionWidth))
        return gain * t
    }

    // ---- ISO 226:2023 mode ----

    private fun computeIso226(volDiff: Double, att: Double, t: TuningParams, numPoints: Int): CurveResult {
        val refPhon = t.isoBasePhon
        val curPhon = (t.isoBasePhon - volDiff).coerceIn(20.0, 90.0)

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

        // Pre-amp: offset by the ACTUAL peak of the combined cascade.
        // Adjacent peaking filters overlap and sum, so the cascade peak
        // is higher than any individual band gain.
        val sr = 48000.0
        val q = t.isoQ
        val nyquist = sr * 0.5
        var cascadePeak = maxGain // floor: at least the max individual gain
        for (i in isoFreqs.indices) {
            // Test at band center and geometric mean with next band
            val testFreqs = mutableListOf(isoFreqs[i])
            if (i < isoFreqs.size - 1)
                testFreqs.add(sqrt(isoFreqs[i] * isoFreqs[i + 1]))
            for (f in testFreqs) {
                if (f <= 0.0) continue
                val w = 2.0 * PI * f / sr
                var total = 0.0
                for (j in isoFreqs.indices) {
                    val gain = isoGains[j]
                    if (abs(gain) < 0.05) continue
                    if (isoFreqs[j] > nyquist * 0.98) continue
                    val w0 = 2.0 * PI * isoFreqs[j] / sr
                    val A = 10.0.pow(gain / 40.0)
                    val alpha = sin(w0) / (2.0 * q)
                    val b0 = 1.0 + alpha * A
                    val b1 = -2.0 * cos(w0)
                    val b2 = 1.0 - alpha * A
                    val a0 = 1.0 + alpha / A
                    val a1 = -2.0 * cos(w0)
                    val a2 = 1.0 - alpha / A
                    val cosw = cos(w); val cos2w = cos(2.0 * w)
                    val sinw = sin(w); val sin2w = sin(2.0 * w)
                    val numRe = b0 + b1 * cosw + b2 * cos2w
                    val numIm = b1 * sinw + b2 * sin2w
                    val denRe = a0 + a1 * cosw + a2 * cos2w
                    val denIm = a1 * sinw + a2 * sin2w
                    val numSq = numRe * numRe + numIm * numIm
                    val denSq = denRe * denRe + denIm * denIm
                    if (denSq > 1e-20 && numSq > 0.0)
                        total += 10.0 * log10(numSq / denSq)
                }
                if (total > cascadePeak) cascadePeak = total
            }
        }
        val preAmpDb = -cascadePeak

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
