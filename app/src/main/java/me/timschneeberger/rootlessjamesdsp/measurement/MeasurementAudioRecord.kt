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
    BUILTIN_BOTTOM,
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
        val audioFormat = AudioFormat.ENCODING_PCM_FLOAT

        // Всегда пишем стерео — HAL отдаёт два микрофона (bottom=L, top=R).
        // Нужный канал выбираем после записи.
        val stereoConfig = AudioFormat.CHANNEL_IN_STEREO
        val minBufStereo = AudioRecord.getMinBufferSize(sampleRate, stereoConfig, audioFormat)
        val bufferSizeStereo = (sampleRate * durationSec * 2).toInt().coerceAtLeast(minBufStereo * 2)

        // Выбор AudioSource
        val source = when (micType) {
            MicType.BUILTIN_UNPROCESSED, MicType.BUILTIN_BOTTOM -> {
                if (hasUnprocessedSource()) MediaRecorder.AudioSource.UNPROCESSED
                else MediaRecorder.AudioSource.MIC
            }
            MicType.BUILTIN_MIC -> MediaRecorder.AudioSource.MIC
            MicType.BUILTIN_BACK -> {
                if (hasUnprocessedSource()) MediaRecorder.AudioSource.UNPROCESSED
                else MediaRecorder.AudioSource.CAMCORDER
            }
            MicType.USB -> {
                if (hasUnprocessedSource()) MediaRecorder.AudioSource.UNPROCESSED
                else MediaRecorder.AudioSource.MIC
            }
        }

        // Какой канал извлекать из стерео: BUILTIN_BACK → правый (top), остальные → левый (bottom)
        val selectRight = micType == MicType.BUILTIN_BACK

        var channelConfig = stereoConfig
        var bufferSize = bufferSizeStereo
        var isStereo = true

        try {
            audioRecord = AudioRecord(source, sampleRate, channelConfig, audioFormat, bufferSize)

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                // Fallback: mono
                Log.w(TAG, "Stereo init failed, falling back to mono")
                channelConfig = AudioFormat.CHANNEL_IN_MONO
                isStereo = false
                val minBufMono = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                bufferSize = (sampleRate * durationSec).toInt().coerceAtLeast(minBufMono * 2)
                audioRecord = AudioRecord(source, sampleRate, channelConfig, audioFormat, bufferSize)
            }

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized (source=$source, micType=$micType)")
                return null
            }

            Log.i(TAG, "Recording: source=$source, stereo=$isStereo, selectRight=$selectRight, bufSize=$bufferSize")

        } catch (e: SecurityException) {
            Log.e(TAG, "RECORD_AUDIO permission not granted", e)
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AudioRecord", e)
            return null
        }

        val totalMonoSamples = (sampleRate * durationSec).toInt()
        val monoBuffer = FloatArray(totalMonoSamples)
        var monoWritten = 0

        isRecording = true
        audioRecord?.startRecording()

        try {
            if (isStereo) {
                // Читаем interleaved стерео, извлекаем нужный канал
                val stereoChunk = FloatArray(8192) // 4096 frames * 2 channels
                while (isRecording && monoWritten < totalMonoSamples) {
                    val framesWanted = min(4096, totalMonoSamples - monoWritten)
                    val samplesWanted = framesWanted * 2
                    val read = audioRecord?.read(stereoChunk, 0, samplesWanted, AudioRecord.READ_BLOCKING) ?: -1

                    if (read <= 0) {
                        Log.e(TAG, "AudioRecord.read failed: $read")
                        break
                    }

                    val framesRead = read / 2
                    // Deinterleave: извлекаем выбранный канал
                    val monoChunk = FloatArray(framesRead)
                    for (i in 0 until framesRead) {
                        monoChunk[i] = if (selectRight) stereoChunk[i * 2 + 1] else stereoChunk[i * 2]
                    }

                    // Копируем в выходной буфер
                    val copyLen = min(framesRead, totalMonoSamples - monoWritten)
                    System.arraycopy(monoChunk, 0, monoBuffer, monoWritten, copyLen)

                    // Стримим в визуализатор
                    onSamples?.invoke(monoChunk, 0, framesRead)

                    monoWritten += copyLen
                }
            } else {
                // Mono fallback
                while (isRecording && monoWritten < totalMonoSamples) {
                    val toRead = min(4096, totalMonoSamples - monoWritten)
                    val read = audioRecord?.read(monoBuffer, monoWritten, toRead, AudioRecord.READ_BLOCKING) ?: -1

                    if (read <= 0) {
                        Log.e(TAG, "AudioRecord.read failed: $read")
                        break
                    }

                    onSamples?.invoke(monoBuffer, monoWritten, read)
                    monoWritten += read
                }
            }
        } finally {
            isRecording = false
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        }

        Log.i(TAG, "Recorded $monoWritten mono samples (from ${if (isStereo) "stereo" else "mono"})")
        return if (monoWritten > 0) monoBuffer.copyOf(monoWritten) else null
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

        // Все встроенные типы маршрутизируем на конкретный физический микрофон по address.
        // Qualcomm HAL по умолчанию смешивает верхний + нижний в моно — setPreferredDevice
        // принудительно выбирает один.
        val target: AudioDeviceInfo? = when (micType) {
            MicType.USB -> devices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
            MicType.BUILTIN_BACK -> devices.firstOrNull {
                (it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) &&
                it.address?.contains("back", ignoreCase = true) == true
            }
            MicType.BUILTIN_BOTTOM -> devices.firstOrNull {
                (it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) &&
                it.address?.contains("bottom", ignoreCase = true) == true
            }
            // Для BUILTIN_UNPROCESSED и BUILTIN_MIC — выбираем нижний (основной) микрофон
            MicType.BUILTIN_UNPROCESSED, MicType.BUILTIN_MIC -> devices.firstOrNull {
                (it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) &&
                (it.address?.contains("bottom", ignoreCase = true) == true ||
                 it.address.isNullOrBlank())
            }
        }

        if (target != null) {
            val ok = ar.setPreferredDevice(target)
            Log.i(TAG, "Route to ${target.productName} (type=${target.type}, addr=${target.address}): $ok")
        } else {
            Log.w(TAG, "No specific input device found for micType=$micType, using default (HAL may mix mics)")
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
                result.add(MicType.BUILTIN_UNPROCESSED to "Built-in microphone")
                return result
            }

            val devices = am.getDevices(AudioManager.GET_DEVICES_INPUTS)

            // Встроенные микрофоны
            val builtInMics = devices.filter { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            val hasBack = builtInMics.any {
                it.address?.contains("back", ignoreCase = true) == true
            }

            if (hasUnprocessedSourceStatic()) {
                result.add(MicType.BUILTIN_UNPROCESSED to "Built-in microphone — Bottom (Raw / Unprocessed)")
            }
            result.add(MicType.BUILTIN_MIC to "Built-in microphone — Bottom (Standard)")
            if (hasBack || devices.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC && it.address?.contains("back", ignoreCase = true) == true }) {
                result.add(MicType.BUILTIN_BACK to "Built-in microphone — Top / Rear (Raw / Unprocessed)")
            }
            // Нижний микрофон (явный выбор)
            val hasBottom = builtInMics.any {
                it.address?.contains("bottom", ignoreCase = true) == true
            }
            if (hasBottom) {
                result.add(MicType.BUILTIN_BOTTOM to "Built-in microphone — Bottom (Explicit)")
            }

            // USB микрофоны
            val usbDevices = devices.filter {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
            usbDevices.forEach { dev ->
                val name = dev.productName?.toString()?.ifBlank { "USB microphone" } ?: "USB microphone"
                result.add(MicType.USB to name)
            }

            return result
        }

        private fun hasUnprocessedSourceStatic(): Boolean {
            return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N
        }
    }
}
