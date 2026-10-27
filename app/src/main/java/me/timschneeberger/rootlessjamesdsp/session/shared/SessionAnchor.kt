package me.timschneeberger.rootlessjamesdsp.session.shared

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import timber.log.Timber

/**
 * Тихий трек-якорь на sid сессии плеера. Пока на сессии существует хотя бы
 * один трек, AudioFlinger не уничтожает EffectChain — mute-эффект переживает
 * release/пересоздание трека плеера и накрывает новый трек с первого сэмпла.
 * Убирает само окно смерти сессии вместо гонки за ним через dumpsys
 */
class SessionAnchor private constructor(private val track: AudioTrack) {

    fun release() {
        try {
            track.pause()
            track.flush()
        }
        catch (ex: IllegalStateException) {
            // Track already stopped
        }
        track.release()
    }

    companion object {
        private const val SAMPLE_RATE = 48000
        private const val LOOP_FRAMES = 4096

        fun create(sid: Int): SessionAnchor? {
            if (sid <= 0)
                return null

            return try {
                // PCM16 stereo, нули; статический буфер закольцован, чтобы
                // трек оставался в PLAYING бесконечно и не требовал потока-писателя
                val silence = ByteArray(LOOP_FRAMES * 4)
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build()
                    )
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(silence.size)
                    .setSessionId(sid)
                    .build()

                // У static-трека до записи данных штатное состояние —
                // STATE_NO_STATIC_DATA (2); провал только STATE_UNINITIALIZED
                if (track.state == AudioTrack.STATE_UNINITIALIZED) {
                    Timber.e("[SPL] anchor: track init failed on sid=$sid")
                    track.release()
                    return null
                }

                track.write(silence, 0, silence.size, AudioTrack.WRITE_BLOCKING)
                track.setVolume(0f)
                track.setLoopPoints(0, LOOP_FRAMES, -1)
                track.play()
                Timber.i("[SPL] anchor created on sid=$sid")
                SessionAnchor(track)
            }
            catch (ex: Exception) {
                Timber.e("[SPL] anchor: failed to create track on sid=$sid")
                Timber.e(ex)
                null
            }
        }
    }
}
