package me.timschneeberger.rootlessjamesdsp.measurement

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * Канал вывода для воспроизведения.
 */
enum class OutputChannel {
    LEFT,
    RIGHT,
    BOTH
}

/**
 * Воспроизведение sweep-сигнала через AudioTrack (MODE_STREAM).
 *
 * Поддерживает:
 *  - [play] — моно-сигнал с маршрутизацией на выбранный канал (LEFT/RIGHT/BOTH)
 *  - [playStereo] — готовый stereo-interleaved сигнал (L,R,L,R,...)
 *    для раздельного воспроизведения L и R сегментов на разных каналах.
 *
 * @param sampleRate частота дискретизации (48000)
 * @param volume громкость воспроизведения (0.0..1.0)
 * @param channel канал вывода для моно-режима (LEFT, RIGHT, BOTH)
 */
class SweepPlayer(
    private val sampleRate: Int = 48000,
    private val volume: Float = 0.8f,
    private val channel: OutputChannel = OutputChannel.BOTH
) {
    private var audioTrack: AudioTrack? = null
    private var isPlaying = false

    /**
     * Воспроизвести моно-сигнал с маршрутизацией на выбранный канал.
     * Блокирует до завершения воспроизведения.
     *
     * @param signal PCM-данные (mono, float) — конвертируются в стерео
     *               с маршрутизацией на выбранный канал.
     * @return true при успехе
     */
    fun play(signal: FloatArray): Boolean {
        // Конвертируем mono → stereo interleaved с маршрутизацией канала
        val stereo = FloatArray(signal.size * 2)
        when (channel) {
            OutputChannel.LEFT -> {
                for (i in signal.indices) {
                    stereo[i * 2] = signal[i]
                    stereo[i * 2 + 1] = 0f
                }
            }
            OutputChannel.RIGHT -> {
                for (i in signal.indices) {
                    stereo[i * 2] = 0f
                    stereo[i * 2 + 1] = signal[i]
                }
            }
            OutputChannel.BOTH -> {
                for (i in signal.indices) {
                    stereo[i * 2] = signal[i]
                    stereo[i * 2 + 1] = signal[i]
                }
            }
        }
        return playStereo(stereo)
    }

    /**
     * Воспроизвести готовый stereo-interleaved сигнал (L,R,L,R,...).
     * Используется в режиме L/R sequential, где L-сегмент и R-сегмент
     * находятся на разных каналах одного stereo-потока.
     *
     * Блокирует до завершения воспроизведения.
     *
     * @param stereo PCM-данные (stereo interleaved, float)
     * @return true при успехе
     */
    fun playStereo(stereo: FloatArray): Boolean {
        val channelConfig = AudioFormat.CHANNEL_OUT_STEREO
        val audioFormat = AudioFormat.ENCODING_PCM_FLOAT

        val minBufSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = minBufSize.coerceAtLeast(8192)

        try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack not initialized, state=${audioTrack?.state}")
                audioTrack?.release()
                audioTrack = null
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AudioTrack", e)
            audioTrack?.release()
            audioTrack = null
            return false
        }

        audioTrack?.setVolume(volume)
        audioTrack?.play()
        isPlaying = true

        // Запись данных чанками в потоковом режиме
        val chunkSize = bufferSize / 8
        var offset = 0
        while (offset < stereo.size && isPlaying) {
            val frames = minOf(chunkSize, stereo.size - offset)
            val written = audioTrack?.write(stereo, offset, frames, AudioTrack.WRITE_BLOCKING) ?: -1
            if (written < 0) {
                Log.e(TAG, "AudioTrack write error: $written")
                break
            }
            offset += written
        }

        // Ожидание завершения воспроизведения (drain)
        // stereo-массив: 2 сэмпла на кадр
        val durationMs = (stereo.size.toFloat() / 2f / sampleRate * 1000).toLong()
        try {
            Thread.sleep(durationMs + 200)
        } catch (e: InterruptedException) {
            // ok
        }

        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        isPlaying = false

        return true
    }

    /** Остановить воспроизведение досрочно. */
    fun stop() {
        isPlaying = false
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }

    companion object {
        private const val TAG = "SweepPlayer"
    }
}
