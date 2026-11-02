package me.timschneeberger.rootlessjamesdsp.voiceisolation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for BUG-08: the additional mixer latency was computed in
 * hops but converted to milliseconds without the hopSize multiplier, so one
 * extra 256-sample hop at 16 kHz was reported as 0.0625 ms instead of 16 ms.
 */
class VoiceIsolationLatencyTest {

    @Test
    fun `one additional 256-sample hop at 16 kHz is 16 ms, not a fraction of a millisecond`() {
        // initialDelayHops = 1 (STFT hop, already in HopPipeline.latencyMs) → 0 extra
        assertEquals(0f, mixerExtraLatencyMs(1, hopSize = 256, modelSampleRate = 16000), 1e-4f)
        // one hop above the baseline: 256 samples at 16 kHz = 16 ms
        assertEquals(16f, mixerExtraLatencyMs(2, hopSize = 256, modelSampleRate = 16000), 1e-4f)
        assertEquals(32f, mixerExtraLatencyMs(3, hopSize = 256, modelSampleRate = 16000), 1e-4f)
    }

    @Test
    fun `model rates 16, 32 and 48 kHz scale linearly with hop size`() {
        // hop = 240 samples
        assertEquals(15f, mixerExtraLatencyMs(2, hopSize = 240, modelSampleRate = 16000), 1e-4f)
        assertEquals(7.5f, mixerExtraLatencyMs(2, hopSize = 240, modelSampleRate = 32000), 1e-4f)
        assertEquals(5f, mixerExtraLatencyMs(2, hopSize = 240, modelSampleRate = 48000), 1e-4f)
        // hop = 120 samples at 16 kHz = 7.5 ms
        assertEquals(7.5f, mixerExtraLatencyMs(2, hopSize = 120, modelSampleRate = 16000), 1e-4f)
    }

    @Test
    fun `delay below the baseline never reports negative latency`() {
        assertEquals(0f, mixerExtraLatencyMs(0, hopSize = 256, modelSampleRate = 16000), 1e-4f)
        assertEquals(0f, mixerExtraLatencyMs(1, hopSize = 256, modelSampleRate = 16000), 1e-4f)
    }

    @Test
    fun `displayed latency matches measured impulse delay`() {
        // Measured impulse is delayed by (hopSize * detectedDelayHops) samples at the model
        // rate; displayed total = HopPipeline hop (1 hop) + extra (detected - 1 hops) = detected hops.
        val detectedDelayHops = 3
        val hopSize = 256
        val modelSampleRate = 16000
        val measuredImpulseDelayMs = detectedDelayHops * hopSize * 1000f / modelSampleRate
        val displayedMs =
            hopSize * 1000f / modelSampleRate + // HopPipeline.latencyMs hop term
            mixerExtraLatencyMs(detectedDelayHops, hopSize, modelSampleRate)
        assertEquals(measuredImpulseDelayMs, displayedMs, 1e-3f)
    }
}
