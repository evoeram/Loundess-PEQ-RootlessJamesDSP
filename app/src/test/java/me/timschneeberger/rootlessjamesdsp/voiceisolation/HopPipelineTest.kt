package me.timschneeberger.rootlessjamesdsp.voiceisolation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The capture loop delivers blocks that are much smaller than one enhancer hop and whose size is
 * not a divisor of it. These tests pin the framing contract with an identity hop: no partial hop is
 * ever discarded, every block gets exactly its own frame count back, and a constant signal stays
 * constant instead of collapsing into silence.
 */
class HopPipelineTest {
    private val settings = VoiceIsolationSettings()

    private fun identity(sampleRate: Int, hops: MutableList<Int> = mutableListOf()) =
        HopPipeline(sampleRate) { dry, mixed, _ ->
            hops += 1
            dry.copyInto(mixed)
        }

    private fun feed(pipeline: HopPipeline, buffer: FloatArray, frames: Int, channel: Int) {
        for (index in 0 until frames) buffer[2 * index + channel] = 0.5f
        pipeline.process(buffer, channel, frames, 0, settings)
    }

    /** Runs a block stream and reports the first block that was served in full, plus its defects. */
    private class Stream(pipeline: HopPipeline, sizes: IntArray, blocks: Int) {
        var firstFullBlock = -1
            private set
        var silentBlocksAfterStart = 0
            private set
        var worstError = 0f
            private set

        init {
            val buffer = FloatArray(sizes.max() * 2)
            val settings = VoiceIsolationSettings()
            repeat(blocks) { block ->
                val frames = sizes[block % sizes.size]
                for (index in 0 until frames) buffer[2 * index] = 0.5f
                pipeline.process(buffer, 0, frames, 0, settings)

                val silent = (0 until frames).count { buffer[2 * it] == 0f }
                if (silent == 0 && firstFullBlock < 0) firstFullBlock = block
                if (silent > 0 && firstFullBlock >= 0) silentBlocksAfterStart++
                if (block >= blocks - STEADY_STATE_BLOCKS)
                    for (index in 0 until frames) worstError = maxOf(worstError, abs(buffer[2 * index] - 0.5f))
            }
        }

        companion object {
            const val STEADY_STATE_BLOCKS = 32
        }
    }

    private fun stream(sampleRate: Int, vararg sizes: Int, blocks: Int) =
        Stream(identity(sampleRate), sizes, blocks)

    @Test
    fun everyBlockKeepsBeingAnsweredOnceTheCushionIsFull() {
        listOf(48_000 to 1_000, 44_100 to 3_000, 96_000 to 3_000, 16_000 to 500).forEach {
            (sampleRate, blocks) ->
            val stream = stream(sampleRate, 128, blocks = blocks)
            assertTrue(
                "$sampleRate starved in $blocks blocks after serving started at ${stream.firstFullBlock}",
                stream.silentBlocksAfterStart == 0,
            )
        }
    }

    @Test
    fun unevenBlockSizeNeverStarvesTheStream() {
        listOf(
            48_000 to intArrayOf(128, 112, 160, 96, 256, 64, 144),
            44_100 to intArrayOf(128, 96, 176, 128, 64),
            96_000 to intArrayOf(128, 240, 96, 128),
        ).forEach { (sampleRate, sizes) ->
            val stream = stream(sampleRate, *sizes, blocks = 2_000)
            assertTrue(
                "$sampleRate with blocks ${sizes.joinToString("/")} starved " +
                    "${stream.silentBlocksAfterStart} times after ${stream.firstFullBlock}",
                stream.silentBlocksAfterStart == 0,
            )
        }
    }

    @Test
    fun aConstantSignalStaysConstantInSteadyState() {
        listOf(48_000 to 128, 44_100 to 128, 48_000 to 256, 96_000 to 128, 16_000 to 256).forEach {
            (sampleRate, frames) ->
            val stream = stream(sampleRate, frames, blocks = 400)
            assertEquals(0, stream.silentBlocksAfterStart)
            assertTrue(
                "$sampleRate/$frames framing error ${stream.worstError} on a constant signal",
                stream.worstError < 1e-3f,
            )
        }
    }

    @Test
    fun partialHopsAreCarriedAcrossBlocksInsteadOfBeingDropped() {
        val hops = mutableListOf<Int>()
        val pipeline = identity(48_000, hops)
        val frames = 128
        val buffer = FloatArray(frames * 2)

        // 128 frames at 48 kHz is 42.7 samples at 16 kHz, so a hop only completes every ~6 blocks
        repeat(100) { feed(pipeline, buffer, frames, 0) }

        assertEquals(16, pipeline.processedHops.toInt())
        assertEquals(16, hops.size)
    }

    @Test
    fun blocksOfUnevenSizesStillProduceTheSameStream() {
        val stream = stream(48_000, 128, 200, 512, 96, 1024, 256, blocks = 120)
        assertEquals(0, stream.silentBlocksAfterStart)
        assertTrue("uneven blocks distorted the signal: ${stream.worstError}", stream.worstError < 1e-3f)
    }

    @Test
    fun latencyAnnouncesTheDelayTheFramingActuallyIntroduces() {
        val pipeline = identity(48_000)
        val hopOutput = StftProcessor.HOP_SIZE * 48_000 / GtcrnProcessor.SAMPLE_RATE
        // 49 samples of decimator delay + one hop + 51 samples of interpolator delay + the cushion
        val expectedFrames = 49 + hopOutput + 51 + 2 * hopOutput

        assertTrue(
            "announced latency ${pipeline.latencyMs} ms is far from $expectedFrames frames",
            abs(pipeline.latencyMs * 48.0f - expectedFrames) < 8.0f,
        )
        assertEquals(2 * hopOutput, pipeline.prebufferFrames)
    }

    @Test
    fun resetDiscardsQueuedHopsAndPartials() {
        val pipeline = identity(48_000)
        val buffer = FloatArray(128 * 2)
        repeat(40) { feed(pipeline, buffer, 128, 0) }
        assertTrue(pipeline.processedHops > 0)

        pipeline.reset()
        feed(pipeline, buffer, 128, 0)

        assertEquals(0, pipeline.processedHops)
        assertTrue("output survived the reset", buffer.all { it == 0f })
    }

    @Test
    fun channelsDoNotShareTheirQueues() {
        val left = identity(48_000)
        val right = identity(48_000)
        val frames = 128
        val buffer = FloatArray(frames * 2)

        repeat(120) { round ->
            for (index in 0 until frames) {
                buffer[2 * index] = 0.5f
                buffer[2 * index + 1] = -0.25f
            }
            left.process(buffer, 0, frames, 0, settings)
            right.process(buffer, 1, frames, 0, settings)
            if (round < 100) return@repeat
            for (index in 0 until frames) {
                assertEquals(0.5f, buffer[2 * index], 1e-3f)
                assertEquals(-0.25f, buffer[2 * index + 1], 1e-3f)
            }
        }
    }
}
