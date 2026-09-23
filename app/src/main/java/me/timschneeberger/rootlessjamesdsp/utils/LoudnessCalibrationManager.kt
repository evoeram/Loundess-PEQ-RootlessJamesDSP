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
 * Процесс калибровки:
 * 1. Генерируется розовый шум и воспроизводится через AudioTrack на текущей громкости.
 * 2. Параллельно записывается сигнал с микрофона через AudioRecord.
 * 3. Вычисляется RMS записанного сигнала → переводится в dB SPL (относительно full-scale).
 * 4. Измеренный SPL и текущая системная громкость (dB) используются для вычисления:
 *    - referenceLevel = измеренный SPL (dB) — уровень, при котором АЧХ flat
 *    - referenceOffset = referenceLevel - текущая громкость (dB) — связывает SPL с системной громкостью
 *
 * В runtime при изменении громкости:
 *    volDiff = referenceLevel - referenceOffset - currentVolume
 *    → volDiff > 0: громкость ниже референсной → boost НЧ/ВЧ (Fletcher-Munson)
 *    → volDiff < 0: громкость выше референсной → cut НЧ/ВЧ
 *
 * Точность зависит от микрофона. Для абсолютного SPL требуется калибровочный файл микрофона.
 * Без калибровки измеряется относительный SPL (dBFS), что достаточно для loudness-коррекции.
 */
class LoudnessCalibrationManager(
    private val context: Context,
) {
    /** Результат калибровки. */
    data class CalibrationResult(
        val measuredSplDb: Double,      // Измеренный SPL (dBFS или dB SPL с калибровкой)
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

    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    /**
     * Запустить калибровку.
     *
     * @param durationSec длительность измерения в секундах (по умолчанию 5)
     * @param sampleRate частота дискретизации (по умолчанию 48000)
     */
    fun start(durationSec: Int = 5, sampleRate: Int = 48000) {
        if (calibrationJob?.isActive == true) {
            Log.w(TAG, "Calibration already in progress")
            return
        }

        calibrationJob = CoroutineScope(Dispatchers.Default).launch {
            val result = runCalibration(durationSec, sampleRate)
            withContext(Dispatchers.Main) {
                onComplete?.invoke(result)
            }
        }
    }

    /** Отменить калибровку. */
    fun cancel() {
        calibrationJob?.cancel()
        stopPlayback()
        stopRecording()
    }

    /**
     * Основная логика калибровки.
     */
    private suspend fun runCalibration(
        durationSec: Int,
        sampleRate: Int,
    ): CalibrationResult = withContext(Dispatchers.Default) {
        val totalSamples = sampleRate * durationSec
        val chunkSize = 1024
        var recordedSamples = 0
        var sumSquares = 0.0
        var maxAmplitude = 0.0f

        try {
            // --- Запуск воспроизведения розового шума ---
            val trackResult = startPinkNoisePlayback(sampleRate)
            if (!trackResult) {
                return@withContext CalibrationResult(
                    measuredSplDb = 0.0,
                    systemVolumeDb = 0.0,
                    referenceLevel = 0.0,
                    referenceOffset = 0.0,
                    success = false,
                    errorMessage = "Не удалось запустить воспроизведение розового шума",
                )
            }

            // Небольшая задержка для стабилизации воспроизведения
            Thread.sleep(200)

            // --- Запуск записи с микрофона ---
            val recordResult = startRecording(sampleRate)
            if (!recordResult) {
                stopPlayback()
                return@withContext CalibrationResult(
                    measuredSplDb = 0.0,
                    systemVolumeDb = 0.0,
                    referenceLevel = 0.0,
                    referenceOffset = 0.0,
                    success = false,
                    errorMessage = "Не удалось получить доступ к микрофону",
                )
            }

            // --- Цикл записи и вычисления RMS ---
            val buffer = ShortArray(chunkSize)
            while (recordedSamples < totalSamples && isActive) {
                val read = audioRecord?.read(buffer, 0, chunkSize) ?: -1
                if (read <= 0) break

                for (i in 0 until read) {
                    val sample = buffer[i].toFloat() / Short.MAX_VALUE
                    sumSquares += (sample * sample).toDouble()
                    val absSample = kotlin.math.abs(sample)
                    if (absSample > maxAmplitude) maxAmplitude = absSample
                }
                recordedSamples += read

                val progress = recordedSamples.toFloat() / totalSamples
                withContext(Dispatchers.Main) { onProgress?.invoke(progress) }
            }

            stopPlayback()
            stopRecording()

            if (recordedSamples == 0) {
                return@withContext CalibrationResult(
                    measuredSplDb = 0.0,
                    systemVolumeDb = 0.0,
                    referenceLevel = 0.0,
                    referenceOffset = 0.0,
                    success = false,
                    errorMessage = "Не удалось записать ни одного сэмпла",
                )
            }

            // --- Вычисление RMS и SPL ---
            val rms = sqrt(sumSquares / recordedSamples)
            val measuredSplDb = if (rms > 0.0) 20.0 * log10(rms) else -120.0

            // --- Получение текущей системной громкости в dB ---
            val systemVolumeDb = getCurrentSystemVolumeDb()

            // --- Вычисление referenceLevel и referenceOffset ---
            // referenceLevel = измеренный SPL (точка flat АЧХ)
            // referenceOffset = referenceLevel - systemVolumeDb
            //   → связывает измеренный SPL с позицией системной громкости
            //   → volDiff = referenceLevel - referenceOffset - volume = measuredSpl - (measuredSpl - sysVol) - volume
            //              = sysVol - volume
            //   При калибровке: volDiff = sysVol - sysVol = 0 (flat)
            //   При уменьшении громкости: volDiff > 0 → boost НЧ/ВЧ
            val referenceLevel = measuredSplDb
            val referenceOffset = measuredSplDb - systemVolumeDb

            Log.i(TAG, "Calibration complete: SPL=%.1f dBFS, sysVol=%.1f dB, refLevel=%.1f, refOffset=%.1f"
                .format(measuredSplDb, systemVolumeDb, referenceLevel, referenceOffset))

            CalibrationResult(
                measuredSplDb = measuredSplDb,
                systemVolumeDb = systemVolumeDb,
                referenceLevel = referenceLevel,
                referenceOffset = referenceOffset,
                success = true,
            )

        } catch (e: Exception) {
            Log.e(TAG, "Calibration failed", e)
            stopPlayback()
            stopRecording()
            CalibrationResult(
                measuredSplDb = 0.0,
                systemVolumeDb = 0.0,
                referenceLevel = 0.0,
                referenceOffset = 0.0,
                success = false,
                errorMessage = e.message ?: "Unknown error",
            )
        }
    }

    // ── Воспроизведение розового шума ──────────────────────────────

    /**
     * Запустить воспроизведение розового шума через AudioTrack.
     * Используется алгоритм Voss-McCartney для генерации pink noise.
     *
     * @return true если запуск успешен
     */
    private fun startPinkNoisePlayback(sampleRate: Int): Boolean {
        return try {
            val channelConfig = AudioFormat.CHANNEL_OUT_MONO
            val audioFormat = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(channelConfig)
                .build()

            val minBufSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
            val bufSize = (minBufSize * 2).coerceAtLeast(sampleRate) // минимум 1 секунда

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

            // Генерация и запуск pink noise в отдельном потоке
            val noiseBuffer = generatePinkNoise(bufSize / 2, sampleRate)
            audioTrack?.play()
            audioTrack?.write(noiseBuffer, 0, noiseBuffer.size)

            // Постоянная подача шума в цикле
            Thread {
                val loopBuffer = generatePinkNoise(bufSize / 2, sampleRate)
                while (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    audioTrack?.write(loopBuffer, 0, loopBuffer.size)
                }
            }.start()

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start pink noise playback", e)
            false
        }
    }

    private fun stopPlayback() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping playback", e)
        }
        audioTrack = null
    }

    /**
     * Генерация розового шума (алгоритм Voss-McCartney).
     *
     * @param numSamples количество сэмплов
     * @param sampleRate частота дискретизации (не используется, но для совместимости)
     * @return массив 16-bit PCM сэмплов
     */
    private fun generatePinkNoise(numSamples: Int, @Suppress("UNUSED_PARAMETER") sampleRate: Int): ShortArray {
        val output = ShortArray(numSamples)
        // Voss-McCartney: 7 октавных генераторов
        val rows = 7
        val rng = java.util.Random()
        var b0 = 0.0
        var b1 = 0.0
        var b2 = 0.0
        var b3 = 0.0
        var b4 = 0.0
        var b5 = 0.0
        var b6 = 0.0

        // Нормализующий коэффициент: pink noise имеет RMS ~ 0.37 от white noise
        val gain = 0.11 // эмпирически подобранный gain для RMS ~ -12 dBFS

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

            val sample = (pink * gain * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            output[i] = sample.toShort()
        }
        return output
    }

    // ── Запись с микрофона ──────────────────────────────────────────

    /**
     * Запустить запись с микрофона через AudioRecord.
     *
     * @return true если запуск успешен
     */
    private fun startRecording(sampleRate: Int): Boolean {
        return try {
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(channelConfig)
                .build()

            val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
            val bufSize = (minBufSize * 2).coerceAtLeast(1024)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
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
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping recording", e)
        }
        audioRecord = null
    }

    // ── Системная громкость ─────────────────────────────────────────

    /**
     * Получить текущую системную громкость MEDIA в dB.
     * Использует тот же метод, что и JamesDspBaseEngine.getCurrentMediaVolumeDb().
     */
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
    }
}
