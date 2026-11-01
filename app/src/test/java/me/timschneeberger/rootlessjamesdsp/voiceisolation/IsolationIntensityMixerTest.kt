package me.timschneeberger.rootlessjamesdsp.voiceisolation

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Pins the mixer contract ported from Voice Isolation for Android: the background is synthesised
 * as `delayedDry - isolated`, so the voice estimate survives at any reduction and the whole stage
 * is transparent when both controls are neutral.
 */
class IsolationIntensityMixerTest {
    private val frameSize = GtcrnProcessor.HOP_SIZE

    private fun tone(amplitude: Float, cycles: Int = 8) =
        FloatArray(frameSize) { amplitude * sin(2.0 * PI * cycles * it / frameSize).toFloat() }

    private fun constant(value: Float) = FloatArray(frameSize) { value }

    private fun settings(reduction: Float, gain: Float = 1f, auto: Boolean = false) =
        VoiceIsolationSettings(reduction, gain, auto)

    @Test
    fun neutralControlsLeaveTheSignalUntouchedApartFromOneFrameOfDelay() {
        val mixer = IsolationIntensityMixer(frameSize, GtcrnProcessor.SAMPLE_RATE)
        val dry = tone(0.25f)
        val isolated = tone(0.25f, cycles = 3)
        val output = FloatArray(frameSize)

        mixer.mix(dry, isolated, output, settings(0f))
        mixer.mix(dry, isolated, output, settings(0f))

        assertArrayEquals(dry, output, 1e-6f)
    }

    @Test
    fun fullReductionOutputsOnlyTheIsolatedVoice() {
        val mixer = IsolationIntensityMixer(frameSize, GtcrnProcessor.SAMPLE_RATE)
        val dry = tone(0.4f)
        val isolated = tone(0.2f, cycles = 5)
        val output = FloatArray(frameSize)

        mixer.mix(dry, isolated, output, settings(1f))
        mixer.mix(dry, isolated, output, settings(1f))

        assertArrayEquals(isolated, output, 1e-6f)
    }

    @Test
    fun backgroundIsAttenuatedByTheReductionFactor() {
        val mixer = IsolationIntensityMixer(frameSize, GtcrnProcessor.SAMPLE_RATE)
        val dry = tone(0.4f)
        val isolated = tone(0.2f, cycles = 5)
        val output = FloatArray(frameSize)

        mixer.mix(dry, isolated, output, settings(0.5f))
        mixer.mix(dry, isolated, output, settings(0.5f))

        val expected = FloatArray(frameSize) { 0.5f * (dry[it] - isolated[it]) + isolated[it] }
        assertArrayEquals(expected, output, 1e-6f)
    }

    @Test
    fun voiceGainBoostsOnlyTheIsolatedPart() {
        val mixer = IsolationIntensityMixer(frameSize, GtcrnProcessor.SAMPLE_RATE)
        val dry = tone(0.3f)
        val output = FloatArray(frameSize)

        mixer.mix(dry, dry, output, settings(0f, gain = 2f))
        mixer.mix(dry, dry, output, settings(0f, gain = 2f))

        // reduction 0 keeps the whole background, gain 2 doubles the voice: dry + isolated
        val expected = FloatArray(frameSize) { 2f * dry[it] }
        assertArrayEquals(expected, output, 1e-6f)
    }

    @Test
    fun samplesAboveTheSoftLimitStartAreCompressedNotClipped() {
        val mixer = IsolationIntensityMixer(frameSize, GtcrnProcessor.SAMPLE_RATE)
        val frame = constant(0.5f)
        val output = FloatArray(frameSize)

        mixer.mix(frame, frame, output, settings(0f, gain = 2f))
        mixer.mix(frame, frame, output, settings(0f, gain = 2f))

        val range = 0.0999695f
        val start = 0.9f
        // raw = dry + isolated = 1.0, so every sample sits in the tanh knee
        val expected = start + range * tanh(((1.0 - start) / range).toDouble()).toFloat()
        for (sample in output) {
            assertEquals(expected, sample, 1e-5f)
            assertTrue("output escaped the limiter: $sample", abs(sample) <= 0.99997f)
        }
    }

    @Test
    fun autoModeHoldsTheBackgroundReductionBackWhileNoSpeechIsPresent() {
        val mixer = IsolationIntensityMixer(frameSize, GtcrnProcessor.SAMPLE_RATE)
        val silent = FloatArray(frameSize)
        val output = FloatArray(frameSize)

        mixer.mix(silent, silent, output, settings(0.65f, auto = true))
        assertEquals(0.2f, mixer.effectiveReduction, 1e-6f)

        val speech = tone(0.3f)
        val trajectory = mutableListOf<Float>()
        repeat(12) {
            mixer.mix(speech, speech, output, settings(0.65f, auto = true))
            trajectory += mixer.effectiveReduction
        }

        assertTrue("attack ramp is not monotonic: $trajectory", trajectory == trajectory.sorted())
        assertTrue("ramp never left the idle cap: $trajectory", trajectory.last() > 0.6f)
        assertTrue("ramp overshot the requested reduction: $trajectory", trajectory.all { it <= 0.65f + 1e-4f })
    }

    @Test
    fun manualModeAppliesTheReductionWithoutRamping() {
        val mixer = IsolationIntensityMixer(frameSize, GtcrnProcessor.SAMPLE_RATE)
        val silent = FloatArray(frameSize)
        val output = FloatArray(frameSize)

        mixer.mix(silent, silent, output, settings(0.65f))

        assertEquals(0.65f, mixer.effectiveReduction, 1e-6f)
    }

    @Test
    fun resetClearsTheRampAndTheDelayLine() {
        val mixer = IsolationIntensityMixer(frameSize, GtcrnProcessor.SAMPLE_RATE)
        val speech = tone(0.3f)
        val output = FloatArray(frameSize)
        repeat(6) { mixer.mix(speech, speech, output, settings(0.65f, auto = true)) }
        assertTrue(mixer.effectiveReduction > 0.2f)

        mixer.reset()

        assertEquals(0f, mixer.effectiveReduction, 0f)
        assertTrue(!mixer.speechDetected)
        // the first frame after a reset has an empty delay line, so a neutral mix is silent
        mixer.mix(speech, speech, output, settings(0f))
        assertArrayEquals(FloatArray(frameSize), output, 1e-6f)
    }

    @Test
    fun framesOfTheWrongSizeAreRejected() {
        val mixer = IsolationIntensityMixer(frameSize, GtcrnProcessor.SAMPLE_RATE)
        val short = FloatArray(frameSize - 1)

        assertThrows(IllegalArgumentException::class.java) {
            mixer.mix(short, short, short, settings(0f))
        }
    }
}
