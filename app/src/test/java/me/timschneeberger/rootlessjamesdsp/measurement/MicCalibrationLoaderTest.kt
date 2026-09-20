package me.timschneeberger.rootlessjamesdsp.measurement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Unit tests for [MicCalibrationLoader].
 *
 * Tests verify:
 *  - Loading UMIK-1 .cal format
 *  - Loading simple text format
 *  - Comment lines are skipped
 *  - Identity calibration returns 0 dB
 *  - Interpolation between calibration points
 *  - Application to SPL arrays
 */
class MicCalibrationLoaderTest {

    private fun createTempCalFile(content: String): File {
        val file = File.createTempFile("test_cal", ".cal")
        file.writeText(content)
        file.deleteOnExit()
        return file
    }

    @Test
    fun `load UMIK-1 cal file`() {
        val content = """
            -- CAL file for UMIK-1
            -- Calibration data
            20.0  -50.0
            100.0 -49.5
            1000.0 -60.0
            10000.0 -65.0
            20000.0 -70.0
        """.trimIndent()

        val file = createTempCalFile(content)
        val cal = MicCalibrationLoader().load(file)

        assertNotNull(cal)
        assertEquals(5, cal!!.frequencies.size)
        assertEquals(20.0, cal.frequencies[0], 0.001)
        assertEquals(-50.0, cal.gains[0], 0.001)
        assertEquals(20000.0, cal.frequencies[4], 0.001)
        assertEquals(-70.0, cal.gains[4], 0.001)
    }

    @Test
    fun `skip comment lines starting with hash and semicolon`() {
        val content = """
            # This is a comment
            ; Another comment
            * Star comment
            20.0 0.0
            20000.0 0.0
        """.trimIndent()

        val file = createTempCalFile(content)
        val cal = MicCalibrationLoader().load(file)

        assertNotNull(cal)
        assertEquals(2, cal!!.frequencies.size)
    }

    @Test
    fun `identity calibration returns zero gain`() {
        val cal = MicCalibrationLoader.CalibrationData.identity()
        assertEquals(0.0, cal.gainAt(20.0), 0.001)
        assertEquals(0.0, cal.gainAt(1000.0), 0.001)
        assertEquals(0.0, cal.gainAt(20000.0), 0.001)
    }

    @Test
    fun `interpolation between calibration points`() {
        val cal = MicCalibrationLoader.CalibrationData(
            doubleArrayOf(100.0, 1000.0),
            doubleArrayOf(-50.0, -60.0),
            null
        )

        // At 100 Hz: -50 dB
        assertEquals(-50.0, cal.gainAt(100.0), 0.001)
        // At 1000 Hz: -60 dB
        assertEquals(-60.0, cal.gainAt(1000.0), 0.001)
        // At 550 Hz (midpoint): -55 dB
        assertEquals(-55.0, cal.gainAt(550.0), 0.1)
    }

    @Test
    fun `extrapolation uses endpoint values`() {
        val cal = MicCalibrationLoader.CalibrationData(
            doubleArrayOf(100.0, 1000.0),
            doubleArrayOf(-50.0, -60.0),
            null
        )

        // Below range: first value
        assertEquals(-50.0, cal.gainAt(20.0), 0.001)
        // Above range: last value
        assertEquals(-60.0, cal.gainAt(20000.0), 0.001)
    }

    @Test
    fun `apply calibration to SPL arrays`() {
        val freqs = floatArrayOf(100.0f, 1000.0f)
        val spl = floatArrayOf(90.0f, 80.0f)
        val cal = MicCalibrationLoader.CalibrationData(
            doubleArrayOf(100.0, 1000.0),
            doubleArrayOf(-50.0, -60.0),
            null
        )

        val calibrated = cal.apply(freqs, spl)
        assertEquals(2, calibrated.size)
        // SPL_calibrated = SPL_raw - cal_gain
        assertEquals(140.0f, calibrated[0], 0.01f) // 90 - (-50) = 140
        assertEquals(140.0f, calibrated[1], 0.01f) // 80 - (-60) = 140
    }

    @Test
    fun `apply DoubleArray version`() {
        val freqs = doubleArrayOf(100.0, 1000.0)
        val spl = doubleArrayOf(90.0, 80.0)
        val cal = MicCalibrationLoader.CalibrationData(
            doubleArrayOf(100.0, 1000.0),
            doubleArrayOf(-10.0, -20.0),
            null
        )

        val calibrated = cal.apply(freqs, spl)
        assertEquals(100.0, calibrated[0], 0.01)
        assertEquals(100.0, calibrated[1], 0.01)
    }

    @Test
    fun `empty file returns null`() {
        val file = createTempCalFile("")
        val cal = MicCalibrationLoader().load(file)
        assertNull(cal)
    }

    @Test
    fun `nonexistent file returns null`() {
        val cal = MicCalibrationLoader().load(File("/nonexistent/path/file.cal"))
        assertNull(cal)
    }

    @Test
    fun `file with only comments returns null`() {
        val content = """
            -- Only comments
            # Nothing else
            ; No data
        """.trimIndent()

        val file = createTempCalFile(content)
        val cal = MicCalibrationLoader().load(file)
        assertNull(cal)
    }
}
