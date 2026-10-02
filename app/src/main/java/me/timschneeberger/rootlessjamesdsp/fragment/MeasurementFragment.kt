package me.timschneeberger.rootlessjamesdsp.fragment

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.view.MicLevelView
import me.timschneeberger.rootlessjamesdsp.view.WaveformView
import me.timschneeberger.rootlessjamesdsp.databinding.FragmentMeasurementBinding
import me.timschneeberger.rootlessjamesdsp.measurement.AudioInputDiagnostics
import me.timschneeberger.rootlessjamesdsp.measurement.AutoEqEngine
import me.timschneeberger.rootlessjamesdsp.measurement.DeconvConfig
import me.timschneeberger.rootlessjamesdsp.measurement.MeasurementDebugExporter
import me.timschneeberger.rootlessjamesdsp.measurement.IrWindowMode
import me.timschneeberger.rootlessjamesdsp.measurement.MeasurementMode
import me.timschneeberger.rootlessjamesdsp.measurement.MeasurementSession
import me.timschneeberger.rootlessjamesdsp.measurement.MicCalibrationLoader
import me.timschneeberger.rootlessjamesdsp.measurement.MicType
import me.timschneeberger.rootlessjamesdsp.measurement.SmoothingType
import me.timschneeberger.rootlessjamesdsp.measurement.TargetCurve
import me.timschneeberger.rootlessjamesdsp.measurement.TargetType
import me.timschneeberger.rootlessjamesdsp.measurement.MeasurementAudioRecord
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqBand
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqBandList
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqChannel
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqFilterType
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.toast
import timber.log.Timber
import java.io.File

/**
 * Fragment for acoustic measurement and Auto-EQ.
 *
 * Orchestrates the full measurement pipeline through [MeasurementSession]:
 *  1. Plays a log sweep through the speaker/headphones
 *  2. Records the microphone response
 *  3. Deconvolves to extract the impulse response
 *  4. Computes SPL frequency response
 *  5. Applies microphone calibration (optional)
 *  6. Smooths the response
 *  7. Runs the Auto-EQ engine to generate PEQ bands
 *  8. Optionally applies the result to the parametric EQ
 *
 * The graph shows three layers:
 *  - Measured SPL (green dashed)
 *  - Target curve (orange)
 *  - Filter response (accent color, L+R)
 */
class MeasurementFragment : Fragment() {

    private lateinit var binding: FragmentMeasurementBinding

    private var measurementSession: MeasurementSession? = null
    private var lastResult: MeasurementSession.MeasurementResult? = null

    // Continuous mic monitor (always running while fragment is alive)
    private var micMonitor: MeasurementAudioRecord? = null
    private var micMonitorThread: Thread? = null
    @Volatile private var micMonitorRunning = false

    private var targetCurve: TargetCurve = TargetCurve.flat()
    private var micCalibration: MicCalibrationLoader.CalibrationData? = null
    private var autoEqConfig: AutoEqEngine.Config = AutoEqEngine.Config()

    // Выбранный тип микрофона
    private var selectedMicType: MicType = MicType.BUILTIN_UNPROCESSED

    // Выбранный режим измерения каналов
    private var selectedMode: MeasurementMode = MeasurementMode.BOTH

    // Выбранный тип сглаживания (по умолчанию — psychoacoustic)
    private var selectedSmoothing: SmoothingType = SmoothingType.PSYCHOACOUSTIC
    private var currentDeconvConfig: DeconvConfig = DeconvConfig()
    private var currentAverageCount: Int = 1

    // Режим отображения L/R: абсолютный (две кривые) или относительный (L−R разница)
    private var lrRelativeMode = false

    // Layer visibility toggles
    private var showMeasured = true
    private var showTarget = true
    private var showFilter = true
    private var showPredicted = false
    private var showPhase = false

    // Target type preset
    private var targetType: TargetType = TargetType.FULL_RANGE

    // Export filters launcher
    private val exportFiltersLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        uri ?: return@registerForActivityResult
        val result = lastResult ?: return@registerForActivityResult
        val (bands, preamp) = if (lrRelativeMode && result.autoEqBandsDiff.isNotEmpty()) {
            result.autoEqBandsDiff to result.autoEqPreampDiff
        } else {
            result.autoEqBands to result.autoEqPreamp
        }
        if (bands.isEmpty()) return@registerForActivityResult
        val bandList = ParametricEqBandList()
        bandList.addAll(bands)
        val apoText = bandList.toApoString(preamp)
        try {
            requireContext().contentResolver.openOutputStream(uri)?.use { output ->
                output.write(apoText.toByteArray())
            }
            requireContext().toast("EQ exported (${bands.size} bands)")
        } catch (e: Exception) {
            Timber.e(e, "Failed to export filters")
            requireContext().toast("Export failed")
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startMeasurement()
        } else {
            requireContext().toast("Microphone permission required")
        }
    }

    private val micCalLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        try {
            val file = File(requireContext().cacheDir, "mic_cal_temp.cal")
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            val cal = MicCalibrationLoader().load(file)
            if (cal != null) {
                micCalibration = cal
                updateMicCalChip()
                requireContext().toast(getString(
                    R.string.mic_calibration_loaded, file.name, cal.frequencies.size))
            } else {
                requireContext().toast(R.string.mic_calibration_load_failed)
            }
            file.delete()
        } catch (e: Exception) {
            Timber.e(e, "Failed to load mic calibration")
            requireContext().toast(R.string.mic_calibration_load_failed)
        }
    }

    private val saveIrLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("audio/wav")
    ) { uri ->
        uri ?: return@registerForActivityResult
        val ir = lastResult?.ir ?: return@registerForActivityResult
        try {
            requireContext().contentResolver.openOutputStream(uri)?.use { output ->
                val session = measurementSession ?: return@use
                // Write WAV to a temp file, then copy
                val tempFile = File(requireContext().cacheDir, "ir_temp.wav")
                if (session.saveIrAsWav(ir, tempFile)) {
                    tempFile.inputStream().use { input -> input.copyTo(output) }
                    tempFile.delete()
                }
            }
            requireContext().toast(getString(R.string.measurement_save_ir_success, uri.lastPathSegment ?: "file"))
        } catch (e: Exception) {
            Timber.e(e, "Failed to save IR")
            requireContext().toast(R.string.measurement_save_ir_failed)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentMeasurementBinding.inflate(layoutInflater, container, false)

        // Start / Stop
        binding.chipStart.setOnClickListener {
            if (hasMicPermission()) {
                startMeasurement()
            } else {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        binding.chipStop.setOnClickListener {
            measurementSession?.cancel()
            binding.chipStop.isVisible = false
            binding.chipStart.isVisible = true
        }

        // Target curve
        binding.chipTargetCurve.setOnClickListener {
            showTargetCurveSelector()
        }

        // Mic calibration
        binding.chipMicCal.setOnClickListener {
            micCalLauncher.launch(arrayOf("*/*"))
        }

        // Audio input info (diagnostic)
        binding.chipAudioInfo.setOnClickListener {
            showAudioInputInfo()
        }

        // IR settings (normalize, windows, length, averaging)
        binding.chipIrSettings.setOnClickListener {
            showIrSettings()
        }

        // Microphone selection
        binding.chipMicSelect.setOnClickListener {
            showMicSelector()
        }

        // Measurement mode selection (L / R / L+R)
        binding.chipModeSelect.setOnClickListener {
            showModeSelector()
        }

        // Smoothing type selection
        binding.chipSmoothingSelect.setOnClickListener {
            showSmoothingSelector()
        }

        // L/R display mode: absolute vs relative (toggle)
        binding.chipLrMode.setOnClickListener {
            lrRelativeMode = !lrRelativeMode
            binding.chipLrMode.text = if (lrRelativeMode) "L−R" else "Abs"
            lastResult?.let { displayMeasurementOnGraph(it) }
        }

        // Auto-EQ config
        binding.chipAutoeqConfig.setOnClickListener {
            showAutoEqConfigDialog()
        }

        // Apply to PEQ
        binding.chipApply.setOnClickListener {
            applyAutoEqToPeq()
        }

        // Save IR
        binding.chipSaveIr.setOnClickListener {
            saveIrLauncher.launch("impulse_response.wav")
        }

        // Export filters as text
        binding.chipExportFilters.setOnClickListener {
            exportFiltersLauncher.launch("autoeq_filters.txt")
        }

        // Target type selector
        binding.chipTargetType.setOnClickListener {
            showTargetTypeSelector()
        }

        // THD info
        binding.chipThdInfo.setOnClickListener {
            showThdInfo()
        }

        // RT60 info
        binding.chipRt60Info.setOnClickListener {
            showRt60Info()
        }

        // Export debug data
        binding.chipExportDebug.setOnClickListener {
            exportDebugData()
        }

        // Layer toggles
        binding.toggleMeasured.isChecked = showMeasured
        binding.toggleTarget.isChecked = showTarget
        binding.toggleFilter.isChecked = showFilter
        binding.togglePredicted.isChecked = showPredicted
        binding.togglePhase.isChecked = showPhase

        binding.layerToggle.addOnButtonCheckedListener { _: MaterialButtonToggleGroup, buttonId: Int, isChecked: Boolean ->
            when (buttonId) {
                R.id.toggle_measured -> {
                    showMeasured = isChecked
                    updateGraphLayers()
                }
                R.id.toggle_target -> {
                    showTarget = isChecked
                    updateGraphLayers()
                }
                R.id.toggle_filter -> {
                    showFilter = isChecked
                    updateGraphLayers()
                }
                R.id.toggle_predicted -> {
                    showPredicted = isChecked
                    updateGraphLayers()
                }
                R.id.toggle_phase -> {
                    showPhase = isChecked
                    updateGraphLayers()
                }
            }
        }

        updateStatusText(getString(R.string.measurement_warning))
        return binding.root
    }

    private fun startMeasurement() {
        if (measurementSession == null) {
            measurementSession = MeasurementSession(
                context = requireContext(),
                micType = selectedMicType,
                mode = selectedMode,
                smoothingType = selectedSmoothing,
                deconvConfig = currentDeconvConfig,
                averageCount = currentAverageCount,
                onInputSamples = { samples, offset, length ->
                    // Capture activity reference on the calling thread to avoid
                    // requireActivity() throwing IllegalStateException when the
                    // fragment is detached mid-measurement (the recording runs
                    // on a raw Thread that is NOT cancelled by lifecycleScope).
                    val act = activity
                    if (act != null) {
                        // Compute RMS → dBFS on the background thread (cheap)
                        var sumSq = 0.0
                        for (i in offset until offset + length) {
                            sumSq += samples[i].toDouble() * samples[i].toDouble()
                        }
                        val rms = Math.sqrt(sumSq / length)
                        val dbFs = if (rms > 1e-10) 20.0 * Math.log10(rms) else -100.0
                        val isClipping = samples.any { kotlin.math.abs(it) >= 0.99f }

                        // All UI access on the main thread, guarded by isAdded
                        act.runOnUiThread {
                            if (!isAdded) return@runOnUiThread
                            binding.waveformView.updateSamples(samples, offset, length)
                            binding.micLevelView.setLevelDb(dbFs.toFloat(), isClipping)
                            if (isClipping) {
                                binding.inputLevelText.text = getString(R.string.measurement_input_clipping)
                                binding.inputLevelText.setTextColor(0xFFFF5252.toInt())
                            } else {
                                binding.inputLevelText.text = getString(R.string.measurement_input_level, dbFs)
                                binding.inputLevelText.setTextColor(0xFF4CAF50.toInt())
                            }
                        }
                    }
                }
            )
        }

        binding.chipStart.isVisible = false
        binding.chipStop.isVisible = true
        binding.progressBar.isVisible = true
        binding.resultText.isVisible = false
        binding.chipApply.isVisible = false
        binding.chipSaveIr.isVisible = false
        binding.inputLevelText.text = getString(R.string.measurement_input_recording)

        // Stop continuous monitor during measurement (avoids dual AudioRecord)
        stopMicMonitor()

        updateStatusText(getString(R.string.measurement_in_progress))

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                measurementSession?.runMeasurement(
                    target = targetCurve,
                    autoEqConfig = autoEqConfig,
                    micCalibration = micCalibration,
                    applyAutoEq = true
                )
            }

            binding.progressBar.isVisible = false
            binding.chipStop.isVisible = false
            binding.chipStart.isVisible = true
            binding.waveformView.clear()
            binding.inputLevelText.text = getString(R.string.measurement_input_idle)
            binding.inputLevelText.setTextColor(0xFF888888.toInt())

            // Resume continuous mic monitor after measurement
            startMicMonitor()

            if (result?.success == true) {
                lastResult = result
                updateStatusText(getString(R.string.measurement_done))
                showResultSummary(result)
                displayMeasurementOnGraph(result)
                binding.chipApply.isVisible = result.autoEqBands.isNotEmpty() || result.autoEqBandsDiff.isNotEmpty()
                binding.chipSaveIr.isVisible = result.ir.isNotEmpty()
                binding.chipExportFilters.isVisible = result.autoEqBands.isNotEmpty() || result.autoEqBandsDiff.isNotEmpty()
                // Show THD/RT60 chips if data available
                val hasThd = (result.left?.thd?.isNotEmpty() == true) || (result.right?.thd?.isNotEmpty() == true)
                val hasRt60 = (result.left?.rt60?.isNotEmpty() == true) || (result.right?.rt60?.isNotEmpty() == true)
                binding.chipThdInfo.isVisible = hasThd
                binding.chipRt60Info.isVisible = hasRt60
                binding.chipExportDebug.isVisible = true
            } else {
                val msg = result?.errorMessage ?: getString(R.string.measurement_no_result)
                updateStatusText(getString(R.string.measurement_failed, msg))
            }
        }
    }

    private fun displayMeasurementOnGraph(result: MeasurementSession.MeasurementResult) {
        if (result.frequencies.isEmpty()) return

        // Очищаем R-канал
        binding.equalizerSurface.clearMeasurementDataR()

        // В режиме L/R показываем оба канала
        if (result.left != null && result.right != null) {
            if (lrRelativeMode) {
                // Относительный режим: одна кривая L−R (зелёный)
                binding.equalizerSurface.clearMeasurementDataR()
                if (showMeasured && result.left.splSmoothed.isNotEmpty() &&
                    result.right.splSmoothed.isNotEmpty()) {
                    val minLen = minOf(result.left.splSmoothed.size, result.right.splSmoothed.size)
                    val diff = FloatArray(minLen) { i ->
                        result.left.splSmoothed[i] - result.right.splSmoothed[i]
                    }
                    val freqs = result.left.frequencies.copyOfRange(0, minLen)
                    binding.equalizerSurface.setMeasurementData(freqs, diff)
                } else {
                    binding.equalizerSurface.clearMeasurementData()
                }
            } else {
                // Абсолютный режим: две кривые L (зелёный) + R (синий)
                if (showMeasured && result.left.splSmoothed.isNotEmpty()) {
                    binding.equalizerSurface.setMeasurementData(result.left.frequencies, result.left.splSmoothed)
                } else {
                    binding.equalizerSurface.clearMeasurementData()
                }
                if (showMeasured && result.right.splSmoothed.isNotEmpty()) {
                    binding.equalizerSurface.setMeasurementDataR(result.right.frequencies, result.right.splSmoothed)
                } else {
                    binding.equalizerSurface.clearMeasurementDataR()
                }
            }
        } else if (result.right != null && result.left == null) {
            // Только правый канал — синий
            binding.equalizerSurface.clearMeasurementData()
            if (showMeasured && result.right.splSmoothed.isNotEmpty()) {
                binding.equalizerSurface.setMeasurementDataR(result.right.frequencies, result.right.splSmoothed)
            } else {
                binding.equalizerSurface.clearMeasurementDataR()
            }
        } else {
            // Один канал (L или L+R) — только основной measurement
            if (showMeasured) {
                binding.equalizerSurface.setMeasurementData(result.frequencies, result.splSmoothed)
            } else {
                binding.equalizerSurface.clearMeasurementData()
            }
        }

        // Show target curve
        if (showTarget) {
            val targetFreqs = FloatArray(result.frequencies.size) { result.frequencies[it] }
            val targetGains = FloatArray(result.frequencies.size) {
                targetCurve.gainAt(result.frequencies[it].toDouble()).toFloat()
            }
            binding.equalizerSurface.setTargetCurve(targetFreqs, targetGains)
        } else {
            binding.equalizerSurface.clearTargetCurve()
        }

        // Show filter response (Auto-EQ bands) — выбираем набор по режиму Abs/L−R
        val (bandsAbs, preampAbs) = if (lrRelativeMode && result.autoEqBandsDiff.isNotEmpty()) {
            result.autoEqBandsDiff to result.autoEqPreampDiff
        } else {
            result.autoEqBands to result.autoEqPreamp
        }
        if (showFilter && bandsAbs.isNotEmpty()) {
            val bandList = ParametricEqBandList()
            bandList.addAll(bandsAbs)
            binding.equalizerSurface.setBands(bandList, preampAbs)
        }

        // Predicted response (measured + filter)
        updatePredictedResponse(result)
    }

    private fun updateGraphLayers() {
        val result = lastResult ?: return

        // В режиме L/R показываем оба канала
        if (result.left != null && result.right != null) {
            if (lrRelativeMode) {
                // Относительный режим: одна кривая L−R
                binding.equalizerSurface.clearMeasurementDataR()
                if (showMeasured && result.left.splSmoothed.isNotEmpty() &&
                    result.right.splSmoothed.isNotEmpty()) {
                    val minLen = minOf(result.left.splSmoothed.size, result.right.splSmoothed.size)
                    val diff = FloatArray(minLen) { i ->
                        result.left.splSmoothed[i] - result.right.splSmoothed[i]
                    }
                    val freqs = result.left.frequencies.copyOfRange(0, minLen)
                    binding.equalizerSurface.setMeasurementData(freqs, diff)
                } else {
                    binding.equalizerSurface.clearMeasurementData()
                }
            } else {
                if (showMeasured && result.left.splSmoothed.isNotEmpty()) {
                    binding.equalizerSurface.setMeasurementData(result.left.frequencies, result.left.splSmoothed)
                } else {
                    binding.equalizerSurface.clearMeasurementData()
                }
                if (showMeasured && result.right.splSmoothed.isNotEmpty()) {
                    binding.equalizerSurface.setMeasurementDataR(result.right.frequencies, result.right.splSmoothed)
                } else {
                    binding.equalizerSurface.clearMeasurementDataR()
                }
            }
        } else if (result.right != null && result.left == null) {
            // Только правый канал — синий
            binding.equalizerSurface.clearMeasurementData()
            if (showMeasured && result.right.splSmoothed.isNotEmpty()) {
                binding.equalizerSurface.setMeasurementDataR(result.right.frequencies, result.right.splSmoothed)
            } else {
                binding.equalizerSurface.clearMeasurementDataR()
            }
        } else {
            if (showMeasured && result.frequencies.isNotEmpty()) {
                binding.equalizerSurface.setMeasurementData(result.frequencies, result.splSmoothed)
            } else {
                binding.equalizerSurface.clearMeasurementData()
            }
            binding.equalizerSurface.clearMeasurementDataR()
        }

        if (showTarget && result.frequencies.isNotEmpty()) {
            val targetFreqs = FloatArray(result.frequencies.size) { result.frequencies[it] }
            val targetGains = FloatArray(result.frequencies.size) {
                targetCurve.gainAt(result.frequencies[it].toDouble()).toFloat()
            }
            binding.equalizerSurface.setTargetCurve(targetFreqs, targetGains)
        } else {
            binding.equalizerSurface.clearTargetCurve()
        }

        if (showFilter) {
            val (bandsUL, preampUL) = if (lrRelativeMode && result.autoEqBandsDiff.isNotEmpty()) {
                result.autoEqBandsDiff to result.autoEqPreampDiff
            } else {
                result.autoEqBands to result.autoEqPreamp
            }
            if (bandsUL.isNotEmpty()) {
                val bandList = ParametricEqBandList()
                bandList.addAll(bandsUL)
                binding.equalizerSurface.setBands(bandList, preampUL)
            } else {
                binding.equalizerSurface.setBands(ParametricEqBandList(), 0.0)
            }
        } else {
            binding.equalizerSurface.setBands(ParametricEqBandList(), 0.0)
        }

        // Predicted response (measured + filter)
        if (showPredicted) {
            updatePredictedResponse(result)
        } else {
            binding.equalizerSurface.clearCorrectedFR()
        }
    }

    private fun showResultSummary(result: MeasurementSession.MeasurementResult) {
        val lines = mutableListOf<String>()
        // Абсолютный набор
        lines.add("Absolute EQ: ${result.autoEqBands.size} bands, preamp ${"%.1f".format(result.autoEqPreamp)} dB")
        // Относительный набор (если есть)
        if (result.autoEqBandsDiff.isNotEmpty()) {
            lines.add("Relative EQ (L−R): ${result.autoEqBandsDiff.size} bands, preamp ${"%.1f".format(result.autoEqPreampDiff)} dB")
        }
        lines.add(getString(R.string.autoeq_result_deviation, result.finalMaxDeviation))
        lines.add(getString(
            if (result.targetMet) R.string.autoeq_result_target_met
            else R.string.autoeq_result_target_not_met
        ))
        // Межканальная задержка (только в режиме L/R sequential)
        if (result.left != null && result.right != null) {
            lines.add("Межканальная задержка: ${"%.2f".format(result.interChannelDelayMs)} мс")
            lines.add("Режим: ${if (lrRelativeMode) "относительный (L−R)" else "абсолютный (L+R)"}")
        }
        // THD summary
        val thdData = result.left?.thd ?: result.right?.thd
        if (thdData != null && thdData.isNotEmpty()) {
            val validThd = thdData.filter { it >= 0f && it < 100f }
            if (validThd.isNotEmpty()) {
                lines.add("THD: avg ${"%.2f".format(validThd.average())}%, max ${"%.2f".format(validThd.maxOrNull())}%")
            }
        }
        // RT60 summary
        val rt60Data = result.left?.rt60 ?: result.right?.rt60
        if (rt60Data != null && rt60Data.isNotEmpty()) {
            val validRt60 = rt60Data.filter { it > 0f && it < 10f }
            if (validRt60.isNotEmpty()) {
                lines.add("RT60: avg ${"%.3f".format(validRt60.average())} s")
            }
        }
        binding.resultText.text = lines.joinToString("\n")
        binding.resultText.isVisible = true
    }

    private fun showTargetCurveSelector() {
        val presets = arrayOf(
            getString(R.string.target_curve_flat),
            getString(R.string.target_curve_harman),
            "Room Curve",
            getString(R.string.target_curve_custom) + "…"
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.target_curve)
            .setItems(presets) { _, which ->
                when (which) {
                    0 -> {
                        targetCurve = TargetCurve.flat()
                        binding.chipTargetCurve.text = getString(R.string.target_curve_flat)
                    }
                    1 -> {
                        targetCurve = TargetCurve.harman()
                        binding.chipTargetCurve.text = getString(R.string.target_curve_harman)
                    }
                    2 -> {
                        targetCurve = TargetCurve.roomCurve()
                        binding.chipTargetCurve.text = "Room Curve"
                    }
                    3 -> {
                        openTargetCurveEditor()
                    }
                }
            }
            .show()
    }

    private fun openTargetCurveEditor() {
        val editor = TargetCurveEditorFragment.newInstance(targetCurve)
        editor.setListener(object : TargetCurveEditorFragment.OnTargetCurveSelectedListener {
            override fun onTargetCurveSelected(curve: TargetCurve) {
                targetCurve = curve
                binding.chipTargetCurve.text = getString(R.string.target_curve_custom)
            }
        })
        editor.show(parentFragmentManager, "target_curve_editor")
    }

    private fun showAutoEqConfigDialog() {
        val keys = arrayOf(
            getString(R.string.autoeq_max_bands),
            getString(R.string.autoeq_max_boost),
            "Overall max boost (dB)",
            getString(R.string.autoeq_flatness_target),
            getString(R.string.autoeq_match_range_start),
            getString(R.string.autoeq_match_range_end),
            "Nelder-Mead optimization"
        )
        val values = arrayOf(
            autoEqConfig.maxBands.toString(),
            autoEqConfig.individualMaxBoost.toString(),
            autoEqConfig.overallMaxBoost.toString(),
            autoEqConfig.flatnessTarget.toString(),
            autoEqConfig.matchRangeStart.toInt().toString(),
            autoEqConfig.matchRangeEnd.toInt().toString(),
            if (autoEqConfig.useNelderMead) "ON" else "OFF"
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.autoeq_config)
            .setItems(keys.zip(values).map { "${it.first}: ${it.second}" }.toTypedArray()) { _, which ->
                showConfigValueEditor(keys[which], which)
            }
            .setPositiveButton(R.string.peq_done, null)
            .show()
    }

    private fun showConfigValueEditor(label: String, index: Int) {
        // Index 6 is Nelder-Mead toggle
        if (index == 6) {
            autoEqConfig = autoEqConfig.copy(useNelderMead = !autoEqConfig.useNelderMead)
            showAutoEqConfigDialog()
            return
        }

        val current = when (index) {
            0 -> autoEqConfig.maxBands.toString()
            1 -> autoEqConfig.individualMaxBoost.toString()
            2 -> autoEqConfig.overallMaxBoost.toString()
            3 -> autoEqConfig.flatnessTarget.toString()
            4 -> autoEqConfig.matchRangeStart.toInt().toString()
            5 -> autoEqConfig.matchRangeEnd.toInt().toString()
            else -> ""
        }

        val input = com.google.android.material.textfield.TextInputEditText(requireContext()).apply {
            setText(current)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(label)
            .setView(input)
            .setPositiveButton(R.string.peq_done) { _, _ ->
                val v = input.text?.toString()?.toDoubleOrNull() ?: return@setPositiveButton
                when (index) {
                    0 -> autoEqConfig = autoEqConfig.copy(maxBands = v.toInt().coerceIn(1, 64))
                    1 -> autoEqConfig = autoEqConfig.copy(individualMaxBoost = v.coerceIn(0.0, 30.0))
                    2 -> autoEqConfig = autoEqConfig.copy(overallMaxBoost = v.coerceIn(0.0, 30.0))
                    3 -> autoEqConfig = autoEqConfig.copy(flatnessTarget = v.coerceIn(0.1, 10.0))
                    4 -> autoEqConfig = autoEqConfig.copy(matchRangeStart = v.coerceIn(10.0, 1000.0))
                    5 -> autoEqConfig = autoEqConfig.copy(matchRangeEnd = v.coerceIn(1000.0, 24000.0))
                }
            }
            .setNegativeButton(R.string.peq_cancel, null)
            .show()
    }

    @SuppressLint("ApplySharedPref")
    private fun applyAutoEqToPeq() {
        val result = lastResult ?: return

        // Выбираем набор фильтров по текущему режиму отображения
        val (bands, preamp) = if (lrRelativeMode && result.autoEqBandsDiff.isNotEmpty()) {
            result.autoEqBandsDiff to result.autoEqPreampDiff
        } else if (result.autoEqBands.isNotEmpty()) {
            result.autoEqBands to result.autoEqPreamp
        } else return

        val bandList = ParametricEqBandList()
        bandList.addAll(bands)

        requireContext().getSharedPreferences(Constants.PREF_PEQ, Context.MODE_PRIVATE)
            .edit()
            .putString(getString(R.string.key_peq_bands), bandList.serialize())
            .putFloat(getString(R.string.key_peq_preamp), preamp.toFloat())
            .commit()

        requireContext().sendLocalBroadcast(Intent(Constants.ACTION_PARAMETRIC_EQ_CHANGED))

        val label = if (lrRelativeMode && result.autoEqBandsDiff.isNotEmpty()) "L−R" else "L+R"
        requireContext().toast("🐱 MEOW: $label EQ applied (${bands.size} bands, preamp ${"%.1f".format(preamp)} dB)")
    }

    private fun showIrSettings() {
        val ctx = context ?: return

        // Window mode options
        val windowModes = IrWindowMode.values()
        val windowLabels = windowModes.map { it.displayName }.toTypedArray()
        val currentWindowIdx = windowModes.indexOfFirst { it == currentDeconvConfig.windowMode }

        // Averaging options
        val avgOptions = arrayOf("1 (no averaging)", "2", "3", "4", "5")
        val currentAvgIdx = (currentAverageCount - 1).coerceIn(0, avgOptions.size - 1)

        // IR length options (ms)
        val irLenOptions = arrayOf("333 ms", "500 ms", "1000 ms", "2000 ms", "No limit")
        val irLenValues = intArrayOf(333, 500, 1000, 2000, 0)
        val currentIrLenIdx = irLenValues.indexOfFirst { it == currentDeconvConfig.maxIrLenMs }.coerceAtLeast(0)

        // Build items
        val items = mutableListOf<String>()
        items.add("Normalize IR: ${if (currentDeconvConfig.normalizeIr) "ON" else "OFF"}")
        items.add("Window: ${currentDeconvConfig.windowMode.displayName}")
        items.add("IR length: ${irLenOptions[currentIrLenIdx]}")
        items.add("Averaging: ${if (currentAverageCount == 1) "1 (no averaging)" else "$currentAverageCount"}")

        MaterialAlertDialogBuilder(ctx)
            .setTitle("IR Settings")
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> {
                        // Toggle normalize
                        currentDeconvConfig = currentDeconvConfig.copy(normalizeIr = !currentDeconvConfig.normalizeIr)
                        showIrSettings()
                    }
                    1 -> {
                        // Window mode
                        MaterialAlertDialogBuilder(ctx)
                            .setTitle("Window mode")
                            .setSingleChoiceItems(windowLabels, currentWindowIdx) { dialog, w ->
                                currentDeconvConfig = currentDeconvConfig.copy(windowMode = windowModes[w])
                                dialog.dismiss()
                                showIrSettings()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    2 -> {
                        // IR length
                        MaterialAlertDialogBuilder(ctx)
                            .setTitle("IR length")
                            .setSingleChoiceItems(irLenOptions, currentIrLenIdx) { dialog, l ->
                                currentDeconvConfig = currentDeconvConfig.copy(maxIrLenMs = irLenValues[l])
                                dialog.dismiss()
                                showIrSettings()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    3 -> {
                        // Averaging
                        MaterialAlertDialogBuilder(ctx)
                            .setTitle("Averaging (number of measurements)")
                            .setSingleChoiceItems(avgOptions, currentAvgIdx) { dialog, a ->
                                currentAverageCount = a + 1
                                dialog.dismiss()
                                showIrSettings()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showSmoothingSelector() {
        val types = SmoothingType.values()
        val labels = types.map { it.displayName }.toTypedArray()
        val currentIdx = types.indexOfFirst { it == selectedSmoothing }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Сглаживание")
            .setSingleChoiceItems(labels, currentIdx.coerceAtLeast(0)) { dialog, which ->
                selectedSmoothing = types[which]
                binding.chipSmoothingSelect.text = types[which].displayName.replace(" smoothing", "")
                measurementSession = null
                dialog.dismiss()
            }
            .setNegativeButton(R.string.peq_cancel, null)
            .show()
    }

    private fun showModeSelector() {
        val modes = arrayOf(
            "L+R" to MeasurementMode.BOTH,
            "L/R" to MeasurementMode.LR_SEQUENTIAL,
            "L" to MeasurementMode.LEFT,
            "R" to MeasurementMode.RIGHT
        )
        val labels = modes.map { it.first }.toTypedArray()
        val currentIdx = modes.indexOfFirst { it.second == selectedMode }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Режим измерения")
            .setSingleChoiceItems(labels, currentIdx.coerceAtLeast(0)) { dialog, which ->
                selectedMode = modes[which].second
                binding.chipModeSelect.text = modes[which].first
                // Показываем переключатель Abs/L−R только в режиме L/R sequential
                binding.chipLrMode.isVisible = (selectedMode == MeasurementMode.LR_SEQUENTIAL)
                measurementSession = null
                dialog.dismiss()
            }
            .setNegativeButton(R.string.peq_cancel, null)
            .show()
    }

    private fun showAudioInputInfo() {
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) {
                AudioInputDiagnostics.probe(ctx, selectedMicType)
            }
            if (!isAdded) return@launch
            val report = AudioInputDiagnostics.formatReport(info)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("MEOW Audio Input")
                .setMessage(report)
                .setPositiveButton(R.string.peq_done, null)
                .show()
        }
    }

    private fun showMicSelector() {
        val available = MeasurementAudioRecord.getAvailableMicTypes(requireContext())
        if (available.isEmpty()) {
            requireContext().toast("No microphones available")
            return
        }

        val labels = available.map { it.second }.toTypedArray()
        val currentIdx = available.indexOfFirst { it.first == selectedMicType }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.measurement_mic_select)
            .setSingleChoiceItems(labels, currentIdx.coerceAtLeast(0)) { dialog, which ->
                selectedMicType = available[which].first
                binding.chipMicSelect.text = available[which].second
                // Сбрасываем сессию, чтобы новый микрофон применился
                measurementSession = null
                // Перезапускаем VU-монитор с новым выбором канала
                stopMicMonitor()
                startMicMonitor()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.peq_cancel, null)
            .show()
    }

    private fun updateMicCalChip() {
        binding.chipMicCal.text = if (micCalibration != null) {
            getString(R.string.mic_calibration)
        } else {
            getString(R.string.mic_calibration_load)
        }
    }

    private fun updateStatusText(text: String) {
        binding.statusText.text = text
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun showTargetTypeSelector() {
        val types = TargetType.values()
        val labels = types.map { it.displayName }.toTypedArray()
        val currentIdx = types.indexOfFirst { it == targetType }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Target type")
            .setSingleChoiceItems(labels, currentIdx.coerceAtLeast(0)) { dialog, which ->
                targetType = types[which]
                binding.chipTargetType.text = types[which].displayName
                // Apply target type defaults
                targetCurve = types[which].defaultCurve
                binding.chipTargetCurve.text = types[which].defaultCurve.name
                autoEqConfig = autoEqConfig.copy(
                    matchRangeStart = types[which].matchRangeStart,
                    matchRangeEnd = types[which].matchRangeEnd
                )
                measurementSession = null
                dialog.dismiss()
            }
            .setNegativeButton(R.string.peq_cancel, null)
            .show()
    }

    private fun showThdInfo() {
        val result = lastResult ?: return
        val channel = result.left ?: result.right ?: return
        val thd = channel.thd ?: return
        val freqs = channel.thdFrequencies ?: return

        val sb = StringBuilder()
        sb.appendLine("THD — Total Harmonic Distortion")
        sb.appendLine()
        // Show THD at key frequencies
        val keyFreqs = doubleArrayOf(100.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0)
        for (kf in keyFreqs) {
            // Find closest frequency bin
            var bestIdx = -1
            var bestDist = Double.MAX_VALUE
            for (i in freqs.indices) {
                val dist = Math.abs(freqs[i].toDouble() - kf)
                if (dist < bestDist) { bestDist = dist; bestIdx = i }
            }
            if (bestIdx >= 0 && bestDist < kf * 0.2) {
                sb.appendLine(String.format("%.0f Hz: %.2f%%", kf, thd[bestIdx]))
            }
        }
        // Min/Max/Average
        val validThd = thd.filter { it >= 0f && it < 100f }
        if (validThd.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine(String.format("Min: %.2f%%", validThd.minOrNull()))
            sb.appendLine(String.format("Max: %.2f%%", validThd.maxOrNull()))
            sb.appendLine(String.format("Avg: %.2f%%", validThd.average()))
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("THD")
            .setMessage(sb.toString())
            .setPositiveButton(R.string.peq_done, null)
            .show()
    }

    private fun showRt60Info() {
        val result = lastResult ?: return
        val channel = result.left ?: result.right ?: return
        val rt60 = channel.rt60 ?: return
        val freqs = channel.rt60Frequencies ?: return

        val sb = StringBuilder()
        sb.appendLine("RT60 — Reverberation Time")
        sb.appendLine()
        sb.appendLine(String.format("%-10s  %s", "Freq", "RT60 (s)"))
        for (i in freqs.indices) {
            if (rt60[i] > 0f && rt60[i] < 10f) {
                val freqLabel = if (freqs[i] >= 1000f) {
                    String.format("%.1fk", freqs[i] / 1000f)
                } else {
                    String.format("%.0f", freqs[i])
                }
                sb.appendLine(String.format("%-10s  %.3f", freqLabel, rt60[i]))
            }
        }

        val validRt60 = rt60.filter { it > 0f && it < 10f }
        if (validRt60.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine(String.format("Average RT60: %.3f s", validRt60.average()))
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("RT60")
            .setMessage(sb.toString())
            .setPositiveButton(R.string.peq_done, null)
            .show()
    }

    /**
     * Compute predicted response: measured SPL + filter response.
     * Shows what the frequency response would look like after applying the EQ.
     */
    private fun updatePredictedResponse(result: MeasurementSession.MeasurementResult) {
        if (!showPredicted) {
            binding.equalizerSurface.clearCorrectedFR()
            return
        }

        val (bands, preamp) = if (lrRelativeMode && result.autoEqBandsDiff.isNotEmpty()) {
            result.autoEqBandsDiff to result.autoEqPreampDiff
        } else {
            result.autoEqBands to result.autoEqPreamp
        }

        if (bands.isEmpty()) {
            binding.equalizerSurface.clearCorrectedFR()
            return
        }

        // Get measured SPL
        val measuredSpl = if (result.left != null && result.right != null && !lrRelativeMode) {
            // Average L+R
            val minLen = minOf(result.left.splSmoothed.size, result.right.splSmoothed.size)
            FloatArray(minLen) { i -> (result.left.splSmoothed[i] + result.right.splSmoothed[i]) / 2f }
        } else if (lrRelativeMode && result.left != null && result.right != null) {
            val minLen = minOf(result.left.splSmoothed.size, result.right.splSmoothed.size)
            FloatArray(minLen) { i -> result.left.splSmoothed[i] - result.right.splSmoothed[i] }
        } else {
            result.splSmoothed
        }
        val measuredFreqs = result.frequencies

        if (measuredFreqs.isEmpty() || measuredSpl.isEmpty()) {
            binding.equalizerSurface.clearCorrectedFR()
            return
        }

        // Compute filter response at measured frequency points
        val calculator = me.timschneeberger.rootlessjamesdsp.utils.ParametricEqResponseCalculator(
            48000.0, 20.0, 20000.0, 512
        )
        val evalFreqs = calculator.logSpacedFrequencies()
        val filterResponse = calculator.compute(bands, preamp).leftResponseDb

        // Interpolate filter response to measured frequency points
        val predicted = FloatArray(measuredFreqs.size) { i ->
            val f = measuredFreqs[i].toDouble()
            // Binary search in evalFreqs
            var lo = 0
            var hi = evalFreqs.size - 1
            while (hi - lo > 1) {
                val mid = (lo + hi) / 2
                if (evalFreqs[mid] <= f) lo = mid else hi = mid
            }
            val t = if (evalFreqs[hi] != evalFreqs[lo]) {
                (f - evalFreqs[lo]) / (evalFreqs[hi] - evalFreqs[lo])
            } else 0.0
            val filterAtF = filterResponse[lo] + t * (filterResponse[hi] - filterResponse[lo])
            (measuredSpl[i] + filterAtF).toFloat()
        }

        binding.equalizerSurface.setCorrectedFR(measuredFreqs, predicted)
    }

    private fun exportDebugData() {
        val session = measurementSession ?: return
        val dbg = session.debugData ?: run {
            requireContext().toast("No debug data available")
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val dir = withContext(Dispatchers.IO) {
                MeasurementDebugExporter.export(requireContext(), dbg)
            }
            if (dir != null) {
                requireContext().toast("Debug data exported to ${dir.name}")
                // Also show in status
                updateStatusText("Debug data exported to: ${dir.absolutePath}")
            } else {
                requireContext().toast("Debug export failed")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        startMicMonitor()
    }

    override fun onPause() {
        super.onPause()
        stopMicMonitor()
    }

    /**
     * Start continuous microphone monitor for the VU meter.
     * Runs in a background thread, feeds level data to the UI.
     * Only active when no measurement is in progress (avoids dual AudioRecord).
     */
    private fun startMicMonitor() {
        if (micMonitorRunning) return
        if (!hasMicPermission()) return
        val ctx = context ?: return

        micMonitorRunning = true
        micMonitorThread = Thread {
            val source = when (selectedMicType) {
                MicType.BUILTIN_UNPROCESSED, MicType.BUILTIN_BOTTOM ->
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N)
                        android.media.MediaRecorder.AudioSource.UNPROCESSED
                    else android.media.MediaRecorder.AudioSource.MIC
                MicType.BUILTIN_MIC -> android.media.MediaRecorder.AudioSource.MIC
                MicType.BUILTIN_BACK ->
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N)
                        android.media.MediaRecorder.AudioSource.UNPROCESSED
                    else android.media.MediaRecorder.AudioSource.CAMCORDER
                MicType.USB ->
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N)
                        android.media.MediaRecorder.AudioSource.UNPROCESSED
                    else android.media.MediaRecorder.AudioSource.MIC
            }

            val selectRight = selectedMicType == MicType.BUILTIN_BACK
            val sampleRate = 48000

            // Пытаемся стерео — HAL отдаёт два микрофона
            var channelConfig = android.media.AudioFormat.CHANNEL_IN_STEREO
            val audioFormat = android.media.AudioFormat.ENCODING_PCM_FLOAT
            var minBuf = android.media.AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            var bufSize = (minBuf.coerceAtLeast(2048) * 2)

            var record = try {
                android.media.AudioRecord(source, sampleRate, channelConfig, audioFormat, bufSize)
            } catch (e: Exception) {
                micMonitorRunning = false
                return@Thread
            }

            var isStereo = true
            if (record.state != android.media.AudioRecord.STATE_INITIALIZED) {
                // Fallback: mono
                record.release()
                channelConfig = android.media.AudioFormat.CHANNEL_IN_MONO
                isStereo = false
                minBuf = android.media.AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                bufSize = (minBuf.coerceAtLeast(2048))
                record = try {
                    android.media.AudioRecord(source, sampleRate, channelConfig, audioFormat, bufSize)
                } catch (e: Exception) {
                    micMonitorRunning = false
                    return@Thread
                }
            }

            if (record.state != android.media.AudioRecord.STATE_INITIALIZED) {
                record.release()
                micMonitorRunning = false
                return@Thread
            }

            android.util.Log.d("MicRoute", "Monitor: stereo=$isStereo selectRight=$selectRight source=$source")

            if (isStereo) {
                val stereoBuf = FloatArray(2048) // 1024 frames * 2
                record.startRecording()
                while (micMonitorRunning) {
                    val read = record.read(stereoBuf, 0, stereoBuf.size, android.media.AudioRecord.READ_NON_BLOCKING)
                    if (read > 0) {
                        val frames = read / 2
                        var sumSqL = 0.0
                        var sumSqR = 0.0
                        var clip = false
                        for (i in 0 until frames) {
                            val l = stereoBuf[i * 2]
                            val r = stereoBuf[i * 2 + 1]
                            sumSqL += l.toDouble() * l.toDouble()
                            sumSqR += r.toDouble() * r.toDouble()
                            if (kotlin.math.abs(l) >= 0.99f || kotlin.math.abs(r) >= 0.99f) clip = true
                        }
                        val rmsL = Math.sqrt(sumSqL / frames)
                        val rmsR = Math.sqrt(sumSqR / frames)
                        val dbL = if (rmsL > 1e-10) 20.0 * Math.log10(rmsL) else -100.0
                        val dbR = if (rmsR > 1e-10) 20.0 * Math.log10(rmsR) else -100.0
                        val dbFs = if (selectRight) dbR else dbL

                        val act = activity
                        if (act != null) {
                            act.runOnUiThread {
                                if (!isAdded) return@runOnUiThread
                                binding.micLevelView.setLevelDb(dbFs.toFloat(), clip)
                            }
                        }
                    }
                    Thread.sleep(30)
                }
            } else {
                val readBuf = FloatArray(1024)
                record.startRecording()
                while (micMonitorRunning) {
                    val read = record.read(readBuf, 0, readBuf.size, android.media.AudioRecord.READ_NON_BLOCKING)
                    if (read > 0) {
                        var sumSq = 0.0
                        var clip = false
                        for (i in 0 until read) {
                            val s = readBuf[i]
                            sumSq += s.toDouble() * s.toDouble()
                            if (kotlin.math.abs(s) >= 0.99f) clip = true
                        }
                        val rms = Math.sqrt(sumSq / read)
                        val dbFs = if (rms > 1e-10) 20.0 * Math.log10(rms) else -100.0

                        val act = activity
                        if (act != null) {
                            act.runOnUiThread {
                                if (!isAdded) return@runOnUiThread
                                binding.micLevelView.setLevelDb(dbFs.toFloat(), clip)
                            }
                        }
                    }
                    Thread.sleep(30)
                }
            }

            try { record.stop() } catch (_: Exception) {}
            record.release()
        }
        micMonitorThread?.start()
    }

    private fun stopMicMonitor() {
        micMonitorRunning = false
        micMonitorThread?.join(500)
        micMonitorThread = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopMicMonitor()
        measurementSession?.cancel()
        measurementSession = null
    }

    companion object {
        fun newInstance(): MeasurementFragment = MeasurementFragment()
    }
}
