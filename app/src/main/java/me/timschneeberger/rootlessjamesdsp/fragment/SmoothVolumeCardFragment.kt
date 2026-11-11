package me.timschneeberger.rootlessjamesdsp.fragment

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import com.google.android.material.slider.Slider
import kotlinx.coroutines.launch
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.activity.SettingsActivity
import me.timschneeberger.rootlessjamesdsp.databinding.FragmentSmoothVolumeCardBinding
import me.timschneeberger.rootlessjamesdsp.service.SmoothVolumeHudService
import me.timschneeberger.rootlessjamesdsp.smoothvolume.SmoothVolumeController
import me.timschneeberger.rootlessjamesdsp.smoothvolume.SmoothVolumeMath
import me.timschneeberger.rootlessjamesdsp.smoothvolume.VolumeSource
import org.koin.android.ext.android.inject

/**
 * Main-screen Smooth Volume card: live dB value, slider (-60..0 dB, 0.5 step),
 * Mute/Unmute, Reset 0 dB, DSP/HUD status and a link to the Mini-HUD settings.
 *
 * Works independently of the accessibility service; moving the slider while
 * the feature is disabled re-enables it (the previous value was reset to 0 dB
 * on disable).
 */
class SmoothVolumeCardFragment : Fragment() {

    private var _binding: FragmentSmoothVolumeCardBinding? = null
    private val binding get() = _binding!!

    private val controller: SmoothVolumeController by inject()

    // Guard against feedback: slider updates from state collection vs user drags
    private var suppressSliderListener = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSmoothVolumeCardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.smoothVolumeSlider.addOnChangeListener(Slider.OnChangeListener { _, value, fromUser ->
            if (suppressSliderListener || !fromUser) return@OnChangeListener
            if (!controller.state.value.enabled) {
                // User intent is explicit: sliding the card turns the feature on
                controller.setEnabled(true)
            }
            controller.setGain(value.toDouble(), VolumeSource.APP)
        })

        binding.smoothVolumeMute.setOnClickListener {
            val state = controller.state.value
            if (!state.enabled) controller.setEnabled(true)
            controller.toggleMute(VolumeSource.APP)
        }

        binding.smoothVolumeReset.setOnClickListener {
            if (!controller.state.value.enabled) controller.setEnabled(true)
            controller.reset(VolumeSource.APP)
        }

        binding.smoothVolumeHudSettings.setOnClickListener {
            // Make sure the fallback host is running when accessibility is off
            SmoothVolumeHudService.start(requireContext())
            startActivity(
                Intent(requireContext(), SettingsActivity::class.java)
                    .putExtra(SettingsActivity.EXTRA_OPEN_FRAGMENT,
                        "me.timschneeberger.rootlessjamesdsp.fragment.settings.SettingsSmoothVolumeHudFragment")
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.state.collect { render(it) }
            }
        }
    }

    private fun render(state: SmoothVolumeController.SmoothVolumeState) {
        suppressSliderListener = true
        binding.smoothVolumeValue.text = if (state.muted)
            getString(R.string.smooth_volume_card_mute).uppercase()
        else
            SmoothVolumeMath.formatDb(state.gainDb)
        binding.smoothVolumeSlider.value = state.gainDb.toFloat().coerceIn(-60f, 0f)
        binding.smoothVolumePositionLabel.text = getString(
            R.string.smooth_volume_card_slider_position_percent,
            Math.round(SmoothVolumeMath.dbToPosition(state.gainDb) * 100).toInt()
        )
        suppressSliderListener = false

        binding.smoothVolumeMute.text = getString(
            if (state.muted) R.string.smooth_volume_card_unmute else R.string.smooth_volume_card_mute)

        binding.smoothVolumeDisabledNotice.isVisible = !state.enabled

        val dspText = getString(
            if (state.enabled && state.dspActive) R.string.smooth_volume_card_status_dsp_active
            else R.string.smooth_volume_card_status_dsp_inactive)
        val hudText = getString(
            if (state.hudAvailable) R.string.smooth_volume_card_status_hud_ok
            else R.string.smooth_volume_card_status_hud_unavailable)
        val muteSuffix = if (state.muted && state.enabled) " · ${getString(R.string.smooth_volume_card_status_muted)}" else ""
        binding.smoothVolumeStatus.text = "$dspText · $hudText$muteSuffix"
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        fun newInstance(): SmoothVolumeCardFragment = SmoothVolumeCardFragment()
    }
}
