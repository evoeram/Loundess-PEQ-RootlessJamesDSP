package me.timschneeberger.rootlessjamesdsp.fragment

import android.animation.LayoutTransition
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.audio.ProcessingMode
import me.timschneeberger.rootlessjamesdsp.databinding.FragmentDspBinding
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.util.Locale

class DspFragment : Fragment(), SharedPreferences.OnSharedPreferenceChangeListener {
    private val prefsApp: Preferences.App by inject()
    private val prefsVar: Preferences.Var by inject()

    private lateinit var binding: FragmentDspBinding
    private var updateNoticeOnClick: (() -> Unit)? = null
    private var updateNoticeOnCloseClick: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        prefsApp.registerOnSharedPreferenceChangeListener(this)
        super.onCreate(savedInstanceState)
    }

    override fun onDestroy() {
        prefsApp.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentDspBinding.inflate(layoutInflater, container, false)

        binding.translationNotice.setOnCloseClickListener(::hideTranslationNotice)
        binding.translationNotice.setOnRootClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://crowdin.com/project/rootlessjamesdsp")))
            hideTranslationNotice()
        }

        binding.updateNotice.setOnCloseClickListener {
            updateNoticeOnCloseClick?.invoke()
        }
        binding.updateNotice.setOnRootClickListener {
            updateNoticeOnClick?.invoke()
        }

        // Should show notice?
        Timber.e(Locale.getDefault().language.toString())
        binding.translationNotice.isVisible =
           prefsVar.get<Long>(R.string.key_snooze_translation_notice) < (System.currentTimeMillis() / 1000L) &&
                    !Locale.getDefault().language.equals("en")
        binding.updateNotice.isVisible = false

        val transition = LayoutTransition()
        transition.enableTransitionType(LayoutTransition.CHANGING)
        binding.cardContainer.layoutTransition = transition

        childFragmentManager.beginTransaction()
            .replace(R.id.card_device_profiles, DeviceCardsFragment.newInstance())
            .replace(
                R.id.card_output_control, PreferenceGroupFragment.newInstance(Constants.PREF_OUTPUT,
                    R.xml.dsp_output_control_preferences
                ))
            .replace(
                R.id.card_compressor, PreferenceGroupFragment.newInstance(Constants.PREF_COMPANDER,
                    R.xml.dsp_compander_preferences
                ))
            .replace(
                R.id.card_bass, PreferenceGroupFragment.newInstance(Constants.PREF_BASS,
                    R.xml.dsp_bass_preferences
                ))
            .replace(
                R.id.card_eq, PreferenceGroupFragment.newInstance(Constants.PREF_EQ,
                    R.xml.dsp_equalizer_preferences
                ))
            .replace(
                R.id.card_measurement, PreferenceGroupFragment.newInstance(Constants.PREF_MEASUREMENT,
                    R.xml.dsp_measurement_preferences
                ))
            .replace(
                R.id.card_peq, PreferenceGroupFragment.newInstance(Constants.PREF_PEQ,
                    R.xml.dsp_parametriceq_preferences
                ))
            .replace(
                R.id.card_squig, PreferenceGroupFragment.newInstance(Constants.PREF_SQUIG,
                    R.xml.dsp_squig_preferences
                ))
            .replace(
                R.id.card_squig_preview, SquigPreviewFragment.newInstance()
            )
            .replace(
                R.id.card_nosr2r, PreferenceGroupFragment.newInstance(Constants.PREF_NOSR2R,
                    R.xml.dsp_nosr2r_preferences
                ))
            .replace(
                R.id.card_geq, PreferenceGroupFragment.newInstance(Constants.PREF_GEQ,
                    R.xml.dsp_graphiceq_preferences
                ))
            .replace(
                R.id.card_ddc, PreferenceGroupFragment.newInstance(Constants.PREF_DDC,
                    R.xml.dsp_ddc_preferences
                ))
            .replace(
                R.id.card_convolver, PreferenceGroupFragment.newInstance(Constants.PREF_CONVOLVER,
                    R.xml.dsp_convolver_preferences
                ))
            .replace(
                R.id.card_liveprog, PreferenceGroupFragment.newInstance(Constants.PREF_LIVEPROG,
                    R.xml.dsp_liveprog_preferences
                ))
            .replace(
                R.id.card_tube, PreferenceGroupFragment.newInstance(Constants.PREF_TUBE,
                    R.xml.dsp_tube_preferences
                ))
            .replace(
                R.id.card_harmonic_expander, PreferenceGroupFragment.newInstance(Constants.PREF_HARMONIC_EXPANDER,
                    R.xml.dsp_harmonic_expander_preferences
                ))
            .replace(
                R.id.card_subharmonic_expander, PreferenceGroupFragment.newInstance(Constants.PREF_SUBHARMONIC_EXPANDER,
                    R.xml.dsp_subharmonic_expander_preferences
                ))
            .replace(
                R.id.card_stereowide, PreferenceGroupFragment.newInstance(Constants.PREF_STEREOWIDE,
                    R.xml.dsp_stereowide_preferences
                ))
            .replace(
                R.id.card_crossfeed, PreferenceGroupFragment.newInstance(Constants.PREF_CROSSFEED,
                    R.xml.dsp_crossfeed_preferences
                ))
            .replace(
                R.id.card_reverb, PreferenceGroupFragment.newInstance(Constants.PREF_REVERB,
                    R.xml.dsp_reverb_preferences
                ))
            .replace(
                R.id.card_loudness, PreferenceGroupFragment.newInstance(Constants.PREF_LOUDNESS,
                    R.xml.dsp_loudness_preferences
                ))
            .commit()

        // Load initial preferences
        arrayOf(R.string.key_device_profiles_enable, R.string.key_processing_mode).forEach {
            onSharedPreferenceChanged(null, getString(it))
        }

        return binding.root
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when(key) {
            getString(R.string.key_device_profiles_enable) -> {
                (binding.cardDeviceProfiles.parent as ViewGroup).isVisible =
                    prefsApp.get<Boolean>(R.string.key_device_profiles_enable)
            }
            getString(R.string.key_processing_mode) -> {
                updateCardVisibility()
            }
        }
    }

    /**
     * В Direct Mode (Android EQ) capture loop не запускается, поэтому плагины
     * JamesDSP (компрессор, бас, реверберация и т.д.) не работают.
     * Скрываем их карточки, оставляя только EQ (GraphicEQ + PEQ).
     */
    private fun updateCardVisibility() {
        val modeInt = prefsApp.get<String>(R.string.key_processing_mode).toIntOrNull() ?: 1
        val mode = ProcessingMode.fromInt(modeInt)
        val directMode = mode == ProcessingMode.DIRECT

        // В Direct Mode доступны только EQ-карточки (GEQ + PEQ через AndroidEq/DynamicsProcessing);
        // Multi EQ — нативный плагин JamesDSP, требует capture loop — скрываем
        binding.cardEq.isVisible = !directMode
        // Остальные плагины тоже требуют capture loop — скрываем
        binding.cardCompressor.isVisible = !directMode
        binding.cardBass.isVisible = !directMode
        binding.cardDdc.isVisible = !directMode
        binding.cardConvolver.isVisible = !directMode
        binding.cardLiveprog.isVisible = !directMode
        binding.cardTube.isVisible = !directMode
        binding.cardHarmonicExpander.isVisible = !directMode
        binding.cardSubharmonicExpander.isVisible = !directMode
        binding.cardNosr2r.isVisible = !directMode
        binding.cardStereowide.isVisible = !directMode
        binding.cardCrossfeed.isVisible = !directMode
        binding.cardReverb.isVisible = !directMode
        binding.cardLoudness.isVisible = !directMode
        // Output control (limiter) тоже не работает без capture loop
        binding.cardOutputControl.isVisible = !directMode
        // Measurement (MEOW) бесполезен без capture loop
        binding.cardSquig.isVisible = !directMode
        binding.cardSquigPreview.isVisible = !directMode
        binding.cardMeasurement.isVisible = !directMode
    }

    private fun hideTranslationNotice() {
        binding.translationNotice.isVisible = false
        // Set timer +1y
        prefsVar.set<Long>(R.string.key_snooze_translation_notice, (System.currentTimeMillis() / 1000L) + 31536000L)
    }

    fun setUpdateCardVisible(visible: Boolean) {
        binding.updateNotice.isVisible = visible
    }

    fun setUpdateCardTitle(title: String) {
        binding.updateNotice.titleText = title
    }

    fun setUpdateCardOnClick(onClick: () -> Unit) {
        updateNoticeOnClick = onClick
    }

    fun setUpdateCardOnCloseClick(onClick: () -> Unit) {
        updateNoticeOnCloseClick = onClick
    }

    fun restartFragment(id: Int, newFragment: Fragment) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                childFragmentManager.beginTransaction()
                    .replace(id, newFragment)
                    .commitAllowingStateLoss()
            }
            catch(ex: IllegalStateException) {
                Timber.e("Failed to restart fragment")
                Timber.i(ex)
            }
        }
    }

    companion object {
        fun newInstance(): DspFragment {
            return DspFragment()
        }
    }
}