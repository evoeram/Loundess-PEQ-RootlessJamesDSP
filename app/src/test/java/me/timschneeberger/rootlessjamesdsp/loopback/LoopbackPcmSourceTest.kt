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

        val second = ShortArray(4)
        assertEquals(2, source.read(second, 0, second.size))
        assertArrayEquals(shortArrayOf(5, 6, 0, 0), second)
    }
}
