package me.timschneeberger.rootlessjamesdsp.model

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for APO import/export with per-channel directives.
 *
 * Tests the Channel: ALL / L / R directive parsing and emission,
 * round-trip import→export fidelity, and backwards compatibility.
 */
class ParametricEqBandListApoChannelTest {

    // ── Test 1: Parse Channel directives ──

    @Test
    fun parseChannelDirectives() {
        val apo = """
            Preamp: -1.0 dB
            Channel: ALL
            Filter 1: ON LSC Fc 260 Hz Gain -6.0 dB Q 0.600
            Channel: L
            Filter 2: ON PK Fc 3440 Hz Gain -0.9 dB Q 3.870
            Channel: R
            Filter 3: ON PK Fc 2840 Hz Gain 1.0 dB Q 2.390
        """.trimIndent()

        val bands = ParametricEqBandList()
        val result = bands.fromApoString(apo)

        assertEquals(-1.0, result.preampDb, 0.01)
        assertEquals(3, bands.size)

        assertEquals(ParametricEqChannel.LEFT_RIGHT, bands[0].channel)
        assertEquals(ParametricEqChannel.LEFT, bands[1].channel)
        assertEquals(ParametricEqChannel.RIGHT, bands[2].channel)

        assertEquals(260.0, bands[0].frequency, 0.01)
        assertEquals(3440.0, bands[1].frequency, 0.01)
        assertEquals(2840.0, bands[2].frequency, 0.01)
    }

    // ── Test 2: Default to BOTH when no Channel directive ──

    @Test
    fun noChannelDirective_defaultsToBoth() {
        val apo = """
            Preamp: 0 dB
            Filter 1: ON PK Fc 1000 Hz Gain 3.0 dB Q 1.41
            Filter 2: ON LSC Fc 100 Hz Gain 5.0 dB Q 0.71
        """.trimIndent()

        val bands = ParametricEqBandList()
        bands.fromApoString(apo)

        assertEquals(2, bands.size)
        assertEquals(ParametricEqChannel.LEFT_RIGHT, bands[0].channel)
        assertEquals(ParametricEqChannel.LEFT_RIGHT, bands[1].channel)
    }

    // ── Test 3: Channel directive persists until changed ──

    @Test
    fun channelDirectivePersists() {
        val apo = """
            Channel: L
            Filter 1: ON PK Fc 1000 Hz Gain 3.0 dB Q 1.41
            Filter 2: ON PK Fc 2000 Hz Gain -2.0 dB Q 1.0
            Channel: R
            Filter 3: ON PK Fc 3000 Hz Gain 1.0 dB Q 2.0
        """.trimIndent()

        val bands = ParametricEqBandList()
        bands.fromApoString(apo)

        assertEquals(3, bands.size)
        assertEquals(ParametricEqChannel.LEFT, bands[0].channel)
        assertEquals(ParametricEqChannel.LEFT, bands[1].channel)
        assertEquals(ParametricEqChannel.RIGHT, bands[2].channel)
    }

    // ── Test 4: Export with per-channel directives ──

    @Test
    fun exportWithChannelDirectives() {
        val bands = ParametricEqBandList()
        bands.add(ParametricEqBand(260.0, -6.0, 0.6, ParametricEqFilterType.LOW_SHELF, ParametricEqChannel.LEFT_RIGHT))
        bands.add(ParametricEqBand(3440.0, -0.9, 3.87, ParametricEqFilterType.PEAKING, ParametricEqChannel.LEFT))
        bands.add(ParametricEqBand(2840.0, 1.0, 2.39, ParametricEqFilterType.PEAKING, ParametricEqChannel.RIGHT))

        val apo = bands.toApoString(-1.0)

        // Should contain Channel directives
        assertTrue("Should have Channel: all", apo.contains("Channel: all"))
        assertTrue("Should have Channel: L", apo.contains("Channel: L"))
        assertTrue("Should have Channel: R", apo.contains("Channel: R"))
        assertTrue("Should have Preamp", apo.contains("Preamp: -1"))
    }

    // ── Test 5: Export without Channel directives when all BOTH ──

    @Test
    fun exportAllBoth_noChannelDirectives() {
        val bands = ParametricEqBandList()
        bands.add(ParametricEqBand(1000.0, 3.0, 1.41, ParametricEqFilterType.PEAKING, ParametricEqChannel.LEFT_RIGHT))
        bands.add(ParametricEqBand(100.0, 5.0, 0.71, ParametricEqFilterType.LOW_SHELF, ParametricEqChannel.LEFT_RIGHT))

        val apo = bands.toApoString(0.0)

        assertFalse("Should NOT have per-channel directives", apo.contains("Channel: L"))
        assertFalse("Should NOT have per-channel directives", apo.contains("Channel: R"))
    }

    // ── Test 6: Round-trip import → export → import ──

    @Test
    fun roundTripImportExport() {
        val original = """
            Preamp: -1.0 dB
            Channel: ALL
            Filter 1: ON LSC Fc 260 Hz Gain -6.0 dB Q 0.600
            Filter 2: ON PK Fc 560 Hz Gain 1.7 dB Q 1.480
            Channel: L
            Filter 3: ON PK Fc 3440 Hz Gain -0.9 dB Q 3.870
            Filter 4: ON PK Fc 5300 Hz Gain -0.6 dB Q 3.220
            Channel: R
            Filter 5: ON PK Fc 2840 Hz Gain 1.0 dB Q 2.390
            Filter 6: ON PK Fc 3500 Hz Gain -0.8 dB Q 4.270
        """.trimIndent()

        // Import
        val bands1 = ParametricEqBandList()
        val result1 = bands1.fromApoString(original)
        assertEquals(-1.0, result1.preampDb, 0.01)
        assertEquals(6, bands1.size)

        // Export
        val exported = bands1.toApoString(-1.0)

        // Re-import
        val bands2 = ParametricEqBandList()
        val result2 = bands2.fromApoString(exported)
        assertEquals(-1.0, result2.preampDb, 0.01)
        assertEquals(6, bands2.size)

        // Verify channel modes match
        for (i in bands1.indices) {
            assertEquals("Band $i channel mismatch", bands1[i].channel, bands2[i].channel)
            assertEquals("Band $i freq mismatch", bands1[i].frequency, bands2[i].frequency, 0.01)
            assertEquals("Band $i gain mismatch", bands1[i].gain, bands2[i].gain, 0.01)
            assertEquals("Band $i q mismatch", bands1[i].q, bands2[i].q, 0.001)
            assertEquals("Band $i type mismatch", bands1[i].filterType, bands2[i].filterType)
        }
    }

    // ── Test 7: Various channel label formats ──

    @Test
    fun variousChannelLabelFormats() {
        val apo = """
            Channel: ALL
            Filter 1: ON PK Fc 100 Hz Gain 1.0 dB Q 1.0
            Channel: LEFT
            Filter 2: ON PK Fc 200 Hz Gain 2.0 dB Q 1.0
            Channel: RIGHT
            Filter 3: ON PK Fc 300 Hz Gain 3.0 dB Q 1.0
            Channel: L+R
            Filter 4: ON PK Fc 400 Hz Gain 4.0 dB Q 1.0
        """.trimIndent()

        val bands = ParametricEqBandList()
        bands.fromApoString(apo)

        assertEquals(4, bands.size)
        assertEquals(ParametricEqChannel.LEFT_RIGHT, bands[0].channel)
        assertEquals(ParametricEqChannel.LEFT, bands[1].channel)
        assertEquals(ParametricEqChannel.RIGHT, bands[2].channel)
        assertEquals(ParametricEqChannel.LEFT_RIGHT, bands[3].channel)
    }

    // ── Test 8: Full example from task ──

    @Test
    fun fullExampleFromTask() {
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

        assertEquals(-1.0, result.preampDb, 0.01)
        assertEquals(0, result.skippedFilters)
        assertEquals(14, bands.size)

        // First 3: BOTH
        for (i in 0..2) {
            assertEquals("Band $i should be BOTH", ParametricEqChannel.LEFT_RIGHT, bands[i].channel)
        }

        // Bands 4-10 (index 3-9): LEFT
        for (i in 3..9) {
            assertEquals("Band $i should be LEFT", ParametricEqChannel.LEFT, bands[i].channel)
        }

        // Bands 11-14 (index 10-13): RIGHT
        for (i in 10..13) {
            assertEquals("Band $i should be RIGHT", ParametricEqChannel.RIGHT, bands[i].channel)
        }

        // Verify specific values
        assertEquals(260.0, bands[0].frequency, 0.01)
        assertEquals(ParametricEqFilterType.LOW_SHELF, bands[0].filterType)
        assertEquals(-6.0, bands[0].gain, 0.01)

        assertEquals(16369.0, bands[9].frequency, 0.01)
        assertEquals(ParametricEqFilterType.HIGH_SHELF, bands[9].filterType)

        assertEquals(8400.0, bands[13].frequency, 0.01)
    }

    // ── Test 9: Export groups consecutive same-channel bands ──

    @Test
    fun exportGroupsConsecutiveSameChannel() {
        val bands = ParametricEqBandList()
        bands.add(ParametricEqBand(100.0, 1.0, 1.0, ParametricEqFilterType.PEAKING, ParametricEqChannel.LEFT_RIGHT))
        bands.add(ParametricEqBand(200.0, 2.0, 1.0, ParametricEqFilterType.PEAKING, ParametricEqChannel.LEFT_RIGHT))
        bands.add(ParametricEqBand(300.0, 3.0, 1.0, ParametricEqFilterType.PEAKING, ParametricEqChannel.LEFT))
        bands.add(ParametricEqBand(400.0, 4.0, 1.0, ParametricEqFilterType.PEAKING, ParametricEqChannel.LEFT))
        bands.add(ParametricEqBand(500.0, 5.0, 1.0, ParametricEqFilterType.PEAKING, ParametricEqChannel.RIGHT))

        val apo = bands.toApoString(0.0)

        // Count Channel directives — should be exactly 3: ALL, L, R
        val channelLines = apo.lines().filter { it.startsWith("Channel:") }
        assertEquals(3, channelLines.size)
        assertEquals("Channel: all", channelLines[0])
        assertEquals("Channel: L", channelLines[1])
        assertEquals("Channel: R", channelLines[2])

        // Verify filter numbering is continuous
        val filterLines = apo.lines().filter { it.startsWith("Filter") }
        assertEquals(5, filterLines.size)
        for (i in filterLines.indices) {
            assertTrue("Filter ${i+1} should be numbered ${i+1}", filterLines[i].contains("Filter ${i+1}:"))
        }
    }

    // ── Test 10: Backwards compatibility — old format without Channel ──

    @Test
    fun backwardsCompatibility_oldFormatWithoutChannel() {
        val oldFormat = """
            Preamp: -2.0 dB
            Filter 1: ON PK Fc 1000 Hz Gain 3.0 dB Q 1.41
            Filter 2: ON LSC Fc 100 Hz Gain 5.0 dB Q 0.71
            Filter 3: ON HSC Fc 10000 Hz Gain -3.0 dB Q 0.71
        """.trimIndent()

        val bands = ParametricEqBandList()
        val result = bands.fromApoString(oldFormat)

        assertEquals(-2.0, result.preampDb, 0.01)
        assertEquals(3, bands.size)
        bands.forEach { assertEquals(ParametricEqChannel.LEFT_RIGHT, it.channel) }
    }
}
