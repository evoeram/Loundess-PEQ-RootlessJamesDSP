package me.timschneeberger.rootlessjamesdsp.voiceisolation

/**
 * Grows the playback buffer when the output track reports new underruns, within a preallocated
 * ceiling. Ported from Voice Isolation for Android (MIT); the underrun counter and the track
 * itself stay in the caller because JamesDSP owns the [android.media.AudioTrack] lifecycle.
 */
class AdaptiveBufferController(
    private val initialBufferFrames: Int,
    capacityFrames: Int,
    private val frameSize: Int,
    actualBufferFrames: Int = initialBufferFrames,
) {
    var bufferFrames: Int = actualBufferFrames
        private set

    /** 0 = Normal, 1 = Buffered, 2 = Safe. */
    val stabilityLevel: Int
        get() = when {
            bufferFrames >= initialBufferFrames * 2 -> 2
            bufferFrames > initialBufferFrames -> 1
            else -> 0
        }

    private var previousUnderruns = 0
    private val maximumBufferFrames = capacityFrames
    private var capacityFrames = capacityFrames

    init {
        require(initialBufferFrames > 0 && capacityFrames > 0 && frameSize > 0)
        require(actualBufferFrames > 0)
    }

    fun resizeRequest(underruns: Int): Int? {
        val normalizedUnderruns = underruns.coerceAtLeast(0)
        val newUnderruns = normalizedUnderruns > previousUnderruns
        previousUnderruns = normalizedUnderruns
        if (!newUnderruns || bufferFrames >= capacityFrames) return null

        return (bufferFrames.toLong() + maxOf(frameSize, bufferFrames / 2))
            .coerceAtMost(capacityFrames.toLong()).toInt()
    }

    fun onResizeResult(actualFrames: Int) {
        // The track may reject a resize or round it; trust only valid actual sizes.
        if (actualFrames in bufferFrames..capacityFrames) bufferFrames = actualFrames
    }

    fun synchronize(actualFrames: Int, actualCapacityFrames: Int) {
        // Routing can change the device's size or capacity. Report actual latency and keep future
        // requests bounded by both the allocation budget and the current device capacity.
        if (actualFrames > 0) bufferFrames = actualFrames
        if (actualCapacityFrames > 0) capacityFrames = minOf(maximumBufferFrames, actualCapacityFrames)
    }
}
