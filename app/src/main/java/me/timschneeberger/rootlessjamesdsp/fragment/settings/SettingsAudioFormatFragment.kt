package me.timschneeberger.rootlessjamesdsp.fragment.settings

import android.content.Intent
import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.Preference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.activity.OnboardingActivity
import me.timschneeberger.rootlessjamesdsp.interop.BenchmarkManager
import me.timschneeberger.rootlessjamesdsp.preference.MaterialSeekbarPreference
import me.timschneeberger.rootlessjamesdsp.preference.MaterialSwitchPreference
import me.timschneeberger.rootlessjamesdsp.service.RootAudioProcessorService
import me.timschneeberger.rootlessjamesdsp.utils.AccessibilityServiceHelper
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.requestIgnoreBatteryOptimizations
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.showAlert
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.toast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.PermissionExtensions.hasDumpPermission
import me.timschneeberger.rootlessjamesdsp.utils.isRoot
import me.timschneeberger.rootlessjamesdsp.utils.isRootless
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import org.koin.android.ext.android.inject
import timber.log.Timber

class SettingsAudioFormatFragment : SettingsBaseFragment() {

    private val encoding by lazy { findPreference<ListPreference>(getString(R.string.key_audioformat_encoding)) }
    private val bufferSize by lazy { findPreference<MaterialSeekbarPreference>(getString(R.string.key_audioformat_buffersize)) }
    private val legacyMode by lazy { findPreference<MaterialSwitchPreference>(getString(R.string.key_audioformat_processing)) }
    private val enhancedMode by lazy { findPreference<MaterialSwitchPreference>(getString(R.string.key_audioformat_enhanced_processing)) }
    private val enhancedModeInfo by lazy { findPreference<Preference>(getString(R.string.key_audioformat_enhanced_processing_info)) }
    private val benchmark by lazy { findPreference<MaterialSwitchPreference>(getString(R.string.key_audioformat_optimization_benchmark)) }
    private val benchmarkRefresh by lazy { findPreference<Preference>(getString(R.string.key_audioformat_optimization_refresh)) }
    private val processingMode by lazy { findPreference<ListPreference>(getString(R.string.key_processing_mode)) }
    private val androidEqLimiter by lazy { findPreference<MaterialSwitchPreference>(getString(R.string.key_android_eq_limiter)) }
    private val androidEqLatency by lazy { findPreference<ListPreference>(getString(R.string.key_android_eq_latency)) }

    private val preferences: Preferences.App by inject()

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = Constants.PREF_APP
        setPreferencesFromResource(R.xml.app_audio_format_preferences, rootKey)

        // Root: Hide audio format & benchmark category
        encoding?.parent?.isVisible = isRootless()
        benchmark?.parent?.isVisible = !isRoot()

        // Rootless: Hide audio processing category
        legacyMode?.parent?.isVisible = isRoot()
        legacyMode?.setOnPreferenceChangeListener { _, newValue ->
            if (!(newValue as Boolean))
                requireContext().requestIgnoreBatteryOptimizations()
            else
                enhancedMode?.isChecked = false

            RootAudioProcessorService.updateLegacyMode(requireContext(), newValue)
            true
        }
        enhancedMode?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue as Boolean) {
                // Check DUMP permissions
                if(!requireContext().hasDumpPermission()) {
                    Timber.i("Launching enhanced processing onboarding")

                    Intent(requireContext(), OnboardingActivity::class.java).let {
                        it.putExtra(OnboardingActivity.EXTRA_ROOT_SETUP_DUMP_PERM, true)
                        startActivity(it)
                    }
                    return@setOnPreferenceChangeListener false
                }

                RootAudioProcessorService.startServiceEnhanced(requireContext())
            }
            else {
                requireContext().toast(R.string.audio_format_media_apps_need_restart)
                RootAudioProcessorService.stopService(requireContext())
            }
            true
        }
        enhancedModeInfo?.setOnPreferenceClickListener {
            context?.showAlert(
                R.string.audio_format_enhanced_processing_info_title,
                R.string.audio_format_enhanced_processing_info_content
            )
            true
        }

        fun runBenchmark() = context?.let { ctx ->
            BenchmarkManager.runBenchmarks(ctx) {
                CoroutineScope(Dispatchers.Main).launch {
                    benchmark?.isChecked = BenchmarkManager.hasBenchmarksCached()
                }
            }
        }

        benchmark?.isChecked = BenchmarkManager.hasBenchmarksCached()
        benchmark?.setOnPreferenceChangeListener { _, newValue ->
            if(newValue as Boolean)
                runBenchmark()
            else
                BenchmarkManager.clearBenchmarks()
            true
        }
        benchmarkRefresh?.setOnPreferenceClickListener {
            runBenchmark()
            true
        }

        bufferSize?.setDefaultValue(preferences.getDefault<Float>(R.string.key_audioformat_buffersize))
        bufferSize?.setOnPreferenceChangeListener { _, newValue ->
            if((newValue as Float) <= 1024){
                requireContext().toast(R.string.audio_format_buffer_size_warning_low_value, false)
            }
            context?.sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_HARD_REBOOT_CORE))
            true
        }
        encoding?.setOnPreferenceChangeListener { _, _ ->
            context?.sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_HARD_REBOOT_CORE))
            true
        }

        // Processing mode: показывать только в rootless
        processingMode?.let { pref ->
            pref.parent?.isVisible = isRootless()

            pref.setOnPreferenceChangeListener { _, newValue ->
                val modeInt = (newValue as String).toIntOrNull() ?: 1
                val mode = me.timschneeberger.rootlessjamesdsp.audio.ProcessingMode.fromInt(modeInt)
                val oldModeInt = preferences.get<String>(R.string.key_processing_mode).toIntOrNull() ?: 1
                val oldMode = me.timschneeberger.rootlessjamesdsp.audio.ProcessingMode.fromInt(oldModeInt)
                val currentBuffer = preferences.get<Float>(R.string.key_audioformat_buffersize).toInt()

                // Предупреждение при выборе Movie Mode
                if (mode == me.timschneeberger.rootlessjamesdsp.audio.ProcessingMode.MOVIE) {
                    context?.showAlert(
                        R.string.processing_mode_movie,
                        R.string.processing_mode_movie_warning
                    )
                }

                // Suggest buffer adjustment when switching between Standard and Low-latency
                val suggestLowLatency = mode == me.timschneeberger.rootlessjamesdsp.audio.ProcessingMode.LOW_LATENCY
                    && oldMode != me.timschneeberger.rootlessjamesdsp.audio.ProcessingMode.LOW_LATENCY
                    && currentBuffer < 2048
                val suggestStandard = mode == me.timschneeberger.rootlessjamesdsp.audio.ProcessingMode.STANDARD
                    && oldMode == me.timschneeberger.rootlessjamesdsp.audio.ProcessingMode.LOW_LATENCY
                    && currentBuffer > 512

                if (suggestLowLatency || suggestStandard) {
                    val ctx = context
                    if (ctx != null) {
                        val titleId: Int
                        val messageId: Int
                        val targetBuffer: Int
                        if (suggestLowLatency) {
                            titleId = R.string.buffer_suggest_low_latency_title
                            messageId = R.string.buffer_suggest_low_latency_message
                            targetBuffer = 2048
                        } else {
                            titleId = R.string.buffer_suggest_standard_title
                            messageId = R.string.buffer_suggest_standard_message
                            targetBuffer = 512
                        }

                        val builder = com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                            .setTitle(titleId)
                            .setMessage(String.format(getString(messageId), currentBuffer))
                            .setPositiveButton(R.string.buffer_suggest_yes) { dialog, _ ->
                                preferences.set(R.string.key_audioformat_buffersize, targetBuffer.toFloat())
                                bufferSize?.setValue(targetBuffer.toFloat())
                                context?.sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_HARD_REBOOT_CORE))
                                dialog.dismiss()
                            }
                            .setNegativeButton(R.string.buffer_suggest_no) { dialog, _ -> dialog.dismiss() }

                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            builder.show()
                        }
                    }
                }

                context?.sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_HARD_REBOOT_CORE))
                true
            }
        }

        // Android EQ настройки: видны только в rootless
        androidEqLimiter?.parent?.let { category ->
            category.isVisible = isRootless()
        }
        androidEqLimiter?.setOnPreferenceChangeListener { _, _ ->
            context?.sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_HARD_REBOOT_CORE))
            true
        }
        androidEqLatency?.setOnPreferenceChangeListener { _, _ ->
            context?.sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_HARD_REBOOT_CORE))
            true
        }

        // Smooth volume control
        val smoothVolumeSwitch = findPreference<MaterialSwitchPreference>(getString(R.string.key_smooth_volume_enabled))
        val smoothVolumeSettings = findPreference<Preference>("smooth_volume_accessibility_settings")

        smoothVolumeSettings?.setOnPreferenceClickListener {
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            true
        }

        smoothVolumeSwitch?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue as Boolean) {
                // Check if accessibility service is enabled
                val enabled = isAccessibilityServiceEnabled()
                if (!enabled) {
                    // Try to enable automatically first
                    val ctx = context
                    if (ctx != null) {
                        val result = AccessibilityServiceHelper.tryEnableService(ctx)
                        when (result) {
                            AccessibilityServiceHelper.Result.SUCCESS -> {
                                // Service enabled! Update switch state
                                true
                            }
                            AccessibilityServiceHelper.Result.NEEDS_MANUAL_SETUP -> {
                                showAccessibilitySetupDialog()
                                // Don't toggle the switch yet
                                false
                            }
                            else -> {
                                // Failed - show dialog
                                showAccessibilitySetupDialog()
                                false
                            }
                        }
                    } else {
                        false
                    }
                } else {
                    true
                }
            } else {
                // Disabling - also try to disable the accessibility service
                context?.let { ctx ->
                    val component = "${ctx.packageName}/${me.timschneeberger.rootlessjamesdsp.service.VolumeKeyAccessibilityService::class.java.name}"
                    val existing = android.provider.Settings.Secure.getString(
                        ctx.contentResolver,
                        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                    ) ?: ""
                    if (existing.contains(component)) {
                        val newServices = existing.split(":")
                            .filter { it != component }
                            .joinToString(":")
                        try {
                            android.provider.Settings.Secure.putString(
                                ctx.contentResolver,
                                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                                newServices
                            )
                        } catch (e: SecurityException) {
                            // No WRITE_SECURE_SETTINGS - can't disable via settings
                            // User will need to disable manually
                        }
                    }
                }
                true
            }
        }
    }

    private fun showAccessibilitySetupDialog() {
        val ctx = context ?: return
        val isRestricted = AccessibilityServiceHelper.isRestricted(ctx)
        val hasWriteSecure = ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

        if (isRestricted && !hasWriteSecure) {
            // Show the restricted dialog with ADB instructions
            val adbCommands = AccessibilityServiceHelper.getAdbEnableCommands(ctx)
            com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.smooth_volume_restricted_title)
                .setMessage(getString(R.string.smooth_volume_restricted_message, adbCommands))
                .setPositiveButton(R.string.smooth_volume_auto_enable) { dialog, _ ->
                    // Try auto-enable (maybe Shizuku was set up)
                    val result = AccessibilityServiceHelper.tryEnableService(ctx)
                    if (result != AccessibilityServiceHelper.Result.SUCCESS) {
                        // Fall back to opening system settings
                        startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        } else {
            // Not restricted or has WRITE_SECURE_SETTINGS - try to enable directly
            val result = AccessibilityServiceHelper.tryEnableService(ctx)
            if (result != AccessibilityServiceHelper.Result.SUCCESS) {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.smooth_volume_header)
                    .setMessage(R.string.smooth_volume_accessibility_hint)
                    .setPositiveButton(R.string.smooth_volume_open_settings) { dialog, _ ->
                        startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        dialog.dismiss()
                    }
                    .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                        dialog.dismiss()
                    }
                    .show()
            }
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponent = "${requireContext().packageName}/${me.timschneeberger.rootlessjamesdsp.service.VolumeKeyAccessibilityService::class.java.name}"
        val enabledServices = android.provider.Settings.Secure.getString(
            requireContext().contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(expectedComponent)
    }

    companion object {
        fun newInstance(): SettingsAudioFormatFragment {
            return SettingsAudioFormatFragment()
        }
    }
}