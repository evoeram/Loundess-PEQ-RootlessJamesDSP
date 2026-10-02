package me.timschneeberger.rootlessjamesdsp.measurement

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Менеджер сессии измерения: оркестрирует полный pipeline.
 *
 * Поддерживает три режима:
 *  - [MeasurementMode.LEFT] — измерение левого канала
 *  - [MeasurementMode.RIGHT] — измерение правого канала
 *  - [MeasurementMode.BOTH] — последовательное измерение L+R с расчётом
 *    межканальной задержки через два probe-сигнала
 *
 * Pipeline:
 *  1. Генерация sweep + inverse filter
 *  2. Генерация probe-сигнала(ов) для компенсации задержки
 *  3. Параллельное воспроизведение + запись с микрофона
 *  4. Детекция probe → компенсация задержки → обрезка записи
 *  5. Деконволюция → IR
 *  6. Вычисление SPL
 *  7. Калибровка микрофона
 *  8. Сглаживание
 *  9. Auto-EQ
 *
 * @param sampleRate частота дискретизации (48000)
 * @param sweepF1 начальная частота sweep (20 Гц)
 * @param sweepF2 конечная частота sweep (20000 Гц)
 * @param sweepDuration длительность sweep в секундах (5.0)
 * @param mode режим измерения (LEFT, RIGHT, BOTH)
 * @param micType тип микрофона
 * @param onInputSamples колбэк стриминга сэмплов в визуализатор
 */
class MeasurementSession(
    private val context: Context,
    private val sampleRate: Int = 48000,
    private val sweepF1: Double = 20.0,
    private val sweepF2: Double = 20000.0,
    private val sweepDuration: Double = 5.0,
    private val mode: MeasurementMode = MeasurementMode.BOTH,
    private val micType: MicType = MicType.BUILTIN_UNPROCESSED,
    private val smoothingType: SmoothingType = SmoothingType.PSYCHOACOUSTIC,
    private val deconvConfig: DeconvConfig = DeconvConfig(),
    private val averageCount: Int = 1,
    private val onInputSamples: ((FloatArray, Int, Int) -> Unit)? = null
) {
    private val nativeEngine = MeasurementNativeEngine()
    private var contextHandle: Long = 0L
    @Volatile
    private var isCancelled = false
    private var currentRecorder: MeasurementAudioRecord? = null
    private var currentPlayer: SweepPlayer? = null

    /** Debug data captured during last measurement (for export). */
    @Volatile
    var debugData: MeasurementDebugExporter.DebugData? = null
        private set

    /** Результат измерения одного канала. */
    data class ChannelResult(
        val frequencies: FloatArray,
        val splRaw: FloatArray,
        val splCalibrated: FloatArray,
        val splSmoothed: FloatArray,
        val ir: FloatArray,
        val latencyMs: Double,
        val success: Boolean,
        val errorMessage: String?,
        // THD data (optional, null if not computed)
        val thd: FloatArray? = null,
        val thdFrequencies: FloatArray? = null,
        // RT60 data (optional, null if not computed)
        val rt60: FloatArray? = null,
        val rt60Frequencies: FloatArray? = null,
        // Group delay (optional, null if not computed)
        val groupDelay: FloatArray? = null
    )

    /** Результат полного измерения (один или два канала). */
    data class MeasurementResult(
        val left: ChannelResult?,
        val right: ChannelResult?,
        val interChannelDelayMs: Double,
        // Auto-EQ по усреднённому SPL (абсолютный режим)
        val autoEqBands: List<me.timschneeberger.rootlessjamesdsp.model.ParametricEqBand>,
        val autoEqPreamp: Double,
        // Auto-EQ по разнице L−R (относительный режим)
        val autoEqBandsDiff: List<me.timschneeberger.rootlessjamesdsp.model.ParametricEqBand>,
        val autoEqPreampDiff: Double,
        val iterations: Int,
        val finalMaxDeviation: Double,
        val targetMet: Boolean,
        val success: Boolean,
        val errorMessage: String?,
        // legacy fields for backward compatibility
        val frequencies: FloatArray,
        val splRaw: FloatArray,
        val splCalibrated: FloatArray,
        val splSmoothed: FloatArray,
        val ir: FloatArray
    ) {
        companion object {
            fun error(msg: String) = MeasurementResult(
                null, null, 0.0, emptyList(), 0.0, emptyList(), 0.0,
                0, 0.0, false, false, msg,
                FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0)
            )
        }
    }

    /**
     * Запустить полное измерение.
     */
    suspend fun runMeasurement(
        target: TargetCurve = TargetCurve.flat(),
        autoEqConfig: AutoEqEngine.Config = AutoEqEngine.Config(),
        micCalibration: MicCalibrationLoader.CalibrationData? = null,
        applyAutoEq: Boolean = true
    ): MeasurementResult = withContext(Dispatchers.IO) {
        isCancelled = false
        contextHandle = nativeEngine.createContext()
        if (contextHandle == 0L) {
            return@withContext MeasurementResult.error("Failed to create native context")
        }

        // Debug data collection
        val dbg = DebugCollector()

        try {
            // 1. Генерация sweep и inverse filter
            Log.i(TAG, "Generating sweep: ${sweepF1}-${sweepF2} Hz, ${sweepDuration}s, mode=$mode")
            val sweep = nativeEngine.generateSweep(sweepF1, sweepF2, sweepDuration, sampleRate)
            val inverseFilter = nativeEngine.generateInverseFilter(sweepF1, sweepF2, sweepDuration, sampleRate)

            // 2. Генерация probe и композитного сигнала
            val probe = generateProbe(100, sampleRate) // 100 мс, 1 кГц
            val preSilenceMs = 100
            val gapMs = 300
            val preSilenceSamples = preSilenceMs * sampleRate / 1000
            val gapSamples = gapMs * sampleRate / 1000

            // Композитный сигнал и канал вывода зависят от режима.
            // Для LR_SEQUENTIAL строим stereo-interleaved сигнал, где
            // L-сегмент только на левом канале, R-сегмент только на правом.
            val outputChannel: OutputChannel
            val composite: FloatArray      // mono (для LEFT/RIGHT/BOTH)
            val compositeStereo: FloatArray?  // stereo interleaved (для LR_SEQUENTIAL)

            when (mode) {
                MeasurementMode.LEFT -> {
                    composite = buildCompositeSignal(preSilenceSamples, probe, gapSamples, sweep)
                    outputChannel = OutputChannel.LEFT
                    compositeStereo = null
                }
                MeasurementMode.RIGHT -> {
                    composite = buildCompositeSignal(preSilenceSamples, probe, gapSamples, sweep)
                    outputChannel = OutputChannel.RIGHT
                    compositeStereo = null
                }
                MeasurementMode.LR_SEQUENTIAL -> {
                    // Последовательное измерение: L-сегмент на левом канале,
                    // R-сегмент на правом. Stereo-interleaved сигнал:
                    //
                    //   Кадр:  [L,R, L,R, L,R, ...]
                    //   Левый:  [тишина 100мс][probe L 100мс][тишина 300мс][sweep L 5с][тишина 100мс...]
                    //   Правый: [тишина ...                                    ][probe R 100мс][тишина 300мс][sweep R 5с]
                    //
                    // segmentL = [тишина 100мс][probe 100мс][тишина 300мс][sweep 5с]
                    // segmentR = segmentL (та же структура, но начинаются позже в stereo-массиве)
                    //
                    // Полная длина = segmentL.size + interSilence + segmentR.size
                    // Левый канал:  segmentL в начале, затем тишина до конца
                    // Правый канал: тишина до смещения, затем segmentR до конца

                    val segment = buildCompositeSignal(preSilenceSamples, probe, gapSamples, sweep)
                    val interSegmentSilence = sampleRate / 10 // 100 мс между сегментами
                    val totalFrames = segment.size + interSegmentSilence + segment.size

                    // Stereo interleaved: [L0,R0, L1,R1, ...]
                    compositeStereo = FloatArray(totalFrames * 2)
                    composite = FloatArray(0) // не используется в этом режиме
                    outputChannel = OutputChannel.BOTH // не используется, playStereo вызывается напрямую

                    // Заполняем левый канал: segment в начале
                    for (i in 0 until segment.size) {
                        compositeStereo[i * 2] = segment[i]
                    }
                    // Правый канал: тишина до смещения, затем segment
                    val rOffset = segment.size + interSegmentSilence
                    for (i in 0 until segment.size) {
                        compositeStereo[(rOffset + i) * 2 + 1] = segment[i]
                    }

                    Log.i(TAG, "LR_SEQUENTIAL stereo: ${totalFrames} frames " +
                            "(${totalFrames.toFloat() / sampleRate}s), " +
                            "L segment at 0, R segment at ${rOffset} " +
                            "(${rOffset * 1000f / sampleRate}ms)")
                }
                MeasurementMode.BOTH -> {
                    composite = buildCompositeSignal(preSilenceSamples, probe, gapSamples, sweep)
                    outputChannel = OutputChannel.BOTH
                    compositeStereo = null
                }
            }

            val compositeDurSec = if (compositeStereo != null) {
                compositeStereo.size.toFloat() / 2f / sampleRate
            } else {
                composite.size.toFloat() / sampleRate
            }
            Log.i(TAG, "Composite: ${if (compositeStereo != null) "${compositeStereo.size} stereo samples" else "${composite.size} mono samples"} " +
                    "(${compositeDurSec}s), mode=$mode")

            // Capture stimulus for debug
            dbg.stimulus = if (composite.isNotEmpty()) composite.copyOf() else null
            dbg.stimulusStereo = compositeStereo?.copyOf()

            // 3. Воспроизведение + запись (с усреднением N замеров)
            val numAverages = averageCount.coerceAtLeast(1)
            Log.i(TAG, "Starting playback + recording (averages=$numAverages)")

            // Накопители для усреднения записанных sweep-сегментов
            var avgRecordedL: FloatArray? = null
            var avgRecordedR: FloatArray? = null
            var avgRecordedMono: FloatArray? = null
            var avgInterChannelDelayMs = 0.0
            var successfulRuns = 0

            for (iter in 0 until numAverages) {
                if (isCancelled) break

                if (numAverages > 1) {
                    Log.i(TAG, "=== Measurement iteration ${iter + 1}/$numAverages ===")
                }

                val player = SweepPlayer(sampleRate, 0.3f, outputChannel)
                currentPlayer = player
                val recorder = MeasurementAudioRecord(
                    sampleRate = sampleRate,
                    durationSec = (compositeDurSec + 1.0f),
                    micType = micType,
                    appContext = context,
                    onSamples = onInputSamples
                )
                currentRecorder = recorder

                val recordedRef = arrayOfNulls<FloatArray>(1)
                val recThread = Thread { recordedRef[0] = recorder.record() }
                recThread.start()
                Thread.sleep(50)

                // В режиме LR_SEQUENTIAL воспроизводим stereo-interleaved сигнал
                if (compositeStereo != null) {
                    player.playStereo(compositeStereo)
                } else {
                    player.play(composite)
                }
                recThread.join(30000)

                val rawRecorded = recordedRef[0]
                if (isCancelled) {
                    Log.i(TAG, "Measurement cancelled by user")
                    return@withContext MeasurementResult.error("Cancelled")
                }
                if (rawRecorded == null || rawRecorded.isEmpty()) {
                    Log.w(TAG, "Iteration ${iter + 1}: recording failed, skipping")
                    continue
                }
                Log.i(TAG, "Iteration ${iter + 1}: recorded ${rawRecorded.size} samples")

                // Capture raw recording for debug (first successful iteration)
                if (dbg.recording == null) {
                    dbg.recording = rawRecorded.copyOf()
                }

                // 4. Компенсация задержки и извлечение sweep
                when (mode) {
                    MeasurementMode.LEFT -> {
                        val probeStart = findProbeStart(rawRecorded, probe, sampleRate)
                        val sweepStart = probeStart + probe.size + gapSamples
                        val sweepEnd = minOf(sweepStart + sweep.size, rawRecorded.size)
                        if (sweepStart >= rawRecorded.size) {
                            Log.w(TAG, "Iteration ${iter + 1}: L sweep out of bounds, skipping")
                            continue
                        }
                        val recorded = rawRecorded.copyOfRange(sweepStart, sweepEnd)
                        avgRecordedMono = averageArrays(avgRecordedMono, recorded, successfulRuns)
                        successfulRuns++
                    }
                    MeasurementMode.RIGHT -> {
                        val probeStart = findProbeStart(rawRecorded, probe, sampleRate)
                        val sweepStart = probeStart + probe.size + gapSamples
                        val sweepEnd = minOf(sweepStart + sweep.size, rawRecorded.size)
                        if (sweepStart >= rawRecorded.size) {
                            Log.w(TAG, "Iteration ${iter + 1}: R sweep out of bounds, skipping")
                            continue
                        }
                        val recorded = rawRecorded.copyOfRange(sweepStart, sweepEnd)
                        avgRecordedMono = averageArrays(avgRecordedMono, recorded, successfulRuns)
                        successfulRuns++
                    }
                    MeasurementMode.LR_SEQUENTIAL -> {
                        val segmentLen = preSilenceSamples + probe.size + gapSamples + sweep.size
                        val interSegmentSilence = sampleRate / 10

                        val searchEndL = minOf(rawRecorded.size - probe.size, segmentLen + interSegmentSilence)
                        val probeLStart = findProbeStartInRange(rawRecorded, probe, 0, searchEndL, sampleRate)
                        val sweepLStart = probeLStart + probe.size + gapSamples
                        val sweepLEnd = minOf(sweepLStart + sweep.size, rawRecorded.size)
                        if (sweepLStart >= rawRecorded.size) {
                            Log.w(TAG, "Iteration ${iter + 1}: L sweep out of bounds, skipping")
                            continue
                        }
                        val recordedL = rawRecorded.copyOfRange(sweepLStart, sweepLEnd)

                        val searchStartR = segmentLen + interSegmentSilence - sampleRate
                        val searchStartRClamped = maxOf(0, searchStartR)
                        val probeRStart = findProbeStartInRange(rawRecorded, probe, searchStartRClamped, rawRecorded.size - probe.size, sampleRate)
                        val sweepRStart = probeRStart + probe.size + gapSamples
                        val sweepREnd = minOf(sweepRStart + sweep.size, rawRecorded.size)
                        if (sweepRStart >= rawRecorded.size) {
                            Log.w(TAG, "Iteration ${iter + 1}: R sweep out of bounds, skipping")
                            continue
                        }
                        val recordedR = rawRecorded.copyOfRange(sweepRStart, sweepREnd)

                        val latencyL = (probeLStart - preSilenceSamples) * 1000.0 / sampleRate
                        val latencyR = (probeRStart - (preSilenceSamples + segmentLen + interSegmentSilence)) * 1000.0 / sampleRate
                        avgInterChannelDelayMs += (latencyR - latencyL)

                        avgRecordedL = averageArrays(avgRecordedL, recordedL, successfulRuns)
                        avgRecordedR = averageArrays(avgRecordedR, recordedR, successfulRuns)
                        successfulRuns++
                    }
                    MeasurementMode.BOTH -> {
                        val probeStart = findProbeStart(rawRecorded, probe, sampleRate)
                        val sweepStart = probeStart + probe.size + gapSamples
                        val sweepEnd = minOf(sweepStart + sweep.size, rawRecorded.size)
                        if (sweepStart >= rawRecorded.size) {
                            Log.w(TAG, "Iteration ${iter + 1}: L+R sweep out of bounds, skipping")
                            continue
                        }
                        val recorded = rawRecorded.copyOfRange(sweepStart, sweepEnd)
                        avgRecordedMono = averageArrays(avgRecordedMono, recorded, successfulRuns)
                        successfulRuns++
                    }
                }
            }

            if (successfulRuns == 0) {
                return@withContext MeasurementResult.error("All measurement iterations failed")
            }

            if (numAverages > 1) {
                Log.i(TAG, "Averaged $successfulRuns successful runs")
            }

            val interChannelDelayMs = if (mode == MeasurementMode.LR_SEQUENTIAL) {
                avgInterChannelDelayMs / successfulRuns
            } else 0.0

            // 5. Деконволюция усреднённых данных
            val leftResult: ChannelResult?
            val rightResult: ChannelResult?

            when (mode) {
                MeasurementMode.LEFT -> {
                    leftResult = processChannel(avgRecordedMono!!, inverseFilter, micCalibration, "L", dbg)
                    rightResult = null
                }
                MeasurementMode.RIGHT -> {
                    leftResult = null
                    rightResult = processChannel(avgRecordedMono!!, inverseFilter, micCalibration, "R", dbg)
                }
                MeasurementMode.LR_SEQUENTIAL -> {
                    leftResult = processChannel(avgRecordedL!!, inverseFilter, micCalibration, "L", dbg)
                    rightResult = processChannel(avgRecordedR!!, inverseFilter, micCalibration, "R")
                    Log.i(TAG, "Inter-channel delay: ${interChannelDelayMs}ms (averaged)")
                }
                MeasurementMode.BOTH -> {
                    leftResult = processChannel(avgRecordedMono!!, inverseFilter, micCalibration, "L+R", dbg)
                    rightResult = null
                }
            }

            // 5. Auto-EQ: два набора фильтров — абсолютный (L+R среднее) и относительный (L−R разница)
            var autoEqBands = emptyList<me.timschneeberger.rootlessjamesdsp.model.ParametricEqBand>()
            var autoEqPreamp = 0.0
            var autoEqBandsDiff = emptyList<me.timschneeberger.rootlessjamesdsp.model.ParametricEqBand>()
            var autoEqPreampDiff = 0.0
            var iterations = 0
            var finalMaxDeviation = 0.0
            var targetMet = false

            if (applyAutoEq) {
                val autoEq = AutoEqEngine(sampleRate.toDouble())

                // --- Абсолютный набор: по усреднённому SPL (L+R) или одному каналу ---
                val splForAutoEq: FloatArray = when {
                    leftResult != null && rightResult != null -> {
                        FloatArray(leftResult.splSmoothed.size) { i ->
                            (leftResult.splSmoothed[i] + rightResult.splSmoothed[i]) / 2f
                        }
                    }
                    leftResult != null -> leftResult.splSmoothed
                    rightResult != null -> rightResult.splSmoothed
                    else -> FloatArray(0)
                }
                val freqsForAutoEq = leftResult?.frequencies ?: rightResult?.frequencies ?: FloatArray(0)

                if (freqsForAutoEq.isNotEmpty()) {
                    Log.i(TAG, "Starting Auto-EQ (absolute, L+R average)")
                    val result = autoEq.run(
                        freqsForAutoEq.map { it.toDouble() }.toDoubleArray(),
                        splForAutoEq.map { it.toDouble() }.toDoubleArray(),
                        target,
                        autoEqConfig
                    )
                    autoEqBands = result.bands
                    autoEqPreamp = result.preampDb
                    iterations = result.iterations
                    finalMaxDeviation = result.finalMaxDeviation
                    targetMet = result.targetMet
                    Log.i(TAG, "Auto-EQ (absolute): ${autoEqBands.size} bands, preamp=${autoEqPreamp}dB, " +
                            "deviation=${finalMaxDeviation}dB, targetMet=$targetMet")
                }

                // --- Относительный набор: по разнице L−R (только в режиме L/R sequential) ---
                if (leftResult != null && rightResult != null &&
                    leftResult.splSmoothed.isNotEmpty() && rightResult.splSmoothed.isNotEmpty()) {
                    val minLen = minOf(leftResult.splSmoothed.size, rightResult.splSmoothed.size)
                    val splDiff = DoubleArray(minLen) { i ->
                        (leftResult.splSmoothed[i] - rightResult.splSmoothed[i]).toDouble()
                    }
                    val freqsDiff = leftResult.frequencies.copyOfRange(0, minLen)
                        .map { it.toDouble() }.toDoubleArray()

                    Log.i(TAG, "Starting Auto-EQ (relative, L−R difference)")
                    val resultDiff = autoEq.run(
                        freqsDiff,
                        splDiff,
                        TargetCurve.flat(), // цель: L−R → 0 (выровнять каналы)
                        autoEqConfig
                    )
                    autoEqBandsDiff = resultDiff.bands
                    autoEqPreampDiff = resultDiff.preampDb
                    Log.i(TAG, "Auto-EQ (relative): ${autoEqBandsDiff.size} bands, preamp=${autoEqPreampDiff}dB")
                }
            }

            // Legacy: используем левый канал (или правый) для совместимости
            val primary = leftResult ?: rightResult
            MeasurementResult(
                left = leftResult,
                right = rightResult,
                interChannelDelayMs = interChannelDelayMs,
                autoEqBands = autoEqBands,
                autoEqPreamp = autoEqPreamp,
                autoEqBandsDiff = autoEqBandsDiff,
                autoEqPreampDiff = autoEqPreampDiff,
                iterations = iterations,
                finalMaxDeviation = finalMaxDeviation,
                targetMet = targetMet,
                success = (leftResult?.success ?: true) && (rightResult?.success ?: true),
                errorMessage = null,
                frequencies = primary?.frequencies ?: FloatArray(0),
                splRaw = primary?.splRaw ?: FloatArray(0),
                splCalibrated = primary?.splCalibrated ?: FloatArray(0),
                splSmoothed = primary?.splSmoothed ?: FloatArray(0),
                ir = primary?.ir ?: FloatArray(0)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Measurement failed", e)
            MeasurementResult.error(e.message ?: "Unknown error")
        } finally {
            // Store debug data
            debugData = MeasurementDebugExporter.DebugData(
                stimulus = dbg.stimulus,
                stimulusStereo = dbg.stimulusStereo,
                recording = dbg.recording,
                deconvolvedIr = dbg.deconvolvedIr,
                processedIr = dbg.processedIr,
                frequencies = dbg.frequencies,
                splRaw = dbg.splRaw,
                splCalibrated = dbg.splCalibrated,
                splSmoothed = dbg.splSmoothed,
                phase = dbg.phase,
                sampleRate = sampleRate,
                settings = mapOf(
                    "sampleRate" to sampleRate,
                    "sweepF1" to sweepF1,
                    "sweepF2" to sweepF2,
                    "sweepDuration" to sweepDuration,
                    "mode" to mode.name,
                    "micType" to micType.name,
                    "smoothingType" to smoothingType.name,
                    "averageCount" to averageCount,
                    "deconvConfig" to mapOf(
                        "normalizeIr" to deconvConfig.normalizeIr,
                        "windowMode" to deconvConfig.windowMode.name,
                        "maxIrLenMs" to deconvConfig.maxIrLenMs,
                        "arrivalThreshold" to deconvConfig.arrivalThreshold,
                        "leftWindowMs" to deconvConfig.leftWindowMs,
                        "rightWindowPercent" to deconvConfig.rightWindowPercent
                    )
                )
            )

            if (contextHandle != 0L) {
                nativeEngine.destroyContext(contextHandle)
                contextHandle = 0L
            }
            currentRecorder = null
            currentPlayer = null
        }
    }

    /** Отменить измерение: останавливает запись и воспроизведение. */
    fun cancel() {
        isCancelled = true
        currentRecorder?.stop()
        currentPlayer?.stop()
    }

    /**
     * Обработать один канал: деконволюция → IR → SPL → калибровка → сглаживание.
     * Если [dbg] не null, сохраняет промежуточные данные для отладочного экспорта.
     */
    private suspend fun processChannel(
        recorded: FloatArray,
        inverseFilter: FloatArray,
        micCalibration: MicCalibrationLoader.CalibrationData?,
        label: String,
        dbg: DebugCollector? = null
    ): ChannelResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "$label: processing ${recorded.size} samples")

        // Деконволюция → IR (с расширенной конфигурацией)
        // For debug: also run plain deconvolution to get raw IR (no windowing/normalization)
        if (dbg != null) {
            val rawResult = nativeEngine.deconvolve(contextHandle, recorded, inverseFilter, sampleRate)
            if (rawResult == 0) {
                dbg.deconvolvedIr = nativeEngine.getIr(contextHandle)?.copyOf()
            }
        }

        val deconvResult = nativeEngine.deconvolveEx(
            contextHandle, recorded, inverseFilter, sampleRate,
            deconvConfig.normalizeIr, deconvConfig.windowMode.nativeId,
            deconvConfig.maxIrLenMs, deconvConfig.arrivalThreshold,
            deconvConfig.leftWindowMs, deconvConfig.rightWindowPercent
        )
        if (deconvResult != 0) {
            return@withContext ChannelResult(
                FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0),
                FloatArray(0), 0.0, false, "$label deconvolution failed: $deconvResult"
            )
        }
        val ir = nativeEngine.getIr(contextHandle) ?: FloatArray(0)
        Log.i(TAG, "$label: IR ${ir.size} samples")

        // Capture processed IR for debug
        dbg?.processedIr = ir.copyOf()

        // SPL
        val splResult = nativeEngine.computeSpl(contextHandle, 16384)
        if (splResult != 0) {
            return@withContext ChannelResult(
                FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0),
                ir, 0.0, false, "$label SPL failed: $splResult"
            )
        }
        val freqs = nativeEngine.getSplFrequencies(contextHandle) ?: FloatArray(0)
        val splRaw = nativeEngine.getSpl(contextHandle) ?: FloatArray(0)
        Log.i(TAG, "$label: SPL ${freqs.size} bins")

        // Capture SPL data for debug
        if (dbg != null) {
            dbg.frequencies = freqs.copyOf()
            dbg.splRaw = splRaw.copyOf()
            // Capture phase
            try { dbg.phase = nativeEngine.getSplPhase(contextHandle).copyOf() } catch (_: Exception) {}
        }

        // Калибровка
        val splCalibrated = if (micCalibration != null && freqs.isNotEmpty()) micCalibration.apply(freqs, splRaw)
                            else splRaw.copyOf()

        // Сглаживание выбранного типа
        nativeEngine.applySmoothingByType(contextHandle, smoothingType.nativeId)
        val splSmoothed = nativeEngine.getSpl(contextHandle) ?: FloatArray(0)

        // Capture calibrated + smoothed for debug
        if (dbg != null) {
            dbg.splCalibrated = splCalibrated.copyOf()
            dbg.splSmoothed = splSmoothed.copyOf()
        }

        ChannelResult(
            frequencies = freqs,
            splRaw = splRaw,
            splCalibrated = splCalibrated,
            splSmoothed = splSmoothed,
            ir = ir ?: FloatArray(0),
            latencyMs = 0.0,
            success = true,
            errorMessage = null,
            // THD (optional — fails silently if native not available)
            thd = try {
                if (nativeEngine.computeThd(contextHandle) == 0) nativeEngine.getThd(contextHandle) else null
            } catch (e: Exception) { null },
            thdFrequencies = try { nativeEngine.getThdFrequencies(contextHandle) } catch (e: Exception) { null },
            // RT60 (optional)
            rt60 = try {
                if (nativeEngine.computeRt60(contextHandle) == 0) nativeEngine.getRt60(contextHandle) else null
            } catch (e: Exception) { null },
            rt60Frequencies = try { nativeEngine.getRt60Frequencies(contextHandle) } catch (e: Exception) { null },
            // Group delay (optional)
            groupDelay = try {
                if (nativeEngine.computeGroupDelay(contextHandle) == 0) nativeEngine.getGroupDelay(contextHandle) else null
            } catch (e: Exception) { null }
        )
    }

    /**
     * Сохранить IR как WAV файл.
     */
    fun saveIrAsWav(ir: FloatArray, file: File): Boolean {
        return try {
            FileOutputStream(file).use { fos ->
                val dataSize = ir.size * 4
                val byteRate = sampleRate * 4
                val chunkSize = 36 + dataSize

                fos.write("RIFF".toByteArray())
                writeInt(fos, chunkSize)
                fos.write("WAVE".toByteArray())
                fos.write("fmt ".toByteArray())
                writeInt(fos, 16)
                writeShort(fos, 3.toShort()) // IEEE float
                writeShort(fos, 1.toShort()) // mono
                writeInt(fos, sampleRate)
                writeInt(fos, byteRate)
                writeShort(fos, 4.toShort())
                writeShort(fos, 32.toShort())
                fos.write("data".toByteArray())
                writeInt(fos, dataSize)
                for (sample in ir) writeFloat(fos, sample)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save IR WAV", e)
            false
        }
    }

    private fun writeInt(fos: FileOutputStream, value: Int) {
        fos.write(value and 0xFF)
        fos.write((value shr 8) and 0xFF)
        fos.write((value shr 16) and 0xFF)
        fos.write((value shr 24) and 0xFF)
    }

    private fun writeShort(fos: FileOutputStream, value: Short) {
        fos.write(value.toInt() and 0xFF)
        fos.write((value.toInt() shr 8) and 0xFF)
    }

    private fun writeFloat(fos: FileOutputStream, value: Float) {
        writeInt(fos, java.lang.Float.floatToRawIntBits(value))
    }

    /**
     * Mutable container for collecting intermediate debug data during measurement.
     */
    private class DebugCollector {
        var stimulus: FloatArray? = null
        var stimulusStereo: FloatArray? = null
        var recording: FloatArray? = null
        var deconvolvedIr: FloatArray? = null
        var processedIr: FloatArray? = null
        var frequencies: FloatArray? = null
        var splRaw: FloatArray? = null
        var splCalibrated: FloatArray? = null
        var splSmoothed: FloatArray? = null
        var phase: FloatArray? = null
    }

    companion object {
        private const val TAG = "MeasurementSession"

        /**
         * Усреднить новый массив с накопленным средним.
         * avg = (avg * count + newArray) / (count + 1)
         * Если avg == null, возвращает копию newArray.
         */
        private fun averageArrays(avg: FloatArray?, newArray: FloatArray, count: Int): FloatArray {
            if (avg == null || avg.size == 0) return newArray.copyOf()
            val result = FloatArray(avg.size)
            val n = count + 1
            for (i in result.indices) {
                result[i] = (avg[i] * count + newArray.getOrElse(i) { 0f }) / n
            }
            return result
        }

        /**
         * Сгенерировать probe-сигнал: sine-всплеск заданной длительности.
         * @param durMs длительность в мс
         * @param sampleRate частота дискретизации
         * @param freq частота sine (1 кГц по умолчанию)
         */
        fun generateProbe(durMs: Int, sampleRate: Int, freq: Double = 1000.0): FloatArray {
            val n = durMs * sampleRate / 1000
            return FloatArray(n) { i ->
                val t = i.toDouble() / sampleRate
                val env = when {
                    i < n / 10 -> i.toFloat() / (n / 10)
                    i > n * 9 / 10 -> (n - i).toFloat() / (n / 10)
                    else -> 1f
                }
                (Math.sin(2.0 * Math.PI * freq * t) * env * 0.5).toFloat()
            }
        }

        /**
         * Собрать композитный сигнал: [тишина][probe][тишина][sweep]
         */
        fun buildCompositeSignal(
            preSilence: Int, probe: FloatArray, gap: Int, sweep: FloatArray
        ): FloatArray {
            val composite = FloatArray(preSilence + probe.size + gap + sweep.size)
            System.arraycopy(probe, 0, composite, preSilence, probe.size)
            System.arraycopy(sweep, 0, composite, preSilence + probe.size + gap, sweep.size)
            return composite
        }

        /**
         * Найти позицию probe в записи через нормализованную кросс-корреляцию.
         */
        fun findProbeStart(recorded: FloatArray, probe: FloatArray, sampleRate: Int): Int {
            return findProbeStartInRange(recorded, probe, 0, recorded.size - probe.size, sampleRate)
        }

        /**
         * Найти позицию probe в заданном диапазоне записи.
         */
        fun findProbeStartInRange(
            recorded: FloatArray, probe: FloatArray,
            searchStart: Int, searchEnd: Int, sampleRate: Int
        ): Int {
            val probeLen = probe.size
            if (searchEnd - searchStart <= 0) return searchStart

            var probeEnergy = 0.0
            for (i in 0 until probeLen) probeEnergy += probe[i].toDouble() * probe[i]
            if (probeEnergy < 1e-12) return searchStart

            var bestCorr = -1.0
            var bestIdx = searchStart
            var windowEnergy = 0.0

            for (i in searchStart until minOf(searchStart + probeLen, recorded.size)) {
                windowEnergy += recorded[i].toDouble() * recorded[i]
            }

            for (start in searchStart until searchEnd) {
                if (start > searchStart) {
                    windowEnergy -= recorded[start - 1].toDouble() * recorded[start - 1]
                    if (start + probeLen - 1 < recorded.size) {
                        windowEnergy += recorded[start + probeLen - 1].toDouble() * recorded[start + probeLen - 1]
                    }
                    if (windowEnergy < 1e-12) windowEnergy = 1e-12
                }

                var dotProduct = 0.0
                for (i in 0 until probeLen) {
                    if (start + i < recorded.size) {
                        dotProduct += recorded[start + i].toDouble() * probe[i]
                    }
                }

                val normCorr = dotProduct / Math.sqrt(windowEnergy * probeEnergy)
                if (normCorr > bestCorr) {
                    bestCorr = normCorr
                    bestIdx = start
                }
            }

            Log.i(TAG, "Probe detection: corr=${"%.3f".format(bestCorr)} at sample $bestIdx " +
                    "(${bestIdx * 1000.0 / sampleRate}ms)")

            if (bestCorr < 0.1) {
                Log.w(TAG, "Probe correlation too low, using fallback")
                return searchStart
            }
            return bestIdx
        }
    }
}
