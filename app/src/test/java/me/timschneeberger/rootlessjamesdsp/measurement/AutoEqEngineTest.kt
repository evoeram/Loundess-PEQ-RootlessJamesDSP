package me.timschneeberger.rootlessjamesdsp.measurement

import me.timschneeberger.rootlessjamesdsp.model.ParametricEqBand
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqChannelMode
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqFilterType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Unit tests for [AutoEqEngine].
 *
 * Tests verify:
 *  - Flat response → no bands generated
 *  - Single peak → one band with correct gain and frequency
 *  - Single dip → one band (if within correction limits)
 *  - Deep narrow notch → skipped (non-minimum phase)
 *  - Max bands limit respected
 *  - Preamp computation
 *  - Q estimation and clamping
 */
class AutoEqEngineTest {

    private val sampleRate = 48000.0
    private val engine = AutoEqEngine(sampleRate, 512)

    /** Helper: generate log-spaced frequencies. */
    private fun logSpacedFreqs(n: Int, fMin: Double = 20.0, fMax: Double = 20000.0): DoubleArray {
        val freqs = DoubleArray(n)
        val logMin = kotlin.math.log10(fMin)
        val logMax = kotlin.math.log10(fMax)
        for (i in 0 until n) {
            val t = i.toDouble() / (n - 1)
            freqs[i] = Math.pow(10.0, logMin + t * (logMax - logMin))
        }
        return freqs
    }

    /** Helper: flat SPL at 0 dB. */
    private fun flatSpl(n: Int): DoubleArray = DoubleArray(n) { 0.0 }

    @Test
    fun `flat response produces no bands`() {
        val freqs = logSpacedFreqs(256)
        val spl = flatSpl(256)
        val target = TargetCurve.flat()

        val result = engine.run(freqs, spl, target)

        assertTrue("Should have no bands for flat response", result.bands.isEmpty())
        assertEquals("Preamp should be 0 for no bands", 0.0, result.preampDb, 0.01)
        assertTrue("Target should be met for flat response", result.targetMet)
    }

    @Test
    fun `single peak generates one correction band`() {
        val freqs = logSpacedFreqs(512)
        val peakFreq = 1000.0
        val peakGain = 6.0 // +6 dB peak

        // Create SPL with a single peak at 1000 Hz
        val spl = DoubleArray(freqs.size) { i ->
            val f = freqs[i]
            val logF = kotlin.math.log10(f)
            val logPeak = kotlin.math.log10(peakFreq)
            val dist = abs(logF - logPeak)
            // Gaussian-like peak
            peakGain * kotlin.math.exp(-(dist * dist) / 0.02)
        }

        val target = TargetCurve.flat()
        val result = engine.run(freqs, spl, target)

        assertTrue("Should generate at least one band", result.bands.isNotEmpty())
        assertTrue("Should have at most a few bands for a single peak",
            result.bands.size <= 5)

        // The first band should be near the peak frequency
        val firstBand = result.bands.first()
        assertTrue("First band frequency should be near 1000 Hz",
            firstBand.frequency > 500.0 && firstBand.frequency < 2000.0)

        // The gain should be negative (cutting the peak)
        assertTrue("Band gain should be negative (cut)",
            firstBand.gain < 0.0)
    }

    @Test
    fun `single broad dip generates correction band`() {
        val freqs = logSpacedFreqs(512)
        val dipFreq = 500.0
        val dipDepth = -4.0 // -4 dB dip

        // Create SPL with a broad dip at 500 Hz
        val spl = DoubleArray(freqs.size) { i ->
            val f = freqs[i]
            val logF = kotlin.math.log10(f)
            val logDip = kotlin.math.log10(dipFreq)
            val dist = abs(logF - logDip)
            // Broad Gaussian dip
            dipDepth * kotlin.math.exp(-(dist * dist) / 0.1)
        }

        val target = TargetCurve.flat()
        val config = AutoEqEngine.Config(
            maxBands = 10,
            dipMaxDepth = 10.0,
            dipMinWidthHz = 5.0
        )
        val result = engine.run(freqs, spl, target, config)

        assertTrue("Should generate at least one band for a broad dip",
            result.bands.isNotEmpty())

        // At least one band should be near the dip frequency
        val hasNearDip = result.bands.any {
            it.frequency > 250.0 && it.frequency < 1000.0
        }
        assertTrue("Should have a band near 500 Hz", hasNearDip)
    }

    @Test
    fun `deep narrow notch is skipped`() {
        val freqs = logSpacedFreqs(512)
        val notchFreq = 2000.0
        val notchDepth = -20.0 // -20 dB very deep notch

        // Very narrow notch (cancellation)
        val spl = DoubleArray(freqs.size) { i ->
            val f = freqs[i]
            val logF = kotlin.math.log10(f)
            val logNotch = kotlin.math.log10(notchFreq)
            val dist = abs(logF - logNotch)
            // Very narrow notch
            notchDepth * kotlin.math.exp(-(dist * dist) / 0.0001)
        }

        val target = TargetCurve.flat()
        val config = AutoEqEngine.Config(
            maxBands = 15,
            dipMaxDepth = 8.0,  // notch is deeper than this
            dipMinWidthHz = 10.0
        )
        val result = engine.run(freqs, spl, target, config)

        // The deep notch should be skipped (no band at 2000 Hz)
        val hasNearNotch = result.bands.any {
            abs(it.frequency - notchFreq) / notchFreq < 0.2
        }
        assertTrue("Deep narrow notch should be skipped (non-minimum phase)",
            !hasNearNotch)
    }

    @Test
    fun `max bands limit is respected`() {
        val freqs = logSpacedFreqs(512)

        // Create a very irregular SPL with many peaks
        val spl = DoubleArray(freqs.size) { i ->
            val f = freqs[i]
            val logF = kotlin.math.log10(f)
            // Multiple peaks at different frequencies
            var s = 0.0
            for (peakF in doubleArrayOf(100.0, 300.0, 800.0, 1500.0, 3000.0, 6000.0, 12000.0)) {
                val dist = abs(logF - kotlin.math.log10(peakF))
                s += 5.0 * kotlin.math.exp(-(dist * dist) / 0.005)
            }
            s
        }

        val target = TargetCurve.flat()
        val config = AutoEqEngine.Config(maxBands = 5, flatnessTarget = 0.1)
        val result = engine.run(freqs, spl, target, config)

        assertTrue("Should not exceed maxBands limit",
            result.bands.size <= 5)
    }

    @Test
    fun `preamp is computed as negative of max positive gain`() {
        val freqs = logSpacedFreqs(256)
        val dipFreq = 200.0
        val dipDepth = -6.0

        val spl = DoubleArray(freqs.size) { i ->
            val f = freqs[i]
            val logF = kotlin.math.log10(f)
            val logDip = kotlin.math.log10(dipFreq)
            val dist = abs(logF - logDip)
            dipDepth * kotlin.math.exp(-(dist * dist) / 0.1)
        }

        val target = TargetCurve.flat()
        val config = AutoEqEngine.Config(
            maxBands = 10,
            dipMaxDepth = 10.0,
            individualMaxBoost = 6.0,
            overallMaxBoost = 15.0
        )
        val result = engine.run(freqs, spl, target, config)

        if (result.bands.isNotEmpty()) {
            val maxPositiveGain = result.bands.maxOf { it.gain }
            if (maxPositiveGain > 0) {
                assertEquals("Preamp should be -maxPositiveGain",
                    -maxPositiveGain, result.preampDb, 0.01)
            }
        }
    }

    @Test
    fun `Q is clamped within valid range`() {
        val freqs = logSpacedFreqs(512)
        val peakFreq = 10000.0
        val peakGain = 8.0

        // Very narrow peak at high frequency
        val spl = DoubleArray(freqs.size) { i ->
            val f = freqs[i]
            val logF = kotlin.math.log10(f)
            val logPeak = kotlin.math.log10(peakFreq)
            val dist = abs(logF - logPeak)
            peakGain * kotlin.math.exp(-(dist * dist) / 0.0001)
        }

        val target = TargetCurve.flat()
        val config = AutoEqEngine.Config(
            maxBands = 5,
            minQ = 0.5,
            maxQHigh = 5.0,
            maxQLow = 15.0
        )
        val result = engine.run(freqs, spl, target, config)

        for (band in result.bands) {
            assertTrue("Q should be >= minQ (${band.q})",
                band.q >= 0.5)
            val maxQ = if (band.frequency < config.qCrossoverFreq) config.maxQLow else config.maxQHigh
            assertTrue("Q should be <= maxQ for this frequency (${band.q}, max=$maxQ)",
                band.q <= maxQ)
        }
    }

    @Test
    fun `harman target curve is applied correctly`() {
        val freqs = logSpacedFreqs(256)
        // Flat SPL at 0 dB, target is Harman
        val spl = flatSpl(256)
        val target = TargetCurve.harman()

        val result = engine.run(freqs, spl, target)

        // With flat SPL and Harman target, the error at 20 Hz is:
        // E = SPL(20) - Target(20) = 0 - 1.0 = -1.0 (dip of 1 dB)
        // The engine should generate bands to boost low frequencies
        if (result.bands.isNotEmpty()) {
            // Check that at least one band has positive gain (boost) at low frequencies
            val lowFreqBoost = result.bands.any {
                it.frequency < 500.0 && it.gain > 0.0
            }
            assertTrue("Should boost low frequencies to match Harman target",
                lowFreqBoost)
        }
    }

    @Test
    fun `overall max boost limit is respected`() {
        val freqs = logSpacedFreqs(256)

        // Multiple deep dips requiring lots of boost
        val spl = DoubleArray(freqs.size) { i ->
            val f = freqs[i]
            val logF = kotlin.math.log10(f)
            var s = 0.0
            for (dipF in doubleArrayOf(100.0, 200.0, 500.0)) {
                val dist = abs(logF - kotlin.math.log10(dipF))
                s += -6.0 * kotlin.math.exp(-(dist * dist) / 0.05)
            }
            s
        }

        val target = TargetCurve.flat()
        val config = AutoEqEngine.Config(
            maxBands = 20,
            individualMaxBoost = 6.0,
            overallMaxBoost = 10.0,  // Low overall limit
            dipMaxDepth = 20.0
        )
        val result = engine.run(freqs, spl, target, config)

        val totalPositiveGain = result.bands.filter { it.gain > 0 }.sumOf { it.gain }
        assertTrue("Total positive gain should not exceed overallMaxBoost",
            totalPositiveGain <= 10.5) // small tolerance
    }

    @Test
    fun `result contains valid band parameters`() {
        val freqs = logSpacedFreqs(256)
        val peakFreq = 2000.0
        val peakGain = 5.0

        val spl = DoubleArray(freqs.size) { i ->
            val f = freqs[i]
            val logF = kotlin.math.log10(f)
            val logPeak = kotlin.math.log10(peakFreq)
            val dist = abs(logF - logPeak)
            peakGain * kotlin.math.exp(-(dist * dist) / 0.01)
        }

        val target = TargetCurve.flat()
        val result = engine.run(freqs, spl, target)

        for (band in result.bands) {
            assertTrue("Frequency should be in valid range",
                band.frequency >= 20.0 && band.frequency <= 20000.0)
            assertTrue("Gain should be in valid range",
                abs(band.gain) <= 30.0)
            assertTrue("Q should be positive",
                band.q > 0.0)
            assertEquals("Filter type should be peaking",
                ParametricEqFilterType.PEAKING, band.filterType)
            assertEquals("Channel mode should be BOTH",
                ParametricEqChannelMode.BOTH, band.channelMode)
        }
    }
}
