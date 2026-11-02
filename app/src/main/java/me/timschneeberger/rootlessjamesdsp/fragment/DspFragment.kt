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
import me.timschneeberger.rootlessjamesdsp.interop.PreferenceCache
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.isRootless
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

    /** Effect cards in their baseline (layout) order, resolved after inflation. */
    private val effectCards = mutableListOf<EffectCard>()
    /** Non-effect cards pinned above all effect cards (notices, device profiles). */
    private var pinnedCards = emptyList<View>()
    private val registeredPrefNames = mutableSetOf<String>()

    /** Maps the inner card id (FragmentContainerView) to its prefs file and enable key. */
    private val effectCardSpecs = listOf(
        Triple(R.id.card_output_control, Constants.PREF_OUTPUT, R.string.key_output_control_enable),
        Triple(R.id.card_compressor, Constants.PREF_COMPANDER, R.string.key_compander_enable),
        Triple(R.id.card_bass, Constants.PREF_BASS, R.string.key_bass_enable),
        Triple(R.id.card_eq, Constants.PREF_EQ, R.string.key_eq_enable),
        Triple(R.id.card_peq, Constants.PREF_PEQ, R.string.key_peq_enable),
        Triple(R.id.card_dynamiceq, Constants.PREF_DYNAMICEQ, R.string.key_dynamiceq_enable),
        Triple(R.id.card_squig, Constants.PREF_SQUIG, R.string.key_squig_enable),
        Triple(R.id.card_geq, Constants.PREF_GEQ, R.string.key_geq_enable),
        Triple(R.id.card_ddc, Constants.PREF_DDC, R.string.key_ddc_enable),
        Triple(R.id.card_convolver, Constants.PREF_CONVOLVER, R.string.key_convolver_enable),
        Triple(R.id.card_liveprog, Constants.PREF_LIVEPROG, R.string.key_liveprog_enable),
        Triple(R.id.card_tube, Constants.PREF_TUBE, R.string.key_tube_enable),
        Triple(R.id.card_harmonic_expander, Constants.PREF_HARMONIC_EXPANDER, R.string.key_harmonic_expander_enable),
        Triple(R.id.card_subharmonic_expander, Constants.PREF_SUBHARMONIC_EXPANDER, R.string.key_subharmonic_expander_enable),
        Triple(R.id.card_stereowide, Constants.PREF_STEREOWIDE, R.string.key_stereowide_enable),
        Triple(R.id.card_crossfeed, Constants.PREF_CROSSFEED, R.string.key_crossfeed_enable),
        Triple(R.id.card_reverb, Constants.PREF_REVERB, R.string.key_reverb_enable),
        Triple(R.id.card_loudness, Constants.PREF_LOUDNESS, R.string.key_loudness_enable),
        Triple(R.id.card_fetcomp, Constants.PREF_FETCOMP, R.string.key_fetcomp_enable),
        Triple(R.id.card_clarity, Constants.PREF_CLARITY, R.string.key_clarity_enable),
        Triple(R.id.card_fieldsurround, Constants.PREF_FIELDSURROUND, R.string.key_fieldsurround_enable),
        Triple(R.id.card_agc, Constants.PREF_AGC, R.string.key_agc_enable),
        Triple(R.id.card_cure, Constants.PREF_CURE, R.string.key_cure_enable),
        Triple(R.id.card_tape, Constants.PREF_TAPE, R.string.key_tape_enable),
        Triple(R.id.card_voice_isolation, Constants.PREF_VOICE_ISOLATION, R.string.key_voice_isolation_enable),
    )

    private data class EffectCard(
        val view: View,
        val prefName: String,
        val enableKey: String,
    )

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
                R.id.card_dynamiceq, PreferenceGroupFragment.newInstance(Constants.PREF_DYNAMICEQ,
                    R.xml.dsp_dynamiceq_preferences
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
            .replace(
                R.id.card_fetcomp, PreferenceGroupFragment.newInstance(Constants.PREF_FETCOMP,
                    R.xml.dsp_fetcomp_preferences
                ))
            .replace(
                R.id.card_clarity, PreferenceGroupFragment.newInstance(Constants.PREF_CLARITY,
                    R.xml.dsp_clarity_preferences
                ))
            .replace(
                R.id.card_fieldsurround, PreferenceGroupFragment.newInstance(Constants.PREF_FIELDSURROUND,
                    R.xml.dsp_fieldsurround_preferences
                ))
            .replace(
                R.id.card_agc, PreferenceGroupFragment.newInstance(Constants.PREF_AGC,
                    R.xml.dsp_agc_preferences
                ))
            .replace(
                R.id.card_cure, PreferenceGroupFragment.newInstance(Constants.PREF_CURE,
                    R.xml.dsp_cure_preferences
                ))
            .replace(
                R.id.card_tape, PreferenceGroupFragment.newInstance(Constants.PREF_TAPE,
                    R.xml.dsp_tape_preferences
                ))
            .replace(
                R.id.card_voice_isolation, PreferenceGroupFragment.newInstance(Constants.PREF_VOICE_ISOLATION,
                    R.xml.dsp_voice_isolation_preferences
                ))
            .commit()

        collectEffectCards()
        applyEffectCardOrder()

        // Load initial preferences
        arrayOf(R.string.key_device_profiles_enable, R.string.key_processing_mode).forEach {
            onSharedPreferenceChanged(null, getString(it))
        }

        return binding.root
    }

    override fun onDestroyView() {
        registeredPrefNames.forEach {
            PreferenceCache.getPreferences(requireContext(), it)
                .unregisterOnSharedPreferenceChangeListener(this)
        }
        registeredPrefNames.clear()
        effectCards.clear()
        pinnedCards = emptyList()
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        // Catch enable changes made outside this fragment's SharedPreferences
        // instance (service process, preset/profile loads).
        applyEffectCardOrder()
    }

    /**
     * Resolve the card_container children into effect cards and pinned cards.
     * Also registers preference change listeners on every effect prefs file
     * (same-process SharedPreferences instances are shared across modes, so
     * edits made by the embedded PreferenceGroupFragments fire these too).
     */
    private fun collectEffectCards() {
        val container = binding.cardContainer
        effectCards.clear()
        pinnedCards = emptyList()
        val pinned = mutableListOf<View>()

        val children = (0 until container.childCount).map { container.getChildAt(it) }
        for (child in children) {
            val spec = effectCardSpecs.firstOrNull { child.findViewById<View>(it.first) != null }
            when {
                spec != null -> effectCards.add(
                    EffectCard(child, spec.second, getString(spec.third))
                )
                child.id == R.id.translation_notice ||
                    child.id == R.id.update_notice ||
                    child.findViewById<View>(R.id.card_device_profiles) != null -> pinned.add(child)
            }
        }
        pinnedCards = pinned

        effectCards.map { it.prefName }.distinct().forEach { name ->
            PreferenceCache.getPreferences(requireContext(), name)
                .registerOnSharedPreferenceChangeListener(this)
            registeredPrefNames.add(name)
        }
    }

    private fun isEffectEnabled(card: EffectCard): Boolean =
        runCatching {
            PreferenceCache.getPreferences(requireContext(), card.prefName)
                .getBoolean(card.enableKey, false)
        }.getOrDefault(false)

    /**
     * Bring enabled effect cards to the top (keeping their relative order),
     * below the pinned non-effect cards; everything else keeps baseline order.
     */
    private fun applyEffectCardOrder() {
        if (!::binding.isInitialized || effectCards.isEmpty()) return
        val container = binding.cardContainer
        val children = (0 until container.childCount).map { container.getChildAt(it) }
        if (children.size < 2) return

        val enabled = effectCards.filter { isEffectEnabled(it) }.map { it.view }.toSet()
        val pinned = pinnedCards.toSet()

        val ordered = ArrayList<View>(children.size)
        ordered.addAll(children.filter { it in pinned })
        ordered.addAll(children.filter { it in enabled })
        ordered.addAll(children.filter { it !in pinned && it !in enabled })

        if (children == ordered) return

        val parent = container.parent as? ViewGroup ?: return
        // Pause layout transitions while re-attaching to avoid flicker; the
        // CHANGING transition animates the final layout shift once.
        val transition = container.layoutTransition
        container.layoutTransition = null
        children.forEach { container.removeView(it) }
        ordered.forEach { container.addView(it, it.layoutParams) }
        container.layoutTransition = transition
        container.requestLayout()
        parent.invalidate()
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
            else -> if (key != null && key.endsWith("_enable") && ::binding.isInitialized) {
                applyEffectCardOrder()
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
        binding.cardDynamiceq.isVisible = !directMode
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
        binding.cardFetcomp.isVisible = !directMode
        binding.cardClarity.isVisible = !directMode
        binding.cardFieldsurround.isVisible = !directMode
        binding.cardAgc.isVisible = !directMode
        binding.cardCure.isVisible = !directMode
        binding.cardTape.isVisible = !directMode
        // The enhancer runs in this process, so the root HAL engine cannot host it
        binding.cardVoiceIsolation.isVisible = !directMode && isRootless()
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