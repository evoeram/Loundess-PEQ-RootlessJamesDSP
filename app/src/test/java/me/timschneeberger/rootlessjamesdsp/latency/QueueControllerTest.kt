package me.timschneeberger.rootlessjamesdsp.latency

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for BUG-01: [QueueController.onWritten] expects interleaved
 * samples (frames = samples / 2 for stereo). Feeding byte counts from the
 * packed PCM24/PCM32 write paths inflates the queue estimate 3x/4x and puts
 * the controller into a permanent drop loop.
 */
class QueueControllerTest {

    @Test
    fun `onWritten counts stereo frames from samples`() {
        val controller = QueueController(maxQueueFrames = 48000)
        controller.onTrackStarted()
        controller.onWritten(2048) // samples from write(short[]|float[], …)
        assertEquals(1024, controller.totalFramesWritten)
    }

    @Test
    fun `packed pcm24 write result keeps counters in samples`() {
        val controller = QueueController(maxQueueFrames = 48000)
        controller.onTrackStarted()
        // The service converts the ByteBuffer byte count before calling onWritten
        val bytesWritten = 1536 // 512 samples * 3 bytes (PCM24 packed)
        controller.onWritten(PcmWriteUnits.bytesToSamples(bytesWritten, 3))
        assertEquals(512 / 2, controller.totalFramesWritten)
    }

    @Test
    fun `packed pcm24 byte count would inflate frames threefold`() {
        // Documents the BUG-01 failure mode: passing raw bytes triples the count
        val controller = QueueController(maxQueueFrames = 48000)
        controller.onTrackStarted()
        controller.onWritten(1536) // raw bytes, wrongly used as samples
        assertEquals(768, controller.totalFramesWritten) // 3x the correct 256
    }

    @Test
    fun `error codes from write are not accumulated`() {
        val controller = QueueController(maxQueueFrames = 48000)
        controller.onTrackStarted()
        controller.onWritten(2048)
        controller.onWritten(-6) // ERROR_DEAD_OBJECT
        controller.onWritten(0)
        assertEquals(1024, controller.totalFramesWritten)
    }

    @Test
    fun `onTrackStarted resets the counter`() {
        val controller = QueueController(maxQueueFrames = 48000)
        controller.onTrackStarted()
        controller.onWritten(4096)
        assertEquals(2048, controller.totalFramesWritten)
        controller.onTrackStarted()
        assertEquals(0, controller.totalFramesWritten)
    }

    @Test
    fun `disabled controller still tracks written frames`() {
        val controller = QueueController(maxQueueFrames = 0)
        assertFalse(controller.enabled)
        controller.onTrackStarted()
        controller.onWritten(1024)
        assertEquals(512, controller.totalFramesWritten)
    }

    @Test
    fun `drop state machine needs a trigger first`() {
        val controller = QueueController(maxQueueFrames = 48000)
        controller.onTrackStarted()
        assertFalse(controller.shouldDrop())
        // Without an overTarget trigger, repeated calls stay false
        assertFalse(controller.shouldDrop())
    }

    @Test
    fun `enabled reflects maxQueueFrames`() {
        assertTrue(QueueController(maxQueueFrames = 1).enabled)
        assertFalse(QueueController(maxQueueFrames = 0).enabled)
    }
}
