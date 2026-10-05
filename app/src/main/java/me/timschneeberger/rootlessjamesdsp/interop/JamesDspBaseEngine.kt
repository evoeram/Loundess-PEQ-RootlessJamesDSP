package me.timschneeberger.rootlessjamesdsp.interop

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.interop.structure.EelVmVariable
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqBandList
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqChannel
import me.timschneeberger.rootlessjamesdsp.model.ProcessorMessage
import me.timschneeberger.rootlessjamesdsp.preference.FileLibraryPreference
import me.timschneeberger.rootlessjamesdsp.utils.BiquadUtils
import me.timschneeberger.rootlessjamesdsp.utils.ConvolverSampleRateFiles
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException
import java.io.FileReader

abstract class JamesDspBaseEngine(val context: Context, val callbacks: JamesDspWrapper.JamesDspCallbacks? = null) : AutoCloseable {
    abstract var enabled: Boolean
    open var sampleRate: Float = 0.0f
        set(value) {
            field = value
            reportSampleRate(value)
        }

    private val syncScope = CoroutineScope(Dispatchers.IO)
    private val syncMutex = Mutex()
    protected val cache = PreferenceCache(context)

    override fun close() {
        Timber.d("Closing engine")
        reportSampleRate(0f)
        syncScope.cancel()
    }

    open fun syncWithPreferences(forceUpdateNamespaces: Array<String>? = null) {
        syncScope.launch {
            syncWithPreferencesAsync(forceUpdateNamespaces)
        }
    }

    fun clearCache() {
        cache.clear()
    }

    private fun reportSampleRate(value: Float) {
        context.sendLocalBroadcast(Intent(Constants.ACTION_REPORT_SAMPLE_RATE).apply {
            putExtra(Constants.EXTRA_SAMPLE_RATE, value)
        })
    }

    private suspend fun syncWithPreferencesAsync(forceUpdateNamespaces: Array<String>? = null) {
        Timber.d("Synchronizing with preferences... (forced: %s)", forceUpdateNamespaces?.joinToString(";") { it })

        syncMutex.withLock {
            cache.select(Constants.PREF_OUTPUT)
            val outputPostGain = cache.get(R.string.key_output_postgain, 0f)
            val limiterThreshold = cache.get(R.string.key_limiter_threshold, -0.1f)
            val limiterRelease = cache.get(R.string.key_limiter_release, 60f)

            cache.select(Constants.PREF_COMPANDER)
            val compEnabled = cache.get(R.string.key_compander_enable, false)
            val compTimeConst = cache.get(R.string.key_compander_timeconstant, 0.22f)
            val compGranularity = cache.get(R.string.key_compander_granularity, 2f).toInt()
            val compTfTransforms = cache.get(R.string.key_compander_tftransforms, "0").toInt()
            val compResponse = cache.get(R.string.key_compander_response, "95.0;200.0;400.0;800.0;1600.0;3400.0;7500.0;0;0;0;0;0;0;0")

            cache.select(Constants.PREF_BASS)
            val bassEnabled = cache.get(R.string.key_bass_enable, false)
            val bassMaxGain = cache.get(R.string.key_bass_max_gain, 5f)

            cache.select(Constants.PREF_EQ)
            val eqEnabled = cache.get(R.string.key_eq_enable, false)
            val eqFilterType = cache.get(R.string.key_eq_filter_type, "0").toInt()
            val eqInterpolationMode = cache.get(R.string.key_eq_interpolation, "0").toInt()
            val eqBands = cache.get(R.string.key_eq_bands, Constants.DEFAULT_EQ)

            cache.select(Constants.PREF_GEQ)
            val geqEnabled = cache.get(R.string.key_geq_enable, false)
            val geqBands = cache.get(R.string.key_geq_nodes, Constants.DEFAULT_GEQ_INTERNAL)

            cache.select(Constants.PREF_PEQ)
            val peqEnabled = cache.get(R.string.key_peq_enable, false)
            val peqBandsStr = cache.get(R.string.key_peq_bands, Constants.DEFAULT_PEQ)
            val peqPreamp = cache.get(R.string.key_peq_preamp, 0f)

            // Squig Live plugin PEQ (independent namespace; merged with main PEQ)
            cache.select(Constants.PREF_SQUIG)
            val squigEnabled = cache.get(R.string.key_squig_peq_enable, false)
            val squigBandsStr = cache.get(R.string.key_squig_peq_bands, Constants.DEFAULT_PEQ)
            val squigPreamp = cache.get(R.string.key_squig_peq_preamp, 0f)

            cache.select(Constants.PREF_REVERB)
            val reverbEnabled = cache.get(R.string.key_reverb_enable, false)
            val reverbPreset = cache.get(R.string.key_reverb_preset, "0").toInt()

            cache.select(Constants.PREF_STEREOWIDE)
            val swEnabled = cache.get(R.string.key_stereowide_enable, false)
            val swMode = cache.get(R.string.key_stereowide_mode, 60f)

            cache.select(Constants.PREF_CROSSFEED)
            val crossfeedEnabled = cache.get(R.string.key_crossfeed_enable, false)
            val crossfeedMode = cache.get(R.string.key_crossfeed_mode, "0").toInt()

            cache.select(Constants.PREF_TUBE)
            val tubeEnabled = cache.get(R.string.key_tube_enable, false)
            val tubeDrive = cache.get(R.string.key_tube_drive, 2f)

            // Harmonic Expander
            cache.select(Constants.PREF_HARMONIC_EXPANDER)
            val harmExpEnabled = cache.get(R.string.key_harmonic_expander_enable, false)
            val harmExpGainIds = intArrayOf(
                R.string.key_harmonic_expander_gain_2, R.string.key_harmonic_expander_gain_3,
                R.string.key_harmonic_expander_gain_4, R.string.key_harmonic_expander_gain_5,
                R.string.key_harmonic_expander_gain_6, R.string.key_harmonic_expander_gain_7,
                R.string.key_harmonic_expander_gain_8, R.string.key_harmonic_expander_gain_9,
                R.string.key_harmonic_expander_gain_10
            )
            val harmExpGains = FloatArray(9) { idx -> cache.get(harmExpGainIds[idx], 0.001f) }
            val harmExpCrossover = cache.get(R.string.key_harmonic_expander_crossover, 2000f)
            val harmExpMix = cache.get(R.string.key_harmonic_expander_mix, 50f)

            // Sub-Harmonic Expander
            cache.select(Constants.PREF_SUBHARMONIC_EXPANDER)
            val subHarmExpEnabled = cache.get(R.string.key_subharmonic_expander_enable, false)
            val subHarmExpGainIds = intArrayOf(
                R.string.key_subharmonic_expander_gain_1, R.string.key_subharmonic_expander_gain_2,
                R.string.key_subharmonic_expander_gain_3, R.string.key_subharmonic_expander_gain_4,
                R.string.key_subharmonic_expander_gain_5, R.string.key_subharmonic_expander_gain_6,
                R.string.key_subharmonic_expander_gain_7, R.string.key_subharmonic_expander_gain_8,
                R.string.key_subharmonic_expander_gain_9
            )
            val subHarmExpGains = FloatArray(9) { idx -> cache.get(subHarmExpGainIds[idx], 0.001f) }
            val subHarmExpCrossover = cache.get(R.string.key_subharmonic_expander_crossover, 120f)
            val subHarmExpMix = cache.get(R.string.key_subharmonic_expander_mix, 50f)

            // NOS R2R Simulator
            cache.select(Constants.PREF_NOSR2R)
            val nosR2REnabled = cache.get(R.string.key_nosr2r_enable, false)
            val nosR2RTargetRate = cache.get(R.string.key_nosr2r_target_rate, "44100").toDouble()
            val nosR2RBitDepth = cache.get(R.string.key_nosr2r_bit_depth, 24f).toInt()
            val nosR2RTolerance = cache.get(R.string.key_nosr2r_tolerance, 0f) / 100.0
            val nosR2RDeviationGrowth = cache.get(R.string.key_nosr2r_deviation_growth, 1f).toInt()
            val nosR2RHarmony = cache.get(R.string.key_nosr2r_harmony, "0").toInt()
            val nosR2RSerial = cache.get(R.string.key_nosr2r_serial, "11").toLongOrNull() ?: 11L
            val nosR2RJitter = cache.get(R.string.key_nosr2r_jitter, 0f) / 100.0
            val nosR2RAddHarmonics = cache.get(R.string.key_nosr2r_add_harmonics, 0f) / 100.0
            val nosR2RInvertPhase = cache.get(R.string.key_nosr2r_invert_phase, false)

            cache.select(Constants.PREF_DDC)
            val ddcEnabled = cache.get(R.string.key_ddc_enable, false)
            val ddcFile = cache.get(R.string.key_ddc_file, "")

            cache.select(Constants.PREF_LIVEPROG)
            val liveProgEnabled = cache.get(R.string.key_liveprog_enable, false)
            val liveprogFile = cache.get(R.string.key_liveprog_file, "")

            cache.select(Constants.PREF_LOUDNESS)
            val loudnessEnabled = cache.get(R.string.key_loudness_enable, false)
            val loudnessRefLevel = cache.get(R.string.key_loudness_reference_level, 0f)
            val loudnessRefOffset = cache.get(R.string.key_loudness_reference_offset, 0f)
            val loudnessAttenuation = cache.get(R.string.key_loudness_attenuation, 100f) / 100f
            val loudnessVolume = cache.get(R.string.key_loudness_volume, 0f)
            val loudnessAutoVolume = cache.get(R.string.key_loudness_auto_volume, false)
            val loudnessMode = cache.get(R.string.key_loudness_mode, "0").toInt()
            // Tunable shelf/contour parameters (defaults match original hardcoded values)
            val lsFreq = cache.get(R.string.key_loudness_ls_freq, 75f).toDouble()
            val lsSlope = cache.get(R.string.key_loudness_ls_slope, 52f).toDouble() / 100.0
            val lsRatio = cache.get(R.string.key_loudness_ls_ratio, 55f).toDouble() / 100.0
            val hsFreq = cache.get(R.string.key_loudness_hs_freq, 10000f).toDouble()
            val hsSlope = cache.get(R.string.key_loudness_hs_slope, 90f).toDouble() / 100.0
            val hsRatio = cache.get(R.string.key_loudness_hs_ratio, 225f).toDouble() / 1000.0
            val isoBasePhon = cache.get(R.string.key_loudness_iso_base_phon, 80f).toDouble()
            val isoQ = cache.get(R.string.key_loudness_iso_q, 432f).toDouble() / 100.0
            val subsonicEnabled = cache.get(R.string.key_loudness_subsonic_enable, false)
            val subsonicFreq = cache.get(R.string.key_loudness_subsonic_freq, 20f).toDouble()
            val subsonicOrder = cache.get(R.string.key_loudness_subsonic_order, 4f).toInt()
            val subsonicQ = cache.get(R.string.key_loudness_subsonic_q, 707f).toDouble() / 1000.0

            cache.select(Constants.PREF_CONVOLVER)
            val convolverEnabled = cache.get(R.string.key_convolver_enable, false)
            val convolverFile = cache.get(R.string.key_convolver_file, "")
            val convolverSampleRateFiles = cache.get(R.string.key_convolver_sample_rate_files, "")
            val convolverAdvImp = cache.get(R.string.key_convolver_adv_imp, Constants.DEFAULT_CONVOLVER_ADVIMP)
            val convolverMode = cache.get(R.string.key_convolver_mode, "0").toInt()

            val targets = (cache.changedNamespaces + (forceUpdateNamespaces ?: arrayOf())).toMutableList()
            // Deduplicate: a namespace appearing in both changed + forced would
            // otherwise be applied twice; for PEQ/SQUIG that means the second
            // pass overwrites the merged cascade from the first pass with
            // main-PEQ-only bands, silently dropping Squig Live EQ.
            val seen = HashSet<String>()
            targets.retainAll { seen.add(it) }
            targets.forEach {
                Timber.i("Committing new changes in namespace '$it'")

                val result = when (it) {
                    Constants.PREF_OUTPUT -> setOutputControl(limiterThreshold, limiterRelease, outputPostGain)
                    Constants.PREF_COMPANDER -> setCompander(compEnabled, compTimeConst, compGranularity, compTfTransforms, compResponse)
                    Constants.PREF_BASS -> setBassBoost(bassEnabled, bassMaxGain)
                    Constants.PREF_EQ -> setMultiEqualizer(eqEnabled, eqFilterType, eqInterpolationMode, eqBands)
                    Constants.PREF_GEQ -> {
                        // GEQ and PEQ are now independent: GEQ uses frequency-domain,
                        // PEQ uses time-domain biquad cascade (if supported).
                        // For engines without native PEQ support, fall back to GEQ+PEQ merge.
                        if (supportsParametricEqCascade()) {
                            setGraphicEq(geqEnabled, geqBands)
                        } else {
                            setGraphicEqCombined(geqEnabled, geqBands, peqEnabled, peqBandsStr, peqPreamp)
                        }
                    }
                    Constants.PREF_PEQ -> {
                        // Main PEQ and Squig Live PEQ are a single biquad cascade:
                        // changing either namespace must apply both together.
                        // Without this, a PREF_PEQ commit after a PREF_SQUIG commit
                        // would overwrite the merged cascade with main-PEQ-only
                        // bands and silently drop the Squig Live EQ.
                        if (supportsParametricEqCascade()) {
                            val mergedBands = ParametricEqBandList()
                            if (peqEnabled) {
                                mergedBands.deserialize(peqBandsStr)
                            }
                            if (squigEnabled) {
                                val squigBandList = ParametricEqBandList()
                                squigBandList.deserialize(squigBandsStr)
                                mergedBands.addAll(squigBandList.toList())
                            }
                            val mergedPreamp = (if (peqEnabled) peqPreamp else 0f) +
                                (if (squigEnabled) squigPreamp else 0f)
                            val anyEnabled = peqEnabled || squigEnabled
                            setParametricEqCascade(anyEnabled, sampleRate.toDouble(), mergedPreamp.toDouble(), mergedBands.toList())
                        } else {
                            // Fallback: merge PEQ into GraphicEQ for engines without native PEQ.
                            // Squig bands are merged into the PEQ band string so they reach the
                            // graphic-eq combined path too.
                            val combinedBandsStr = if (squigEnabled) {
                                val merged = ParametricEqBandList()
                                if (peqEnabled) merged.deserialize(peqBandsStr)
                                val squigBandList = ParametricEqBandList()
                                squigBandList.deserialize(squigBandsStr)
                                merged.addAll(squigBandList.toList())
                                merged.serialize()
                            } else peqBandsStr
                            val combinedPreamp = (if (peqEnabled) peqPreamp else 0f) +
                                (if (squigEnabled) squigPreamp else 0f)
                            setGraphicEqCombined(geqEnabled, geqBands, peqEnabled || squigEnabled, combinedBandsStr, combinedPreamp)
                        }
                    }
                    Constants.PREF_REVERB -> setReverb(reverbEnabled, reverbPreset)
                    Constants.PREF_STEREOWIDE -> setStereoEnhancement(swEnabled, swMode)
                    Constants.PREF_CROSSFEED -> setCrossfeed(crossfeedEnabled, crossfeedMode)
                    Constants.PREF_TUBE -> setVacuumTube(tubeEnabled, tubeDrive)
                    Constants.PREF_HARMONIC_EXPANDER -> setHarmonicExpander(harmExpEnabled, harmExpGains, harmExpCrossover, harmExpMix)
                    Constants.PREF_SUBHARMONIC_EXPANDER -> setSubHarmonicExpander(subHarmExpEnabled, subHarmExpGains, subHarmExpCrossover, subHarmExpMix)
                    Constants.PREF_NOSR2R -> setNosR2R(nosR2REnabled, nosR2RTargetRate, nosR2RBitDepth,
                        nosR2RTolerance, nosR2RDeviationGrowth, nosR2RHarmony, nosR2RSerial,
                        nosR2RJitter, nosR2RAddHarmonics, nosR2RInvertPhase)
                    Constants.PREF_DDC -> setVdc(ddcEnabled, ddcFile)
                    Constants.PREF_LIVEPROG -> setLiveprog(liveProgEnabled, liveprogFile)
                    Constants.PREF_SQUIG -> {
                        // Squig Live plugin: independent PEQ stored in the
                        // PREF_SQUIG namespace (key_squig_peq_*). Both main PEQ
                        // and Squig PEQ form a single biquad cascade; this
                        // handler mirrors PREF_PEQ so a Squig-only change still
                        // applies the merged (main + squig) band list. Does NOT
                        // mutate the PREF_PEQ namespace.
                        if (supportsParametricEqCascade()) {
                            val mergedBands = ParametricEqBandList()
                            if (peqEnabled) {
                                mergedBands.deserialize(peqBandsStr)
                            }
                            if (squigEnabled) {
                                val squigBandList = ParametricEqBandList()
                                squigBandList.deserialize(squigBandsStr)
                                mergedBands.addAll(squigBandList.toList())
                            }
                            val mergedPreamp = (if (peqEnabled) peqPreamp else 0f) +
                                (if (squigEnabled) squigPreamp else 0f)
                            val anyEnabled = peqEnabled || squigEnabled
                            setParametricEqCascade(anyEnabled, sampleRate.toDouble(), mergedPreamp.toDouble(), mergedBands.toList())
                        } else {
                            // Fallback for engines without native PEQ cascade:
                            // merge squig bands into the graphic-eq combined path.
                            val combinedBandsStr = if (squigEnabled) {
                                val merged = ParametricEqBandList()
                                if (peqEnabled) merged.deserialize(peqBandsStr)
                                val squigBandList = ParametricEqBandList()
                                squigBandList.deserialize(squigBandsStr)
                                merged.addAll(squigBandList.toList())
                                merged.serialize()
                            } else peqBandsStr
                            val combinedPreamp = (if (peqEnabled) peqPreamp else 0f) +
                                (if (squigEnabled) squigPreamp else 0f)
                            setGraphicEqCombined(geqEnabled, geqBands, peqEnabled || squigEnabled, combinedBandsStr, combinedPreamp)
                        }
                        true
                    }
                    Constants.PREF_LOUDNESS -> {
                        if (supportsLoudnessCorrection()) {
                            // Apply user-tunable shelf/contour parameters
                            setLoudnessTuning(
                                lsFreq, lsSlope, lsRatio,
                                hsFreq, hsSlope, hsRatio,
                                isoBasePhon, isoQ
                            )
                            // Apply subsonic filter parameters
                            setLoudnessSubsonic(
                                subsonicEnabled, subsonicFreq, subsonicOrder, subsonicQ
                            )
                            // Enable/disable system volume tracking
                            setLoudnessAutoVolume(loudnessEnabled && loudnessAutoVolume)
                            // Determine the effective volume: auto-tracked or manual
                            val effectiveVolume = if (loudnessEnabled && loudnessAutoVolume)
                                getCurrentMediaVolumeDb() else loudnessVolume.toDouble()
                            setLoudnessCorrection(
                                loudnessEnabled, sampleRate.toDouble(),
                                loudnessRefLevel.toDouble(), loudnessRefOffset.toDouble(),
                                loudnessAttenuation.toDouble(), effectiveVolume,
                                loudnessMode
                            )
                        } else {
                            true
                        }
                    }
                    Constants.PREF_CONVOLVER -> {
                        val mappedFile = ConvolverSampleRateFiles.resolve(
                            convolverSampleRateFiles,
                            sampleRate.toInt(),
                            convolverFile,
                        )
                        val selectedFile = mappedFile.takeIf {
                            File(FileLibraryPreference.createFullPathCompat(context, it)).isFile
                        } ?: convolverFile
                        setConvolver(convolverEnabled, selectedFile, convolverMode, convolverAdvImp)
                    }
                    else -> true
                }

                if(!result) {
                    Timber.e("Failed to apply $it")
                }
            }

            cache.markChangesAsCommitted()
            Timber.i("Preferences synchronized")
        }
    }

    fun setMultiEqualizer(enable: Boolean, filterType: Int, interpolationMode: Int, bands: String): Boolean
    {
        val doubleArray = DoubleArray(30)
        val array = bands.split(";")
        for((i, str) in array.withIndex())
        {
            val number = str.toDoubleOrNull()
            if(number == null) {
                Timber.e("setFirEqualizer: malformed EQ string")
                return false
            }
            doubleArray[i] = number
        }

        return setMultiEqualizerInternal(enable, filterType, interpolationMode, doubleArray)
    }

    fun setCompander(enable: Boolean, timeConstant: Float, granularity: Int, tfTransforms: Int, bands: String): Boolean
    {
        val doubleArray = DoubleArray(14)
        val array = bands.split(";")
        for((i, str) in array.withIndex())
        {
            val number = str.toDoubleOrNull()
            if(number == null) {
                Timber.e("setCompander: malformed string")
                return false
            }
            doubleArray[i] = number
        }

        return setCompanderInternal(enable, timeConstant, granularity, tfTransforms, doubleArray)
    }

    fun setVdc(enable: Boolean, vdcPath: String): Boolean
    {
        val fullPath = FileLibraryPreference.createFullPathCompat(context, vdcPath)

        if(!File(fullPath).exists() || File(fullPath).isDirectory) {
            Timber.w("setVdc: file does not exist")
            setVdcInternal(false, "")
            return true /* non-critical */
        }

        return safeFileReader(fullPath)?.use {
            setVdcInternal(enable, it.readText())
        } ?: false
    }

    fun setConvolver(enable: Boolean, impulseResponsePath: String, optimizationMode: Int, waveEditStr: String): Boolean
    {
        val path = FileLibraryPreference.createFullPathCompat(context, impulseResponsePath)
        val targetSampleRate = sampleRate.toInt()

        // Handle disabled state before everything else
        if(!enable || !File(path).exists() || File(path).isDirectory) {
            setConvolverInternal(false, FloatArray(0), 0, 0, 0, targetSampleRate)
            return true
        }

        val advConv = waveEditStr.split(";")
        val advSetting = IntArray(6)
        advSetting.fill(0)
        advSetting[0] = -80
        advSetting[1] = -100
        try
        {
            if (advConv.size == 6)
            {
                for (i in advConv.indices) advSetting[i] = Integer.valueOf(advConv[i])
            }
            else {
                Timber.w("setConvolver: AdvImp setting has the wrong size (${advConv.size})")
                callbacks?.onConvolverParseError(ProcessorMessage.ConvolverErrorCode.AdvParamsInvalid)
            }
        }
        catch(ex: NumberFormatException) {
            Timber.e("setConvolver: NumberFormatException while parsing AdvImp setting. Using defaults.")
            callbacks?.onConvolverParseError(ProcessorMessage.ConvolverErrorCode.AdvParamsInvalid)
        }

        val info = IntArray(4)
        val imp = JdspImpResToolbox.ReadImpulseResponseToFloat(
            path,
            targetSampleRate,
            info,
            optimizationMode,
            advSetting
        )

        if(imp == null) {
            Timber.e("setConvolver: Failed to read IR")
            setConvolverInternal(false, FloatArray(0), 0, 0, 0, targetSampleRate)
            callbacks?.onConvolverParseError(ProcessorMessage.ConvolverErrorCode.Corrupted)
            return false
        }

        // check frame count
        if(info[1] == 0) {
            Timber.e("setConvolver: IR has no frames")
            setConvolverInternal(false, FloatArray(0), 0, 0, 0, targetSampleRate)
            callbacks?.onConvolverParseError(ProcessorMessage.ConvolverErrorCode.NoFrames)
            return false
        }

        // check if advSetting was invalid
        if(info[3] == 0) {
            Timber.w("setConvolver: advSetting was invalid")
            callbacks?.onConvolverParseError(ProcessorMessage.ConvolverErrorCode.AdvParamsInvalid)
        }

        return setConvolverInternal(true, imp, info[0], info[1], info[2], targetSampleRate)
    }

    fun setGraphicEq(enable: Boolean, bands: String): Boolean
    {
        // Sanity check
        if(!bands.contains("GraphicEQ:", true)) {
            Timber.e("setGraphicEq: malformed string")
            setGraphicEqInternal(false, "")
            return false
        }

        return setGraphicEqInternal(enable, bands)
    }

    fun setGraphicEqCombined(
        geqEnabled: Boolean, geqBands: String,
        peqEnabled: Boolean, peqBandsStr: String,
        peqPreamp: Float = 0f
    ): Boolean {
        val peqBands = ParametricEqBandList()
        peqBands.deserialize(peqBandsStr)
        val hasPeqBands = peqEnabled && peqBands.isNotEmpty()
        // Per-channel PEQ responses: LEFT → L+L+R bands, RIGHT → R+L+R bands
        val leftPeqResponse = if (hasPeqBands) {
            val sr = if (sampleRate > 0f) sampleRate.toDouble() else 48000.0
            BiquadUtils.computeCombinedResponse(peqBands, numPoints = 512, sampleRate = sr, channel = ParametricEqChannel.LEFT)
        } else {
            emptyList()
        }
        val rightPeqResponse = if (hasPeqBands) {
            val sr = if (sampleRate > 0f) sampleRate.toDouble() else 48000.0
            BiquadUtils.computeCombinedResponse(peqBands, numPoints = 512, sampleRate = sr, channel = ParametricEqChannel.RIGHT)
        } else {
            emptyList()
        }

        val hasGeq = geqEnabled && geqBands.contains("GraphicEQ:", true)
        val anyEnabled = hasGeq || leftPeqResponse.isNotEmpty() || rightPeqResponse.isNotEmpty()
        if (!anyEnabled) {
            return setGraphicEqInternal(false, "")
        }

        val preampOffset = if (hasPeqBands) peqPreamp.toDouble() else 0.0
        val geqNodes = if (hasGeq) parseGeqNodes(geqBands) else emptyList()

        val leftString = buildChannelGraphicEqString(
            peqResponse = leftPeqResponse,
            otherPeqResponse = rightPeqResponse,
            geqBands = geqBands,
            geqNodes = geqNodes,
            hasGeq = hasGeq,
            preampOffset = preampOffset
        )
        val rightString = buildChannelGraphicEqString(
            peqResponse = rightPeqResponse,
            otherPeqResponse = leftPeqResponse,
            geqBands = geqBands,
            geqNodes = geqNodes,
            hasGeq = hasGeq,
            preampOffset = preampOffset
        )

        val graphicEqPayload = when {
            leftString == null && rightString == null -> ""
            leftString != null && rightString != null && leftString != rightString ->
                buildStereoGraphicEqPayload(leftString, rightString)
            else -> leftString ?: rightString ?: ""
        }

        return setGraphicEqInternal(graphicEqPayload.isNotBlank(), graphicEqPayload)
    }

    private fun buildChannelGraphicEqString(
        peqResponse: List<Pair<Double, Double>>,
        otherPeqResponse: List<Pair<Double, Double>>,
        geqBands: String,
        geqNodes: List<Pair<Double, Double>>,
        hasGeq: Boolean,
        preampOffset: Double
    ): String? {
        // A channel without its own PEQ bands still gets the preamp, so both sides stay level-matched
        val flatPeq = otherPeqResponse.map { it.first to 0.0 }
        return when {
            peqResponse.isNotEmpty() && hasGeq -> mergeGeqWithPeq(geqNodes, peqResponse, preampOffset)
            peqResponse.isNotEmpty() -> BiquadUtils.toGraphicEqString(peqResponse, preampOffset)
            flatPeq.isNotEmpty() && hasGeq -> mergeGeqWithPeq(geqNodes, flatPeq, preampOffset)
            hasGeq -> geqBands
            flatPeq.isNotEmpty() -> BiquadUtils.toGraphicEqString(flatPeq, preampOffset)
            else -> null
        }
    }

    private fun parseGeqNodes(geqBands: String): List<Pair<Double, Double>> {
        val geqNodes = mutableListOf<Pair<Double, Double>>()
        val content = geqBands.replace("GraphicEQ:", "").trim()
        content.split(";").map { it.trim() }.filter { it.isNotBlank() }.forEach { s ->
            val parts = s.split(" ").filter { it.isNotBlank() }
            val freq = parts.getOrNull(0)?.toDoubleOrNull()
            val gain = parts.getOrNull(1)?.toDoubleOrNull()
            if (freq != null && gain != null) {
                geqNodes.add(Pair(freq, gain))
            }
        }
        geqNodes.sortBy { it.first }
        return geqNodes
    }

    private fun mergeGeqWithPeq(
        geqNodes: List<Pair<Double, Double>>,
        peqResponse: List<Pair<Double, Double>>,
        preampOffset: Double = 0.0
    ): String {
        val sb = StringBuilder("GraphicEQ: ")
        for ((peqFreq, peqGain) in peqResponse) {
            val geqGain = interpolateGeq(geqNodes, peqFreq)
            sb.append("${dfMergeFreq.format(peqFreq)} ${dfMergeGain.format(peqGain + geqGain + preampOffset)}; ")
        }

        return sb.toString()
    }

    private fun buildStereoGraphicEqPayload(leftBands: String, rightBands: String): String {
        return leftBands.trim() + STEREO_GRAPHIC_EQ_SPLIT + rightBands.trim()
    }

    private fun interpolateGeq(nodes: List<Pair<Double, Double>>, freq: Double): Double {
        if (nodes.isEmpty()) return 0.0
        if (freq <= nodes.first().first) return nodes.first().second
        if (freq >= nodes.last().first) return nodes.last().second

        // Find surrounding nodes and do log-linear interpolation
        for (i in 0 until nodes.size - 1) {
            val (f0, g0) = nodes[i]
            val (f1, g1) = nodes[i + 1]
            if (freq in f0..f1) {
                if (f1 <= f0) return g0
                val logF = kotlin.math.ln(freq)
                val logF0 = kotlin.math.ln(f0)
                val logF1 = kotlin.math.ln(f1)
                val t = (logF - logF0) / (logF1 - logF0)
                return g0 + t * (g1 - g0)
            }
        }
        return 0.0
    }

    fun setLiveprog(enable: Boolean, path: String): Boolean
    {
        val fullPath = FileLibraryPreference.createFullPathCompat(context, path)

        if(!File(fullPath).exists() || File(fullPath).isDirectory) {
            Timber.w("setLiveprog: file does not exist")
            return setLiveprogInternal(false, "", "")
        }

        return safeFileReader(fullPath)?.use {
            val name = File(fullPath).name
            setLiveprogInternal(enable, name, it.readText())
        } ?: false
    }

    private fun safeFileReader(path: String) =
        try { FileReader(path) }
        catch (ex: FileNotFoundException) {
            /* Exception may occur when old presets created with version <1.4.3 are swapped
               between root, rootless, debug, or release builds due to path name differences. */
            Timber.w(ex)
            null
        }

    // Effect config
    abstract fun setOutputControl(threshold: Float, release: Float, postGain: Float): Boolean
    abstract fun setPostGainDb(postGain: Float): Boolean
    abstract fun setReverb(enable: Boolean, preset: Int): Boolean
    abstract fun setCrossfeed(enable: Boolean, mode: Int): Boolean
    abstract fun setCrossfeedCustom(enable: Boolean, fcut: Int, feed: Int): Boolean
    abstract fun setBassBoost(enable: Boolean, maxGain: Float): Boolean
    abstract fun setStereoEnhancement(enable: Boolean, level: Float): Boolean
    abstract fun setVacuumTube(enable: Boolean, level: Float): Boolean
    abstract fun setHarmonicExpander(enable: Boolean, harmonicGains: FloatArray, crossoverFreq: Float, mix: Float): Boolean
    abstract fun setSubHarmonicExpander(enable: Boolean, subHarmonicGains: FloatArray, crossoverFreq: Float, mix: Float): Boolean
    abstract fun setNosR2R(enable: Boolean, targetRate: Double, bitDepth: Int,
                           resistorTolerance: Double, deviationGrowth: Int,
                           harmony: Int, serialNumber: Long,
                           jitterAmount: Double, harmonicsAmount: Double, invertPhase: Boolean): Boolean

    protected abstract fun setMultiEqualizerInternal(enable: Boolean, filterType: Int, interpolationMode: Int, bands: DoubleArray): Boolean
    protected abstract fun setCompanderInternal(enable: Boolean, timeConstant: Float, granularity: Int, tfTransforms: Int, bands: DoubleArray): Boolean
    protected abstract fun setVdcInternal(enable: Boolean, vdc: String): Boolean
    protected abstract fun setConvolverInternal(
        enable: Boolean,
        impulseResponse: FloatArray,
        irChannels: Int,
        irFrames: Int,
        irCrc: Int,
        irSampleRate: Int,
    ): Boolean
    protected abstract fun setGraphicEqInternal(enable: Boolean, bands: String): Boolean
    protected abstract fun setLiveprogInternal(enable: Boolean, name: String, script: String): Boolean

    // Time-domain parametric EQ (biquad cascade, all 8 filter types, L+R/L/R per band)
    abstract fun setParametricEqCascade(
        enable: Boolean,
        sampleRate: Double,
        preampDb: Double,
        bands: List<me.timschneeberger.rootlessjamesdsp.model.ParametricEqBand>
    ): Boolean

    // Loudness correction (Fletcher-Munson compensation, local engine only)
    // mode: 0 = classic (Fletcher-Munson two-shelf), 1 = ISO 226:2023 (29-band)
    abstract fun setLoudnessCorrection(
        enable: Boolean,
        sampleRate: Double,
        referenceLevel: Double,
        referenceOffset: Double,
        attenuation: Double,
        currentVolumeDb: Double,
        mode: Int
    ): Boolean

    // Push a volume-only update without full reconfiguration.
    open fun setLoudnessCorrectionVolume(currentVolumeDb: Double): Boolean = false

    // Set user-tunable loudness shelf/contour parameters.
    open fun setLoudnessTuning(
        lsFreq: Double, lsSlope: Double, lsRatio: Double,
        hsFreq: Double, hsSlope: Double, hsRatio: Double,
        isoBasePhon: Double, isoQ: Double
    ): Boolean = false

    // Set subsonic (infrasonic) high-pass filter for loudness module.
    open fun setLoudnessSubsonic(
        enable: Boolean, freq: Double, order: Int, qFactor: Double
    ): Boolean = false

    // Enable/disable automatic system media volume tracking for loudness correction.
    open fun setLoudnessAutoVolume(enable: Boolean) {}

    // Get the current system media volume in dB (for auto-volume mode).
    open fun getCurrentMediaVolumeDb(): Double = 0.0

    // Feature support
    abstract fun supportsEelVmAccess(): Boolean
    abstract fun supportsCustomCrossfeed(): Boolean

    /** Whether this engine supports the time-domain parametric EQ biquad cascade */
    open fun supportsParametricEqCascade(): Boolean = false

    /** Whether this engine supports loudness correction */
    open fun supportsLoudnessCorrection(): Boolean = false

    // EEL VM utilities
    abstract fun enumerateEelVariables(): ArrayList<EelVmVariable>
    abstract fun manipulateEelVariable(name: String, value: Float): Boolean
    abstract fun freezeLiveprogExecution(freeze: Boolean)

    protected inner class DummyCallbacks : JamesDspWrapper.JamesDspCallbacks
    {
        override fun onLiveprogOutput(message: String) {}
        override fun onLiveprogExec(id: String) {}
        override fun onLiveprogResult(resultCode: Int, id: String, errorMessage: String?) {}
        override fun onVdcParseError() {}
        override fun onConvolverParseError(errorCode: ProcessorMessage.ConvolverErrorCode) {}
    }

    companion object {
        /** Separates the left and right curve when a GraphicEQ payload differs per channel. */
        const val STEREO_GRAPHIC_EQ_SPLIT = "\n#CHANNEL_SPLIT#\n"
        private val dfMergeFreq = java.text.DecimalFormat("0.00", java.text.DecimalFormatSymbols.getInstance(java.util.Locale.ENGLISH))
        private val dfMergeGain = java.text.DecimalFormat("0.000000", java.text.DecimalFormatSymbols.getInstance(java.util.Locale.ENGLISH))
    }
}
