package me.timschneeberger.rootlessjamesdsp.fragment.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.service.SmoothVolumeHudService
import me.timschneeberger.rootlessjamesdsp.smoothvolume.HudStyle
import me.timschneeberger.rootlessjamesdsp.smoothvolume.SmoothVolumeHudConfig
import me.timschneeberger.rootlessjamesdsp.smoothvolume.SmoothVolumeHudView
import me.timschneeberger.rootlessjamesdsp.smoothvolume.VolumeSource
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import org.koin.android.ext.android.inject
import timber.log.Timber

/**
 * Smooth Volume Mini-HUD customization page.
 *
 * The preview card at the top embeds the very same [SmoothVolumeHudView] used
 * by the real overlay, in preview mode: gestures work, but all callbacks are
 * redirected to a local state so the actual DSP gain is never touched and no
 * extra overlay window is created.
 */
class SettingsSmoothVolumeHudFragment : SettingsBaseFragment() {

    private val prefs: Preferences.App by inject()

    private var previewView: SmoothVolumeHudView? = null
    private var previewDb = 0.0
    private var previewMuted = false

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        // Read/write Constants.PREF_APP ("application") — the same file
        // Preferences.App reads. Without this the androidx default
        // ("<package>_preferences.xml") file is used, which no runtime code
        // reads, so none of the HUD settings had any effect.
        preferenceManager.sharedPreferencesName = Constants.PREF_APP
        setPreferencesFromResource(R.xml.smooth_volume_hud_preferences, rootKey)

        // Live-update the preview on every appearance change
        arrayOf(
            R.string.key_smooth_volume_hud_orientation,
            R.string.key_smooth_volume_hud_style,
            R.string.key_smooth_volume_hud_position,
            R.string.key_smooth_volume_hud_size,
            R.string.key_smooth_volume_hud_opacity,
            R.string.key_smooth_volume_hud_theme,
            R.string.key_smooth_volume_hud_corner_radius,
            R.string.key_smooth_volume_hud_auto_hide,
            R.string.key_smooth_volume_hud_haptics,
            R.string.key_smooth_volume_hud_animations,
            R.string.key_smooth_volume_hud_action_tap,
            R.string.key_smooth_volume_hud_action_double_tap,
            R.string.key_smooth_volume_hud_action_long_press,
            R.string.key_smooth_volume_hud_swipe_step,
            R.string.key_smooth_volume_hud_swipe_accel,
            R.string.key_smooth_volume_hud_reverse,
            R.string.key_smooth_volume_hud_enabled,
        ).forEach { keyRes ->
            // ListPreference/SeekBar handle persistence themselves; we only
            // need the change notification to rebuild the preview.
            findPreference<Preference>(getString(keyRes))?.setOnPreferenceChangeListener { _, _ ->
                refreshPreview()
                true
            }
        }

        // The unavailable notice is only relevant when no hosting path exists
        updateUnavailableNotice()

        findPreference<Preference>("smooth_volume_hud_grant_overlay")?.setOnPreferenceClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${requireContext().packageName}")))
            true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        refreshPreview()
    }

    override fun onResume() {
        super.onResume()
        updateUnavailableNotice()
    }

    private fun updateUnavailableNotice() {
        val accessibilityConnected = try {
            me.timschneeberger.rootlessjamesdsp.utils.AccessibilityServiceHelper
                .isServiceEnabled(requireContext())
        } catch (e: Exception) {
            Timber.w(e)
            false
        }
        val canOverlay = Settings.canDrawOverlays(requireContext())
        findPreference<PreferenceCategory>("smooth_volume_hud_availability")?.isVisible =
            !accessibilityConnected && !canOverlay
        findPreference<Preference>("smooth_volume_hud_grant_overlay")?.isVisible =
            !accessibilityConnected && !canOverlay

        // Keep the fallback host in sync with the (possibly new) state
        SmoothVolumeHudService.start(requireContext())
    }

    private fun refreshPreview() {
        val container = view?.findViewById<FrameLayout>(R.id.smooth_volume_hud_preview_container) ?: return
        val config = SmoothVolumeHudConfig.fromPreferences(prefs)
        val existing = previewView
        if (existing == null) {
            val hudView = SmoothVolumeHudView(requireContext(), config, previewMode = true)
            hudView.callbacks = object : SmoothVolumeHudView.Callbacks {
                override fun onGainRequested(db: Double) { previewDb = db; hudView.gainDb = db }
                override fun onAdjustRequested(deltaDb: Double) {
                    previewDb = me.timschneeberger.rootlessjamesdsp.smoothvolume.SmoothVolumeMath
                        .adjust(previewDb, deltaDb)
                    hudView.gainDb = previewDb
                }
                override fun onMuteRequested() { previewMuted = true; hudView.muted = true }
                override fun onUnmuteRequested() { previewMuted = false; hudView.muted = false }
                override fun onResetRequested() { previewDb = 0.0; previewMuted = false; hudView.gainDb = 0.0; hudView.muted = false }
                override fun onOpenAppRequested() { /* no-op in preview */ }
                override fun onCollapseRequested() { hudView.expanded = false }
            override fun onExpandStateChanged(expanded: Boolean) { /* preview resizes via requestLayout */ }
                override fun onMoved(x: Int, y: Int) { /* in-panel drag: view-local */ }
                override fun onMoveEnded() {}
            }
            previewView = hudView
            container.addView(hudView)
        }
        else {
            existing.reconfigure(config)
        }
    }

    companion object {
        fun newInstance(): SettingsSmoothVolumeHudFragment = SettingsSmoothVolumeHudFragment()
    }
}
