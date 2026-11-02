package me.timschneeberger.rootlessjamesdsp.interop

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import me.timschneeberger.rootlessjamesdsp.interop.structure.EelVmVariable
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.voiceisolation.EnhancerModel
import me.timschneeberger.rootlessjamesdsp.voiceisolation.VoiceIsolationProcessor
import me.timschneeberger.rootlessjamesdsp.voiceisolation.VoiceIsolationSettings
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import timber.log.Timber
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.math.log10

class JamesDspLocalEngine(context: Context, callbacks: JamesDspWrapper.JamesDspCallbacks? = null) : JamesDspBaseEngine(context, callbacks) {
    var handle: JamesDspHandle = JamesDspWrapper.alloc(callbacks ?: DummyCallbacks())

    /**
     * Lifecycle lock для native handle (BUG-03). Все JNI-вызовы выполняются под
     * read-lock; close() берёт write-lock, что детерминированно дожидается
     * завершения in-flight process и set вызовов и только затем вызывает free().
     * Заменяет старый Timer().schedule(100), который не доказывал завершение
     * активных вызовов.
     */
    private val handleLock = ReentrantReadWriteLock()

    @Volatile
    private var handleClosed = false

    @Volatile
    private var voiceIsolation: VoiceIsolationProcessor? = null

    /** Выполняет [block] с живым handle под read-lock; null после close(). */
    private inline fun <T> withNativeHandle(crossinline block: (JamesDspHandle) -> T): T? {
        if (handleClosed) return null
        val readLock = handleLock.readLock()
        readLock.lock()
        try {
            if (handleClosed) return null
            return block(handle)
        } finally {
            readLock.unlock()
        }
    }

    override var sampleRate: Float
        set(value) {
            super.sampleRate = value
            withNativeHandle { JamesDspWrapper.setSamplingRate(it, value, false) }
            context.sendLocalBroadcast(Intent(Constants.ACTION_SAMPLE_RATE_UPDATED))
        }
        get() = super.sampleRate
    override var enabled: Boolean = true

    init {
        if(BenchmarkManager.hasBenchmarksCached())
            BenchmarkManager.loadBenchmarksFromCache()
    }

    override fun close() {
        // Отключаем трекинг громкости и ML-стадию до закрытия handle:
        // receiver и worker не должны обращаться к native после free()
        setLoudnessAutoVolume(false)

        voiceIsolation?.close()
        voiceIsolation = null

        // Отменяет syncScope: новые syncWithPreferences() больше не запускаются,
        // уже запущенная синхронизация завершится под read-lock ниже
        super.close()

        // Write-lock: запрет новых JNI-вызовов -> ожидание активных -> free()
        val writeLock = handleLock.writeLock()
        writeLock.lock()
        try {
            val oldHandle = handle
            handle = 0
            handleClosed = true
            if (oldHandle != 0L) {
                JamesDspWrapper.free(oldHandle)
                Timber.d("Handle $oldHandle has been freed")
            }
        } finally {
            writeLock.unlock()
        }
    }

    // Processing
    private fun passthroughCopy(input: ShortArray, output: ShortArray, offset: Int, length: Int) {
        if (input === output) return
        if(offset < 0 && length < 0) {
            input.copyInto(output)
        }
        else {
            input.copyInto(output, 0, offset, offset + length)
        }
    }

    private fun passthroughCopy(input: IntArray, output: IntArray, offset: Int, length: Int) {
        if (input === output) return
        if(offset < 0 && length < 0) {
            input.copyInto(output)
        }
        else {
            input.copyInto(output, 0, offset, offset + length)
        }
    }

    private fun passthroughCopy(input: FloatArray, output: FloatArray, offset: Int, length: Int) {
        if (input === output) return
        if(offset < 0 && length < 0) {
            input.copyInto(output)
        }
        else {
            input.copyInto(output, 0, offset, offset + length)
        }
    }

    fun processInt16(input: ShortArray, output: ShortArray, offset: Int = -1, length: Int = -1)
    {
        if(!enabled || handleClosed) {
            passthroughCopy(input, output, offset, length)
            return
        }
        // Read-lock удерживается, пока активен JNI-вызов: close() не сможет
        // освободить handle из-под обрабатываемого блока (BUG-03)
        handleLock.readLock().lock()
        try {
            if (handleClosed) {
                passthroughCopy(input, output, offset, length)
                return
            }
            JamesDspWrapper.processInt16(handle, input, output, offset, length)
        } finally {
            handleLock.readLock().unlock()
        }
        processVoiceIsolation(output, offset, length)
    }

    fun processInt32(input: IntArray, output: IntArray, offset: Int = -1, length: Int = -1)
    {
        if(!enabled || handleClosed) {
            passthroughCopy(input, output, offset, length)
            return
        }
        handleLock.readLock().lock()
        try {
            if (handleClosed) {
                passthroughCopy(input, output, offset, length)
                return
            }
            JamesDspWrapper.processInt32(handle, input, output, offset, length)
        } finally {
            handleLock.readLock().unlock()
        }
    }

    fun processFloat(input: FloatArray, output: FloatArray, offset: Int = -1, length: Int = -1)
    {
        if(!enabled || handleClosed) {
            passthroughCopy(input, output, offset, length)
            return
        }
        handleLock.readLock().lock()
        try {
            if (handleClosed) {
                passthroughCopy(input, output, offset, length)
                return
            }
            JamesDspWrapper.processFloat(handle, input, output, offset, length)
        } finally {
            handleLock.readLock().unlock()
        }
        processVoiceIsolation(output, offset, length)
    }

    // Voice Isolation runs in this process (ONNX Runtime), after the native JamesDSP chain
    private fun processVoiceIsolation(buffer: ShortArray, offset: Int, length: Int) {
        val processor = voiceIsolation ?: return
        if (!processor.enabled) return
        val start = if (offset < 0) 0 else offset
        val samples = if (offset < 0 && length < 0) buffer.size else length.coerceAtMost(buffer.size - start)
        processor.processShort(buffer, samples / 2, start)
    }

    private fun processVoiceIsolation(buffer: FloatArray, offset: Int, length: Int) {
        val processor = voiceIsolation ?: return
        if (!processor.enabled) return
        val start = if (offset < 0) 0 else offset
        val samples = if (offset < 0 && length < 0) buffer.size else length.coerceAtMost(buffer.size - start)
        processor.processFloat(buffer, samples / 2, start)
    }

    override fun supportsVoiceIsolation(): Boolean = true

    override fun isVoiceIsolationActive(): Boolean = voiceIsolation?.enabled == true

    /** Real-time budget report of the Voice Isolation stage, for logcat verification. */
    fun voiceIsolationStats(): String {
        val processor = voiceIsolation ?: return "voiceIsolation=off"
        val inference = processor.inferenceMs()
        val budget = processor.hopBudgetMs()
        return (
            "voiceIsolation[${processor.activeModelKey ?: "none"}]: inference=%.2fms budget=%.2fms " +
                "rtf=%.2f latency=%.1fms speech=%s reduction=%.0f%% out=%.0fdB drops=%d"
            ).format(
                inference, budget, if (budget > 0f) inference / budget else 0f,
                processor.latencyMs,
                processor.speechDetected(), processor.effectiveReduction() * 100f,
                processor.takeOutputLevelDb(),
                processor.underruns(),
            )
    }

    override fun voiceIsolationHopMs(): Float = voiceIsolation?.hopBudgetMs() ?: 0f

    override fun setVoiceIsolation(
        enable: Boolean,
        sampleRate: Double,
        reductionPct: Double,
        voiceReductionPct: Double,
        boostDb: Double,
        autoMode: Boolean,
        modelKey: String
    ): Boolean {
        if (!enable) {
            voiceIsolation?.enabled = false
            return true
        }
        val processor = voiceIsolation ?: VoiceIsolationProcessor(context).also { voiceIsolation = it }
        if (sampleRate <= 0.0) {
            Timber.w("setVoiceIsolation: skipping, sample rate not yet known ($sampleRate)")
            return false
        }
        if (!processor.configure(sampleRate.toInt(), EnhancerModel.fromKey(modelKey))) {
            Timber.w("setVoiceIsolation: model $modelKey is not available at $sampleRate Hz")
            return false
        }
        processor.settings = VoiceIsolationSettings(
            backgroundReduction = (reductionPct / 100.0).toFloat(),
            voiceReduction = (voiceReductionPct / 100.0).toFloat(),
            voiceGain = VoiceIsolationSettings.boostDbToGain(boostDb.toFloat()),
            autoMode = autoMode,
        )
        processor.enabled = true
        return true
    }

    // Effect config
    override fun setOutputControl(enable: Boolean, threshold: Float, release: Float, postGain: Float): Boolean {
        val limiterSet = withNativeHandle { JamesDspWrapper.setLimiter(it, enable, threshold, release) } ?: return false
        val postGainSet = withNativeHandle { JamesDspWrapper.setPostGain(it, postGain) } ?: return false
        return limiterSet && postGainSet
    }

    override fun setSmoothVolumeDb(db: Double): Boolean {
        return withNativeHandle { JamesDspWrapper.setSmoothVolumeGain(it, db) } ?: false
    }

    override fun setReverb(enable: Boolean, preset: Int): Boolean
    {
        return withNativeHandle { JamesDspWrapper.setReverb(it, enable, preset) } ?: false
    }

    override fun setCrossfeed(enable: Boolean, mode: Int): Boolean
    {
        return withNativeHandle { JamesDspWrapper.setCrossfeed(it, enable, mode, 0, 0) } ?: false
    }

    override fun setCrossfeedCustom(enable: Boolean, fcut: Int, feed: Int): Boolean
    {
        return withNativeHandle { JamesDspWrapper.setCrossfeed(it, enable, 99, fcut, feed) } ?: false
    }

    override fun setBassBoost(enable: Boolean, maxGain: Float): Boolean
    {
        return withNativeHandle { JamesDspWrapper.setBassBoost(it, enable, maxGain) } ?: false
    }

    override fun setStereoEnhancement(enable: Boolean, level: Float): Boolean
    {
        return withNativeHandle { JamesDspWrapper.setStereoEnhancement(it, enable, level) } ?: false
    }

    override fun setVacuumTube(enable: Boolean, level: Float): Boolean
    {
        return withNativeHandle { JamesDspWrapper.setVacuumTube(it, enable, level) } ?: false
    }

    override fun setHarmonicExpander(enable: Boolean, harmonicGains: FloatArray, crossoverFreq: Float, mix: Float): Boolean
    {
        return withNativeHandle { JamesDspWrapper.setHarmonicExpander(it, enable, harmonicGains, crossoverFreq, mix) } ?: false
    }

    override fun setSubHarmonicExpander(enable: Boolean, subHarmonicGains: FloatArray, crossoverFreq: Float, mix: Float): Boolean
    {
        return withNativeHandle { JamesDspWrapper.setSubHarmonicExpander(it, enable, subHarmonicGains, crossoverFreq, mix) } ?: false
    }

    override fun setNosR2R(enable: Boolean, targetRate: Double, bitDepth: Int,
                           resistorTolerance: Double, deviationGrowth: Int,
                           harmony: Int, serialNumber: Long,
                           jitterAmount: Double, harmonicsAmount: Double, invertPhase: Boolean): Boolean
    {
        return withNativeHandle {
            JamesDspWrapper.setNosR2R(it, enable, targetRate, bitDepth,
                resistorTolerance, deviationGrowth, harmony, serialNumber,
                jitterAmount, harmonicsAmount, invertPhase)
        } ?: false
    }

    override fun setMultiEqualizerInternal(
        enable: Boolean,
        filterType: Int,
        interpolationMode: Int,
        bands: DoubleArray
    ): Boolean {
        return withNativeHandle { JamesDspWrapper.setMultiEqualizer(it, enable, filterType, interpolationMode, bands) } ?: false
    }

    override fun setCompanderInternal(
        enable: Boolean,
        timeConstant: Float,
        granularity: Int,
        tfTransforms: Int,
        bands: DoubleArray
    ): Boolean {
        return withNativeHandle { JamesDspWrapper.setCompander(it, enable, timeConstant, granularity, tfTransforms, bands) } ?: false
    }

    override fun setVdcInternal(enable: Boolean, vdc: String): Boolean {
        return withNativeHandle { JamesDspWrapper.setVdc(it, enable, vdc) } ?: false
    }

    override fun setConvolverInternal(
        enable: Boolean,
        impulseResponse: FloatArray,
        irChannels: Int,
        irFrames: Int,
        irCrc: Int,
        irSampleRate: Int,
    ): Boolean {
        return withNativeHandle { JamesDspWrapper.setConvolver(it, enable, impulseResponse, irChannels, irFrames) } ?: false
    }

    override fun setEqPhaseMode(linearPhase: Boolean): Boolean =
        withNativeHandle { JamesDspWrapper.setEqPhaseMode(it, linearPhase) } ?: false

    override fun setGraphicEqInternal(enable: Boolean, bands: String): Boolean {
        // Передаём EQ-кривую в AndroidEq (Direct Mode) — поддерживает per-channel стерео
        // AndroidEq.setCurve парсит строку (включая STEREO_GRAPHIC_EQ_SPLIT) и запускает refit
        if (me.timschneeberger.rootlessjamesdsp.utils.SdkCheck.isPie)
            me.timschneeberger.rootlessjamesdsp.androideq.AndroidEq.setCurve(enable, bands)

        // Стерео payload (две кривые через CHANNEL_SPLIT): нативный движок
        // загружает их в 2x2 convolver как L/R impulse responses.
        if (bands.contains(JamesDspBaseEngine.STEREO_GRAPHIC_EQ_SPLIT)) {
            val left = bands.substringBefore(JamesDspBaseEngine.STEREO_GRAPHIC_EQ_SPLIT)
            val right = bands.substringAfter(JamesDspBaseEngine.STEREO_GRAPHIC_EQ_SPLIT)
            return withNativeHandle { JamesDspWrapper.setGraphicEqStereo(it, enable, left, right) } ?: false
        }

        return withNativeHandle { JamesDspWrapper.setGraphicEq(it, enable, bands) } ?: false
    }

    override fun setGraphicEqStereoInternal(enable: Boolean, bandsLeft: String, bandsRight: String): Boolean {
        // Тот же payload, что и setGraphicEqInternal со стерео-разделителем:
        // AndroidEq получает обе кривые, нативный движок — тоже.
        if (me.timschneeberger.rootlessjamesdsp.utils.SdkCheck.isPie)
            me.timschneeberger.rootlessjamesdsp.androideq.AndroidEq.setCurve(
                enable,
                buildStereoGraphicEqPayload(bandsLeft, bandsRight))

        return withNativeHandle { JamesDspWrapper.setGraphicEqStereo(it, enable, bandsLeft, bandsRight) } ?: false
    }

    override fun setLiveprogInternal(enable: Boolean, name: String, script: String): Boolean {
        return withNativeHandle { JamesDspWrapper.setLiveprog(it, enable, name, script) } ?: false
    }

    override fun setParametricEqCascade(
        enable: Boolean,
        sampleRate: Double,
        preampDb: Double,
        bands: List<me.timschneeberger.rootlessjamesdsp.model.ParametricEqBand>
    ): Boolean {
        // Biquad coefficients depend on sample rate; applying with sr=0 produces
        // invalid coefficients (division by zero) and silences audio. Skip until
        // a valid sample rate is known — re-applied when sample rate arrives.
        if (enable && bands.isNotEmpty() && sampleRate <= 0.0) {
            Timber.w("setParametricEqCascade: skipping, sample rate not yet known ($sampleRate)")
            return false
        }

        if (!enable || bands.isEmpty()) {
            return withNativeHandle {
                JamesDspWrapper.setParametricEq(it, false, sampleRate, preampDb,
                    DoubleArray(0), DoubleArray(0), DoubleArray(0),
                    IntArray(0), IntArray(0)
                )
            } ?: false
        }

        val freq = DoubleArray(bands.size)
        val gain = DoubleArray(bands.size)
        val q = DoubleArray(bands.size)
        val type = IntArray(bands.size)
        val chan = IntArray(bands.size)

        for ((i, band) in bands.withIndex()) {
            freq[i] = band.frequency
            gain[i] = band.gain
            q[i] = band.q
            type[i] = band.filterType.code
            chan[i] = band.channel.code
        }

        return withNativeHandle {
            JamesDspWrapper.setParametricEq(it, true, sampleRate, preampDb,
                freq, gain, q, type, chan
            )
        } ?: false
    }

    override fun setDynamicEq(
        enable: Boolean,
        sampleRate: Double,
        bands: List<me.timschneeberger.rootlessjamesdsp.model.DynamicEqBand>,
        mix: Float,
        msMode: Int
    ): Boolean {
        // Sidechain coefficients depend on sample rate; a zero rate would
        // produce invalid coefficients. Re-applied when the rate is known.
        if (enable && bands.isNotEmpty() && sampleRate <= 0.0) {
            Timber.w("setDynamicEq: skipping, sample rate not yet known ($sampleRate)")
            return false
        }

        val bandList = me.timschneeberger.rootlessjamesdsp.model.DynamicEqBandList()
        bandList.addAll(bands)
        return withNativeHandle {
            JamesDspWrapper.setDynamicEq(it,
                enable && bands.isNotEmpty(),
                sampleRate,
                bandList.toNativeConfig(),
                mix,
                msMode
            )
        } ?: false
    }

    // Feature support
    override fun supportsEelVmAccess(): Boolean { return true }
    override fun supportsCustomCrossfeed(): Boolean { return true }
    override fun supportsParametricEqCascade(): Boolean {
        // В Direct Mode нативный PEQ-каскад недоступен — нет capture loop.
        // Fallback: PEQ мёрджится в GraphicEQ → AndroidEq (DynamicsProcessing).
        return !me.timschneeberger.rootlessjamesdsp.androideq.AndroidEq.isEnabled
    }
    override fun supportsLoudnessCorrection(): Boolean { return true }

    // Loudness correction
    override fun setLoudnessCorrection(
        enable: Boolean,
        sampleRate: Double,
        referenceLevel: Double,
        referenceOffset: Double,
        attenuation: Double,
        currentVolumeDb: Double,
        mode: Int
    ): Boolean {
        // Biquad coefficients depend on sample rate; applying with sr=0 produces
        // invalid coefficients (division by zero) and silences audio. Skip until
        // a valid sample rate is known — re-applied when sample rate arrives.
        if (enable && sampleRate <= 0.0) {
            Timber.w("setLoudnessCorrection: skipping, sample rate not yet known ($sampleRate)")
            return false
        }

        return withNativeHandle {
            JamesDspWrapper.setLoudnessCorrection(it, enable, sampleRate,
                referenceLevel, referenceOffset, attenuation, currentVolumeDb, mode
            )
        } ?: false
    }

    override fun setLoudnessCorrectionVolume(currentVolumeDb: Double): Boolean {
        if (handle == 0L) return false
        return withNativeHandle { JamesDspWrapper.setLoudnessCorrectionVolume(it, currentVolumeDb) } ?: false
    }

    override fun setLoudnessTuning(
        lsFreq: Double, lsSlope: Double, lsRatio: Double,
        hsFreq: Double, hsSlope: Double, hsRatio: Double,
        isoBasePhon: Double, isoQ: Double
    ): Boolean {
        if (handle == 0L) return false
        return withNativeHandle {
            JamesDspWrapper.setLoudnessTuning(it, lsFreq, lsSlope, lsRatio,
                hsFreq, hsSlope, hsRatio, isoBasePhon, isoQ
            )
        } ?: false
    }

    override fun setLoudnessSubsonic(
        enable: Boolean, freq: Double, order: Int, qFactor: Double
    ): Boolean {
        if (handle == 0L) return false
        return withNativeHandle { JamesDspWrapper.setLoudnessSubsonic(it, enable, freq, order, qFactor) } ?: false
    }

    override fun setLufsNormalizer(
        enable: Boolean,
        sampleRate: Double,
        targetLufs: Double,
        maxGainDb: Double,
        mode: Int
    ): Boolean {
        // K-weighting coefficients and window sizes depend on the sample rate;
        // a zero rate would produce invalid filters. Re-applied when known.
        if (enable && sampleRate <= 0.0) {
            Timber.w("setLufsNormalizer: skipping, sample rate not yet known ($sampleRate)")
            return false
        }
        if (handle == 0L) return false
        return withNativeHandle {
            JamesDspWrapper.setLufsNormalizer(it, enable, sampleRate, targetLufs, maxGainDb, mode
            )
        } ?: false
    }

    override fun setFetCompressor(
        enable: Boolean,
        sampleRate: Double,
        thresholdDb: Double,
        ratio: Double,
        attackMs: Double,
        releaseMs: Double,
        makeupDb: Double
    ): Boolean {
        // Attack/release coefficients depend on the sample rate; re-applied
        // when the rate becomes known.
        if (enable && sampleRate <= 0.0) {
            Timber.w("setFetCompressor: skipping, sample rate not yet known ($sampleRate)")
            return false
        }
        if (handle == 0L) return false
        return withNativeHandle {
            JamesDspWrapper.setFetCompressor(it, enable, sampleRate, thresholdDb, ratio, attackMs, releaseMs, makeupDb
            )
        } ?: false
    }

    override fun setViperClarity(
        enable: Boolean,
        sampleRate: Double,
        mode: Int,
        gainDb: Double
    ): Boolean {
        if (enable && sampleRate <= 0.0) {
            Timber.w("setViperClarity: skipping, sample rate not yet known ($sampleRate)")
            return false
        }
        if (handle == 0L) return false
        return withNativeHandle { JamesDspWrapper.setViperClarity(it, enable, sampleRate, mode, gainDb) } ?: false
    }

    override fun setFieldSurround(
        enable: Boolean,
        strengthPct: Double,
        midImagePct: Double
    ): Boolean {
        if (handle == 0L) return false
        return withNativeHandle { JamesDspWrapper.setFieldSurround(it, enable, strengthPct, midImagePct) } ?: false
    }

    override fun setViperAgc(
        enable: Boolean,
        sampleRate: Double,
        targetPct: Double,
        maxBoostDb: Double
    ): Boolean {
        if (enable && sampleRate <= 0.0) {
            Timber.w("setViperAgc: skipping, sample rate not yet known ($sampleRate)")
            return false
        }
        if (handle == 0L) return false
        return withNativeHandle { JamesDspWrapper.setViperAgc(it, enable, sampleRate, targetPct, maxBoostDb) } ?: false
    }

    override fun setCure(
        enable: Boolean,
        sampleRate: Double,
        level: Int
    ): Boolean {
        if (enable && sampleRate <= 0.0) {
            Timber.w("setCure: skipping, sample rate not yet known ($sampleRate)")
            return false
        }
        if (handle == 0L) return false
        return withNativeHandle { JamesDspWrapper.setCure(it, enable, sampleRate, level) } ?: false
    }

    override fun setTape(
        enable: Boolean,
        sampleRate: Double,
        wowPct: Double,
        flutterPct: Double,
        saturationPct: Double,
        biasPct: Double,
        headBumpDb: Double,
        mixPct: Double
    ): Boolean {
        // Modulation depths, base offset and fade length depend on the sample
        // rate; re-applied when the rate becomes known.
        if (enable && sampleRate <= 0.0) {
            Timber.w("setTape: skipping, sample rate not yet known ($sampleRate)")
            return false
        }
        if (handle == 0L) return false
        return withNativeHandle {
            JamesDspWrapper.setTape(it, enable, sampleRate, wowPct, flutterPct,
                saturationPct, biasPct, headBumpDb, mixPct
            )
        } ?: false
    }

    // ---- Auto system volume tracking ----

    private var volumeReceiverRegistered = false
    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == VOLUME_CHANGED_ACTION) {
                val volDb = getCurrentMediaVolumeDb()
                withNativeHandle { JamesDspWrapper.setLoudnessCorrectionVolume(it, volDb) }
            }
        }
    }

    override fun setLoudnessAutoVolume(enable: Boolean) {
        if (enable && !volumeReceiverRegistered) {
            val filter = IntentFilter(VOLUME_CHANGED_ACTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(volumeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(volumeReceiver, filter)
            }
            volumeReceiverRegistered = true
            // Push initial volume
            val volDb = getCurrentMediaVolumeDb()
            withNativeHandle { JamesDspWrapper.setLoudnessCorrectionVolume(it, volDb) }
            Timber.d("Loudness auto-volume tracking enabled (vol=%.1f dB)", volDb)
        } else if (!enable && volumeReceiverRegistered) {
            try {
                context.unregisterReceiver(volumeReceiver)
            } catch (e: Exception) {
                Timber.w(e, "Failed to unregister volume receiver")
            }
            volumeReceiverRegistered = false
            Timber.d("Loudness auto-volume tracking disabled")
        }
    }

    override fun getCurrentMediaVolumeDb(): Double {
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (maxVol <= 0) return 0.0
        if (curVol <= 0) return -60.0 // mute → maximum correction
        val ratio = curVol.toDouble() / maxVol.toDouble()
        return 20.0 * log10(ratio.coerceIn(0.001, 1.0))
    }

    // EEL VM utilities
    override fun enumerateEelVariables(): ArrayList<EelVmVariable>
    {
        return withNativeHandle { JamesDspWrapper.enumerateEelVariables(it) } ?: ArrayList()
    }

    override fun manipulateEelVariable(name: String, value: Float): Boolean
    {
        return withNativeHandle { JamesDspWrapper.manipulateEelVariable(it, name, value) } ?: false
    }

    override fun freezeLiveprogExecution(freeze: Boolean)
    {
        withNativeHandle { JamesDspWrapper.freezeLiveprogExecution(it, freeze) }
    }

    companion object {
        // AudioManager.VOLUME_CHANGED_ACTION is a hidden API
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
    }
}
