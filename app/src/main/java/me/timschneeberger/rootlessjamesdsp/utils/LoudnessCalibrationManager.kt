package me.timschneeberger.rootlessjamesdsp.utils

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Менеджер автокалибровки loudness-коррекции по измеренному SPL с микрофона.
 *
 * Поддерживает два режима:
 * 1. MICROPHONE — воспроизводит розовый шум и записывает с микрофона, вычисляет RMS → dBFS.
 * 2. MANUAL_SPL — пользователь измеряет SPL внешним SPL-метром, вводит значение вручную.
 *    Шум может воспроизводиться (опционально) или не воспроизводиться вообще.
 *
 * Канал воспроизведения шума: LEFT, RIGHT, BOTH.
 *
 * Процесс:
 * 1. (Опционально) Генерируется розовый шум и воспроизводится через AudioTrack.
 * 2. (MICROPHONE) Параллельно записывается сигнал с микрофона через AudioRecord.
 * 3. (MICROPHONE) Вычисляется RMS → dBFS.
 *    (MANUAL_SPL) Пользователь вводит измеренный SPL (dB SPL) вручную.
 * 4. referenceLevel = measuredSpl, referenceOffset = measuredSpl - systemVolumeDb.
 */
class LoudnessCalibrationManager(
    private val context: Context,
) {
    /** Режим калибровки. */
    enum class CalibrationMode {
        /** Запись через микрофон, вычисление dBFS. */
        MICROPHONE,
        /** Ручной ввод SPL, измеренного внешним SPL-метром. */
        MANUAL_SPL,
    }

    /** Канал воспроизведения розового шума. */
    enum class NoiseChannel {
        LEFT, RIGHT, BOTH,
    }

    /** Результат калибровки. */
    data class CalibrationResult(
        val measuredSplDb: Double,      // Измеренный SPL (dBFS или dB SPL)
        val systemVolumeDb: Double,     // Системная громкость в dB на момент калибровки
        val referenceLevel: Double,     // = measuredSplDb
        val referenceOffset: Double,    // = referenceLevel - systemVolumeDb
        val success: Boolean,
        val errorMessage: String? = null,
    )

    /** Колбэк прогресса калибровки (0.0 .. 1.0). */
    var onProgress: ((Float) -> Unit)? = null

    /** Колбэк завершения калибровки. */
    var onComplete: ((CalibrationResult) -> Unit)? = null

    private var calibrationJob: Job? = null
    private var audioTrack: AudioTrack? = null
    private var audioRecord: AudioRecord? = null
    private var noiseThread: Thread? = null

    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    /**
     * Запустить калибровку через микрофон.
     *
     * @param durationSec длительность измерения в секундах
     * @param sampleRate частота дискретизации
     * @param channel канал воспроизведения шума (LEFT / RIGHT / BOTH)
     */
    fun startMicrophone(
        durationSec: Int = 5,
        sampleRate: Int = 48000,
        channel: NoiseChannel = NoiseChannel.BOTH,
    ) {
        if (calibrationJob?.isActive == true) {
            Log.w(TAG, "Calibration already in progress")
            return
        }

        calibrationJob = CoroutineScope(Dispatchers.Default).launch {
            val result = runMicrophoneCalibration(durationSec, sampleRate, channel)
            withContext(Dispatchers.Main) { onComplete?.invoke(result) }
        }
    }

    /**
     * Применить вручную измеренный SPL (от внешнего SPL-метра).
     * Шум НЕ воспроизводится и НЕ записывается — только вычисление referenceLevel/Offset.
     *
     * @param measuredSplDb SPL в dB, измеренный внешним прибором
     */
    fun applyManualSpl(measuredSplDb: Double) {
        val systemVolumeDb = getCurrentSystemVolumeDb()
        val referenceLevel = measuredSplDb
        val referenceOffset = measuredSplDb - systemVolumeDb

        Log.i(TAG, "Manual SPL calibration: SPL=%.1f dB, sysVol=%.1f dB, refLevel=%.1f, refOffset=%.1f"
            .format(measuredSplDb, systemVolumeDb, referenceLevel, referenceOffset))

        onComplete?.invoke(CalibrationResult(
            measuredSplDb = measuredSplDb,
            systemVolumeDb = systemVolumeDb,
            referenceLevel = referenceLevel,
            referenceOffset = referenceOffset,
            success = true,
        ))
    }

    /**
     * Воспроизвести розовый шум для ручной калибровки (без записи).
     * Пользователь измеряет SPL внешним прибором во время воспроизведения.
     *
     * @param sampleRate частота дискретизации
     * @param channel канал воспроизведения (LEFT / RIGHT / BOTH)
     * @return true если запуск успешен
     */
    fun playNoiseForManualCalibration(
        sampleRate: Int = 48000,
        channel: NoiseChannel = NoiseChannel.BOTH,
    ): Boolean {
        return startPinkNoisePlayback(sampleRate, channel)
    }

    /** Остановить воспроизведение шума (для ручной калибровки). */
    fun stopNoise() {
        stopPlayback()
    }

    /** Отменить калибровку. */
    fun cancel() {
        calibrationJob?.cancel()
        stopPlayback()
        stopRecording()
    }

    // ── Калибровка через микрофон ──────────────────────────────────

    private suspend fun runMicrophoneCalibration(
        durationSec: Int,
        sampleRate: Int,
        channel: NoiseChannel,
    ): CalibrationResult = withContext(Dispatchers.Default) {
        val totalSamples = sampleRate * durationSec
        val chunkSize = 1024
        var recordedSamples = 0
        var sumSquares = 0.0

        try {
            val trackResult = startPinkNoisePlayback(sampleRate, channel)
            if (!trackResult) {
                return@withContext failResult("Не удалось запустить воспроизведение розового шума")
            }

            Thread.sleep(200)

            val recordResult = startRecording(sampleRate)
            if (!recordResult) {
                stopPlayback()
                return@withContext failResult("Не удалось получить доступ к микрофону")
            }

            val buffer = ShortArray(chunkSize)
            while (recordedSamples < totalSamples && isActive) {
                val read = audioRecord?.read(buffer, 0, chunkSize) ?: -1
                if (read <= 0) break

                for (i in 0 until read) {
                    val sample = buffer[i].toFloat() / Short.MAX_VALUE
                    sumSquares += (sample * sample).toDouble()
                }
                recordedSamples += read

                val progress = recordedSamples.toFloat() / totalSamples
                withContext(Dispatchers.Main) { onProgress?.invoke(progress) }
            }

            stopPlayback()
            stopRecording()

            if (recordedSamples == 0) {
                return@withContext failResult("Не удалось записать ни одного сэмпла")
            }

            val rms = sqrt(sumSquares / recordedSamples)
            val measuredSplDb = if (rms > 0.0) 20.0 * log10(rms) else -120.0
            val systemVolumeDb = getCurrentSystemVolumeDb()
            val referenceLevel = measuredSplDb
            val referenceOffset = measuredSplDb - systemVolumeDb

            Log.i(TAG, "Mic calibration: SPL=%.1f dBFS, sysVol=%.1f dB, refLevel=%.1f, refOffset=%.1f"
                .format(measuredSplDb, systemVolumeDb, referenceLevel, referenceOffset))

            CalibrationResult(measuredSplDb, systemVolumeDb, referenceLevel, referenceOffset, true)

        } catch (e: Exception) {
            Log.e(TAG, "Calibration failed", e)
            stopPlayback()
            stopRecording()
            failResult(e.message ?: "Unknown error")
        }
    }

    private fun failResult(msg: String) = CalibrationResult(0.0, 0.0, 0.0, 0.0, false, msg)

    // ── Воспроизведение розового шума ──────────────────────────────

    private fun startPinkNoisePlayback(sampleRate: Int, channel: NoiseChannel): Boolean {
        return try {
            val channelConfig = when (channel) {
                NoiseChannel.BOTH -> AudioFormat.CHANNEL_OUT_STEREO
                NoiseChannel.LEFT, NoiseChannel.RIGHT -> AudioFormat.CHANNEL_OUT_MONO
            }

            val audioFormat = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(channelConfig)
                .build()

            val minBufSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
            val bufSize = (minBufSize * 2).coerceAtLeast(sampleSize)

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            val isStereo = channel == NoiseChannel.BOTH
            val playLeft = channel == NoiseChannel.LEFT
            val playRight = channel == NoiseChannel.RIGHT
            val numSamples = bufSize / 2

            // Генерация и запуск pink noise в отдельном потоке
            noiseThread = Thread {
                val noiseBuffer = generatePinkNoise(numSamples, sampleRate)

                // Для моно-режима с одним каналом: заглушаем нужный канал через стерео-микс
                if (isStereo) {
                    // L+R: дублируем шум в оба канала
                    val stereoBuffer = ShortArray(numSamples * 2)
                    for (i in 0 until numSamples) {
                        stereoBuffer[i * 2] = noiseBuffer[i]     // L
                        stereoBuffer[i * 2 + 1] = noiseBuffer[i] // R
                    }
                    audioTrack?.play()
                    while (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        audioTrack?.write(stereoBuffer, 0, stereoBuffer.size)
                    }
                } else {
                    // L или R только: используем стерео-выход с заглушенным каналом
                    // Пересоздаём AudioTrack как стерео для возможности заглушить канал
                    audioTrack?.stop()
                    audioTrack?.release()

                    val stereoFormat = AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                    val stereoMinBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                    val stereoBufSize = (stereoMinBuf * 2).coerceAtLeast(sampleSize)

                    audioTrack = AudioTrack.Builder()
                        .setAudioAttributes(
                            android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build()
                        )
                        .setAudioFormat(stereoFormat)
                        .setBufferSizeInBytes(stereoBufSize)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()

                    val stereoBuffer = ShortArray(numSamples * 2)
                    for (i in 0 until numSamples) {
                        stereoBuffer[i * 2] = if (playLeft) noiseBuffer[i] else 0   // L
                        stereoBuffer[i * 2 + 1] = if (playRight) noiseBuffer[i] else 0 // R
                    }
                    audioTrack?.play()
                    while (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        audioTrack?.write(stereoBuffer, 0, stereoBuffer.size)
                    }
                }
            }
            noiseThread?.start()

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start pink noise playback", e)
            false
        }
    }

    private fun stopPlayback() {
        try {
            audioTrack?.stop()
        } catch (_: Exception) {}
        try {
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
        noiseThread = null
    }

    /**
     * Генерация розового шума (алгоритм Voss-McCartney).
     */
    private fun generatePinkNoise(numSamples: Int, @Suppress("UNUSED_PARAMETER") sampleRate: Int): ShortArray {
        val output = ShortArray(numSamples)
        val rng = java.util.Random()
        var b0 = 0.0; var b1 = 0.0; var b2 = 0.0; var b3 = 0.0
        var b4 = 0.0; var b5 = 0.0; var b6 = 0.0
        val gain = 0.11

        for (i in 0 until numSamples) {
            val white = rng.nextGaussian()
            b0 = 0.99886 * b0 + white * 0.0555179
            b1 = 0.99332 * b1 + white * 0.0750759
            b2 = 0.96900 * b2 + white * 0.1538520
            b3 = 0.86650 * b3 + white * 0.3104856
            b4 = 0.55000 * b4 + white * 0.5329522
            b5 = -0.7616 * b5 - white * 0.0168980
            val pink = b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362
            b6 = white * 0.115926

            output[i] = (pink * gain * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return output
    }

    // ── Запись с микрофона ──────────────────────────────────────────

    private fun startRecording(sampleRate: Int): Boolean {
        return try {
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
            val bufSize = (minBufSize * 2).coerceAtLeast(1024)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate, channelConfig,
                AudioFormat.ENCODING_PCM_16BIT, bufSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized")
                audioRecord?.release()
                audioRecord = null
                return false
            }

            audioRecord?.startRecording()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            false
        }
    }

    private fun stopRecording() {
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
    }

    // ── Системная громкость ─────────────────────────────────────────

    private fun getCurrentSystemVolumeDb(): Double {
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (maxVol <= 0) return 0.0
        if (curVol <= 0) return -60.0
        val ratio = curVol.toDouble() / maxVol.toDouble()
        return 20.0 * log10(ratio.coerceIn(0.001, 1.0))
    }

    companion object {
        private const val TAG = "LoudnessCalibration"
        private const val sampleSize = 4 // bytes per 16-bit mono sample
    }
}
