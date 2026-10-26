package me.timschneeberger.rootlessjamesdsp.model

import me.timschneeberger.rootlessjamesdsp.utils.Constants
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/**
 * Serialization and default-state tests for Dynamic EQ bands.
 *
 * The engine falls back to [Constants.DEFAULT_DYNAMICEQ] when the
 * dsp_dynamiceq namespace (or its key) is absent, so that constant must
 * always parse into a usable band list.
 */
class DynamicEqBandListTest {

    @Test
    fun defaultConstantParsesIntoThreeBands() {
        val bands = DynamicEqBandList()
        bands.deserialize(Constants.DEFAULT_DYNAMICEQ)

        assertEquals(3, bands.size)
        assertEquals(180.0, bands[0].frequency, 0.001)
        assertEquals(-22.0, bands[0].thresholdDb, 0.001)
        assertEquals(-6.0, bands[0].rangeDb, 0.001)
        assertEquals(DynamicEqMode.COMPRESS, bands[0].mode)

        assertEquals(3200.0, bands[1].frequency, 0.001)
        assertEquals(6800.0, bands[2].frequency, 0.001)
    }

    @Test
    fun roundTripPreservesEveryParameter() {
        val original = DynamicEqBandList()
        original.add(DynamicEqBand(120.0, 0.7, -18.5, 2.5, 12.0, 180.0, -6.5, DynamicEqMode.COMPRESS))
        original.add(DynamicEqBand(5400.0, 4.2, -30.0, 1.8, 2.0, 45.0, 9.0, DynamicEqMode.EXPAND))

        val restored = DynamicEqBandList()
        restored.deserialize(original.serialize())

        assertEquals(original.size, restored.size)
        for (i in original.indices) {
            assertEquals(original[i], restored[i])
        }
    }

    @Test
    fun emptyAndHeaderOnlyStringsProduceNoBands() {
        val bands = DynamicEqBandList()
        bands.deserialize("DEQ: ")
        assertTrue(bands.isEmpty())

        bands.deserialize("")
        assertTrue(bands.isEmpty())

        bands.deserialize("DEQ: ; ; ")
        assertTrue(bands.isEmpty())
    }

    @Test
    fun malformedLinesAreSkipped() {
        val bands = DynamicEqBandList()
        bands.deserialize(
            "DEQ: 100 1 -20 2 10 100 -6 0; " +
                    "broken line here; " +
                    "200 2 -25 3 5 50 -8 1;"
        )

        assertEquals(2, bands.size)
        assertEquals(100.0, bands[0].frequency, 0.001)
        assertEquals(200.0, bands[1].frequency, 0.001)
        assertEquals(DynamicEqMode.EXPAND, bands[1].mode)
    }

    @Test
    fun deserializationIsCappedAtNativeMaxBands() {
        val sb = StringBuilder("DEQ: ")
        for (i in 1..12) {
            sb.append("${100 + i} 1 -20 2 10 100 -6 0; ")
        }

        val bands = DynamicEqBandList()
        bands.deserialize(sb.toString())

        assertEquals(DynamicEqBandList.MAX_BANDS, bands.size)
    }

    @Test
    fun unknownModeFallsBackToCompressor() {
        val bands = DynamicEqBandList()
        bands.deserialize("DEQ: 100 1 -20 2 10 100 -6 99;")

        assertEquals(1, bands.size)
        assertEquals(DynamicEqMode.COMPRESS, bands[0].mode)
    }

    @Test
    fun nativeConfigLayoutMatchesParameterOrder() {
        val bands = DynamicEqBandList()
        bands.add(DynamicEqBand(250.0, 1.5, -24.0, 4.0, 8.0, 120.0, -7.0, DynamicEqMode.EXPAND))

        val config = bands.toNativeConfig()

        assertEquals(DynamicEqBandList.VALUES_PER_BAND, config.size)
        assertEquals(250f, config[0], 0.001f)
        assertEquals(1.5f, config[1], 0.001f)
        assertEquals(-24f, config[2], 0.001f)
        assertEquals(4f, config[3], 0.001f)
        assertEquals(8f, config[4], 0.001f)
        assertEquals(120f, config[5], 0.001f)
        assertEquals(-7f, config[6], 0.001f)
        assertEquals(1f, config[7], 0.001f)
    }

    @Test
    fun equalsIgnoresUuid() {
        val uuid = UUID.randomUUID()
        val a = DynamicEqBand(100.0, 1.0, -20.0, 2.0, 10.0, 100.0, -6.0, DynamicEqMode.COMPRESS, uuid)
        val b = DynamicEqBand(100.0, 1.0, -20.0, 2.0, 10.0, 100.0, -6.0, DynamicEqMode.COMPRESS)

        assertEquals(a, b)
    }
}
