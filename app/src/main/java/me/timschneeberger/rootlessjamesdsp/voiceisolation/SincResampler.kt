package me.timschneeberger.rootlessjamesdsp.voiceisolation

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Streaming windowed-sinc resampler with linear phase, used to feed the enhancer its 16 kHz input
 * from the capture loop's rate and to return the result at that rate. Kernels are cached per
 * quantized fractional phase, so a fixed integer ratio (48 kHz -> 16 kHz) uses a single table.
 *
 * Output is produced lazily by [poll]: only samples whose full kernel window is already in the
 * history are emitted, which keeps the pipeline delay constant at [inputDelay] samples.
 */
internal class SincResampler(
    val inputRate: Int,
    val outputRate: Int,
    tapsPerSide: Int = DEFAULT_TAPS_PER_SIDE,
) {
    private val ratio = inputRate.toDouble() / outputRate
    private val halfWidth = max(1, (tapsPerSide * max(1.0, ratio)).roundToInt())
    private val cutoff = 0.45 * min(inputRate, outputRate).toDouble() / inputRate
    private val ring = FloatArray(RING_SIZE)
    private val kernels = arrayOfNulls<FloatArray>(PHASE_COUNT)

    private var written = 0L
    private var produced = 0L

    /** Constant delay of the resampler in input samples. */
    val inputDelay: Int get() = halfWidth + 1

    /**
     * Input samples needed before the very first output exists, because a full kernel window has to
     * sit inside the history. [push]ing this many zeros upfront keeps every later emission complete.
     */
    val primingInputSamples: Int get() = 2 * halfWidth

    fun push(input: FloatArray, offset: Int, stride: Int, count: Int) {
        var index = offset
        repeat(count) {
            ring[(written % RING_SIZE).toInt()] = input[index]
            index += stride
            written++
        }
    }

    /**
     * Emits up to [count] output samples into [destination] at [offset], advancing by [stride].
     * Returns the number emitted; fewer than requested only while the pipeline is priming.
     */
    fun poll(destination: FloatArray, offset: Int, stride: Int, count: Int): Int {
        var emitted = 0
        var index = offset
        while (emitted < count) {
            val position = produced * ratio + halfWidth
            val center = floor(position).toLong()
            val oldest = center - halfWidth + 1
            if (center + halfWidth >= written || oldest < written - RING_SIZE) break

            val phase = (position - center)
            val key = (phase * PHASE_COUNT).toInt().coerceIn(0, PHASE_COUNT - 1)
            val kernel = kernels[key] ?: buildKernel(key).also { kernels[key] = it }

            var accumulator = 0.0
            for (tap in kernel.indices) {
                val sampleIndex = center + tap - halfWidth + 1
                accumulator += ring[(sampleIndex % RING_SIZE).toInt()] * kernel[tap]
            }
            destination[index] = accumulator.toFloat()
            index += stride
            produced++
            emitted++
        }
        return emitted
    }

    fun reset() {
        ring.fill(0f)
        written = 0
        produced = 0
    }

    private fun buildKernel(key: Int): FloatArray {
        val phase = (key + 0.5) / PHASE_COUNT
        val kernel = FloatArray(halfWidth * 2)
        var sum = 0.0
        for (tap in kernel.indices) {
            val distance = phase - (tap - halfWidth + 1)
            val windowPosition = distance / (2.0 * halfWidth) + 0.5
            val window = if (windowPosition < 0.0 || windowPosition > 1.0) {
                0.0
            } else {
                0.42 - 0.5 * cos(2.0 * PI * windowPosition) + 0.08 * cos(4.0 * PI * windowPosition)
            }
            val angle = 2.0 * PI * cutoff * distance
            val sinc = if (distance == 0.0) 1.0 else sin(angle) / angle
            val value = window * sinc
            kernel[tap] = value.toFloat()
            sum += value
        }
        if (sum != 0.0) {
            for (tap in kernel.indices) kernel[tap] = (kernel[tap] / sum).toFloat()
        }
        return kernel
    }

    companion object {
        private const val DEFAULT_TAPS_PER_SIDE = 16
        private const val PHASE_COUNT = 1024
        private const val RING_SIZE = 16384
    }
}
