package me.timschneeberger.rootlessjamesdsp.smoothvolume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SmoothVolumeMathTest {

    @Test
    fun clampKeepsValuesInsideTheDbRange() {
        assertEquals(SmoothVolumeMath.MIN_DB, SmoothVolumeMath.clamp(-100.0), 1e-9)
        assertEquals(SmoothVolumeMath.MAX_DB, SmoothVolumeMath.clamp(5.0), 1e-9)
        assertEquals(-3.5, SmoothVolumeMath.clamp(-3.5), 1e-9)
    }

    @Test
    fun `roundToStep snaps to the nearest 0_5 dB`() {
        assertEquals(-3.5, SmoothVolumeMath.roundToStep(-3.5), 1e-9)
        assertEquals(-3.5, SmoothVolumeMath.roundToStep(-3.4), 1e-9)
        assertEquals(-3.5, SmoothVolumeMath.roundToStep(-3.6), 1e-9)
        assertEquals(-4.0, SmoothVolumeMath.roundToStep(-3.76), 1e-9)
        // kotlin.math.round: exact ties round toward the even integer
        // (-6.5 -> -6, -7.5 -> -8); ties never occur for real slider input.
        assertEquals(-3.0, SmoothVolumeMath.roundToStep(-3.25), 1e-9)
        assertEquals(-4.0, SmoothVolumeMath.roundToStep(-3.75), 1e-9)
        // Half-up: -0.25 sits exactly between 0 and -0.5, rounds toward 0
        assertEquals(0.0, SmoothVolumeMath.roundToStep(-0.25), 1e-9)
        assertEquals(-0.5, SmoothVolumeMath.roundToStep(-0.26), 1e-9)
    }

    @Test
    fun `snap clamps and rounds`() {
        assertEquals(-60.0, SmoothVolumeMath.snap(-1000.0), 1e-9)
        assertEquals(0.0, SmoothVolumeMath.snap(1000.0), 1e-9)
        assertEquals(-0.5, SmoothVolumeMath.snap(-0.3), 1e-9)
        // Result is always an exact multiple of the step
        assertEquals(0.0, SmoothVolumeMath.snap(-13.13) % SmoothVolumeMath.STEP_DB, 1e-9)
    }

    @Test
    fun `adjust moves by the delta and stays in range`() {
        assertEquals(-0.5, SmoothVolumeMath.adjust(0.0, -0.5), 1e-9)
        assertEquals(-60.0, SmoothVolumeMath.adjust(-59.75, -10.0), 1e-9)
        assertEquals(0.0, SmoothVolumeMath.adjust(-0.25, 10.0), 1e-9)
    }

    @Test
    fun dBToLinearConversionMatchesFormula() {
        assertEquals(1.0, SmoothVolumeMath.dbToLinear(0.0), 1e-12)
        abs(SmoothVolumeMath.dbToLinear(-6.0) - 0.501187).let { assertTrue(it < 1e-6) }
        abs(SmoothVolumeMath.dbToLinear(-60.0) - 0.001).let { assertTrue(it < 1e-12) }
    }

    @Test
    fun `position mapping is dB-linear and reversible`() {
        assertEquals(1.0, SmoothVolumeMath.dbToPosition(0.0), 1e-9)
        assertEquals(0.0, SmoothVolumeMath.dbToPosition(-60.0), 1e-9)
        assertEquals(0.5, SmoothVolumeMath.dbToPosition(-30.0), 1e-9)
        // round trip
        for (i in 0..120) {
            val db = -60.0 + i * 0.5
            assertEquals(db, SmoothVolumeMath.positionToDb(SmoothVolumeMath.dbToPosition(db)), 1e-9)
        }
    }

    @Test
    fun `positionToDb snaps to steps`() {
        assertEquals(-30.0, SmoothVolumeMath.positionToDb(0.5), 1e-9)
        assertEquals(0.0, SmoothVolumeMath.positionToDb(1.0), 1e-9)
        assertEquals(-60.0, SmoothVolumeMath.positionToDb(0.0), 1e-9)
    }

    @Test
    fun `sameStep treats values within half a step as equal`() {
        assertTrue(SmoothVolumeMath.sameStep(-3.5, -3.5001))
        assertTrue(SmoothVolumeMath.sameStep(0.0, 0.0))
        assertFalse(SmoothVolumeMath.sameStep(-3.5, -4.0))
        assertFalse(SmoothVolumeMath.sameStep(-3.5, -3.0))
    }

    @Test
    fun `formatDb renders one-step values without trailing zero`() {
        assertEquals("0 dB", SmoothVolumeMath.formatDb(0.0))
        assertEquals("-3.5 dB", SmoothVolumeMath.formatDb(-3.5))
        assertEquals("-60 dB", SmoothVolumeMath.formatDb(-60.0))
    }

    @Test
    fun `mute silence constant is below the user range`() {
        // Mute is distinct from the -60 dB user step and effectively silent
        assertTrue(SmoothVolumeController.SILENCE_DB < SmoothVolumeMath.MIN_DB)
    }
}
