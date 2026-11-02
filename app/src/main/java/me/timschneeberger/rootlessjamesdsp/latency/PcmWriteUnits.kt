package me.timschneeberger.rootlessjamesdsp.latency

/**
 * Unit conversion between the values returned by the different
 * `AudioTrack.write()` overloads, so queue and tracer counters always work
 * in interleaved samples regardless of the output encoding.
 *
 * - `write(float[]|short[], …)` returns the number of samples written.
 * - `write(ByteBuffer, …)` returns the number of bytes written.
 *
 * Feeding raw byte counts into consumers that expect samples inflates the
 * queue by 3x (PCM24) or 4x (PCM32) — see BUG-01.
 */
object PcmWriteUnits {
    /**
     * Converts a ByteBuffer write result to interleaved samples.
     * Negative error codes and zero are passed through unchanged so they are
     * never accumulated into successful counters.
     */
    fun bytesToSamples(bytesWritten: Int, bytesPerSample: Int): Int {
        if (bytesWritten <= 0) return bytesWritten
        require(bytesPerSample > 0) { "bytesPerSample must be positive" }
        return bytesWritten / bytesPerSample
    }
}
