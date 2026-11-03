package me.timschneeberger.rootlessjamesdsp.loopback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression tests for BUG-02: a capture-thread reader blocked in
 * [LoopbackPcmSource.read] must be woken deterministically when the source is
 * closed (previously `clear()` did not notify, so the thread hung forever in
 * `lock.wait()` and `stopRecording()` lost the reference after join(500)).
 */
class LoopbackPcmSourceTest {

    @Test
    fun `blocked reader is woken by close and gets negative result`() {
        val source = LoopbackPcmSource()
        val result = AtomicInteger(Int.MIN_VALUE)
        val done = CountDownLatch(1)

        val reader = Thread {
            val buf = ShortArray(960)
            result.set(source.read(buf, 0, buf.size))
            done.countDown()
        }
        reader.start()

        // Дать читателю гарантированно заснуть в lock.wait()
        Thread.sleep(100)
        assertTrue("reader should be blocked", reader.state == Thread.State.WAITING || reader.state == Thread.State.TIMED_WAITING || reader.state == Thread.State.RUNNABLE)

        source.close()

        assertTrue("read() did not return after close()", done.await(2, TimeUnit.SECONDS))
        assertEquals(-1, result.get())
    }

    @Test
    fun `clear reopens source after close`() {
        val source = LoopbackPcmSource()
        source.close()
        source.clear()

        val block = shortArrayOf(1, 2, 3, 4)
        source.offer(block)

        val buf = ShortArray(4)
        val n = source.read(buf, 0, buf.size)
        assertEquals(4, n)
        assertArrayEquals(block, buf)
    }

    @Test
    fun `offer after close is ignored`() {
        val source = LoopbackPcmSource()
        source.close()
        source.offer(shortArrayOf(9, 9))

        val buf = ShortArray(2)
        assertEquals(-1, source.read(buf, 0, buf.size))
    }

    @Test
    fun `partial remainder is preserved across reads`() {
        val source = LoopbackPcmSource()
        source.offer(shortArrayOf(1, 2, 3, 4, 5, 6))

        val first = ShortArray(4)
        assertEquals(4, source.read(first, 0, first.size))
        assertArrayEquals(shortArrayOf(1, 2, 3, 4), first)

        source.offer(shortArrayOf(7, 8))
        val second = ShortArray(4)
        assertEquals(4, source.read(second, 0, second.size))
        assertArrayEquals(shortArrayOf(5, 6, 7, 8), second)
    }

    @Test
    fun `read waits for a complete block instead of returning scheduling dependent sizes`() {
        val source = LoopbackPcmSource()
        val result = AtomicInteger(Int.MIN_VALUE)
        val done = CountDownLatch(1)
        val output = ShortArray(4)
        val reader = Thread {
            result.set(source.read(output, 0, output.size))
            done.countDown()
        }
        reader.start()

        source.offer(shortArrayOf(1, 2))
        Thread.sleep(100)
        assertTrue("read returned a partial block", reader.isAlive)

        source.offer(shortArrayOf(3, 4))
        assertTrue("read did not resume with a complete block", done.await(2, TimeUnit.SECONDS))
        assertEquals(4, result.get())
        assertArrayEquals(shortArrayOf(1, 2, 3, 4), output)
    }

    @Test
    fun `binder bytes are decoded as little endian pcm16`() {
        val source = LoopbackPcmSource()
        source.offer(byteArrayOf(1, 0, -1, -1, 0, -128, -1, 127))

        val output = ShortArray(4)
        assertEquals(4, source.read(output, 0, output.size))
        assertArrayEquals(shortArrayOf(1, -1, Short.MIN_VALUE, Short.MAX_VALUE), output)
    }

    @Test
    fun `large capture buffers are delivered as fixed twenty millisecond processing blocks`() {
        val source = LoopbackPcmSource()
        val input = ShortArray(LoopbackCaptureUserService.PCM_BLOCK_FRAMES * 2 * 6) { it.toShort() }
        source.offer(input)

        val output = ShortArray(input.size)
        val processingBlock = ShortArray(LoopbackCaptureUserService.PCM_BLOCK_FRAMES * 2)
        var offset = 0
        while (offset < output.size) {
            val read = source.read(processingBlock, 0, processingBlock.size)
            assertEquals(processingBlock.size, read)
            System.arraycopy(processingBlock, 0, output, offset, read)
            offset += read
        }

        assertArrayEquals(input, output)
        assertEquals(0, offset % processingBlock.size)
    }

    @Test
    fun `queue overflow is counted and newest pcm is retained`() {
        val source = LoopbackPcmSource()
        repeat(26) { value -> source.offer(shortArrayOf(value.toShort())) }

        val output = ShortArray(25)
        assertEquals(25, source.read(output, 0, output.size))
        assertEquals(1, source.droppedBlockCount)
        assertEquals(1, output.first().toInt())
        assertEquals(25, output.last().toInt())
    }
}
