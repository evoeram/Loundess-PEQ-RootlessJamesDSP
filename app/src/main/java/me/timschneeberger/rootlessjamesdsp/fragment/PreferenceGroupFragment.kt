package me.timschneeberger.rootlessjamesdsp.fragment

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import me.timschneeberger.rootlessjamesdsp.utils.extensions.PermissionExtensions.hasRecordPermission
import androidx.annotation.XmlRes
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.Preference.SummaryProvider
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import androidx.recyclerview.widget.RecyclerView
import me.timschneeberger.rootlessjamesdsp.MainApplication
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.activity.GraphicEqualizerActivity
import me.timschneeberger.rootlessjamesdsp.activity.LiveprogEditorActivity
import me.timschneeberger.rootlessjamesdsp.activity.ParametricEqualizerActivity
import me.timschneeberger.rootlessjamesdsp.activity.MeasurementActivity
import me.timschneeberger.rootlessjamesdsp.activity.LiveprogParamsActivity
import me.timschneeberger.rootlessjamesdsp.adapter.RoundedRipplePreferenceGroupAdapter
import me.timschneeberger.rootlessjamesdsp.liveprog.EelParser
import me.timschneeberger.rootlessjamesdsp.preference.CompanderPreference
import me.timschneeberger.rootlessjamesdsp.preference.EqualizerPreference
import me.timschneeberger.rootlessjamesdsp.preference.FileLibraryPreference
import me.timschneeberger.rootlessjamesdsp.preference.MaterialSeekbarPreference
import me.timschneeberger.rootlessjamesdsp.preference.SwitchPreferenceGroup
import me.timschneeberger.rootlessjamesdsp.utils.AudioSampleRateDetector
import me.timschneeberger.rootlessjamesdsp.utils.ConvolverSampleRateFiles
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.io.File
import kotlin.math.roundToInt


class PreferenceGroupFragment : PreferenceFragmentCompat(), KoinComponent {
    private val prefsApp: Preferences.App by inject()
    private val eelParser = EelParser()
    private var recyclerView: RecyclerView? = null
    private var reportedProcessingSampleRate: Int? = null
    private var convolverStatusUpdater: (() -> Unit)? = null

    private val listener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            requireContext().sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
            convolverStatusUpdater?.invoke()
        }

    private val listenerApp =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when(key) {
                context?.resources?.getString(R.string.key_appearance_show_icons) -> updateIconState()
            }
        }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when(intent?.action) {
                Constants.ACTION_PRESET_LOADED -> {
                    val id = this@PreferenceGroupFragment.id
                    Timber.d("Reloading group fragment for ${this@PreferenceGroupFragment.preferenceManager.sharedPreferencesName}")
                    (requireParentFragment() as DspFragment).restartFragment(id, cloneInstance(this@PreferenceGroupFragment))
                }
                Constants.ACTION_REPORT_SAMPLE_RATE -> {
                    reportedProcessingSampleRate = intent
                        .getFloatExtra(Constants.EXTRA_SAMPLE_RATE, 0f)
                        .roundToInt()
                    convolverStatusUpdater?.invoke()
                }
            }
        }
    }

    private fun updateIconState() {
        if(preferenceScreen.preferenceCount > 0) {
            (preferenceScreen.getPreference(0) as? SwitchPreferenceGroup?)
                ?.setIsIconVisible(prefsApp.get<Boolean>(R.string.key_appearance_show_icons))
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val args = requireArguments()
        preferenceManager.sharedPreferencesName = args.getString(BUNDLE_PREF_NAME)
        @Suppress("DEPRECATION")
        preferenceManager.sharedPreferencesMode = Context.MODE_MULTI_PROCESS
        addPreferencesFromResource(args.getInt(BUNDLE_XML_RES))

        requireContext().registerLocalReceiver(receiver, IntentFilter().apply {
            addAction(Constants.ACTION_PRESET_LOADED)
            addAction(Constants.ACTION_REPORT_SAMPLE_RATE)
        })

        when(args.getInt(BUNDLE_XML_RES)) {
            R.xml.dsp_convolver_preferences -> setupConvolverSampleRateFiles()
            R.xml.dsp_loudness_preferences -> setupLoudnessCalibration()
            R.xml.dsp_compander_preferences -> {
                findPreference<MaterialSeekbarPreference>(getString(R.string.key_compander_granularity))?.valueLabelOverride =
                    fun(it: Float): String {
                        return when(it.roundToInt()) {
                            0 -> getString(R.string.compander_granularity_very_low)
                            1 -> getString(R.string.compander_granularity_low)
                            2 -> getString(R.string.compander_granularity_medium)
                            3 -> getString(R.string.compander_granularity_high)
                            4 -> getString(R.string.compander_granularity_extreme)
                            else -> it.roundToInt().toString()
                        }
                    }
            }
            R.xml.dsp_stereowide_preferences -> {
                findPreference<MaterialSeekbarPreference>(getString(R.string.key_stereowide_mode))?.valueLabelOverride =
                    fun(it: Float): String {
                        return if (it in 49.0..51.0)
                            getString(R.string.stereowide_level_none)
                        else if(it >= 60)
                            getString(R.string.stereowide_level_very_wide)
                        else if(it >= 51)
                            getString(R.string.stereowide_level_wide)
                        else if(it <= 40)
                            getString(R.string.stereowide_level_very_narrow)
                        else if(it <= 49)
                            getString(R.string.stereowide_level_narrow)
                        else
                            it.toString()
                    }
            }
            R.xml.dsp_liveprog_preferences -> {
                val liveprogParams = findPreference<Preference>(getString(R.string.key_liveprog_params))
                val liveprogEdit = findPreference<Preference>(getString(R.string.key_liveprog_edit))
                val liveprogFile = findPreference<FileLibraryPreference>(getString(R.string.key_liveprog_file))

                fun updateLiveprog(newValue: String) {
                    eelParser.load(FileLibraryPreference.createFullPathCompat(requireContext(), newValue))
                    val count = eelParser.properties.size
                    val filePresent = eelParser.contents != null
                    val uiUpdate = {
                        liveprogEdit?.isEnabled = filePresent

                        liveprogParams?.isEnabled = count > 0

                        try {
                            liveprogParams?.summary = if (count > 0)
                                resources.getQuantityString(R.plurals.custom_parameters, count, count)
                            else
                                getString(R.string.liveprog_additional_params_not_supported)
                        }
                        catch(ex: IllegalStateException) {
                            /* Because this lambda is executed async, it is possible that it is called
                               while the fragment is destroyed, leading to accessing a detached context */
                            Timber.d(ex)
                        }
                    }

                    if (recyclerView == null)
                        // Recycler view doesn't exist yet, directly setup the preference
                        uiUpdate()
                    else
                        // Recycler view does exist, queue on UI thread
                        recyclerView!!.post(uiUpdate)
                }

                liveprogFile?.summaryProvider = SummaryProvider<FileLibraryPreference> {
                    updateLiveprog(it.value)
                    if(it.value == null || it.value.isBlank() || !eelParser.isFileLoaded) {
                        getString(R.string.liveprog_no_script_selected)
                    }
                    else
                        eelParser.description
                }

                FileLibraryPreference.createFullPathNullCompat(requireContext(), liveprogFile?.value)?.let {
                    updateLiveprog(it)
                }

                liveprogFile?.setOnPreferenceChangeListener { _, newValue ->
                    updateLiveprog(newValue as String)
                    true
                }

                liveprogParams?.setOnPreferenceClickListener {
                    val intent = Intent(requireContext(), LiveprogParamsActivity::class.java)
                    intent.putExtra(LiveprogParamsActivity.EXTRA_TARGET_FILE, FileLibraryPreference.createFullPathNullCompat(requireContext(), liveprogFile?.value))
                    startActivity(intent)
                    true
                }

                liveprogEdit?.setOnPreferenceClickListener {
                    val intent = Intent(requireContext(), LiveprogEditorActivity::class.java)
                    intent.putExtra(LiveprogEditorActivity.EXTRA_TARGET_FILE, FileLibraryPreference.createFullPathNullCompat(requireContext(), liveprogFile?.value))
                    startActivity(intent)
                    true
                }
            }
            R.xml.dsp_graphiceq_preferences -> {
                findPreference<Preference>(getString(R.string.key_geq_nodes))?.setOnPreferenceClickListener {
                    val intent = Intent(requireContext(), GraphicEqualizerActivity::class.java)
                    startActivity(intent)
                    true
                }
            }
            R.xml.dsp_parametriceq_preferences -> {
                findPreference<Preference>(getString(R.string.key_peq_bands))?.setOnPreferenceClickListener {
                    val intent = Intent(requireContext(), ParametricEqualizerActivity::class.java)
                    startActivity(intent)
                    true
                }
            }
            R.xml.dsp_measurement_preferences -> {
                findPreference<Preference>(getString(R.string.key_measurement_open))?.setOnPreferenceClickListener {
                    val intent = Intent(requireContext(), MeasurementActivity::class.java)
                    startActivity(intent)
                    true
                }
            }
        }

        updateIconState()

        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(listener)
        prefsApp.registerOnSharedPreferenceChangeListener(listenerApp)
    }

    /**
     * Настройка кнопки автокалибровки loudness по микрофону или внешнему SPL-метру.
     */
    private fun setupLoudnessCalibration() {
        val calibratePref = findPreference<Preference>("loudness_calibrate") ?: return

        calibratePref.setOnPreferenceClickListener {
            val context = requireContext()

            // Проверяем разрешение RECORD_AUDIO (нужно только для микрофонного режима,
            // но запрашиваем сразу, чтобы не прерывать поток калибровки)
            if (!context.hasRecordPermission()) {
                requestRecordAudioPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                return@setOnPreferenceClickListener true
            }

            showCalibrationModeDialog(context, calibratePref)
            true
        }
    }

    /** Launcher для запроса RECORD_AUDIO */
    private val requestRecordAudioPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val context = requireContext()
            val calibratePref = findPreference<Preference>("loudness_calibrate") ?: return@registerForActivityResult
            showCalibrationModeDialog(context, calibratePref)
        } else {
            android.widget.Toast.makeText(
                requireContext(),
                getString(R.string.loudness_calibrate_failed, "RECORD_AUDIO permission denied"),
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Диалог выбора режима калибровки: микрофон или внешний SPL-метр.
     */
    private fun showCalibrationModeDialog(context: Context, calibratePref: Preference) {
        val modes = arrayOf(
            getString(R.string.loudness_calibrate_mode_mic),
            getString(R.string.loudness_calibrate_mode_manual),
        )
        com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
            .setTitle(R.string.loudness_calibrate_mode)
            .setItems(modes) { _, which ->
                when (which) {
                    0 -> showChannelSelectionDialog(context, calibratePref)
                    1 -> showManualSplDialog(context, calibratePref)
                }
            }
            .setNegativeButton(android.R.string.cancel) { d, _ -> d.dismiss() }
            .show()
    }

    /**
     * Диалог выбора канала воспроизведения шума (для микрофонного режима).
     */
    private fun showChannelSelectionDialog(context: Context, calibratePref: Preference) {
        val channels = arrayOf(
            getString(R.string.loudness_calibrate_channel_both),
            getString(R.string.loudness_calibrate_channel_left),
            getString(R.string.loudness_calibrate_channel_right),
        )
        com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
            .setTitle(R.string.loudness_calibrate_channel)
            .setItems(channels) { _, which ->
                val channel = when (which) {
                    0 -> me.timschneeberger.rootlessjamesdsp.utils.LoudnessCalibrationManager.NoiseChannel.BOTH
                    1 -> me.timschneeberger.rootlessjamesdsp.utils.LoudnessCalibrationManager.NoiseChannel.LEFT
                    else -> me.timschneeberger.rootlessjamesdsp.utils.LoudnessCalibrationManager.NoiseChannel.RIGHT
                }
                startMicrophoneCalibration(context, calibratePref, channel)
            }
            .setNegativeButton(android.R.string.cancel) { d, _ -> d.dismiss() }
            .show()
    }

    /**
     * Запустить микрофонную калибровку.
     */
    private fun startMicrophoneCalibration(
        context: Context,
        calibratePref: Preference,
        channel: me.timschneeberger.rootlessjamesdsp.utils.LoudnessCalibrationManager.NoiseChannel,
    ) {
        val prefs = preferenceManager.sharedPreferences

        com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
            .setTitle(R.string.loudness_calibrate)
            .setMessage(R.string.loudness_calibrate_warning)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                dialog.dismiss()

                // Отключаем loudness перед калибровкой
                val wasLoudnessEnabled = prefs?.getBoolean(getString(R.string.key_loudness_enable), false) ?: false
                if (wasLoudnessEnabled) {
                    prefs?.edit()?.putBoolean(getString(R.string.key_loudness_enable), false)?.apply()
                }

                val manager = me.timschneeberger.rootlessjamesdsp.utils.LoudnessCalibrationManager(context)

                manager.onProgress = { progress ->
                    try {
                        val percent = (progress * 100).toInt()
                        calibratePref.summary = getString(R.string.loudness_calibrate_running, percent)
                    } catch (_: IllegalStateException) {}
                }

                manager.onComplete = { result ->
                    handleCalibrationResult(context, calibratePref, prefs, result, wasLoudnessEnabled)
                }

                calibratePref.summary = getString(R.string.loudness_calibrate_running, 0)
                Thread {
                    Thread.sleep(500)
                    manager.startMicrophone(durationSec = 5, sampleRate = 48000, channel = channel)
                }.start()
            }
            .setNegativeButton(android.R.string.cancel) { d, _ -> d.dismiss() }
            .show()
    }

    /**
     * Диалог ручного ввода SPL (внешний SPL-метр).
     * С возможностью воспроизвести розовый шум для измерения.
     */
    private fun showManualSplDialog(context: Context, calibratePref: Preference) {
        val prefs = preferenceManager.sharedPreferences
        val manager = me.timschneeberger.rootlessjamesdsp.utils.LoudnessCalibrationManager(context)

        // Отключаем loudness перед воспроизведением шума
        val wasLoudnessEnabled = prefs?.getBoolean(getString(R.string.key_loudness_enable), false) ?: false
        if (wasLoudnessEnabled) {
            prefs?.edit()?.putBoolean(getString(R.string.key_loudness_enable), false)?.apply()
        }

        val input = android.widget.EditText(context).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            hint = getString(R.string.loudness_calibrate_manual_spl)
        }

        val container = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        container.addView(android.widget.TextView(context).apply {
            text = getString(R.string.loudness_calibrate_manual_warning)
            setPadding(0, 0, 0, 24)
        })

        // Кнопки каналов
        val channelGroup = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }
        val noiseButtons = mutableListOf<com.google.android.material.button.MaterialButton>()

        val channels = listOf(
            Triple(me.timschneeberger.rootlessjamesdsp.utils.LoudnessCalibrationManager.NoiseChannel.BOTH, R.string.loudness_calibrate_channel_both, R.string.loudness_calibrate_play_noise),
            Triple(me.timschneeberger.rootlessjamesdsp.utils.LoudnessCalibrationManager.NoiseChannel.LEFT, R.string.loudness_calibrate_channel_left, R.string.loudness_calibrate_play_noise),
            Triple(me.timschneeberger.rootlessjamesdsp.utils.LoudnessCalibrationManager.NoiseChannel.RIGHT, R.string.loudness_calibrate_channel_right, R.string.loudness_calibrate_play_noise),
        )

        channels.forEach { (ch, labelRes, _) ->
            val btn = com.google.android.material.button.MaterialButton(context).apply {
                text = getString(labelRes)
                setOnClickListener {
                    // Если кнопка показывает "Stop" — останавливаем
                    val isPlaying = text == getString(R.string.loudness_calibrate_stop_noise)
                    if (isPlaying) {
                        manager.stopNoise()
                        text = getString(R.string.loudness_calibrate_play_noise)
                    } else {
                        noiseButtons.forEach { it.text = getString(R.string.loudness_calibrate_play_noise) }
                        Thread {
                            Thread.sleep(500)
                            manager.playNoiseForManualCalibration(48000, ch)
                        }.start()
                        text = getString(R.string.loudness_calibrate_stop_noise)
                    }
                }
            }
            noiseButtons.add(btn)
            channelGroup.addView(btn)
            (btn.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
                weight = 1f
                marginEnd = 8
            }
        }

        container.addView(channelGroup)
        container.addView(input)

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
            .setTitle(R.string.loudness_calibrate)
            .setView(container)
            .setPositiveButton(R.string.loudness_calibrate_apply) { d, _ ->
                val splText = input.text.toString().trim()
                val spl = splText.toDoubleOrNull()
                if (spl == null) {
                    android.widget.Toast.makeText(context,
                        getString(R.string.loudness_calibrate_failed, "Invalid SPL value"),
                        android.widget.Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }

                manager.stopNoise()

                // Применяем введённый SPL
                manager.onComplete = { result ->
                    handleCalibrationResult(context, calibratePref, prefs, result, wasLoudnessEnabled)
                }
                manager.applyManualSpl(spl)
                d.dismiss()
            }
            .setNegativeButton(android.R.string.cancel) { d, _ ->
                manager.stopNoise()
                // Восстанавливаем loudness
                if (wasLoudnessEnabled) {
                    prefs?.edit()?.putBoolean(getString(R.string.key_loudness_enable), true)?.apply()
                }
                d.dismiss()
            }
            .setOnDismissListener {
                manager.stopNoise()
            }
            .create()

        dialog.show()
    }

    /**
     * Обработка результата калибровки (общий для микрофона и ручного ввода).
     */
    private fun handleCalibrationResult(
        context: Context,
        calibratePref: Preference,
        prefs: SharedPreferences?,
        result: me.timschneeberger.rootlessjamesdsp.utils.LoudnessCalibrationManager.CalibrationResult,
        wasLoudnessEnabled: Boolean,
    ) {
        try {
            if (result.success) {
                prefs?.edit()?.apply {
                    putFloat(getString(R.string.key_loudness_reference_level), result.referenceLevel.toFloat())
                    putFloat(getString(R.string.key_loudness_reference_offset), result.referenceOffset.toFloat())
                    putBoolean(getString(R.string.key_loudness_enable), true)
                    putBoolean(getString(R.string.key_loudness_auto_volume), true)
                }?.apply()

                val id = this@PreferenceGroupFragment.id
                (requireParentFragment() as DspFragment)
                    .restartFragment(id, cloneInstance(this@PreferenceGroupFragment))

                android.widget.Toast.makeText(context,
                    getString(R.string.loudness_calibrate_success,
                        result.measuredSplDb, result.referenceLevel, result.referenceOffset),
                    android.widget.Toast.LENGTH_LONG
                ).show()
            } else {
                if (wasLoudnessEnabled) {
                    prefs?.edit()?.putBoolean(getString(R.string.key_loudness_enable), true)?.apply()
                }
                try {
                    calibratePref.summary = getString(R.string.loudness_calibrate_failed, result.errorMessage ?: "")
                } catch (_: IllegalStateException) {}
                android.widget.Toast.makeText(context,
                    getString(R.string.loudness_calibrate_failed, result.errorMessage ?: ""),
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        } catch (_: IllegalStateException) {
            // Fragment may be detached
        }
    }

    private fun setupConvolverSampleRateFiles() {
        val assignmentPreference = findPreference<Preference>(
            getString(R.string.key_convolver_sample_rate_files)
        ) ?: return
        val filePreference = findPreference<FileLibraryPreference>(
            getString(R.string.key_convolver_file)
        ) ?: return
        val processingPreference = findPreference<Preference>(
            getString(R.string.key_convolver_processing_rate)
        ) ?: return
        val sharedPreferences = preferenceManager.sharedPreferences ?: return
        val assignmentKey = getString(R.string.key_convolver_sample_rate_files)

        fun assignments() = ConvolverSampleRateFiles.decode(
            sharedPreferences.getString(assignmentKey, "").orEmpty()
        )

        fun processingRate(): Int = reportedProcessingSampleRate
            ?: (requireActivity().application as MainApplication).engineSampleRate.roundToInt()

        fun selectedImpulseResponse(rate: Int): String {
            if (!sharedPreferences.getBoolean(getString(R.string.key_convolver_enable), false)) {
                return getString(R.string.convolver_sample_rate_disabled)
            }

            val fallbackFile = filePreference.value.orEmpty()
            val mappedFile = ConvolverSampleRateFiles.resolve(
                sharedPreferences.getString(assignmentKey, "").orEmpty(),
                rate,
                fallbackFile,
            )
            val selectedFile = mappedFile.takeIf {
                File(FileLibraryPreference.createFullPathCompat(requireContext(), it)).isFile
            } ?: fallbackFile
            return selectedFile.takeIf(String::isNotBlank)
                ?.let { File(it).nameWithoutExtension }
                ?: getString(R.string.convolver_sample_rate_no_file)
        }

        fun updateSummary() {
            val count = assignments().size
            val rate = processingRate()
            processingPreference.summary = if (rate > 0) {
                getString(
                    R.string.convolver_processing_rate_status,
                    ConvolverSampleRateFiles.formatKilohertz(rate),
                    selectedImpulseResponse(rate),
                )
            } else {
                getString(R.string.convolver_sample_rate_processing_inactive)
            }
            val assignmentsSummary = if (count == 0)
                getString(R.string.convolver_sample_rate_files_summary)
            else
                getString(R.string.convolver_sample_rate_files_count, count)
            assignmentPreference.summary = assignmentsSummary
        }

        convolverStatusUpdater = ::updateSummary

        assignmentPreference.setOnPreferenceClickListener {
            filePreference.refresh()
            val assignedFiles = assignments()
            val detectedRates = AudioSampleRateDetector.getOutputSampleRates(
                requireContext(),
            )
            val sampleRates = (detectedRates + assignedFiles.keys)
                .filter(ConvolverSampleRateFiles::isSupportedSampleRate)
                .distinct()
                .sorted()
            val rateLabels = sampleRates.map { rate ->
                val fileName = assignedFiles[rate]
                    ?.let { File(it).nameWithoutExtension }
                    ?: getString(R.string.convolver_sample_rate_unassigned)
                getString(
                    R.string.convolver_sample_rate_label,
                    ConvolverSampleRateFiles.formatKilohertz(rate),
                    fileName,
                )
            }.toTypedArray()

            AlertDialog.Builder(requireContext())
                .setTitle(R.string.convolver_sample_rate_files)
                .setItems(rateLabels) { _, rateIndex ->
                    val rate = sampleRates[rateIndex]
                    val choices = arrayOf(getString(R.string.convolver_sample_rate_use_default)) +
                        filePreference.entries.map(CharSequence::toString)

                    AlertDialog.Builder(requireContext())
                        .setTitle(
                            getString(
                                R.string.convolver_sample_rate_select_file,
                                ConvolverSampleRateFiles.formatKilohertz(rate),
                            )
                        )
                        .setItems(choices) { _, fileIndex ->
                            val updatedAssignments = assignments().toMutableMap()
                            if (fileIndex == 0) {
                                updatedAssignments.remove(rate)
                            } else {
                                updatedAssignments[rate] =
                                    filePreference.entryValues[fileIndex - 1].toString()
                            }
                            sharedPreferences.edit()
                                .putString(
                                    assignmentKey,
                                    ConvolverSampleRateFiles.encode(updatedAssignments),
                                )
                                .apply()
                            updateSummary()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }

        updateSummary()
    }

    override fun onCreateRecyclerView(
        inflater: LayoutInflater,
        parent: ViewGroup,
        savedInstanceState: Bundle?,
    ): RecyclerView {
        return super.onCreateRecyclerView(inflater, parent, savedInstanceState).apply {
            itemAnimator = null // Fix to prevent RecyclerView crash if group is toggled rapidly
            isNestedScrollingEnabled = false

            this@PreferenceGroupFragment.recyclerView = this
        }
    }

    override fun onCreateAdapter(preferenceScreen: PreferenceScreen): RecyclerView.Adapter<*> {
        return RoundedRipplePreferenceGroupAdapter(preferenceScreen)
    }

    override fun onDestroy() {
        convolverStatusUpdater = null
        super.onDestroy()
        requireContext().unregisterLocalReceiver(receiver)
        prefsApp.unregisterOnSharedPreferenceChangeListener(listener)
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(listener)
    }

    @Suppress("DEPRECATION")
    override fun onDisplayPreferenceDialog(preference: Preference) {
        when (preference) {
            is EqualizerPreference -> {
                val dialogFragment = EqualizerDialogFragment.newInstance(preference.key)
                dialogFragment.setTargetFragment(this, 0)
                dialogFragment.show(parentFragmentManager, null)
            }
            is CompanderPreference -> {
                val dialogFragment = CompanderDialogFragment.newInstance(preference.key)
                dialogFragment.setTargetFragment(this, 0)
                dialogFragment.show(parentFragmentManager, null)
            }
            is FileLibraryPreference -> {
                val dialogFragment = FileLibraryDialogFragment.newInstance(preference.key)
                dialogFragment.setTargetFragment(this, 0)
                dialogFragment.show(parentFragmentManager, null)
            }
            else -> super.onDisplayPreferenceDialog(preference)
        }
    }

    companion object {
        private const val BUNDLE_PREF_NAME = "preferencesName"
        private const val BUNDLE_XML_RES = "preferencesXmlRes"

        fun newInstance(preferencesName: String?, @XmlRes preferencesXmlRes: Int): PreferenceGroupFragment {
            return PreferenceGroupFragment().apply {
                arguments = Bundle().apply {
                    putString(BUNDLE_PREF_NAME, preferencesName)
                    putInt(BUNDLE_XML_RES, preferencesXmlRes)
                }
            }
        }

        fun cloneInstance(fragment: PreferenceGroupFragment): PreferenceGroupFragment {
            return fragment.requireArguments().let { args ->
                 newInstance(args.getString(BUNDLE_PREF_NAME), args.getInt(BUNDLE_XML_RES))
            }
        }
    }
}
