package me.timschneeberger.rootlessjamesdsp.voiceisolation

import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import timber.log.Timber

/**
 * Keeps the rootless output track's playback buffer large enough for the enhancer stage.
 *
 * The enhancer adds per-block work to the capture loop, so scheduling jitter can starve the track.
 * Growth follows the ported [AdaptiveBufferController]: every new underrun enlarges the buffered
 * frames, capped at [MAXIMUM_BUFFER_MULTIPLIER] times the size the module started with. Once the
 * module is disabled the adapter forgets the track size, so the next activation re-reads it.
 */
class PlaybackBufferAdapter(private val sampleRate: Int) {
    private var controller: AdaptiveBufferController? = null
    private var lastStatsAt = 0L

    val status: String
        get() = controller?.let {
            "%s (%.1f ms)".format(
                STABILITY_NAMES[it.stabilityLevel.coerceIn(0, STABILITY_NAMES.lastIndex)],
                it.bufferFrames * 1000f / sampleRate,
            )
        } ?: "inactive"

    /**
     * Call once per processed block; the track is only visited every [STATS_INTERVAL_MS].
     * [hopMs] is the duration of one enhancer hop, which sets the granularity of the growth steps.
     * [allowResize] can disable adaptation while retaining the statistics callback cadence.
     * Returns true on such a tick, so the caller can report module statistics with the same cadence.
     */
    fun onBlock(
        track: AudioTrack,
        active: Boolean,
        hopMs: Float,
        allowResize: Boolean = true,
    ): Boolean {
        if (!active || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            controller = null
            lastStatsAt = 0L
            return false
        }
        val now = SystemClock.elapsedRealtime()
        if (lastStatsAt != 0L && now - lastStatsAt < STATS_INTERVAL_MS) return false
        lastStatsAt = now
        if (!allowResize) return true

        val hopFrames = (sampleRate * hopMs.coerceAtLeast(DEFAULT_HOP_MS) / 1000f)
            .toInt().coerceAtLeast(1)
        val current = controller ?: AdaptiveBufferController(
            initialBufferFrames = track.bufferSizeInFrames.coerceAtLeast(1),
            capacityFrames = minOf(
                track.bufferCapacityInFrames,
                track.bufferSizeInFrames * MAXIMUM_BUFFER_MULTIPLIER,
            ).coerceAtLeast(1),
            frameSize = hopFrames,
        ).also { controller = it }

        current.synchronize(track.bufferSizeInFrames, track.bufferCapacityInFrames)
        val requested = current.resizeRequest(track.underrunCount) ?: return true
        current.onResizeResult(track.setBufferSizeInFrames(requested))
        current.synchronize(track.bufferSizeInFrames, track.bufferCapacityInFrames)
        Timber.i("VoiceIsolation: playback buffer -> ${current.bufferFrames} frames, $status")
        return true
    }

    companion object {
        private const val MAXIMUM_BUFFER_MULTIPLIER = 4
        private const val STATS_INTERVAL_MS = 1000L
        private const val DEFAULT_HOP_MS = 10f
        private val STABILITY_NAMES = arrayOf("Normal", "Buffered", "Safe")
    }
}
