package me.timschneeberger.rootlessjamesdsp.measurement

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Export measurement debug data: all intermediate signals and results
 * for offline analysis and debugging of the measurement pipeline.
 *
 * Output directory structure:
 * measurement_debug_<timestamp>/
 * ├── stimulus.wav          — composite stimulus signal (sweep + probe)
 * ├── recording.wav         — raw microphone recording
 * ├── deconvolved_ir.wav    — IR before windowing/normalization
 * ├── processed_ir.wav      — IR after windowing/normalization
 * ├── frequency_response.csv — freq, spl_raw, spl_calibrated, spl_smoothed
 * ├── phase.csv             — freq, phase_degrees
 * └── settings.json         — measurement parameters
 */
object MeasurementDebugExporter {

    private const val TAG = "MeasurementDebugExporter"

    /**
     * All debug data captured during a measurement run.
     */
    data class DebugData(
        val stimulus: FloatArray? = null,
        val stimulusStereo: FloatArray? = null,
        val recording: FloatArray? = null,
        val deconvolvedIr: FloatArray? = null,
        val processedIr: FloatArray? = null,
        val frequencies: FloatArray? = null,
        val splRaw: FloatArray? = null,
        val splCalibrated: FloatArray? = null,
        val splSmoothed: FloatArray? = null,
        val phase: FloatArray? = null,
        val sampleRate: Int = 48000,
        val settings: Map<String, Any> = emptyMap()
    )

    /**
     * Export all debug data to a directory under the app's external files dir.
     * No special permissions required.
     *
     * @return the output directory, or null on failure
     */
    fun export(context: Context, data: DebugData): File? {
        val timestamp = System.currentTimeMillis()
        val dirName = "measurement_debug_$timestamp"
        val outputDir = File(context.getExternalFilesDir(null), dirName)
        if (!outputDir.mkdirs()) {
            Log.e(TAG, "Failed to create directory: ${outputDir.absolutePath}")
            return null
        }

        try {
            // stimulus.wav
            val stimulus = data.stimulusStereo ?: data.stimulus
            val isStereo = data.stimulusStereo != null
            if (stimulus != null && stimulus.isNotEmpty()) {
                writeWav(File(outputDir, "stimulus.wav"), stimulus, data.sampleRate, isStereo)
            }

            // recording.wav
            if (data.recording != null && data.recording.isNotEmpty()) {
                writeWav(File(outputDir, "recording.wav"), data.recording, data.sampleRate, false)
            }

            // deconvolved_ir.wav
            if (data.deconvolvedIr != null && data.deconvolvedIr.isNotEmpty()) {
                writeWav(File(outputDir, "deconvolved_ir.wav"), data.deconvolvedIr, data.sampleRate, false)
            }

            // processed_ir.wav
            if (data.processedIr != null && data.processedIr.isNotEmpty()) {
                writeWav(File(outputDir, "processed_ir.wav"), data.processedIr, data.sampleRate, false)
            }

            // frequency_response.csv
            if (data.frequencies != null && data.frequencies.isNotEmpty()) {
                writeFrequencyResponseCsv(File(outputDir, "frequency_response.csv"), data)
            }

            // phase.csv
            if (data.frequencies != null && data.phase != null &&
                data.frequencies.isNotEmpty() && data.phase.isNotEmpty()) {
                writePhaseCsv(File(outputDir, "phase.csv"), data)
            }

            // settings.json
            writeSettingsJson(File(outputDir, "settings.json"), data)

            Log.i(TAG, "Debug data exported to ${outputDir.absolutePath}")
            return outputDir
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            return null
        }
    }

    private fun writeWav(file: File, samples: FloatArray, sampleRate: Int, stereo: Boolean) {
        val channels = if (stereo) 2 else 1
        val dataSize = samples.size * 4
        val byteRate = sampleRate * channels * 4
        val blockAlign = channels * 4
        val chunkSize = 36 + dataSize

        FileOutputStream(file).use { fos ->
            fos.write("RIFF".toByteArray())
            writeInt(fos, chunkSize)
            fos.write("WAVE".toByteArray())
            fos.write("fmt ".toByteArray())
            writeInt(fos, 16)
            writeShort(fos, 3.toShort())       // IEEE float
            writeShort(fos, channels.toShort())
            writeInt(fos, sampleRate)
            writeInt(fos, byteRate)
            writeShort(fos, blockAlign.toShort())
            writeShort(fos, 32.toShort())      // bits per sample
            fos.write("data".toByteArray())
            writeInt(fos, dataSize)
            for (sample in samples) writeFloat(fos, sample)
        }
    }

    private fun writeFrequencyResponseCsv(file: File, data: DebugData) {
        FileOutputStream(file).use { fos ->
            fos.write("frequency_hz,spl_raw_db,spl_calibrated_db,spl_smoothed_db\n".toByteArray())
            val freqs = data.frequencies!!
            val splRaw = data.splRaw ?: FloatArray(freqs.size) { Float.NaN }
            val splCal = data.splCalibrated ?: FloatArray(freqs.size) { Float.NaN }
            val splSm = data.splSmoothed ?: FloatArray(freqs.size) { Float.NaN }
            val n = minOf(freqs.size, splRaw.size, splCal.size, splSm.size)
            for (i in 0 until n) {
                fos.write("$freqs[$i],$splRaw[$i],$splCal[$i],$splSm[$i]\n".toByteArray())
            }
        }
    }

    private fun writePhaseCsv(file: File, data: DebugData) {
        FileOutputStream(file).use { fos ->
            fos.write("frequency_hz,phase_degrees\n".toByteArray())
            val freqs = data.frequencies!!
            val phase = data.phase!!
            val n = minOf(freqs.size, phase.size)
            for (i in 0 until n) {
                fos.write("$freqs[$i],$phase[$i]\n".toByteArray())
            }
        }
    }

    private fun writeSettingsJson(file: File, data: DebugData) {
        val json = JSONObject()
        for ((key, value) in data.settings) {
            json.put(key, value)
        }
        FileOutputStream(file).use { fos ->
            fos.write(json.toString(2).toByteArray())
        }
    }

    private fun writeInt(fos: FileOutputStream, value: Int) {
        fos.write(value and 0xFF)
        fos.write((value shr 8) and 0xFF)
        fos.write((value shr 16) and 0xFF)
        fos.write((value shr 24) and 0xFF)
    }

    private fun writeShort(fos: FileOutputStream, value: Short) {
        fos.write(value.toInt() and 0xFF)
        fos.write((value.toInt() shr 8) and 0xFF)
    }

    private fun writeFloat(fos: FileOutputStream, value: Float) {
        writeInt(fos, java.lang.Float.floatToRawIntBits(value))
    }
}
