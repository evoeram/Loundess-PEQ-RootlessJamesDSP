package me.timschneeberger.rootlessjamesdsp.voiceisolation

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Pins the STFT contract ported from Voice Isolation for Android: the sqrt-Hann window at 50%
 * overlap must reconstruct the signal exactly, delayed by exactly one hop. That delay is what
 * [IsolationIntensityMixer] compensates with its `delayedDryFrame`, so the voice estimate stays
 * sample-aligned with the dry signal and the background subtraction does not comb-filter the voice.
 */
class StftProcessorTest {
    private val hop = StftProcessor.HOP_SIZE

    private fun sine(frequency: Double, count: Int): FloatArray =
        FloatArray(count) { sin(2.0 * PI * frequency * it / GtcrnProcessor.SAMPLE_RATE).toFloat() }

    @Test
    fun analysisAndSynthesisReconstructThePreviousHopExactly() {
        val stft = StftProcessor()
        val input = sine(440.0, hop * 6)
        val spectrum = FloatArray(StftProcessor.SPECTRUM_SIZE)
        val output = FloatArray(hop)

        var previous: FloatArray? = null
        var worst = 0f
        for (frame in 0 until 6) {
            val dry = FloatArray(hop)
            input.copyInto(dry, 0, frame * hop, (frame + 1) * hop)
            stft.analyze(dry, spectrum)
            stft.synthesize(spectrum, output)

            // the first hop reconstructs from the zero-filled history, so nothing to compare yet
            val delayed = previous
            if (delayed != null) {
                for (sample in 0 until hop) {
                    worst = maxOf(worst, abs(delayed[sample] - output[sample]))
                }
            }
            previous = dry
        }
        assertTrue("reconstruction error $worst exceeds 1e-4", worst < 1e-4f)
    }

    @Test
    fun synthesisLagsAnalysisByExactlyOneHop() {
        val stft = StftProcessor()
        val spectrum = FloatArray(StftProcessor.SPECTRUM_SIZE)
        val output = FloatArray(hop)
        val empty = FloatArray(hop)
        val click = FloatArray(hop).also { it[hop - 1] = 1f }

        stft.analyze(empty, spectrum)
        stft.synthesize(spectrum, output)
        val firstHop = output.copyOf()

        stft.analyze(click, spectrum)
        stft.synthesize(spectrum, output)
        val secondHop = output.copyOf()

        stft.analyze(empty, spectrum)
        stft.synthesize(spectrum, output)

        assertTrue("empty history produced output", firstHop.all { abs(it) < 1e-6f })
        assertTrue("impulse appeared before its hop", secondHop.all { abs(it) < 1e-3f })
        assertTrue("impulse did not land at the end of the next hop", abs(output[hop - 1]) > 0.99f)
    }

    @Test
    fun resetClearsTheOverlapHistory() {
        val stft = StftProcessor()
        val spectrum = FloatArray(StftProcessor.SPECTRUM_SIZE)
        val output = FloatArray(hop)
        val loud = sine(1000.0, hop)
        val nearSilent = FloatArray(hop) { it * 1e-6f }

        repeat(2) {
            stft.analyze(loud, spectrum)
            stft.synthesize(spectrum, output)
        }
        assertTrue(output.maxOf { abs(it) } > 0.1f)

        stft.reset()
        stft.analyze(nearSilent, spectrum)
        stft.synthesize(spectrum, output)

        val leftover = output.maxOf { abs(it) }
        assertTrue("leftover energy after reset: $leftover", leftover < 1e-3f)
    }
}
