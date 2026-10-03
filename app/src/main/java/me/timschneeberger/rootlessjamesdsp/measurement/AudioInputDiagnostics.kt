package me.timschneeberger.rootlessjamesdsp.measurement

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build

/**
 * Diagnostic probe for the actual AudioRecord capture path.
 *
 * Queries what the audio HAL actually provides when we request UNPROCESSED/MIC:
 *  - Effective source
 *  - Sample rate / channels / format
 *  - AGC, NS, AEC availability (via AcousticEchoCanceler, NoiseSuppressor, AutomaticGainControl)
 *  - Input device info
 *
 * The presence of an API flag does NOT guarantee the vendor HAL actually disables
 * processing — this probe reports what's queryable so the user knows what they get.
 */
object AudioInputDiagnostics {

    data class Info(
        val sourceName: String,
        val sampleRate: Int,
        val channels: String,
        val format: String,
        val agcAvailable: Boolean,
        val agcEnabled: Boolean,
        val nsAvailable: Boolean,
        val nsEnabled: Boolean,
        val aecAvailable: Boolean,
        val aecEnabled: Boolean,
        val inputProcessing: String,
        val deviceName: String,
        val deviceType: String,
        val bufferSize: Int,
        val minBufferSize: Int,
    )

    @SuppressLint("MissingPermission")
    fun probe(context: Context, micType: MicType): Info {
        val source = when (micType) {
            MicType.BUILTIN_UNPROCESSED -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) MediaRecorder.AudioSource.UNPROCESSED
                else MediaRecorder.AudioSource.MIC
            }
            MicType.BUILTIN_MIC -> MediaRecorder.AudioSource.MIC
            MicType.BUILTIN_BACK -> MediaRecorder.AudioSource.UNPROCESSED
            MicType.BUILTIN_BOTTOM -> MediaRecorder.AudioSource.UNPROCESSED
            MicType.USB -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) MediaRecorder.AudioSource.UNPROCESSED
                else MediaRecorder.AudioSource.MIC
            }
        }

        val sampleRate = 48000
        val audioFormat = AudioFormat.ENCODING_PCM_FLOAT

        // Пытаемся стерео — HAL отдаёт два микрофона
        var channelConfig = AudioFormat.CHANNEL_IN_STEREO
        var minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        var bufSize = minBuf.coerceAtLeast(2048) * 2
        var isStereo = true

        var record: AudioRecord? = null
        try {
            record = AudioRecord(source, sampleRate, channelConfig, audioFormat, bufSize)
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                channelConfig = AudioFormat.CHANNEL_IN_MONO
                isStereo = false
                minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                bufSize = minBuf.coerceAtLeast(2048)
                record = AudioRecord(source, sampleRate, channelConfig, audioFormat, bufSize)
            }
        } catch (_: Exception) {
            isStereo = false
        }

        val sourceName = when (source) {
            MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
            MediaRecorder.AudioSource.MIC -> "MIC"
            MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
            else -> "OTHER ($source)"
        }

        // Probe processing APIs
        val aecAvailable = android.media.audiofx.AcousticEchoCanceler.isAvailable()
        val nsAvailable = android.media.audiofx.NoiseSuppressor.isAvailable()
        val agcAvailable = android.media.audiofx.AutomaticGainControl.isAvailable()

        var agcEnabled = false
        var nsEnabled = false
        var aecEnabled = false

        var aec: android.media.audiofx.AcousticEchoCanceler? = null
        var ns: android.media.audiofx.NoiseSuppressor? = null
        var agc: android.media.audiofx.AutomaticGainControl? = null

        try {
            if (record != null && record.state == AudioRecord.STATE_INITIALIZED) {
                // Attach effects to check if they can be created on this audio session
                if (aecAvailable) {
                    aec = android.media.audiofx.AcousticEchoCanceler.create(record.audioSessionId)
                    aecEnabled = aec?.enabled ?: false
                }
                if (nsAvailable) {
                    ns = android.media.audiofx.NoiseSuppressor.create(record.audioSessionId)
                    nsEnabled = ns?.enabled ?: false
                }
                if (agcAvailable) {
                    agc = android.media.audiofx.AutomaticGainControl.create(record.audioSessionId)
                    agcEnabled = agc?.enabled ?: false
                }
            }
        } catch (_: Exception) {
            // ignore
        } finally {
            agc?.release()
            ns?.release()
            aec?.release()
            record?.release()
        }

        // Determine input processing status
        val anyAvailable = aecAvailable || nsAvailable || agcAvailable
        val anyEnabled = aecEnabled || nsEnabled || agcEnabled
        val inputProcessing = when {
            !anyAvailable -> "None (effects not available)"
            anyAvailable && !anyEnabled -> "Available but not active"
            anyEnabled -> "ACTIVE — vendor HAL may apply processing"
            else -> "UNKNOWN"
        }

        // Input device info
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val devices: Array<AudioDeviceInfo> = am?.getDevices(AudioManager.GET_DEVICES_INPUTS)
            ?: emptyArray()
        val inputDevice = when (micType) {
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
            else -> devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        }

        val deviceName = inputDevice?.productName?.toString()?.ifBlank { "Built-in" } ?: "Built-in"
        val deviceType = when (inputDevice?.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in microphone"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB device"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
            else -> "Unknown (${inputDevice?.type ?: "null"})"
        }

        return Info(
            sourceName = sourceName,
            sampleRate = sampleRate,
            channels = if (isStereo) "2 (Stereo → extracting 1 channel)" else "1 (Mono)",
            format = "PCM 32-bit float",
            agcAvailable = agcAvailable,
            agcEnabled = agcEnabled,
            nsAvailable = nsAvailable,
            nsEnabled = nsEnabled,
            aecAvailable = aecAvailable,
            aecEnabled = aecEnabled,
            inputProcessing = inputProcessing,
            deviceName = deviceName,
            deviceType = deviceType,
            bufferSize = bufSize,
            minBufferSize = minBuf,
        )
    }

    fun formatReport(info: Info): String {
        val lines = mutableListOf<String>()
        lines.add("MEOW Audio Input")
        lines.add("")
        lines.add("Source:            ${info.sourceName}")
        lines.add("Device:            ${info.deviceName}")
        lines.add("Device type:       ${info.deviceType}")
        lines.add("Sample rate:       ${String.format("%,d", info.sampleRate)} Hz")
        lines.add("Channels:          ${info.channels}")
        lines.add("Format:            ${info.format}")
        lines.add("Buffer size:       ${String.format("%,d", info.bufferSize)} samples")
        lines.add("Min buffer:        ${String.format("%,d", info.minBufferSize)} samples")
        lines.add("")
        lines.add("AGC:               ${statusStr(info.agcAvailable, info.agcEnabled)}")
        lines.add("Noise suppression: ${statusStr(info.nsAvailable, info.nsEnabled)}")
        lines.add("Echo cancellation: ${statusStr(info.aecAvailable, info.aecEnabled)}")
        lines.add("Input processing:  ${info.inputProcessing}")
        return lines.joinToString("\n")
    }

    private fun statusStr(available: Boolean, enabled: Boolean): String {
        return when {
            !available -> "OFF (not available)"
            available && !enabled -> "OFF"
            enabled -> "ON ⚠"
            else -> "UNKNOWN"
        }
    }
}
