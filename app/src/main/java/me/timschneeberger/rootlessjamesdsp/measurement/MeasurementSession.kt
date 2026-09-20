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
    private val onInputSamples: ((FloatArray, Int, Int) -> Unit)? = null
) {
    private val nativeEngine = MeasurementNativeEngine()
    private var contextHandle: Long = 0L

    /** Результат измерения одного канала. */
    data class ChannelResult(
        val frequencies: FloatArray,
        val splRaw: FloatArray,
        val splCalibrated: FloatArray,
        val splSmoothed: FloatArray,
        val ir: FloatArray,
        val latencyMs: Double,
        val success: Boolean,
        val errorMessage: String?
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
        contextHandle = nativeEngine.createContext()
        if (contextHandle == 0L) {
            return@withContext MeasurementResult.error("Failed to create native context")
        }

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

            // 3. Воспроизведение + запись
            Log.i(TAG, "Starting playback + recording")
            val player = SweepPlayer(sampleRate, 0.3f, outputChannel)
            val recorder = MeasurementAudioRecord(
                sampleRate = sampleRate,
                durationSec = (compositeDurSec + 1.0f),
                micType = micType,
                appContext = context,
                onSamples = onInputSamples
            )

            val recordedRef = arrayOfNulls<FloatArray>(1)
            val recThread = Thread { recordedRef[0] = recorder.record() }
            recThread.start()
            Thread.sleep(50)

            // В режиме LR_SEQUENTIAL воспроизводим stereo-interleaved сигнал,
            // где L-сегмент только на левом канале, R-сегмент только на правом.
            if (compositeStereo != null) {
                player.playStereo(compositeStereo)
            } else {
                player.play(composite)
            }
            recThread.join(30000)

            val rawRecorded = recordedRef[0]
            if (rawRecorded == null || rawRecorded.isEmpty()) {
                return@withContext MeasurementResult.error("Recording failed")
            }
            Log.i(TAG, "Recorded ${rawRecorded.size} samples")

            // 4. Компенсация задержки и извлечение sweep
            val leftResult: ChannelResult?
            val rightResult: ChannelResult?
            var interChannelDelayMs = 0.0

            when (mode) {
                MeasurementMode.LEFT -> {
                    val probeStart = findProbeStart(rawRecorded, probe, sampleRate)
                    val latencyMs = (probeStart - preSilenceSamples) * 1000.0 / sampleRate
                    Log.i(TAG, "L: probe at $probeStart, latency=${latencyMs}ms")
                    val sweepStart = probeStart + probe.size + gapSamples
                    val sweepEnd = minOf(sweepStart + sweep.size, rawRecorded.size)
                    if (sweepStart >= rawRecorded.size) {
                        return@withContext MeasurementResult.error("L: sweep region out of bounds")
                    }
                    val recorded = rawRecorded.copyOfRange(sweepStart, sweepEnd)
                    leftResult = processChannel(recorded, inverseFilter, micCalibration, "L")
                    rightResult = null
                }
                MeasurementMode.RIGHT -> {
                    val probeStart = findProbeStart(rawRecorded, probe, sampleRate)
                    val latencyMs = (probeStart - preSilenceSamples) * 1000.0 / sampleRate
                    Log.i(TAG, "R: probe at $probeStart, latency=${latencyMs}ms")
                    val sweepStart = probeStart + probe.size + gapSamples
                    val sweepEnd = minOf(sweepStart + sweep.size, rawRecorded.size)
                    if (sweepStart >= rawRecorded.size) {
                        return@withContext MeasurementResult.error("R: sweep region out of bounds")
                    }
                    val recorded = rawRecorded.copyOfRange(sweepStart, sweepEnd)
                    leftResult = null
                    rightResult = processChannel(recorded, inverseFilter, micCalibration, "R")
                }
                MeasurementMode.LR_SEQUENTIAL -> {
                    // Последовательный режим: два probe в записи.
                    // Сегмент L: [тишина 100мс][probe L 100мс][тишина 300мс][sweep L 5с]
                    // Сегмент R: [тишина 100мс][probe R 100мс][тишина 300мс][sweep R 5с]
                    val segmentLen = preSilenceSamples + probe.size + gapSamples + sweep.size
                    val interSegmentSilence = sampleRate / 10

                    // Поиск probe L в первой половине записи
                    val searchEndL = minOf(rawRecorded.size - probe.size, segmentLen + interSegmentSilence)
                    val probeLStart = findProbeStartInRange(rawRecorded, probe, 0, searchEndL, sampleRate)
                    val latencyL = (probeLStart - preSilenceSamples) * 1000.0 / sampleRate
                    Log.i(TAG, "L: probe at $probeLStart, latency=${latencyL}ms")

                    val sweepLStart = probeLStart + probe.size + gapSamples
                    val sweepLEnd = minOf(sweepLStart + sweep.size, rawRecorded.size)
                    if (sweepLStart >= rawRecorded.size) {
                        return@withContext MeasurementResult.error("L: sweep region out of bounds")
                    }
                    val recordedL = rawRecorded.copyOfRange(sweepLStart, sweepLEnd)

                    // Поиск probe R во второй половине записи
                    val searchStartR = segmentLen + interSegmentSilence - sampleRate // запас
                    val searchStartRClamped = maxOf(0, searchStartR)
                    val probeRStart = findProbeStartInRange(rawRecorded, probe, searchStartRClamped, rawRecorded.size - probe.size, sampleRate)
                    val latencyR = (probeRStart - (preSilenceSamples + segmentLen + interSegmentSilence)) * 1000.0 / sampleRate
                    Log.i(TAG, "R: probe at $probeRStart, latency=${latencyR}ms")

                    val sweepRStart = probeRStart + probe.size + gapSamples
                    val sweepREnd = minOf(sweepRStart + sweep.size, rawRecorded.size)
                    if (sweepRStart >= rawRecorded.size) {
                        return@withContext MeasurementResult.error("R: sweep region out of bounds")
                    }
                    val recordedR = rawRecorded.copyOfRange(sweepRStart, sweepREnd)

                    // Межканальная задержка — разница между латентностью L и R
                    interChannelDelayMs = latencyR - latencyL
                    Log.i(TAG, "Inter-channel delay: ${interChannelDelayMs}ms " +
                            "(L=${latencyL}ms, R=${latencyR}ms)")

                    leftResult = processChannel(recordedL, inverseFilter, micCalibration, "L")
                    rightResult = processChannel(recordedR, inverseFilter, micCalibration, "R")
                }
                MeasurementMode.BOTH -> {
                    // Одновременный режим: один probe + один sweep на оба канала.
                    // Запись обрабатывается как моно-сумма L+R.
                    val probeStart = findProbeStart(rawRecorded, probe, sampleRate)
                    val latencyMs = (probeStart - preSilenceSamples) * 1000.0 / sampleRate
                    Log.i(TAG, "L+R: probe at $probeStart, latency=${latencyMs}ms")
                    val sweepStart = probeStart + probe.size + gapSamples
                    val sweepEnd = minOf(sweepStart + sweep.size, rawRecorded.size)
                    if (sweepStart >= rawRecorded.size) {
                        return@withContext MeasurementResult.error("L+R: sweep region out of bounds")
                    }
                    val recorded = rawRecorded.copyOfRange(sweepStart, sweepEnd)
                    // Один канал, но помечаем как "L+R"
                    leftResult = processChannel(recorded, inverseFilter, micCalibration, "L+R")
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
            if (contextHandle != 0L) {
                nativeEngine.destroyContext(contextHandle)
                contextHandle = 0L
            }
        }
    }

    /**
     * Обработать один канал: деконволюция → IR → SPL → калибровка → сглаживание.
     */
    private suspend fun processChannel(
        recorded: FloatArray,
        inverseFilter: FloatArray,
        micCalibration: MicCalibrationLoader.CalibrationData?,
        label: String
    ): ChannelResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "$label: processing ${recorded.size} samples")

        // Деконволюция → IR
        val deconvResult = nativeEngine.deconvolve(contextHandle, recorded, inverseFilter, sampleRate)
        if (deconvResult != 0) {
            return@withContext ChannelResult(
                FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0),
                FloatArray(0), 0.0, false, "$label deconvolution failed: $deconvResult"
            )
        }
        val ir = nativeEngine.getIr(contextHandle)
        Log.i(TAG, "$label: IR ${ir?.size ?: 0} samples")

        // SPL
        val splResult = nativeEngine.computeSpl(contextHandle, 16384)
        if (splResult != 0) {
            return@withContext ChannelResult(
                FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0),
                ir ?: FloatArray(0), 0.0, false, "$label SPL failed: $splResult"
            )
        }
        val freqs = nativeEngine.getSplFrequencies(contextHandle)
        val splRaw = nativeEngine.getSpl(contextHandle)
        Log.i(TAG, "$label: SPL ${freqs.size} bins")

        // Калибровка
        val splCalibrated = if (micCalibration != null) micCalibration.apply(freqs, splRaw)
                            else splRaw.copyOf()

        // Сглаживание выбранного типа
        nativeEngine.applySmoothingByType(contextHandle, smoothingType.nativeId)
        val splSmoothed = nativeEngine.getSpl(contextHandle)

        ChannelResult(
            frequencies = freqs,
            splRaw = splRaw,
            splCalibrated = splCalibrated,
            splSmoothed = splSmoothed,
            ir = ir ?: FloatArray(0),
            latencyMs = 0.0,
            success = true,
            errorMessage = null
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

    companion object {
        private const val TAG = "MeasurementSession"

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
