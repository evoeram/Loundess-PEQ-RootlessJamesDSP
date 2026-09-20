package me.timschneeberger.rootlessjamesdsp.measurement

import me.timschneeberger.rootlessjamesdsp.model.ParametricEqBand
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqChannelMode
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
        val individualMaxBoost: Double = 6.0,    // Макс. усиление одной полосы, дБ
        val overallMaxBoost: Double = 15.0,      // Макс. суммарное усиление, дБ
        val maxQLow: Double = 15.0,              // Макс. Q для НЧ (<200 Гц)
        val maxQHigh: Double = 5.0,              // Макс. Q для ВЧ (>200 Гц)
        val minQ: Double = 0.5,                  // Минимальный Q
        val lowFreqBoundary: Double = 200.0,     // Граница НЧ/ВЧ для Q limits
        val dipMinWidthHz: Double = 10.0,        // Мин. ширина dip для коррекции (иначе non-minimum phase)
        val dipMaxDepth: Double = 8.0,           // Макс. глубина корректируемого dip, дБ
        val qCrossoverFreq: Double = 200.0       // Гц, граница переключения Q limits
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

            // Определение типа коррекции
            if (e0 > 0) {
                // Peak: режем pk с отрицательным gain
                val gain = -e0
                val q = estimateQ(evalFreqs, error, maxIdx, e0, config)

                bands.add(ParametricEqBand(
                    f0, gain, q,
                    ParametricEqFilterType.PEAKING,
                    ParametricEqChannelMode.BOTH
                ))
            } else {
                // Dip: проверяем, можно ли корректировать
                val dipDepth = -e0 // положительное число

                // Проверка: только широкие неглубокие dips
                if (dipDepth > config.dipMaxDepth) {
                    // Слишком глубокий — non-minimum phase, пропускаем
                    // Обнуляем ошибку в этой точке чтобы алгоритм шёл дальше
                    iteration++
                    continue
                }

                // Проверка ширины dip
                val dipWidth = measureDipWidth(evalFreqs, error, maxIdx, config)
                if (dipWidth < config.dipMinWidthHz) {
                    // Узкий dip — cancellation notch, пропускаем
                    iteration++
                    continue
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
                    ParametricEqFilterType.PEAKING,
                    ParametricEqChannelMode.BOTH
                ))
            }

            iteration++
        }

        // Вычисление preamp: смещение чтобы максимальный gain не превышал 0 dB
        val preampDb = computePreamp(bands, config)

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
     * Вычисление preamp: отрицательное смещение чтобы суммарный gain фильтров
     * не приводил к клиппингу. Preamp = -max(positive filter gain).
     */
    private fun computePreamp(bands: List<ParametricEqBand>, config: Config): Double {
        if (bands.isEmpty()) return 0.0
        val maxPositiveGain = bands.maxOf { it.gain }
        return if (maxPositiveGain > 0) -maxPositiveGain else 0.0
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
