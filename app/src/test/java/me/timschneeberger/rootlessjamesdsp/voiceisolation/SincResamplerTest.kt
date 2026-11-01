package me.timschneeberger.rootlessjamesdsp.voiceisolation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The enhancer only understands 16 kHz, so the capture loop's 48/44.1 kHz blocks go through this
 * resampler in both directions. These tests pin what [VoiceIsolationProcessor] relies on: a
 * constant, announced group delay, unity gain on DC, no channel crosstalk and a fixed output count
 * per block once primed.
 */
class SincResamplerTest {
    @Test
    fun announcedDelayMatchesTheKernelWidth() {
        assertEquals(49, SincResampler(48_000, 16_000).inputDelay)
        // fewer taps are useless when upsampling, so the width is driven by tapsPerSide only
        assertEquals(17, SincResampler(16_000, 48_000).inputDelay)
    }

    @Test
    fun decimationKeepsDirectCurrentAtUnityGain() {
        val resampler = SincResampler(48_000, 16_000)
        val input = FloatArray(480) { 0.75f }
        val output = FloatArray(160)

        resampler.push(input, 0, 1, input.size)
        val emitted = resampler.poll(output, 0, 1, output.size)

        assertEquals(128, emitted)
        for (index in 10 until emitted) {
            assertEquals(0.75f, output[index], 1e-4f)
        }
    }

    @Test
    fun upsamplingKeepsDirectCurrentAtUnityGain() {
        val resampler = SincResampler(16_000, 48_000)
        val input = FloatArray(160) { 0.75f }
        val output = FloatArray(480)

        resampler.push(input, 0, 1, input.size)
        val emitted = resampler.poll(output, 0, 1, output.size)

        // three output samples per input sample, minus the priming window on both sides
        assertEquals(384, emitted)
        for (index in 10 until emitted) {
            assertEquals(0.75f, output[index], 1e-4f)
        }
    }

    @Test
    fun impulseIsPlacedAtTheAnnouncedDelayAndLowPassedByTheDecimator() {
        val resampler = SincResampler(48_000, 16_000)
        val input = FloatArray(480)
        input[240] = 1f
        val output = FloatArray(100)

        resampler.push(input, 0, 1, input.size)
        val emitted = resampler.poll(output, 0, 1, output.size)

        var peak = 0
        for (index in 1 until emitted) if (abs(output[index]) > abs(output[peak])) peak = index
        // delay = halfWidth (48) input samples -> 240 -> (240 - 48) / 3
        assertEquals(64, peak)
        // a delta is broadband: only the 0.45 * 16 kHz passband survives, i.e. 2 * cutoff = 0.3
        assertTrue("impulse level ${output[peak]} escaped the decimation filter", output[peak] in 0.25f..0.35f)
    }

    @Test
    fun interleavedChannelsResampleWithoutCrosstalk() {
        val frames = 480
        val interleaved = FloatArray(frames * 2)
        for (frame in 0 until frames) {
            interleaved[2 * frame] = 0.5f
            interleaved[2 * frame + 1] = -0.25f
        }
        val left = SincResampler(48_000, 16_000)
        val right = SincResampler(48_000, 16_000)
        left.push(interleaved, 0, 2, frames)
        right.push(interleaved, 1, 2, frames)

        val mono = FloatArray(frames * 2)
        val leftEmitted = left.poll(mono, 0, 2, 100)
        val rightEmitted = right.poll(mono, 1, 2, 100)

        assertEquals(leftEmitted, rightEmitted)
        for (index in 10 until leftEmitted) {
            assertEquals(0.5f, mono[2 * index], 1e-4f)
            assertEquals(-0.25f, mono[2 * index + 1], 1e-4f)
        }
    }

    @Test
    fun emissionCountFollowsTheRateRatioAndStopsWhenDry() {
        val resampler = SincResampler(48_000, 16_000)
        val input = FloatArray(300) { 1f }
        val output = FloatArray(1000)

        assertEquals(0, resampler.poll(output, 0, 1, 10))
        resampler.push(input, 0, 1, input.size)

        var total = 0
        total += resampler.poll(output, 0, 1, 50)
        total += resampler.poll(output, total, 1, 50)
        // (300 - 2 * halfWidth) / 3 -> the full kernel window has to sit inside the pushed history
        assertEquals(68, total)
        assertEquals(0, resampler.poll(output, total, 1, 10))

        resampler.push(FloatArray(3) { 1f }, 0, 1, 3)
        assertEquals(1, resampler.poll(output, total, 1, 10))
    }

    @Test
    fun resetDiscardsTheHistory() {
        val resampler = SincResampler(48_000, 16_000)
        resampler.push(FloatArray(480) { 1f }, 0, 1, 480)
        assertTrue(resampler.poll(FloatArray(160), 0, 1, 160) > 0)

        resampler.reset()

        assertEquals(0, resampler.poll(FloatArray(160), 0, 1, 160))
    }
}
