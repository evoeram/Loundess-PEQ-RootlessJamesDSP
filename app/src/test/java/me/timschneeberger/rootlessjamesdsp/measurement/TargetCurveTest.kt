package me.timschneeberger.rootlessjamesdsp.measurement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Unit tests for [TargetCurve].
 */
class TargetCurveTest {

    @Test
    fun `flat curve returns zero gain at all frequencies`() {
        val curve = TargetCurve.flat()
        for (f in doubleArrayOf(20.0, 100.0, 1000.0, 10000.0, 20000.0)) {
            assertEquals("Flat curve should be 0 dB at $f Hz", 0.0, curve.gainAt(f), 0.001)
        }
    }

    @Test
    fun `harman curve has bass boost and treble cut`() {
        val curve = TargetCurve.harman()
        // Bass region: +1 dB
        assertEquals("Harman should be +1 dB at 20 Hz", 1.0, curve.gainAt(20.0), 0.001)
        assertEquals("Harman should be +1 dB at 100 Hz", 1.0, curve.gainAt(100.0), 0.001)
        // Mid: 0 dB
        assertEquals("Harman should be 0 dB at 1 kHz", 0.0, curve.gainAt(1000.0), 0.001)
        // Treble: negative
        assertEquals("Harman should be -1 dB at 10 kHz", -1.0, curve.gainAt(10000.0), 0.001)
        assertEquals("Harman should be -2 dB at 20 kHz", -2.0, curve.gainAt(20000.0), 0.001)
    }

    @Test
    fun `interpolation between points is correct`() {
        val curve = TargetCurve("Test", listOf(
            TargetCurve.Point(100.0, 0.0),
            TargetCurve.Point(1000.0, 10.0)
        ))

        // At 100 Hz: 0 dB
        assertEquals(0.0, curve.gainAt(100.0), 0.001)
        // At 1000 Hz: 10 dB
        assertEquals(10.0, curve.gainAt(1000.0), 0.001)
        // At geometric mean ~316 Hz: 5 dB (log interpolation)
        val midFreq = kotlin.math.sqrt(100.0 * 1000.0)
        assertEquals(5.0, curve.gainAt(midFreq), 0.1)
    }

    @Test
    fun `extrapolation uses endpoint values`() {
        val curve = TargetCurve("Test", listOf(
            TargetCurve.Point(100.0, 3.0),
            TargetCurve.Point(1000.0, -3.0)
        ))

        // Below range: use first point
        assertEquals(3.0, curve.gainAt(20.0), 0.001)
        // Above range: use last point
        assertEquals(-3.0, curve.gainAt(20000.0), 0.001)
    }

    @Test
    fun `gainAtAll returns array of correct size`() {
        val curve = TargetCurve.flat()
        val freqs = doubleArrayOf(20.0, 100.0, 1000.0, 10000.0)
        val gains = curve.gainAtAll(freqs)
        assertEquals(freqs.size, gains.size)
        for (g in gains) {
            assertEquals(0.0, g, 0.001)
        }
    }

    @Test
    fun `fromArrays creates correct curve`() {
        val freqs = doubleArrayOf(100.0, 1000.0)
        val gains = doubleArrayOf(2.0, -2.0)
        val curve = TargetCurve.fromArrays(freqs, gains, "Custom")
        assertEquals("Custom", curve.name)
        assertEquals(2, curve.points.size)
        assertEquals(100.0, curve.points[0].frequency, 0.001)
        assertEquals(2.0, curve.points[0].gainDb, 0.001)
    }

    @Test
    fun `single point curve returns constant gain`() {
        val curve = TargetCurve("Single", listOf(
            TargetCurve.Point(1000.0, 5.0)
        ))
        assertEquals(5.0, curve.gainAt(100.0), 0.001)
        assertEquals(5.0, curve.gainAt(1000.0), 0.001)
        assertEquals(5.0, curve.gainAt(20000.0), 0.001)
    }

    @Test
    fun `empty curve returns zero gain`() {
        val curve = TargetCurve("Empty", emptyList())
        assertEquals(0.0, curve.gainAt(1000.0), 0.001)
    }
}
