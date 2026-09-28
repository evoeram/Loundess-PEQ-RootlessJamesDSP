package me.timschneeberger.rootlessjamesdsp.squig

import me.timschneeberger.rootlessjamesdsp.model.ParametricEqBand
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqChannel
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqFilterType
import me.timschneeberger.rootlessjamesdsp.model.squig.FrequencyResponse
import me.timschneeberger.rootlessjamesdsp.utils.ParametricEqResponseCalculator
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Движок автоматического эквалайзера (AutoEQ) для Squig Live.
 *
 * Порт алгоритма из MEOW (AutoEqEngine), адаптированный для работы с FrequencyResponse:
 * 1. Жадный итеративный подбор PEQ-фильтров
 * 2. Поиск частоты с максимальным |error| в диапазоне Match Range
 * 3. Shelf-фильтры для краёв диапазона, Peaking для середины
 * 4. Non-minimum phase awareness: узкие глубокие провалы не корректируются
 * 5. Совместная оптимизация Nelder-Mead после каждого добавленного фильтра
 * 6. Preamp = -max(positive gain) для предотвращения клиппинга
 * 7. corrected FR = measurement + filter response
 *
 * @param sampleRate частота дискретизации DSP
 * @param numPoints количество точек для evaluation
 */
class SquigAutoEqEngine(
    private val sampleRate: Double = 48000.0,
    private val numPoints: Int = 512
) {
    /**
     * Конфигурация алгоритма AutoEQ (портирована из MEOW AutoEqEngine.Config).
     */
    data class Config(
        val matchRangeStart: Double = 20.0,
        val matchRangeEnd: Double = 20000.0,
        val flatnessTarget: Double = 1.0,
        val maxFilters: Int = 15,
        val individualMaxBoost: Double = 6.0,
        val overallMaxBoost: Double = 15.0,
        val maxQLow: Double = 15.0,
        val maxQHigh: Double = 5.0,
        val minQ: Double = 0.5,
        val lowFreqBoundary: Double = 200.0,
        val dipMinWidthHz: Double = 10.0,
        val dipMaxDepth: Double = 8.0,
        val qCrossoverFreq: Double = 200.0,
        val allowLowShelf: Boolean = true,
        val allowHighShelf: Boolean = true,
        val lowShelfCutoffHz: Double = 100.0,
        val highShelfCutoffHz: Double = 5000.0,
        val useNelderMead: Boolean = true,
        val nelderMeadIterations: Int = 300,
        val maxGain: Double = 24.0,
        val minFreq: Double = 20.0,
        val maxFreq: Double = 20000.0
    )

    /**
     * Результат работы AutoEQ.
     */
    data class Result(
        val bands: List<ParametricEqBand>,
        val preampDb: Double,
        val correctedFR: FrequencyResponse,
        val maxDeviation: Double
    )

    /** Калькулятор ответа фильтров для вычисления correctedFR. */
    private val calculator = ParametricEqResponseCalculator(
        sampleRate, 20.0, 20000.0, numPoints
    )

    /**
     * Запуск алгоритма AutoEQ.
     *
     * @param measurement нормализованная АЧХ измерения
     * @param target целевая кривая
     * @param config конфигурация алгоритма
     * @return результат AutoEQ с полосами, preamp и скорректированной АЧХ
     */
    fun run(
        measurement: FrequencyResponse,
        target: FrequencyResponse,
        config: Config = Config()
    ): Result {
        Timber.d("SquigAutoEqEngine: запуск, ${measurement.frequencies.size} точек, maxBands=${config.maxFilters}")

        // Логарифмически распределённые частоты для evaluation
        val evalFreqs = calculator.logSpacedFrequencies()

        // Интерполяция измеренной АЧХ на точки evaluation
        val measuredAtEval = interpolateLogFreq(
            measurement.frequencies, measurement.spl, evalFreqs
        )

        // Целевая кривая на точках evaluation
        val targetAtEval = DoubleArray(evalFreqs.size) { i ->
            target.interpolateSpl(evalFreqs[i])
        }

        // Маска: только точки в Match Range
        val inRange = BooleanArray(evalFreqs.size) { i ->
            evalFreqs[i] >= config.matchRangeStart && evalFreqs[i] <= config.matchRangeEnd
        }

        val minUsefulGain = 0.5  // Минимальный полезный gain (dB)
        val minImprovement = 0.15 // Минимальное улучшение maxDeviation за итерацию (dB)
        val cleanupGainThreshold = 0.3 // Порог для удаления бесполезных полос после цикла

        // Накопленные полосы PEQ
        val bands = mutableListOf<ParametricEqBand>()
        var overallBoost = 0.0

        var iteration = 0
        var maxDeviation = Double.MAX_VALUE
        var prevMaxDeviation = Double.MAX_VALUE

        while (iteration < config.maxFilters && maxDeviation > config.flatnessTarget) {
            // Вычисление текущей АЧХ фильтров
            val filterResponse = if (bands.isEmpty()) {
                DoubleArray(evalFreqs.size) { 0.0 }
            } else {
                calculator.compute(bands).leftResponseDb
            }

            // Вектор ошибки: measured + filter - target
            val error = DoubleArray(evalFreqs.size) { i ->
                if (inRange[i]) {
                    (measuredAtEval[i] + filterResponse[i]) - targetAtEval[i]
                } else 0.0
            }

            // Поиск максимального |E(f)| в диапазоне
            var maxIdx = -1
            var maxAbsError = 0.0
            for (i in error.indices) {
                if (!inRange[i]) continue
                val absErr = abs(error[i])
                if (absErr > maxAbsError) {
                    maxAbsError = absErr
                    maxIdx = i
                }
            }

            if (maxIdx < 0 || maxAbsError <= config.flatnessTarget) {
                maxDeviation = maxAbsError
                break
            }

            maxDeviation = maxAbsError
            val f0 = evalFreqs[maxIdx]
            val e0 = error[maxIdx]

            // Определение типа фильтра: shelf для краёв диапазона, peaking для середины
            val filterType = selectFilterType(f0, config)

            if (e0 > 0) {
                // Peak: режем pk/shelf с отрицательным gain
                val gain = (-e0).coerceIn(-config.maxGain, config.maxGain)

                // Пропускаем фильтры с слишком малым gain
                if (abs(gain) < minUsefulGain) {
                    iteration++
                    continue
                }

                val q = estimateQ(evalFreqs, error, maxIdx, e0, config)

                bands.add(ParametricEqBand(
                    f0.coerceIn(config.minFreq, config.maxFreq),
                    gain, q,
                    filterType,
                    ParametricEqChannel.LEFT_RIGHT
                ))
            } else {
                // Dip: проверяем, можно ли корректировать
                val dipDepth = -e0

                // Пропускаем слишком малые ошибки
                if (dipDepth < minUsefulGain) {
                    iteration++
                    continue
                }

                // Проверка: только широкие неглубокие dips (для peaking)
                if (dipDepth > config.dipMaxDepth && filterType == ParametricEqFilterType.PEAKING) {
                    iteration++
                    continue
                }

                if (filterType == ParametricEqFilterType.PEAKING) {
                    val dipWidth = measureDipWidth(evalFreqs, error, maxIdx)
                    if (dipWidth < config.dipMinWidthHz) {
                        iteration++
                        continue
                    }
                }

                // Коррекция dip с ограничением Max Boost
                var gain = min(-e0, config.individualMaxBoost)

                if (overallBoost + gain > config.overallMaxBoost) {
                    gain = (config.overallMaxBoost - overallBoost).coerceAtLeast(0.0)
                    if (gain < minUsefulGain) break
                }
                overallBoost += gain

                val q = estimateQ(evalFreqs, error, maxIdx, e0, config)

                bands.add(ParametricEqBand(
                    f0.coerceIn(config.minFreq, config.maxFreq),
                    gain, q,
                    filterType,
                    ParametricEqChannel.LEFT_RIGHT
                ))
            }

            // Nelder-Mead joint optimization после каждого добавленного фильтра
            if (config.useNelderMead && bands.isNotEmpty()) {
                jointlyOptimizeBands(bands, evalFreqs, measuredAtEval, targetAtEval, inRange, config)
            }

            // Проверка: улучшился ли результат после добавления фильтра?
            // Вычисляем maxDeviation с текущим набором полос
            val filterRespCheck = if (bands.isEmpty()) DoubleArray(evalFreqs.size) { 0.0 }
                                  else calculator.compute(bands).leftResponseDb
            var newMaxDev = 0.0
            for (i in evalFreqs.indices) {
                if (!inRange[i]) continue
                val dev = abs((measuredAtEval[i] + filterRespCheck[i]) - targetAtEval[i])
                if (dev > newMaxDev) newMaxDev = dev
            }

            // Если улучшение слишком маленькое — откатываем последний фильтр и стоп
            if (prevMaxDeviation - newMaxDev < minImprovement && bands.size > 1) {
                Timber.d("SquigAutoEqEngine:_stop - улучшение ${(prevMaxDeviation - newMaxDev)} dB < $minImprovement, откат последнего фильтра")
                bands.removeAt(bands.size - 1)
                maxDeviation = prevMaxDeviation
                break
            }

            prevMaxDeviation = maxDeviation
            maxDeviation = newMaxDev
            iteration++
        }

        // Пост-фильтрация: удаляем полосы с |gain| < cleanupGainThreshold
        val beforeCleanup = bands.size
        bands.removeAll { abs(it.gain) < cleanupGainThreshold }
        if (bands.size < beforeCleanup) {
            Timber.d("SquigAutoEqEngine: удалено ${beforeCleanup - bands.size} бесполезных полос (|gain| < $cleanupGainThreshold dB)")
        }

        // Вычисление preamp: -max(positive gain)
        val preampDb = computePreamp(bands)

        // Вычисление corrected FR
        val filterResponse0 = calculator.compute(bands, 0.0)
        val correctedFR0 = measurement.applyCorrection(
            filterResponse0.leftResponseDb,
            filterResponse0.frequencies
        )
        val correctedFR = correctedFR0.applyPreamp(preampDb)

        // Максимальное отклонение
        val finalMaxDeviation = computeMaxDeviation(correctedFR, target)

        Timber.i("SquigAutoEqEngine: готово, ${bands.size} полос, preamp=$preampDb dB, maxDev=$finalMaxDeviation dB")

        // Детальное логирование
        val logFreqs = doubleArrayOf(20.0, 100.0, 500.0, 1000.0, 5000.0, 10000.0, 20000.0)
        for (lf in logFreqs) {
            val mSpl = measurement.interpolateSpl(lf)
            val tSpl = target.interpolateSpl(lf)
            val cSpl = correctedFR.interpolateSpl(lf)
            Timber.i("SquigAutoEqEngine: @${"%.0f".format(lf)}Hz meas=${"%.2f".format(mSpl)} target=${"%.2f".format(tSpl)} corrected=${"%.2f".format(cSpl)} (preamp=$preampDb)")
        }

        return Result(bands, preampDb, correctedFR, finalMaxDeviation)
    }

    // ── Внутренние алгоритмы (портированы из MEOW AutoEqEngine) ───────────

    /**
     * Оценка Q по ширине ошибки на уровне половины пиковой ошибки.
     */
    private fun estimateQ(
        freqs: DoubleArray, error: DoubleArray,
        maxIdx: Int, peakError: Double,
        config: Config
    ): Double {
        val f0 = freqs[maxIdx]
        val halfLevel = abs(peakError) / 2.0

        var leftIdx = maxIdx
        while (leftIdx > 0 && abs(error[leftIdx]) > halfLevel) leftIdx--
        var rightIdx = maxIdx
        while (rightIdx < error.size - 1 && abs(error[rightIdx]) > halfLevel) rightIdx++

        val fLeft = freqs[leftIdx]
        val fRight = freqs[rightIdx]
        val bandwidth = (fRight - fLeft).coerceAtLeast(1.0)

        var q = f0 / bandwidth
        val maxQ = if (f0 < config.qCrossoverFreq) config.maxQLow else config.maxQHigh
        return q.coerceIn(config.minQ, maxQ)
    }

    /**
     * Измерение ширины dip в Гц на уровне половины глубины.
     */
    private fun measureDipWidth(
        freqs: DoubleArray, error: DoubleArray, maxIdx: Int
    ): Double {
        val halfLevel = abs(error[maxIdx]) / 2.0
        var leftIdx = maxIdx
        while (leftIdx > 0 && abs(error[leftIdx]) > halfLevel) leftIdx--
        var rightIdx = maxIdx
        while (rightIdx < error.size - 1 && abs(error[rightIdx]) > halfLevel) rightIdx++
        return freqs[rightIdx] - freqs[leftIdx]
    }

    /**
     * Выбор типа фильтра на основе частоты.
     * Low-shelf для НЧ-края, High-shelf для ВЧ-края, Peaking для середины.
     */
    private fun selectFilterType(freq: Double, config: Config): ParametricEqFilterType {
        if (config.allowLowShelf && freq < config.lowShelfCutoffHz) {
            return ParametricEqFilterType.LOW_SHELF
        }
        if (config.allowHighShelf && freq > config.highShelfCutoffHz) {
            return ParametricEqFilterType.HIGH_SHELF
        }
        return ParametricEqFilterType.PEAKING
    }

    /**
     * Совместная оптимизация параметров всех полос через Nelder-Mead Simplex.
     * Оптимизирует Fc, Q и Gain каждой полосы для минимизации MSE.
     */
    private fun jointlyOptimizeBands(
        bands: MutableList<ParametricEqBand>,
        evalFreqs: DoubleArray,
        measuredAtEval: DoubleArray,
        targetAtEval: DoubleArray,
        inRange: BooleanArray,
        config: Config
    ) {
        val nBands = bands.size
        if (nBands == 0) return

        val nParams = nBands * 3
        val initial = DoubleArray(nParams)
        val bounds = ArrayList<Pair<Double, Double>>(nParams)

        for (i in 0 until nBands) {
            val band = bands[i]
            initial[i * 3] = kotlin.math.ln(band.frequency.coerceIn(1.0, sampleRate * 0.49))
            initial[i * 3 + 1] = band.q
            initial[i * 3 + 2] = band.gain
            bounds.add(kotlin.math.ln(1.0) to kotlin.math.ln(sampleRate * 0.49))
            bounds.add(config.minQ to config.maxQLow)
            bounds.add(-config.individualMaxBoost to config.individualMaxBoost)
        }

        val fn: (DoubleArray) -> Double = { params ->
            val tempBands = (0 until nBands).map { i ->
                ParametricEqBand(
                    frequency = exp(params[i * 3]).coerceIn(1.0, sampleRate * 0.49),
                    gain = params[i * 3 + 2],
                    q = params[i * 3 + 1],
                    filterType = bands[i].filterType,
                    channel = ParametricEqChannel.LEFT_RIGHT
                )
            }
            val filterResponse = calculator.compute(tempBands).leftResponseDb
            var mse = 0.0
            var count = 0
            for (i in evalFreqs.indices) {
                if (!inRange[i]) continue
                val diff = (measuredAtEval[i] + filterResponse[i]) - targetAtEval[i]
                mse += diff * diff
                count++
            }
            mse /= count.coerceAtLeast(1)

            // Штраф за высокую добротность
            var penalty = 0.0
            for (i in 0 until nBands) {
                val q = params[i * 3 + 1]
                val maxQ = if (exp(params[i * 3]) < config.qCrossoverFreq) config.maxQLow else config.maxQHigh
                if (q > maxQ) {
                    penalty += 0.5 * (q - maxQ).pow(2)
                }
            }
            mse + penalty
        }

        val optimized = nelderMead(initial, bounds, fn, config.nelderMeadIterations)

        for (i in 0 until nBands) {
            val newFc = exp(optimized[i * 3]).coerceIn(1.0, sampleRate * 0.49)
            val newQ = optimized[i * 3 + 1].coerceIn(config.minQ, config.maxQLow)
            val newGain = optimized[i * 3 + 2].coerceIn(-config.individualMaxBoost, config.individualMaxBoost)
            bands[i] = ParametricEqBand(newFc, newGain, newQ, bands[i].filterType, bands[i].channel)
        }
    }

    /**
     * Nelder-Mead Simplex optimization (pure Kotlin).
     */
    private fun nelderMead(
        initial: DoubleArray,
        bounds: List<Pair<Double, Double>>,
        fn: (DoubleArray) -> Double,
        maxIter: Int = 300
    ): DoubleArray {
        val n = initial.size
        val alpha = 1.0   // reflection
        val gamma = 2.0   // expansion
        val rho = 0.5     // contraction
        val sigma = 0.5   // shrink

        val simplex = ArrayList<DoubleArray>(n + 1)
        simplex.add(initial.copyOf())

        for (i in 0 until n) {
            val v = initial.copyOf()
            val range = bounds[i].second - bounds[i].first
            v[i] = (initial[i] + range * 0.05).coerceIn(bounds[i].first, bounds[i].second)
            simplex.add(v)
        }

        val fvals = DoubleArray(n + 1) { i -> fn(simplex[i]) }

        for (iter in 0 until maxIter) {
            val order = (0..n).sortedBy { fvals[it] }
            val bestIdx = order[0]
            val worstIdx = order[n]
            val secondWorstIdx = order[n - 1]

            val fRange = fvals[order[n]] - fvals[order[0]]
            if (fRange < 1e-10) break

            val centroid = DoubleArray(n)
            for (i in 0..n) {
                if (i == worstIdx) continue
                for (j in 0 until n) centroid[j] += simplex[i][j]
            }
            for (j in 0 until n) centroid[j] /= n

            val reflected = DoubleArray(n) { j ->
                (centroid[j] + alpha * (centroid[j] - simplex[worstIdx][j]))
                    .coerceIn(bounds[j].first, bounds[j].second)
            }
            val fReflected = fn(reflected)

            if (fReflected < fvals[bestIdx]) {
                val expanded = DoubleArray(n) { j ->
                    (centroid[j] + gamma * (reflected[j] - centroid[j]))
                        .coerceIn(bounds[j].first, bounds[j].second)
                }
                val fExpanded = fn(expanded)
                if (fExpanded < fReflected) {
                    simplex[worstIdx] = expanded
                    fvals[worstIdx] = fExpanded
                } else {
                    simplex[worstIdx] = reflected
                    fvals[worstIdx] = fReflected
                }
            } else if (fReflected < fvals[secondWorstIdx]) {
                simplex[worstIdx] = reflected
                fvals[worstIdx] = fReflected
            } else {
                val contracted = DoubleArray(n) { j ->
                    (centroid[j] + rho * (simplex[worstIdx][j] - centroid[j]))
                        .coerceIn(bounds[j].first, bounds[j].second)
                }
                val fContracted = fn(contracted)
                if (fContracted < fvals[worstIdx]) {
                    simplex[worstIdx] = contracted
                    fvals[worstIdx] = fContracted
                } else {
                    for (i in 0..n) {
                        if (i == bestIdx) continue
                        simplex[i] = DoubleArray(n) { j ->
                            (simplex[bestIdx][j] + sigma * (simplex[i][j] - simplex[bestIdx][j]))
                                .coerceIn(bounds[j].first, bounds[j].second)
                        }
                        fvals[i] = fn(simplex[i])
                    }
                }
            }
        }

        val bestIdx = (0..n).minByOrNull { fvals[it] } ?: 0
        return simplex[bestIdx]
    }

    /**
     * Вычисление preamp: -max(positive gain) для предотвращения клиппинга.
     */
    private fun computePreamp(bands: List<ParametricEqBand>): Double {
        if (bands.isEmpty()) return 0.0
        val maxPositiveGain = bands.maxOf { it.gain }
        return if (maxPositiveGain > 0) -maxPositiveGain else 0.0
    }

    /**
     * Линейная интерполяция в log-freq пространстве.
     */
    private fun interpolateLogFreq(
        srcFreqs: DoubleArray, srcValues: DoubleArray,
        dstFreqs: DoubleArray
    ): DoubleArray {
        val result = DoubleArray(dstFreqs.size)
        for (i in dstFreqs.indices) {
            val f = dstFreqs[i]
            if (f <= srcFreqs[0]) {
                result[i] = srcValues[0]
                continue
            }
            if (f >= srcFreqs[srcFreqs.size - 1]) {
                result[i] = srcValues[srcFreqs.size - 1]
                continue
            }
            var lo = 0
            var hi = srcFreqs.size - 1
            while (hi - lo > 1) {
                val mid = (lo + hi) / 2
                if (srcFreqs[mid] <= f) lo = mid else hi = mid
            }
            val f0 = srcFreqs[lo]
            val f1 = srcFreqs[hi]
            val v0 = srcValues[lo]
            val v1 = srcValues[hi]
            val t = (f - f0) / (f1 - f0)
            result[i] = v0 + t * (v1 - v0)
        }
        return result
    }

    /**
     * Вычисление максимального отклонения corrected FR от target.
     */
    private fun computeMaxDeviation(corrected: FrequencyResponse, target: FrequencyResponse): Double {
        var maxDev = 0.0
        for (i in corrected.frequencies.indices) {
            val targetSpl = target.interpolateSpl(corrected.frequencies[i])
            val dev = abs(corrected.spl[i] - targetSpl)
            if (dev > maxDev) maxDev = dev
        }
        return maxDev
    }
}
