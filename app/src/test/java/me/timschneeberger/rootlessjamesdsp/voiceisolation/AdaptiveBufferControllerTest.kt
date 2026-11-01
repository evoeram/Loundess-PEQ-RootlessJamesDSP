package me.timschneeberger.rootlessjamesdsp.voiceisolation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * JamesDSP owns the [android.media.AudioTrack], so the ported controller only turns the underrun
 * counter into resize requests. These tests pin the growth curve, the capacity ceiling and the
 * stability levels reported by the playback buffer adapter.
 */
class AdaptiveBufferControllerTest {
    private fun controller(
        initial: Int = 960,
        capacity: Int = 4800,
        frameSize: Int = 240,
    ) = AdaptiveBufferController(initial, capacity, frameSize)

    @Test
    fun aSteadyTrackNeverRequestsAResize() {
        val controller = controller()

        repeat(10) { assertNull(controller.resizeRequest(0)) }
        assertEquals(1440, controller.resizeRequest(1))
        // an underrun counter that does not move must not grow the buffer further
        repeat(5) { assertNull(controller.resizeRequest(1)) }
    }

    @Test
    fun oneNewUnderrunGrowsTheBufferByAtLeastOneFrame() {
        val controller = controller()

        assertEquals(1440, controller.resizeRequest(1))
    }

    @Test
    fun growthIsHalfTheBufferWheneverThatBeatsOneFrame() {
        val controller = controller(initial = 960, frameSize = 100)

        assertEquals(1440, controller.resizeRequest(1))
        assertEquals(960, controller.bufferFrames)
        controller.onResizeResult(1440)
        assertEquals(2160, controller.resizeRequest(2))
        controller.onResizeResult(2160)
        assertEquals(3240, controller.resizeRequest(3))
    }

    @Test
    fun growthStopsAtTheCapacityCeiling() {
        val controller = controller()
        var underruns = 0
        var requests = 0

        while (true) {
            underruns++
            val next = controller.resizeRequest(underruns) ?: break
            requests++
            controller.onResizeResult(next)
        }

        assertEquals(4800, controller.bufferFrames)
        assertEquals(4, requests)
        assertNull(controller.resizeRequest(underruns + 1))
    }

    @Test
    fun aCounterThatGoesBackwardsIsIgnored() {
        val controller = controller()
        controller.resizeRequest(5)
        controller.onResizeResult(1440)

        assertNull(controller.resizeRequest(2))
        // the counter is rebased, so the next increase is measured from the lower value
        assertEquals(2160, controller.resizeRequest(3))
    }

    @Test
    fun resizeResultsOutsideTheBudgetAreNotTrusted() {
        val controller = controller()
        controller.resizeRequest(1)
        controller.onResizeResult(720)
        assertEquals(960, controller.bufferFrames)
        controller.onResizeResult(60_000)
        assertEquals(960, controller.bufferFrames)
        controller.onResizeResult(1200)
        assertEquals(1200, controller.bufferFrames)
    }

    @Test
    fun stabilityLevelReportsHowMuchLatencyWasBought() {
        val controller = controller()
        assertEquals(0, controller.stabilityLevel)

        controller.resizeRequest(1)
        controller.onResizeResult(1440)
        assertEquals(1, controller.stabilityLevel)

        controller.synchronize(1920, 4800)
        assertEquals(2, controller.stabilityLevel)
    }

    @Test
    fun routingChangesCapFutureRequestsByTheRealDeviceCapacity() {
        val controller = controller(capacity = 4800)
        controller.synchronize(1000, 1800)

        assertEquals(1000, controller.bufferFrames)
        assertEquals(1500, controller.resizeRequest(1))
        controller.onResizeResult(1500)
        // the next step would exceed what the device can hold, so it is clamped instead
        assertEquals(1800, controller.resizeRequest(2))
        controller.onResizeResult(1800)
        assertNull(controller.resizeRequest(3))
    }

    @Test
    fun synchronizeIgnoresMeaninglessMeasurements() {
        val controller = controller()
        controller.resizeRequest(1)
        controller.onResizeResult(1440)

        controller.synchronize(0, 0)

        assertEquals(1440, controller.bufferFrames)
    }

    @Test
    fun nonsensicalGeometryIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            AdaptiveBufferController(0, 4800, 240)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AdaptiveBufferController(960, 0, 240)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AdaptiveBufferController(960, 4800, 0)
        }
    }
}
