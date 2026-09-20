package me.timschneeberger.rootlessjamesdsp.measurement

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.min

/**
 * Тип микрофона для записи.
 *
 * - [BUILTIN_UNPROCESSED] — встроенный микрофон, обход Android AGC/NS/AEC (рекомендуется)
 * - [BUILTIN_MIC] — встроенный микрофон, стандартный источник MIC
 * - [BUILTIN_BACK] — задний микрофон (CAMCORDER / address="back"), если поддерживается
 * - [USB] — USB-микрофон (UMIK-1 и др.), автоматически выбирается если подключён
 */
enum class MicType {
    BUILTIN_UNPROCESSED,
    BUILTIN_MIC,
    BUILTIN_BACK,
    USB
}

/**
 * Изолированный модуль записи с микрофона для акустических измерений.
 *
 * Отличается от RootlessAudioProcessorService тем, что:
 *  - Использует AudioSource.UNPROCESSED (обход Android AGC/NS/AEC)
 *  - Mono (достаточно для измерений)
 *  - Не использует MediaProjection (нужен физический микрофон, не loopback)
 *  - Записывает фиксированную длительность (длительность sweep + запас)
 *  - Поддерживает выбор микрофона: передний, задний, USB
 *  - Стримит сэмплы в колбэк для визуализации в реальном времени
 *
 * @param sampleRate частота дискретизации (48000 рекомендуется)
 * @param durationSec длительность записи в секундах
 * @param micType тип микрофона (по умолчанию UNPROCESSED)
 * @param appContext контекст приложения для AudioManager (необязательно)
 * @param onSamples колбэк для стриминга сэмплов в визуализатор (опционально)
 */
class MeasurementAudioRecord(
    private val sampleRate: Int = 48000,
    private val durationSec: Float = 6.0f,
    private val micType: MicType = MicType.BUILTIN_UNPROCESSED,
    private val appContext: Context? = null,
    private val onSamples: ((FloatArray, Int, Int) -> Unit)? = null
) {
    private var audioRecord: AudioRecord? = null
    private var isRecording = false

    /**
     * Начать запись с микрофона.
     * Блокирует вызывающий поток до завершения записи.
     *
     * @return записанный PCM как FloatArray или null при ошибке
     */
    @SuppressLint("MissingPermission")
    fun record(): FloatArray? {
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_FLOAT

        val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = (sampleRate * durationSec).toInt().coerceAtLeast(minBufSize * 2)

        // Выбор AudioSource в зависимости от типа микрофона
        val source = when (micType) {
            MicType.BUILTIN_UNPROCESSED -> {
                if (hasUnprocessedSource()) MediaRecorder.AudioSource.UNPROCESSED
                else MediaRecorder.AudioSource.MIC
            }
            MicType.BUILTIN_MIC -> MediaRecorder.AudioSource.MIC
            MicType.BUILTIN_BACK -> {
                // CAMCORDER обычно направлен на задний микрофон
                MediaRecorder.AudioSource.CAMCORDER
            }
            MicType.USB -> {
                // Для USB лучше UNPROCESSED, fallback MIC
                if (hasUnprocessedSource()) MediaRecorder.AudioSource.UNPROCESSED
                else MediaRecorder.AudioSource.MIC
            }
        }

        try {
            audioRecord = AudioRecord(
                source,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized (source=$source, micType=$micType)")
                return null
            }

            // Маршрутизация на конкретное устройство ввода (API 23+)
            if (appContext != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                routeToPreferredDevice()
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "RECORD_AUDIO permission not granted", e)
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AudioRecord", e)
            return null
        }

        val totalSamples = (sampleRate * durationSec).toInt()
        val buffer = FloatArray(totalSamples)
        var samplesRead = 0

        isRecording = true
        audioRecord?.startRecording()

        try {
            while (isRecording && samplesRead < totalSamples) {
                val toRead = min(4096, totalSamples - samplesRead)
                val read = audioRecord?.read(buffer, samplesRead, toRead, AudioRecord.READ_BLOCKING) ?: -1

                if (read <= 0) {
                    Log.e(TAG, "AudioRecord.read failed: $read")
                    break
                }

                // Стримим сэмплы в визуализатор (каждый чанк)
                onSamples?.invoke(buffer, samplesRead, read)

                samplesRead += read
            }
        } finally {
            isRecording = false
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        }

        return if (samplesRead > 0) buffer.copyOf(samplesRead) else null
    }

    /** Остановить запись досрочно. */
    fun stop() {
        isRecording = false
    }

    /**
     * Переключить AudioRecord на предпочтительное устройство ввода через
     * setPreferredDevice (API 23+).
     *
     * - USB: ищет TYPE_USB_DEVICE / TYPE_USB_HEADSET
     * - BUILTIN_BACK: ищет TYPE_BUILTIN_MIC с address="back" или TYPE_BACK_MIC
     * - Остальные: default routing (не трогаем)
     */
    @SuppressLint("NewApi")
    private fun routeToPreferredDevice() {
        val am = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val devices = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val ar = audioRecord ?: return

        val target: AudioDeviceInfo? = when (micType) {
            MicType.USB -> devices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
            MicType.BUILTIN_BACK -> {
                // Ищем встроенный микрофон с address="back"
                devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC &&
                    it.address?.contains("back", ignoreCase = true) == true
                }
            }
            else -> null
        }

        if (target != null) {
            val ok = ar.setPreferredDevice(target)
            Log.i(TAG, "Route to ${target.productName} (type=${target.type}, addr=${target.address}): $ok")
        } else if (micType == MicType.USB) {
            Log.w(TAG, "USB mic requested but no USB input device found")
        } else if (micType == MicType.BUILTIN_BACK) {
            Log.w(TAG, "Back mic requested but not found, using default")
        }
    }

    /**
     * Проверить поддержку AudioSource.UNPROCESSED.
     */
    private fun hasUnprocessedSource(): Boolean {
        return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N
    }

    companion object {
        private const val TAG = "MeasurementAudioRecord"

        /**
         * Получить список доступных устройств ввода для отображения в UI.
         * Возвращает пары (тип, человекочитаемое имя).
         */
        @SuppressLint("NewApi")
        fun getAvailableMicTypes(context: Context): List<Pair<MicType, String>> {
            val result = mutableListOf<Pair<MicType, String>>()
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return result

            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) {
                result.add(MicType.BUILTIN_UNPROCESSED to "Встроенный микрофон")
                return result
            }

            val devices = am.getDevices(AudioManager.GET_DEVICES_INPUTS)

            // Встроенные микрофоны
            val builtInMics = devices.filter { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            val hasBack = builtInMics.any {
                it.address?.contains("back", ignoreCase = true) == true
            }

            if (hasUnprocessedSourceStatic()) {
                result.add(MicType.BUILTIN_UNPROCESSED to "Встроенный (UNPROCESSED)")
            }
            result.add(MicType.BUILTIN_MIC to "Встроенный микрофон")
            if (hasBack || devices.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC && it.address?.contains("back", ignoreCase = true) == true }) {
                result.add(MicType.BUILTIN_BACK to "Задний микрофон")
            }

            // USB микрофоны
            val usbDevices = devices.filter {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
            usbDevices.forEach { dev ->
                val name = dev.productName?.toString() ?: "USB микрофон"
                result.add(MicType.USB to name)
            }

            return result
        }

        private fun hasUnprocessedSourceStatic(): Boolean {
            return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N
        }
    }
}
