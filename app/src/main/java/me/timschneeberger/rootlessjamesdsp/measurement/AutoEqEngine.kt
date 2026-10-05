package me.timschneeberger.rootlessjamesdsp.measurement

import me.timschneeberger.rootlessjamesdsp.model.ParametricEqBand
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqChannel
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqFilterType
import me.timschneeberger.rootlessjamesdsp.utils.ParametricEqResponseCalculator
import kotlin.math.*
/**
 * Auto-EQ engine: жадный итеративный подбор PEQ-фильтров.
 *
 * Алгоритм (greedy, двунаправленный):
 * 1. Вычисление вектора ошибки: E(f) = SPL_measured(f) - Target(f)
 * 2. Поиск частоты f0 с максимальным |E(f)| в диапазоне Match Range
 * 3. Если E > 0 (peak): PK-фильтр с gain = -E(f0), Q по -3 dB ширине
 * 4. Если E < 0 (dip): только широкие dips, PK с gain = -E(f0), ограничение Max Boost
 * 5. Проверка Q limits: Q ≤ 15–20 для НЧ (<200 Гц), Q ≤ 5 для ВЧ (>200 Гц)
 * 6. Evaluation через ParametricEqResponseCalculator
 * 7. Итерация до Flatness Target или лимита полос
 *
 * Non-minimum phase awareness:
 * Узкие глубокие провалы (cancellation notches) НЕ корректируются.
 * Корректируются только широкие неглубокие провалы (broad dips),
 * где ширина на уровне Max Boost превышает порог.
 *
 * @param sampleRate частота дискретизации DSP (например 48000.0)
 * @param numPoints количество точек для evaluation (больше = точнее, но медленнее)
 */
class AutoEqEngine(
    private val sampleRate: Double = 48000.0,
    private val numPoints: Int = 512
) {
    /** Конфигурация Auto-EQ. */
    data class Config(
        val matchRangeStart: Double = 20.0,      // Начало диапазона коррекции, Гц
        val matchRangeEnd: Double = 20000.0,     // Конец диапазона коррекции, Гц
        val flatnessTarget: Double = 1.0,        // Целевой макс. остаточный разброс, дБ
        val maxBands: Int = 15,                  // Максимум полос PEQ
        val individualMaxBoost: Double = 36.0,    // Макс. усиление одной полосы, дБ
        val overallMaxBoost: Double = 15.0,      // Макс. суммарное усиление, дБ
        val maxQLow: Double = 15.0,              // Макс. Q для НЧ (<200 Гц)
        val maxQHigh: Double = 5.0,              // Макс. Q для ВЧ (>200 Гц)
        val minQ: Double = 0.5,                  // Минимальный Q
        val lowFreqBoundary: Double = 200.0,     // Граница НЧ/ВЧ для Q limits
        val dipMinWidthHz: Double = 10.0,        // Мин. ширина dip для коррекции (иначе non-minimum phase)
        val dipMaxDepth: Double = 8.0,           // Макс. глубина корректируемого dip, дБ
        val qCrossoverFreq: Double = 200.0,      // Гц, граница переключения Q limits
        // Shelf filter support
        val allowLowShelf: Boolean = true,       // Использовать low-shelf для НЧ-диапазона
        val allowHighShelf: Boolean = true,      // Использовать high-shelf для ВЧ-диапазона
        val lowShelfCutoffHz: Double = 100.0,    // Частоты ниже этой → low-shelf кандидат
        val highShelfCutoffHz: Double = 5000.0,  // Частоты выше этой → high-shelf кандидат
        // Nelder-Mead joint optimization
        val useNelderMead: Boolean = true,       // Совместная оптимизация после greedy placement
        val nelderMeadIterations: Int = 300      // Итераций Nelder-Mead на каждый шаг
    )

    /** Результат Auto-EQ. */
    data class Result(
        val bands: List<ParametricEqBand>,
        val preampDb: Double,
        val iterations: Int,
        val finalMaxDeviation: Double,
        val targetMet: Boolean
    )

    private val calculator = ParametricEqResponseCalculator(
        sampleRate, 20.0, 20000.0, numPoints
    )

    /**
     * Запустить Auto-EQ.
     *
     * @param measuredFreqs массив частот измеренной АЧХ (Гц)
     * @param measuredSpl   массив SPL (дБ), той же длины
     * @param target        целевая кривая
     * @param config        параметры Auto-EQ
     * @return результат с подобранными полосами PEQ
     */
    fun run(
        measuredFreqs: DoubleArray,
        measuredSpl: DoubleArray,
        target: TargetCurve,
        config: Config = Config()
    ): Result {
        require(measuredFreqs.size == measuredSpl.size) {
            "freqs and spl arrays must have same length"
        }

        // Логарифмически распределённые частоты для evaluation
        val evalFreqs = calculator.logSpacedFrequencies()

        // Интерполяция измеренной АЧХ на точки evaluation
        val measuredAtEval = interpolateLogFreq(measuredFreqs, measuredSpl, evalFreqs)

        // Целевая кривая на точках evaluation
        val targetAtEval = target.gainAtAll(evalFreqs)

        // Маска: только точки в Match Range
        val inRange = BooleanArray(evalFreqs.size) { i ->
            evalFreqs[i] >= config.matchRangeStart && evalFreqs[i] <= config.matchRangeEnd
        }

        // Накопленные полосы PEQ
        val bands = mutableListOf<ParametricEqBand>()
        var overallBoost = 0.0

        var iteration = 0
        var maxDeviation = Double.MAX_VALUE

        while (iteration < config.maxBands && maxDeviation > config.flatnessTarget) {
            // Вычисление текущей АЧХ фильтров
            val filterResponse = if (bands.isEmpty()) {
                DoubleArray(evalFreqs.size) { 0.0 }
            } else {
                calculator.compute(bands).leftResponseDb
            }

            // Вектор ошибки: measured + filter - target
            // filter уже применён, так что остаточная ошибка:
            // E(f) = (measured(f) + filter(f)) - target(f)
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
            val filterType = selectFilterType(f0, e0, config)

            // Определение типа коррекции
            if (e0 > 0) {
                // Peak: режем pk/shelf с отрицательным gain
                val gain = -e0
                val q = estimateQ(evalFreqs, error, maxIdx, e0, config)

                bands.add(ParametricEqBand(
                    f0, gain, q,
                    filterType,
                    ParametricEqChannel.LEFT_RIGHT
                ))
            } else {
                // Dip: проверяем, можно ли корректировать
                val dipDepth = -e0 // положительное число

                // Проверка: только широкие неглубокие dips
                if (dipDepth > config.dipMaxDepth && filterType == ParametricEqFilterType.PEAKING) {
                    // Слишком глубокий — non-minimum phase, пропускаем
                    iteration++
                    continue
                }

                // Проверка ширины dip (только для peaking; shelf-фильтры широкие по определению)
                if (filterType == ParametricEqFilterType.PEAKING) {
                    val dipWidth = measureDipWidth(evalFreqs, error, maxIdx, config)
                    if (dipWidth < config.dipMinWidthHz) {
                        // Узкий dip — cancellation notch, пропускаем
                        iteration++
                        continue
                    }
                }

                // Коррекция dip с ограничением Max Boost
                var gain = -e0 // положительный (boost)
                gain = min(gain, config.individualMaxBoost)

                // Проверка overall Max Boost
                if (overallBoost + gain > config.overallMaxBoost) {
                    gain = (config.overallMaxBoost - overallBoost).coerceAtLeast(0.0)
                    if (gain < 0.5) break // лимит исчерпан
                }
                overallBoost += gain

                val q = estimateQ(evalFreqs, error, maxIdx, e0, config)

                bands.add(ParametricEqBand(
                    f0, gain, q,
                    filterType,
                    ParametricEqChannel.LEFT_RIGHT
                ))
            }

            // Nelder-Mead joint optimization после каждого добавленного фильтра
            if (config.useNelderMead && bands.isNotEmpty()) {
                jointlyOptimizeBands(bands, evalFreqs, measuredAtEval, targetAtEval, inRange, config)
            }

            iteration++
        }

        // Вычисление preamp из реальной суммарной АЧХ каскада фильтров,
        // а не из max(gain отдельной полосы). Каскад shelf/peaking фильтров
        // может давать пик выше gain любой отдельной полосы (особенно
        // при большом |gain| и низкой Q), а отрицательный shelf может
        // создавать положительный горб выше частоты среза.
        val filterResponse = calculator.compute(bands, 0.0)
        val maxPeak = maxOf(
            filterResponse.leftResponseDb.maxOrNull() ?: 0.0,
            filterResponse.rightResponseDb.maxOrNull() ?: 0.0
        )
        val preampDb = if (maxPeak > 0.0) -maxPeak else 0.0

        return Result(
            bands = bands,
            preampDb = preampDb,
            iterations = iteration,
            finalMaxDeviation = maxDeviation,
            targetMet = maxDeviation <= config.flatnessTarget
        )
    }

    /**
     * Оценка Q по ширине ошибки на уровне -3 dB (или половины пиковой ошибки).
     *
     * Q = f0 / bandwidth, где bandwidth — ширина на уровне -3 dB.
     * Для dips: ширина на уровне половины глубины.
     */
    private fun estimateQ(
        freqs: DoubleArray, error: DoubleArray,
        maxIdx: Int, peakError: Double,
        config: Config
    ): Double {
        val f0 = freqs[maxIdx]
        val halfLevel = abs(peakError) / 2.0

        // Поиск левой границы (-3 dB точка)
        var leftIdx = maxIdx
        while (leftIdx > 0 && abs(error[leftIdx]) > halfLevel) {
            leftIdx--
        }

        // Поиск правой границы (-3 dB точка)
        var rightIdx = maxIdx
        while (rightIdx < error.size - 1 && abs(error[rightIdx]) > halfLevel) {
            rightIdx++
        }

        val fLeft = freqs[leftIdx]
        val fRight = freqs[rightIdx]
        val bandwidth = (fRight - fLeft).coerceAtLeast(1.0)

        var q = f0 / bandwidth

        // Ограничение Q по частоте
        val maxQ = if (f0 < config.qCrossoverFreq) config.maxQLow else config.maxQHigh
        q = q.coerceIn(config.minQ, maxQ)

        return q
    }

    /**
     * Измерение ширины dip в Гц на уровне половины глубины.
     */
    private fun measureDipWidth(
        freqs: DoubleArray, error: DoubleArray,
        maxIdx: Int, config: Config
    ): Double {
        val halfLevel = abs(error[maxIdx]) / 2.0

        var leftIdx = maxIdx
        while (leftIdx > 0 && abs(error[leftIdx]) > halfLevel) leftIdx--
        var rightIdx = maxIdx
        while (rightIdx < error.size - 1 && abs(error[rightIdx]) > halfLevel) rightIdx++

        return freqs[rightIdx] - freqs[leftIdx]
    }

    /**
     * Выбор типа фильтра на основе частоты и знака ошибки.
     *
     * - Low-shelf: для частот ниже lowShelfCutoffHz (НЧ-край диапазона)
     * - High-shelf: для частот выше highShelfCutoffHz (ВЧ-край диапазона)
     * - Peaking: для средних частот
     *
     * Shelf-фильтры не используются, если они отключены в конфигурации.
     */
    private fun selectFilterType(
        freq: Double, error: Double, config: Config
    ): ParametricEqFilterType {
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
     *
     * Оптимизирует Fc, Q и Gain каждой полосы для минимизации
     * среднеквадратичной ошибки между (measured + filter) и target.
     *
     * Параметры хранятся в log-масштабе частоты для лучшей сходимости.
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

        // Параметры: [logFc0, Q0, Gain0, logFc1, Q1, Gain1, ...]
        val nParams = nBands * 3
        val initial = DoubleArray(nParams)
        val bounds = ArrayList<Pair<Double, Double>>(nParams)

        for (i in 0 until nBands) {
            val band = bands[i]
            initial[i * 3] = Math.log(band.frequency.coerceIn(1.0, sampleRate * 0.49))
            initial[i * 3 + 1] = band.q
            initial[i * 3 + 2] = band.gain
            bounds.add(Math.log(1.0) to Math.log(sampleRate * 0.49))  // logFc
            bounds.add(config.minQ to config.maxQLow)                   // Q
            bounds.add(-config.individualMaxBoost to config.individualMaxBoost)  // gain
        }

        // Целевая функция
        val fn: (DoubleArray) -> Double = { params ->
            val tempBands = (0 until nBands).map { i ->
                ParametricEqBand(
                    frequency = Math.exp(params[i * 3]).coerceIn(1.0, sampleRate * 0.49),
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
                val maxQ = if (Math.exp(params[i * 3]) < config.qCrossoverFreq) config.maxQLow else config.maxQHigh
                if (q > maxQ) {
                    penalty += 0.5 * (q - maxQ).pow(2)
                }
            }
            mse + penalty
        }

        val optimized = nelderMead(initial, bounds, fn, config.nelderMeadIterations)

        // Запись результатов обратно
        for (i in 0 until nBands) {
            val newFc = Math.exp(optimized[i * 3]).coerceIn(1.0, sampleRate * 0.49)
            val newQ = optimized[i * 3 + 1].coerceIn(config.minQ, config.maxQLow)
            val newGain = optimized[i * 3 + 2].coerceIn(-config.individualMaxBoost, config.individualMaxBoost)
            bands[i] = ParametricEqBand(newFc, newGain, newQ, bands[i].filterType, bands[i].channel)
        }
    }

    /**
     * Nelder-Mead Simplex optimization (pure Kotlin, no external deps).
     *
     * @param initial начальная точка (n-мерный вектор)
     * @param bounds  границы параметров (мин, макс) для каждого измерения
     * @param fn      целевая функция (минимизация)
     * @param maxIter максимум итераций
     * @return оптимизированный вектор параметров
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
     * Линейная интерполяция в log-freq пространстве.
     * Перенос измеренной АЧХ на точки evaluation.
     */
    private fun interpolateLogFreq(
        srcFreqs: DoubleArray, srcValues: DoubleArray,
        dstFreqs: DoubleArray
    ): DoubleArray {
        val result = DoubleArray(dstFreqs.size)

        for (i in dstFreqs.indices) {
            val f = dstFreqs[i]

            // Ниже первой точки — значение первой
            if (f <= srcFreqs[0]) {
                result[i] = srcValues[0]
                continue
            }
            // Выше последней — значение последней
            if (f >= srcFreqs[srcFreqs.size - 1]) {
                result[i] = srcValues[srcFreqs.size - 1]
                continue
            }

            // Бинарный поиск интервала
            var lo = 0
            var hi = srcFreqs.size - 1
            while (hi - lo > 1) {
                val mid = (lo + hi) / 2
                if (srcFreqs[mid] <= f) lo = mid else hi = mid
            }

            // Линейная интерполяция
            val f0 = srcFreqs[lo]
            val f1 = srcFreqs[hi]
            val v0 = srcValues[lo]
            val v1 = srcValues[hi]
            val t = (f - f0) / (f1 - f0)
            result[i] = v0 + t * (v1 - v0)
        }

        return result
    }
}
