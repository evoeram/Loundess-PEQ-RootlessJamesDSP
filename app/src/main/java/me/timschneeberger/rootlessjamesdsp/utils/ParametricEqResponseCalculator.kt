package me.timschneeberger.rootlessjamesdsp.utils

import me.timschneeberger.rootlessjamesdsp.model.ParametricEqBand
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqChannel
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqFilterType
import kotlin.math.*

/**
 * Pure-Kotlin frequency-response calculator for the parametric EQ visualization layer.
 *
 * Computes independent L and R channel magnitude responses by:
 *  1. Filtering bands per channel (LEFT_RIGHT → both, LEFT → L, RIGHT → R)
 *  2. Computing RBJ biquad coefficients for each band
 *  3. Evaluating H(e^jω) = (b0 + b1·e^-jω + b2·e^-2jω) / (a0 + a1·e^-jω + a2·e^-2jω)
 *  4. Cascading: H_total = H1 · H2 · H3 · …  (multiply complex transfer functions)
 *  5. Converting to dB: 20·log10(|H_total|)
 *
 * No Android dependencies. No time-domain simulation. Pure math.
 *
 * @param sampleRate DSP sample rate in Hz (e.g. 48000.0)
 */
class ParametricEqResponseCalculator(
    private val sampleRate: Double = 48000.0,
    private val minFreq: Double = 20.0,
    private val maxFreq: Double = 20000.0,
    private val numPoints: Int = 256
) {
    /** Result of a response computation: frequency array + L/R gain arrays (in dB). */
    data class ResponseData(
        val frequencies: DoubleArray,
        val leftResponseDb: DoubleArray,
        val rightResponseDb: DoubleArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ResponseData) return false
            return frequencies.contentEquals(other.frequencies) &&
                   leftResponseDb.contentEquals(other.leftResponseDb) &&
                   rightResponseDb.contentEquals(other.rightResponseDb)
        }
        override fun hashCode(): Int {
            var result = frequencies.contentHashCode()
            result = 31 * result + leftResponseDb.contentHashCode()
            result = 31 * result + rightResponseDb.contentHashCode()
            return result
        }
    }

    /**
     * Compute independent L and R frequency responses (filter-only, WITHOUT preamp).
     *
     * - LEFT_RIGHT bands contribute to BOTH channels
     * - LEFT bands contribute ONLY to leftResponseDb
     * - RIGHT bands contribute ONLY to rightResponseDb
     *
     * If no bands affect a channel, that channel's response is 0 dB everywhere.
     *
     * Preamp is NOT included in the response — the caller (visualization layer)
     * applies preamp separately so that preamp changes don't require a full
     * recalculation of the filter response.
     *
     * @param bands EQ band configurations
     * @param preampDb Unused. Kept for API compatibility. Preamp is applied by the view.
     * @return [ResponseData] with frequencies, leftResponseDb, rightResponseDb
     */
    fun compute(bands: List<ParametricEqBand>, @Suppress("UNUSED_PARAMETER") preampDb: Double = 0.0): ResponseData {
        val freqs = logSpacedFrequencies()
        val leftBands = bandsForChannel(bands, ParametricEqChannel.LEFT)
        val rightBands = bandsForChannel(bands, ParametricEqChannel.RIGHT)

        val leftResponse = DoubleArray(freqs.size)
        val rightResponse = DoubleArray(freqs.size)

        for (i in freqs.indices) {
            val f = freqs[i]
            leftResponse[i] = cascadeMagnitudeDb(leftBands, f)
            rightResponse[i] = cascadeMagnitudeDb(rightBands, f)
        }

        return ResponseData(freqs, leftResponse, rightResponse)
    }

    /** Logarithmically spaced frequencies from [minFreq] to [maxFreq]. */
    fun logSpacedFrequencies(): DoubleArray {
        val freqs = DoubleArray(numPoints)
        val logMin = log10(minFreq)
        val logMax = log10(maxFreq)
        for (i in 0 until numPoints) {
            val t = if (numPoints > 1) i.toDouble() / (numPoints - 1) else 0.0
            freqs[i] = 10.0.pow(logMin + t * (logMax - logMin))
        }
        return freqs
    }

    /** Filter bands that affect the given channel. */
    private fun bandsForChannel(
        bands: List<ParametricEqBand>,
        channel: ParametricEqChannel
    ): List<ParametricEqBand> {
        return bands.filter { band ->
            when (band.channel) {
                ParametricEqChannel.LEFT_RIGHT -> true
                ParametricEqChannel.LEFT -> channel == ParametricEqChannel.LEFT
                ParametricEqChannel.RIGHT -> channel == ParametricEqChannel.RIGHT
            }
        }
    }

    /**
     * Compute cascaded magnitude response in dB at a single frequency.
     *
     * H_total = H1 · H2 · … · Hn  (complex multiplication)
     * result = 20 · log10(|H_total|)
     *
     * Returns 0.0 dB if no bands.
     */
    private fun cascadeMagnitudeDb(bands: List<ParametricEqBand>, freq: Double): Double {
        if (bands.isEmpty()) return 0.0

        var totalMagLin = 1.0

        for (band in bands) {
            val coeffs = computeCoefficients(
                band.frequency, band.gain, band.q, band.filterType, sampleRate
            )
            val h = transferFunction(coeffs, freq, sampleRate)
            totalMagLin *= complexMagnitude(h)
        }

        return if (totalMagLin > 0.0) 20.0 * log10(totalMagLin) else 0.0
    }

    /** RBJ biquad coefficients (normalized: a0 = 1). */
    private data class BiquadCoeffs(
        val b0: Double, val b1: Double, val b2: Double,
        val a1: Double, val a2: Double
    )

    /** Compute normalized biquad coefficients for a band. */
    private fun computeCoefficients(
        freq: Double, gain: Double, q: Double,
        filterType: ParametricEqFilterType, sr: Double
    ): BiquadCoeffs {
        // Clamp inputs to safe ranges
        val f = freq.coerceIn(1.0, sr * 0.49)
        val qClamped = q.coerceIn(0.1, 24.0)

        val A = 10.0.pow(gain / 40.0)
        val omega = 2.0 * PI * f / sr
        val sinOmega = sin(omega)
        val cosOmega = cos(omega)
        val alpha = sinOmega / (2.0 * qClamped)

        val (b0, b1, b2, a0, a1, a2) = when (filterType) {
            ParametricEqFilterType.PREAMP -> {
                // Flat frequency-independent gain stage (ported PreampFilter).
                // Represented as a degenerate biquad: H(z) = 10^(gain/20).
                val g = 10.0.pow(gain / 20.0)
                Sextet(g, 0.0, 0.0, 1.0, 0.0, 0.0)
            }
            ParametricEqFilterType.PEAKING -> Sextet(
                1.0 + alpha * A, -2.0 * cosOmega, 1.0 - alpha * A,
                1.0 + alpha / A, -2.0 * cosOmega, 1.0 - alpha / A
            )
            ParametricEqFilterType.LOW_SHELF -> {
                val sqrtA = sqrt(A)
                val beta = 2.0 * sqrtA * alpha
                Sextet(
                    A * ((A + 1.0) - (A - 1.0) * cosOmega + beta),
                    2.0 * A * ((A - 1.0) - (A + 1.0) * cosOmega),
                    A * ((A + 1.0) - (A - 1.0) * cosOmega - beta),
                    (A + 1.0) + (A - 1.0) * cosOmega + beta,
                    -2.0 * ((A - 1.0) + (A + 1.0) * cosOmega),
                    (A + 1.0) + (A - 1.0) * cosOmega - beta
                )
            }
            ParametricEqFilterType.HIGH_SHELF -> {
                val sqrtA = sqrt(A)
                val beta = 2.0 * sqrtA * alpha
                Sextet(
                    A * ((A + 1.0) + (A - 1.0) * cosOmega + beta),
                    -2.0 * A * ((A - 1.0) + (A + 1.0) * cosOmega),
                    A * ((A + 1.0) + (A - 1.0) * cosOmega - beta),
                    (A + 1.0) - (A - 1.0) * cosOmega + beta,
                    2.0 * ((A - 1.0) - (A + 1.0) * cosOmega),
                    (A + 1.0) - (A - 1.0) * cosOmega - beta
                )
            }
            ParametricEqFilterType.LOW_PASS -> Sextet(
                (1.0 - cosOmega) / 2.0, 1.0 - cosOmega, (1.0 - cosOmega) / 2.0,
                1.0 + alpha, -2.0 * cosOmega, 1.0 - alpha
            )
            ParametricEqFilterType.HIGH_PASS -> Sextet(
                (1.0 + cosOmega) / 2.0, -(1.0 + cosOmega), (1.0 + cosOmega) / 2.0,
                1.0 + alpha, -2.0 * cosOmega, 1.0 - alpha
            )
            ParametricEqFilterType.BAND_PASS -> Sextet(
                alpha, 0.0, -alpha,
                1.0 + alpha, -2.0 * cosOmega, 1.0 - alpha
            )
            ParametricEqFilterType.NOTCH -> Sextet(
                1.0, -2.0 * cosOmega, 1.0,
                1.0 + alpha, -2.0 * cosOmega, 1.0 - alpha
            )
            ParametricEqFilterType.ALL_PASS -> Sextet(
                1.0 - alpha, -2.0 * cosOmega, 1.0 + alpha,
                1.0 + alpha, -2.0 * cosOmega, 1.0 - alpha
            )
        }

        // Normalize by a0
        return BiquadCoeffs(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    private data class Sextet(val b0: Double, val b1: Double, val b2: Double,
                              val a0: Double, val a1: Double, val a2: Double)

    /** Complex number pair (real, imag). */
    private data class Complex(val re: Double, val im: Double)

    /**
     * Evaluate the transfer function H(z) at z = e^(jω).
     *
     * H(e^jω) = (b0 + b1·e^-jω + b2·e^-2jω) / (1 + a1·e^-jω + a2·e^-2jω)
     */
    private fun transferFunction(coeffs: BiquadCoeffs, freq: Double, sr: Double): Complex {
        val omega = 2.0 * PI * freq / sr
        val cosW = cos(omega)
        val sinW = sin(omega)
        val cos2W = cos(2.0 * omega)
        val sin2W = sin(2.0 * omega)

        // e^(-jω) = cos(ω) - j·sin(ω)
        // e^(-2jω) = cos(2ω) - j·sin(2ω)

        // Numerator: b0 + b1·e^-jω + b2·e^-2jω
        val numRe = coeffs.b0 + coeffs.b1 * cosW + coeffs.b2 * cos2W
        val numIm = -(coeffs.b1 * sinW + coeffs.b2 * sin2W)

        // Denominator: 1 + a1·e^-jω + a2·e^-2jω  (a0 normalized to 1)
        val denRe = 1.0 + coeffs.a1 * cosW + coeffs.a2 * cos2W
        val denIm = -(coeffs.a1 * sinW + coeffs.a2 * sin2W)

        // H = num / den  (complex division)
        val denMagSq = denRe * denRe + denIm * denIm
        if (denMagSq < 1e-30) return Complex(0.0, 0.0)

        return Complex(
            (numRe * denRe + numIm * denIm) / denMagSq,
            (numIm * denRe - numRe * denIm) / denMagSq
        )
    }

    /** |z| = sqrt(re² + im²) */
    private fun complexMagnitude(z: Complex): Double {
        return sqrt(z.re * z.re + z.im * z.im)
    }
}
