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
        val nelderMeadIterations: Int = 300,     // Итераций Nelder-Mead на каждый шаг
        // Joint optimizer selection (libMultivariateOpt DE/CHIO port)
        val optimizer: Optimizer = Optimizer.NELDER_MEAD, // Алгоритм совместной оптимизации
        val optimizerSeed: Long = 1337L,         // Базовый seed multi-start (детерминизм)
        val multiStartRuns: Int = 3,             // Число рестартов DE/CHIO
        val optimizerEvalBudgetMultiplier: Double = 0.4, // Бюджет стадии 2 (мульти-старт) относительно Nelder-Mead
        val deGroupSize: Int = 5,                // DE: K (популяция = K*N)
        val deSampleSize: Int = 3,               // DE: N (пробных векторов за эпоху)
        val deProbiBound: Double = 0.99,         // DE: вероятность проекции на границу
        val chioPopulationSize: Int = 15,        // CHIO: размер популяции
        val chioMaxSolSurviveEpoch: Int = 100,   // CHIO: предел возраста до регенерации
        val chioC0: Int = 6,                     // CHIO: исходное число заражённых
        val chioSpreadingRate: Double = 0.05,    // CHIO: вероятность мутации координаты
        val optimizerSpreadLogFc: Double = 0.10, // Разброс init-популяции: ln(fc)
        val optimizerSpreadQ: Double = 0.5,      // Разброс init-популяции: Q
        val optimizerSpreadGain: Double = 1.5,   // Разброс init-популяции: gain, дБ
        // Treble averaging (AutoEq `_optimizer_loss`)
        val trebleAveragingEnabled: Boolean = true, // Выше порога loss учитывает только среднее области
        val trebleAverageAboveHz: Double = 10000.0, // Порог ВЧ-усреднения, Гц
        // Sharpness penalty (AutoEq)
        val sharpnessPenaltyEnabled: Boolean = true,  // Штраф за наклон круче ~18 дБ/окт
        val sharpnessPenaltyWeight: Double = 1.0      // Вес штрафа в функции потерь
    )

    /** Алгоритм совместной оптимизации параметров полос. */
    enum class Optimizer {
        /** Nelder-Mead Simplex (историческое поведение, детерминированный). */
        NELDER_MEAD,
        /** Bounded Differential Evolution с Bezier-мутацией, multi-start. */
        DE,
        /** Bounded CHIO (Coronavirus Herd Immunity Optimizer), multi-start. */
        CHIO
    }

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
     * Грубая log-сетка для штрафа за резкость: 128 точек на весь диапазон,
     * чтобы штраф не удорожал loss пропорционально [numPoints].
     */
    private val sharpnessFreqs: DoubleArray by lazy {
        DoubleArray(SHARPNESS_POINTS) { i ->
            val t = i.toDouble() / (SHARPNESS_POINTS - 1)
            10.0.pow(
                log10(SHARPNESS_MIN_HZ) +
                    t * (log10(SHARPNESS_MAX_HZ) - log10(SHARPNESS_MIN_HZ))
            )
        }
    }

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

        // ВЧ-усреднение (AutoEq): выше порога loss видит только среднее
        // отклонение области, чтобы оптимизатор не тратил полосы на узкие
        // ВЧ-пики, которые не слышны и нестабильны между измерениями
        val trebleRange = trebleAverageRegion(evalFreqs, inRange, config)
        val trebleMeanTarget = trebleRange?.let { meanOver(it, targetAtEval) } ?: 0.0
        val residual = DoubleArray(evalFreqs.size)

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

            // residual(f) = measured + filter — текущая скорректированная АЧХ
            for (i in evalFreqs.indices) {
                residual[i] = measuredAtEval[i] + filterResponse[i]
            }

            // Вектор ошибки: residual - target; выше порога ВЧ-усреднения
            // вся область схлопывается в своё среднее отклонение
            val trebleMeanResidual = trebleRange?.let { meanOver(it, residual) } ?: 0.0
            val error = DoubleArray(evalFreqs.size) { i ->
                if (!inRange[i]) 0.0
                else if (trebleRange != null && i >= trebleRange.first)
                    trebleMeanResidual - trebleMeanTarget
                else residual[i] - targetAtEval[i]
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

            // Joint optimization после каждого добавленного фильтра.
            // Траектория greedy всегда идентична Nelder-Mead; мульти-старт
            // DE/CHIO выполняется один раз финальной полировкой (ниже),
            // иначе принятое уточнение меняет residual, greedy кладёт другие
            // полосы и гарантия «не хуже Nelder-Mead» теряется.
            if (config.useNelderMead && bands.isNotEmpty()) {
                jointlyOptimizeBands(
                    bands, evalFreqs, measuredAtEval, targetAtEval, inRange,
                    trebleRange, trebleMeanTarget, config, iteration, refine = false
                )
            }

            iteration++
        }

        // Финальная полировка multi-start DE/CHIO вокруг результата Nelder-Mead
        if (config.optimizer != Optimizer.NELDER_MEAD && bands.isNotEmpty()) {
            jointlyOptimizeBands(
                bands, evalFreqs, measuredAtEval, targetAtEval, inRange,
                trebleRange, trebleMeanTarget, config, iteration, refine = true
            )
        }

        // overallMaxBoost — жёсткий предел суммарного буста каскада.
        // Nelder-Mead ограничивает только усиление отдельных полос, поэтому
        // при превышении лимита положительные gains масштабируются один раз
        // после оптимизации, не меняя траекторию поиска.
        val totalBoost = bands.sumOf { if (it.gain > 0.0) it.gain else 0.0 }
        val boostScaled = totalBoost > config.overallMaxBoost
        if (boostScaled) {
            val scale = config.overallMaxBoost / totalBoost
            for (i in bands.indices) {
                val band = bands[i]
                if (band.gain > 0.0) {
                    bands[i] = ParametricEqBand(
                        band.frequency, band.gain * scale, band.q,
                        band.filterType, band.channel
                    )
                }
            }
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

        // После масштабирования бустов остаточное отклонение нужно пересчитать.
        val finalDeviation = if (boostScaled) {
            for (i in evalFreqs.indices) {
                residual[i] = measuredAtEval[i] + filterResponse.leftResponseDb[i]
            }
            val trebleMeanResidual = trebleRange?.let { meanOver(it, residual) } ?: 0.0
            var dev = 0.0
            for (i in evalFreqs.indices) {
                if (!inRange[i]) continue
                val err = abs(
                    if (trebleRange != null && i >= trebleRange.first)
                        trebleMeanResidual - trebleMeanTarget
                    else residual[i] - targetAtEval[i]
                )
                if (err > dev) dev = err
            }
            dev
        } else {
            maxDeviation
        }

        return Result(
            bands = bands,
            preampDb = preampDb,
            iterations = iteration,
            finalMaxDeviation = finalDeviation,
            targetMet = finalDeviation <= config.flatnessTarget
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
     * Штраф AutoEq за слишком крутой скат пикового фильтра.
     *
     * gainLimit — усиление, при котором наклон достигает ~18 дБ/окт;
     * сигмоида вокруг него быстро включает штраф. Штраф считается по АЧХ
     * самой полосы на отдельной грубой сетке, а не по общему каскаду.
     * Shelf-фильтры, как и в AutoEq, не штрафуются.
     *
     * AutoEq использует знаковое усиление, поэтому для вырезов коэффициент
     * обращается в ноль. Вырез с тем же |gain| имеет тот же наклон и так же
     * звенит, поэтому здесь берётся модуль усиления.
     */
    private fun sharpnessPenalty(bands: List<ParametricEqBand>, weight: Double): Double {
        var total = 0.0
        for (band in bands) {
            if (band.filterType != ParametricEqFilterType.PEAKING) continue
            if (band.q <= 0.0) continue

            val gainLimit = -0.09503189270199464 + 20.575128011847003 / band.q
            if (gainLimit <= 0.0) continue

            val x = abs(band.gain) / gainLimit - 1.0
            val coefficient = 1.0 / (1.0 + exp(-100.0 * x))
            if (coefficient < SHARPNESS_MIN_COEFFICIENT) continue

            val response = calculator.bandMagnitudeDb(band, sharpnessFreqs)
            var sum = 0.0
            for (v in response) {
                val scaled = v * coefficient
                sum += scaled * scaled
            }
            total += sum / response.size
        }
        return total * weight
    }

    /**
     * Совместная оптимизация параметров всех полос.
     *
     * Оптимизирует Fc, Q и Gain каждой полосы для минимизации
     * среднеквадратичной ошибки между (measured + filter) и target.
     *
     * Параметры хранятся в log-масштабе частоты для лучшей сходимости.
     * [callIndex] — номер шага greedy-фазы; входит в производные seed'ы
     * multi-start, делая всю траекторию детерминированной.
     * [refine] — финальная полировка multi-start DE/CHIO; при false
     * (joint-оптимизация в greedy-фазе) всегда используется Nelder-Mead,
     * чтобы траектория greedy совпадала с эталонной побитово.
     */
    private fun jointlyOptimizeBands(
        bands: MutableList<ParametricEqBand>,
        evalFreqs: DoubleArray,
        measuredAtEval: DoubleArray,
        targetAtEval: DoubleArray,
        inRange: BooleanArray,
        trebleRange: IntRange?,
        trebleMeanTarget: Double,
        config: Config,
        callIndex: Int,
        refine: Boolean
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

        val residual = DoubleArray(evalFreqs.size)

        // Целевая функция. Q и gain приводятся к финальному виду теми же
        // coerceIn, что и запись результатов: оптимизаторы (DE/CHIO) могут
        // вернуть значение вне границ, и loss должен соответствовать точке,
        // которая будет реально записана. Nelder-Mead всегда внутри границ,
        // для него приведение — no-op (golden не меняется).
        val fn: (DoubleArray) -> Double = { params ->
            val tempBands = (0 until nBands).map { i ->
                ParametricEqBand(
                    frequency = Math.exp(params[i * 3]).coerceIn(1.0, sampleRate * 0.49),
                    gain = params[i * 3 + 2].coerceIn(
                        -config.individualMaxBoost, config.individualMaxBoost
                    ),
                    q = params[i * 3 + 1].coerceIn(config.minQ, config.maxQLow),
                    filterType = bands[i].filterType,
                    channel = ParametricEqChannel.LEFT_RIGHT
                )
            }
            val filterResponse = calculator.compute(tempBands).leftResponseDb
            for (i in evalFreqs.indices) {
                residual[i] = measuredAtEval[i] + filterResponse[i]
            }
            val trebleMeanResidual = trebleRange?.let { meanOver(it, residual) } ?: 0.0

            var mse = 0.0
            var count = 0
            for (i in evalFreqs.indices) {
                if (!inRange[i]) continue
                if (trebleRange != null && i >= trebleRange.first) continue
                val diff = residual[i] - targetAtEval[i]
                mse += diff * diff
                count++
            }
            if (trebleRange != null) {
                // Регион ВЧ-усреднения входит в loss одним слагаемым: его
                // отклонение — константа, и учёт каждой точки (как при
                // буквальном замене массивов в AutoEq) даёт константе вес
                // пропорционально длине региона, из-за чего оптимизатор
                // жертвует точностью средних частот ради обнуления среднего
                val diff = trebleMeanResidual - trebleMeanTarget
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

            if (config.sharpnessPenaltyEnabled && config.sharpnessPenaltyWeight > 0.0) {
                penalty += sharpnessPenalty(tempBands, config.sharpnessPenaltyWeight)
            }

            mse + penalty
        }

        // Пост-метрика итогового отклонения для кандидата параметров:
        // (maxDev, RMSE) в конвенции finalMaxDeviation (ВЧ-усреднение,
        // маска inRange, без preamp, с учётом масштабирования общего буста).
        // Нужна, потому что fn (MSE + штрафы) может предпочесть точку
        // с меньшим средним, но большим пиковым отклонением — и наоборот.
        // Параметры приводятся к финальному виду теми же coerceIn, что и
        // запись результатов (оптимизатор может вернуть значение вне границ)
        // — иначе гейт оценит не ту точку, которая будет реально записана.
        fun postDeviationMetric(params: DoubleArray): Pair<Double, Double> {
            val candidate = (0 until nBands).map { i ->
                ParametricEqBand(
                    frequency = Math.exp(params[i * 3]).coerceIn(1.0, sampleRate * 0.49),
                    gain = params[i * 3 + 2].coerceIn(
                        -config.individualMaxBoost, config.individualMaxBoost
                    ),
                    q = params[i * 3 + 1].coerceIn(config.minQ, config.maxQLow),
                    filterType = bands[i].filterType,
                    channel = bands[i].channel
                )
            }
            val totalBoost = candidate.sumOf { if (it.gain > 0.0) it.gain else 0.0 }
            val scaled = if (totalBoost > config.overallMaxBoost) {
                val scale = config.overallMaxBoost / totalBoost
                candidate.map {
                    if (it.gain > 0.0) ParametricEqBand(
                        it.frequency, it.gain * scale, it.q, it.filterType, it.channel
                    ) else it
                }
            } else candidate
            val response = calculator.compute(scaled).leftResponseDb
            for (i in evalFreqs.indices) residual[i] = measuredAtEval[i] + response[i]
            val trebleMeanResidual = trebleRange?.let { meanOver(it, residual) } ?: 0.0
            var dev = 0.0
            var sq = 0.0
            var count = 0
            for (i in evalFreqs.indices) {
                if (!inRange[i]) continue
                val err = if (trebleRange != null && i >= trebleRange.first)
                    trebleMeanResidual - trebleMeanTarget
                else residual[i] - targetAtEval[i]
                val absErr = abs(err)
                if (absErr > dev) dev = absErr
                sq += err * err
                count++
            }
            val rmse = sqrt(sq / count.coerceAtLeast(1))
            return dev to rmse
        }

        val optimized = if (!refine || config.optimizer == Optimizer.NELDER_MEAD) {
            nelderMead(initial, bounds, fn, config.nelderMeadIterations)
        } else {
            multiStartBoundedOptimize(initial, bounds, fn, ::postDeviationMetric, callIndex, config)
        }

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
     * Гибридный запуск ограниченного DE/CHIO: полировка multi-start вокруг
     * результата Nelder-Mead. Повторяет официальный паттерн libMultivariateOpt
     * «SGD + DE» / «SGD + CHIO» (IIROptimizer.c), где чистый метаэвристик
     * требует слишком много итераций для уточнения: стадия 1 — локальный
     * проход Nelder-Mead от текущего решения, стадия 2 — multi-start DE/CHIO.
     *
     * Первый член начальной популяции каждого рестарта — точный результат
     * стадии 1, остальные — возмущения с настроенным разбросом; оба
     * оптимизатора обновляются только на улучшение, поэтому результат
     * не может быть хуже seed по целевой функции. Поскольку целевая функция
     * (MSE + штрафы) не совпадает один в один с пост-метрикой max deviation,
     * результат принимается только если не ухудшил [postMetric] относительно
     * входного состояния ([initial]) — иначе выполняется откат на него.
     * Стадия 1 тоже может сместиться в другую яму с лучшим MSE, но худшим
     * max deviation, поэтому гейт привязан именно к входу, а не к seed.
     * Общий бюджет вызовов целевой функции стадии 2 делится между рестартами
     * и детерминированно ограничивает время (оценка числа evals Nelder-Mead
     * на итерацию — reflection + расширение/сжатие).
     */
    private fun multiStartBoundedOptimize(
        initial: DoubleArray,
        bounds: List<Pair<Double, Double>>,
        fn: (DoubleArray) -> Double,
        postMetric: (DoubleArray) -> Pair<Double, Double>,
        callIndex: Int,
        config: Config
    ): DoubleArray {
        // Стадия 1: локальный seed, как в «SGD + DE» эталона
        val seed = nelderMead(initial, bounds, fn, config.nelderMeadIterations)
        val runs = config.multiStartRuns
        if (runs <= 0) return seed

        val dim = initial.size
        val lower = DoubleArray(dim) { bounds[it].first }
        val upper = DoubleArray(dim) { bounds[it].second }

        val evalBudget = (config.optimizerEvalBudgetMultiplier *
            config.nelderMeadIterations * NM_EVALS_PER_ITER_ESTIMATE).toInt()
            .coerceAtLeast(runs * 64)
        val evalsPerRun = evalBudget / runs

        val rng = Pcg32x2Random(
            deriveSeed(config.optimizerSeed, callIndex.toLong()),
            deriveSeed(config.optimizerSeed, callIndex.toLong() + 0x5DEECE66)
        )

        var bestF = Double.MAX_VALUE
        var bestX = seed
        repeat(runs) {
            val result = if (config.optimizer == Optimizer.DE) {
                val pop = perturbedPopulation(
                    seed, lower, upper,
                    config.deGroupSize * config.deSampleSize, config, rng
                )
                differentialEvolution(
                    fn, pop, config.deGroupSize, config.deSampleSize,
                    config.deProbiBound, dim, lower, upper, Int.MAX_VALUE, rng, evalsPerRun
                )
            } else {
                val pop = perturbedPopulation(
                    seed, lower, upper, config.chioPopulationSize, config, rng
                )
                chio(
                    fn, pop, config.chioPopulationSize, config.chioMaxSolSurviveEpoch,
                    config.chioC0, config.chioSpreadingRate,
                    dim, lower, upper, Int.MAX_VALUE, rng, evalsPerRun
                )
            }
            if (result.f < bestF) {
                bestF = result.f
                bestX = result.x
            }
        }
        val baseMetric = postMetric(initial)
        val bestMetric = postMetric(bestX)
        return if (bestMetric.first <= baseMetric.first + 1e-9 &&
            bestMetric.second <= baseMetric.second + 1e-9
        ) bestX else initial
    }

    /**
     * Начальная популяция multi-start: строка 0 — точное текущее решение,
     * остальные — равномерные возмущения в пределах настроенного разброса
     * (ln fc / Q / gain), спроецированные на границы.
     */
    private fun perturbedPopulation(
        initial: DoubleArray,
        lower: DoubleArray,
        upper: DoubleArray,
        rows: Int,
        config: Config,
        rng: Pcg32x2Random
    ): DoubleArray {
        val pop = DoubleArray(rows * initial.size)
        System.arraycopy(initial, 0, pop, 0, initial.size)
        for (r in 1 until rows) {
            for (j in initial.indices) {
                val spread = when (j % 3) {
                    0 -> config.optimizerSpreadLogFc
                    1 -> config.optimizerSpreadQ
                    else -> config.optimizerSpreadGain
                }
                val v = initial[j] + (rng.cRand() * 2.0 - 1.0) * spread
                pop[r * initial.size + j] = v.coerceIn(lower[j], upper[j])
            }
        }
        return pop
    }

    /** SplitMix64-смешивание базового seed и salt для производных потоков PCG32x2. */
    private fun deriveSeed(base: Long, salt: Long): ULong {
        var z = base.toULong() + salt.toULong() * 0x9E3779B97F4A7C15uL
        z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9uL
        z = (z xor (z shr 27)) * 0x94D049BB133111EBuL
        return z xor (z shr 31)
    }

    /**
     * Диапазон индексов ВЧ-усреднения (AutoEq `_optimizer_loss`): непрерывная
     * область inRange с частотой не ниже порога. Область считается по всему
     * evaluation-диапазону, как у AutoEq, где среднее берётся от 10 кГц до
     * конца сетки. Null — усреднение выключено или область пуста.
     */
    private fun trebleAverageRegion(
        evalFreqs: DoubleArray,
        inRange: BooleanArray,
        config: Config
    ): IntRange? {
        if (!config.trebleAveragingEnabled) return null
        var first = -1
        var last = -1
        for (i in evalFreqs.indices) {
            if (inRange[i] && evalFreqs[i] >= config.trebleAverageAboveHz) {
                if (first < 0) first = i
                last = i
            }
        }
        return if (first >= 0) first..last else null
    }

    private fun meanOver(range: IntRange, values: DoubleArray): Double {
        var sum = 0.0
        for (i in range) sum += values[i]
        return sum / (range.last - range.first + 1)
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

    private companion object {
        const val SHARPNESS_POINTS = 128
        const val SHARPNESS_MIN_HZ = 20.0
        const val SHARPNESS_MAX_HZ = 20000.0

        /** Ниже этого коэффициента штраф пренебрежим — АЧХ полосы не считается. */
        const val SHARPNESS_MIN_COEFFICIENT = 1e-4

        /** Оценка числа fn evals Nelder-Mead на итерацию для бюджета DE/CHIO. */
        const val NM_EVALS_PER_ITER_ESTIMATE = 2.5
    }
}
