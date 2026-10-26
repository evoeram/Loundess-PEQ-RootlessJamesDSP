package me.timschneeberger.rootlessjamesdsp.model

import me.timschneeberger.rootlessjamesdsp.utils.BiquadUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ParametricEqBandList.fromApoString] robustness:
 * extreme or broken APO input must never crash, problematic bands are kept
 * and flagged, and correct presets pass through untouched.
 */
class ParametricEqBandListApoRobustnessTest {

    @Test
    fun `extreme filter values do not crash import and are kept`() {
        val apo = """
            Preamp: 0 dB
            Filter 1: ON PK Fc 0 Hz Gain 3.0 dB Q 1.41
            Filter 2: ON PK Fc 1000 Hz Gain 3.0 dB Q 0
            Filter 3: ON PK Fc 999999999 Hz Gain -999999999 dB Q 999999999999
            Filter 4: ON PK Fc 1000 Hz Gain 3.0 dB Q 1.41
        """.trimIndent()

        val bands = ParametricEqBandList()
        val result = bands.fromApoString(apo)

        assertEquals(4, bands.size)
        // Fc 0 and Q 0 are invalid at the model level (sample-rate independent);
        // the huge-but-finite values are valid here and get flagged by the
        // sample-rate-aware stability check in the UI layer instead
        assertEquals(2, result.problematicFilters)
        assertEquals(0, result.skippedFilters)

        // Good band untouched
        assertEquals(1000.0, bands[3].frequency, 0.01)
        assertEquals(3.0, bands[3].gain, 0.01)
        assertEquals(1.41, bands[3].q, 0.001)

        // Problematic bands keep their original (extreme) values
        assertEquals(0.0, bands[0].frequency, 0.0)
        assertEquals(0.0, bands[1].q, 0.0)
    }

    @Test
    fun `overflowing numeric fields are flagged and do not crash`() {
        // A 400-digit frequency overflows to Infinity during parsing
        val apo = "Filter 1: ON PK Fc ${"9".repeat(400)} Hz Gain 0 dB Q 1"

        val bands = ParametricEqBandList()
        val result = bands.fromApoString(apo)

        assertEquals(1, bands.size)
        assertEquals(1, result.problematicFilters)
        assertTrue(bands[0].frequency.isInfinite())
    }

    @Test
    fun `garbage lines are skipped without crashing`() {
        val apo = """
            Filter 1: ON XX Fc 100 Hz Gain 0 dB Q 1
            Filter 2: ON PK Fc NaN Hz Gain 0 dB Q 1
            Filter 3: ON PK Fc 1e9 Hz Gain 0 dB Q 1
            Filter : ON PK Fc 100 Hz Gain 0 dB Q 1
            total garbage
            Filter 4: ON PK Fc 1000 Hz Gain 3.0 dB Q 1.41
        """.trimIndent()

        val bands = ParametricEqBandList()
        val result = bands.fromApoString(apo)

        // Only Filter 4 parses; NaN/1e9 do not match the numeric regex and are
        // dropped as unrecognized lines without incrementing the counters
        assertEquals(1, bands.size)
        assertEquals(1000.0, bands[0].frequency, 0.01)
        assertEquals(1, result.skippedFilters)
        assertEquals(0, result.problematicFilters)
    }

    @Test
    fun `empty and preamp-only inputs do not crash`() {
        val empty = ParametricEqBandList()
        val emptyResult = empty.fromApoString("")
        assertEquals(0, empty.size)
        assertEquals(0, emptyResult.problematicFilters)
        assertEquals(0.0, emptyResult.preampDb, 0.0)

        val onlyPreamp = ParametricEqBandList()
        val preampResult = onlyPreamp.fromApoString("Preamp: -6 dB")
        assertEquals(0, onlyPreamp.size)
        assertEquals(-6.0, preampResult.preampDb, 0.01)
    }

    @Test
    fun `realistic preset is not flagged and all bands are stable at 48 kHz`() {
        val apo = """
            Preamp: -1.0 dB
            Channel: ALL
            Filter 1: ON LSC Fc 260 Hz Gain -6.0 dB Q 0.600
            Filter 2: ON PK Fc 560 Hz Gain 1.7 dB Q 1.480
            Filter 3: ON PK Fc 4400 Hz Gain -5.3 dB Q 1.660
            Channel: L
            Filter 4: ON PK Fc 3440 Hz Gain -0.9 dB Q 3.870
            Filter 5: ON PK Fc 5300 Hz Gain -0.6 dB Q 3.220
            Filter 6: ON PK Fc 7700 Hz Gain 1.1 dB Q 5.550
            Filter 7: ON PK Fc 12100 Hz Gain 1.6 dB Q 3.400
            Filter 8: ON PK Fc 13700 Hz Gain -3.2 dB Q 5.500
            Filter 9: ON PK Fc 15700 Hz Gain -3.0 dB Q 4.570
            Filter 10: ON HSC Fc 16369 Hz Gain -4.3 dB Q 0.800
            Channel: R
            Filter 11: ON PK Fc 2840 Hz Gain 1.0 dB Q 2.390
            Filter 12: ON PK Fc 3500 Hz Gain -0.8 dB Q 4.270
            Filter 13: ON PK Fc 4900 Hz Gain 0.6 dB Q 2.000
            Filter 14: ON PK Fc 8400 Hz Gain 1.2 dB Q 2.570
        """.trimIndent()

        val bands = ParametricEqBandList()
        val result = bands.fromApoString(apo)

        assertEquals(14, bands.size)
        assertEquals("correct presets must not be flagged", 0, result.problematicFilters)
        assertEquals(0, result.skippedFilters)
        for (band in bands) {
            assertTrue(
                "band $band must be stable at 48 kHz",
                BiquadUtils.isBandStable(band, 48000.0)
            )
        }
    }

    @Test
    fun `export of correct preset round-trips without changes`() {
        val bands = ParametricEqBandList()
        bands.add(ParametricEqBand(260.0, -6.0, 0.6, ParametricEqFilterType.LOW_SHELF, ParametricEqChannel.LEFT_RIGHT))
        bands.add(ParametricEqBand(1000.0, 3.0, 1.41, ParametricEqFilterType.PEAKING, ParametricEqChannel.LEFT))

        val exported = bands.toApoString(-1.0)
        val reimported = ParametricEqBandList()
        val result = reimported.fromApoString(exported)

        assertEquals(0, result.problematicFilters)
        assertEquals(0, result.skippedFilters)
        assertEquals(bands.size, reimported.size)
        for (i in bands.indices) {
            assertEquals(bands[i].frequency, reimported[i].frequency, 0.01)
            assertEquals(bands[i].gain, reimported[i].gain, 0.01)
            assertEquals(bands[i].q, reimported[i].q, 0.001)
            assertEquals(bands[i].filterType, reimported[i].filterType)
            assertEquals(bands[i].channel, reimported[i].channel)
        }
        assertFalse(reimported.isEmpty())
    }
}
