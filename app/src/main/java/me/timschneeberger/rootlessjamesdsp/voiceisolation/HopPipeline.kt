package me.timschneeberger.rootlessjamesdsp.voiceisolation

/**
 * Frames a monophonic stream into the enhancer's fixed 16 kHz hops and back out at the device rate.
 *
 * The capture loop hands over blocks whose size has nothing in common with [StftProcessor.HOP_SIZE],
 * so partial hops are accumulated here instead of being thrown away, and the interpolated result is
 * queued so that every call emits exactly the frame count it was given. [hop] runs once per
 * complete hop and must write its result into `mixed`.
 */
internal class HopPipeline(
    val sampleRate: Int,
    private val hop: (dry: FloatArray, mixed: FloatArray, settings: VoiceIsolationSettings) -> Unit,
) {
    private val decimator = SincResampler(sampleRate, GtcrnProcessor.SAMPLE_RATE)
    private val interpolator = SincResampler(GtcrnProcessor.SAMPLE_RATE, sampleRate)
    private val dry = FloatArray(StftProcessor.HOP_SIZE)
    private val mixed = FloatArray(StftProcessor.HOP_SIZE)
    private var dryFilled = 0

    /** One hop of audio measured at the device rate: 256 samples at 16 kHz rounded up. */
    private val hopOutputFrames = ((StftProcessor.HOP_SIZE.toLong() * sampleRate +
        GtcrnProcessor.SAMPLE_RATE - 1) / GtcrnProcessor.SAMPLE_RATE).toInt().coerceAtLeast(1)

    private val outputRing = FloatArray(
        (hopOutputFrames * OUTPUT_RING_HOPS).coerceAtLeast(OUTPUT_RING_FLOOR),
    )
    private var ringWritten = 0L
    private var ringRead = 0L
    private var started = false

    /**
     * Output samples kept in reserve before the first block is answered. A hop only completes every
     * few blocks, so its whole result arrives at once; this cushion has to outlast that gap. One hop
     * is not enough: at the non-integer ratios of 44.1 and 96 kHz the bursts drift apart over time
     * and the queue runs dry again mid-stream.
     */
    val prebufferFrames: Int get() = hopOutputFrames * PREBUFFER_HOPS

    /** Complete hops handed to [hop] so far; used to prove that no audio is dropped. */
    var processedHops = 0L
        private set

    /** Delay of the whole chain in milliseconds, independent of the block size. */
    val latencyMs: Float
        get() = (decimator.inputDelay * 1000f / sampleRate +
            StftProcessor.HOP_SIZE * 1000f / GtcrnProcessor.SAMPLE_RATE +
            interpolator.inputDelay * 1000f / GtcrnProcessor.SAMPLE_RATE +
            prebufferFrames * 1000f / sampleRate)

    init {
        prime()
    }

    // Both resamplers need a full kernel window of history before their first output. Feeding that
    // upfront as zeros keeps every later hop complete, otherwise the very first one comes out short
    // and the block stream gets a hole in it.
    private fun prime() {
        val zeros = FloatArray(maxOf(decimator.primingInputSamples, interpolator.primingInputSamples))
        decimator.push(zeros, 0, 1, decimator.primingInputSamples)
        interpolator.push(zeros, 0, 1, interpolator.primingInputSamples)
    }

    /**
     * Consumes [frames] samples of one channel from [buffer] (starting at [offset] + [channel],
     * interleaved with stride 2) and writes exactly [frames] samples back into the same positions.
     */
    fun process(buffer: FloatArray, channel: Int, frames: Int, offset: Int, settings: VoiceIsolationSettings) {
        val first = offset + channel
        decimator.push(buffer, first, 2, frames)

        while (true) {
            val requested = StftProcessor.HOP_SIZE - dryFilled
            dryFilled += decimator.poll(dry, dryFilled, 1, requested)
            if (dryFilled < StftProcessor.HOP_SIZE) break

            hop(dry, mixed, settings)
            processedHops++
            interpolator.push(mixed, 0, 1, StftProcessor.HOP_SIZE)
            dryFilled = 0
        }

        drainInterpolator()

        val needed = maxOf(frames.toLong(), if (started) 0L else prebufferFrames.toLong())
        if (ringWritten - ringRead < needed) {
            started = false
            for (index in 0 until frames) buffer[first + 2 * index] = 0f
            return
        }
        started = true
        for (index in 0 until frames) {
            buffer[first + 2 * index] = outputRing[(ringRead % outputRing.size).toInt()]
            ringRead++
        }
    }

    private fun drainInterpolator() {
        while (true) {
            val free = (outputRing.size - (ringWritten - ringRead)).coerceAtLeast(0L).toInt()
            val tail = (ringWritten % outputRing.size).toInt()
            val requested = minOf(free, outputRing.size - tail)
            if (requested == 0) return

            val produced = interpolator.poll(outputRing, tail, 1, requested)
            ringWritten += produced
            if (produced < requested) return
        }
    }

    fun reset() {
        decimator.reset()
        interpolator.reset()
        dryFilled = 0
        ringWritten = 0
        ringRead = 0
        processedHops = 0
        prime()
    }

    companion object {
        private const val OUTPUT_RING_FLOOR = 4096
        private const val OUTPUT_RING_HOPS = 6

        /** Kept as [prebufferFrames] plus the extra hops the ring can still take. */
        private const val PREBUFFER_HOPS = 2
    }
}
