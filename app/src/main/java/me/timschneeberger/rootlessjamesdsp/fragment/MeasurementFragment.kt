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
import me.timschneeberger.rootlessjamesdsp.databinding.FragmentMeasurementBinding
import me.timschneeberger.rootlessjamesdsp.measurement.AutoEqEngine
import me.timschneeberger.rootlessjamesdsp.measurement.MeasurementMode
import me.timschneeberger.rootlessjamesdsp.measurement.MeasurementSession
import me.timschneeberger.rootlessjamesdsp.measurement.MicCalibrationLoader
import me.timschneeberger.rootlessjamesdsp.measurement.MicType
import me.timschneeberger.rootlessjamesdsp.measurement.SmoothingType
import me.timschneeberger.rootlessjamesdsp.measurement.TargetCurve
import me.timschneeberger.rootlessjamesdsp.measurement.MeasurementAudioRecord
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqBand
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqBandList
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqChannelMode
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

    private var targetCurve: TargetCurve = TargetCurve.flat()
    private var micCalibration: MicCalibrationLoader.CalibrationData? = null
    private var autoEqConfig: AutoEqEngine.Config = AutoEqEngine.Config()

    // Выбранный тип микрофона
    private var selectedMicType: MicType = MicType.BUILTIN_UNPROCESSED

    // Выбранный режим измерения каналов
    private var selectedMode: MeasurementMode = MeasurementMode.BOTH

    // Выбранный тип сглаживания (по умолчанию — psychoacoustic)
    private var selectedSmoothing: SmoothingType = SmoothingType.PSYCHOACOUSTIC

    // Режим отображения L/R: абсолютный (две кривые) или относительный (L−R разница)
    private var lrRelativeMode = false

    // Layer visibility toggles
    private var showMeasured = true
    private var showTarget = true
    private var showFilter = true

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
            // MeasurementSession doesn't support cancellation yet;
            // the chip is informational
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

        // Layer toggles
        binding.toggleMeasured.isChecked = showMeasured
        binding.toggleTarget.isChecked = showTarget
        binding.toggleFilter.isChecked = showFilter

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
                onInputSamples = { samples, offset, length ->
                    // Обновляем визуализацию входящего сигнала
                    binding.waveformView.updateSamples(samples, offset, length)
                    // Обновляем индикатор уровня (RMS → dBFS)
                    var sumSq = 0.0
                    for (i in offset until offset + length) {
                        sumSq += samples[i].toDouble() * samples[i].toDouble()
                    }
                    val rms = Math.sqrt(sumSq / length)
                    val dbFs = if (rms > 1e-10) 20.0 * Math.log10(rms) else -100.0
                    val isClipping = samples.any { kotlin.math.abs(it) >= 0.99f }
                    requireActivity().runOnUiThread {
                        if (isClipping) {
                            binding.inputLevelText.text = getString(R.string.measurement_input_clipping)
                            binding.inputLevelText.setTextColor(0xFFFF5252.toInt())
                        } else {
                            binding.inputLevelText.text = getString(R.string.measurement_input_level, dbFs)
                            binding.inputLevelText.setTextColor(0xFF4CAF50.toInt())
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

            if (result?.success == true) {
                lastResult = result
                updateStatusText(getString(R.string.measurement_done))
                showResultSummary(result)
                displayMeasurementOnGraph(result)
                binding.chipApply.isVisible = result.autoEqBands.isNotEmpty() || result.autoEqBandsDiff.isNotEmpty()
                binding.chipSaveIr.isVisible = result.ir.isNotEmpty()
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
        binding.resultText.text = lines.joinToString("\n")
        binding.resultText.isVisible = true
    }

    private fun showTargetCurveSelector() {
        val presets = arrayOf(
            getString(R.string.target_curve_flat),
            getString(R.string.target_curve_harman),
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
            getString(R.string.autoeq_flatness_target),
            getString(R.string.autoeq_match_range_start),
            getString(R.string.autoeq_match_range_end)
        )
        val values = arrayOf(
            autoEqConfig.maxBands.toString(),
            autoEqConfig.individualMaxBoost.toString(),
            autoEqConfig.flatnessTarget.toString(),
            autoEqConfig.matchRangeStart.toInt().toString(),
            autoEqConfig.matchRangeEnd.toInt().toString()
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
        val current = when (index) {
            0 -> autoEqConfig.maxBands.toString()
            1 -> autoEqConfig.individualMaxBoost.toString()
            2 -> autoEqConfig.flatnessTarget.toString()
            3 -> autoEqConfig.matchRangeStart.toInt().toString()
            4 -> autoEqConfig.matchRangeEnd.toInt().toString()
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
                    2 -> autoEqConfig = autoEqConfig.copy(flatnessTarget = v.coerceIn(0.1, 10.0))
                    3 -> autoEqConfig = autoEqConfig.copy(matchRangeStart = v.coerceIn(10.0, 1000.0))
                    4 -> autoEqConfig = autoEqConfig.copy(matchRangeEnd = v.coerceIn(1000.0, 24000.0))
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

    override fun onDestroyView() {
        super.onDestroyView()
        measurementSession = null
    }

    companion object {
        fun newInstance(): MeasurementFragment = MeasurementFragment()
    }
}
