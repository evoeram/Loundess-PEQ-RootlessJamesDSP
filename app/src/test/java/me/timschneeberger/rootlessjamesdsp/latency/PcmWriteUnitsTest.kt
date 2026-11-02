package me.timschneeberger.rootlessjamesdsp.latency

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Regression tests for BUG-01: AudioTrack.write(ByteBuffer,…) returns bytes,
 * while the array-based overloads return samples. The packed PCM24/PCM32
 * output paths must convert byte counts before they reach queue/tracer
 * counters, otherwise the queue estimate is inflated 3x/4x.
 */
class PcmWriteUnitsTest {

    @Test
    fun `pcm24 packed bytes convert to samples`() {
        // 512 interleaved samples * 3 bytes = 1536 bytes
        assertEquals(512, PcmWriteUnits.bytesToSamples(1536, 3))
    }

    @Test
    fun `pcm32 bytes convert to samples`() {
        // 512 interleaved samples * 4 bytes = 2048 bytes
        assertEquals(512, PcmWriteUnits.bytesToSamples(2048, 4))
    }

    @Test
    fun `pcm16-sized byte counts stay consistent with array writes`() {
        // 2 bytes per sample: conversion must be a no-op vs. the short[] path
        assertEquals(1024, PcmWriteUnits.bytesToSamples(2048, 2))
    }

    @Test
    fun `partial writes convert proportionally`() {
        // half of a 512-sample PCM24 block
        assertEquals(256, PcmWriteUnits.bytesToSamples(768, 3))
    }

    @Test
    fun `zero passes through`() {
        assertEquals(0, PcmWriteUnits.bytesToSamples(0, 3))
    }

    @Test
    fun `negative error codes pass through unchanged`() {
        // e.g. AudioTrack.ERROR_DEAD_OBJECT (-6), ERROR_INVALID_OPERATION (-3)
        assertEquals(-6, PcmWriteUnits.bytesToSamples(-6, 3))
        assertEquals(-3, PcmWriteUnits.bytesToSamples(-3, 4))
    }

    @Test
    fun `invalid bytesPerSample rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PcmWriteUnits.bytesToSamples(100, 0)
        }
    }
}
