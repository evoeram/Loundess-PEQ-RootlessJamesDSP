package me.timschneeberger.rootlessjamesdsp.utils

import me.timschneeberger.rootlessjamesdsp.model.ParametricEqBand
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqChannel
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqFilterType
import kotlin.math.*

/**
 * Конвертер целевой АЧХ графического эквалайзера (GraphEQ) в минимальный
 * каскад параметрических IIR-фильтров (PEQ / Biquads).
 *
 * Алгоритм:
 *  1. Интерполяция узлов GraphEQ в логарифмическом масштабе (20 Гц – 20 кГц).
 *  2. Жадный итеративный поиск: на каждой итерации добавляется Peaking-фильтр
 *     в точке максимальной остаточной ошибки, затем запускается совместная
 *     оптимизация всех параметров (Fc, Q, Gain).
 *  3. Оптимизатор — Nelder-Mead Simplex (чистый Kotlin, без внешних библиотек).
 *  4. Штраф за высокую добротность Q для предотвращения звона (ringing).
 *  5. Biquad-коэффициенты рассчитываются по RBJ Audio EQ Cookbook (совпадает
 *     с C-кодом в peq_loudness.c).
 *
 * @see peq_loudness.c — biquad_init() для справки по формулам
 */
object GraphEqToPeqConverter {

    // ============================= Параметры =============================

    /** Допуск по максимальной ошибке (дБ). Если max|error| < этого — стоп. */
    private const val TARGET_TOLERANCE_DB = 0.5

    /** Допуск по RMS-ошибке (дБ). Альтернативный критерий останова. */
    private const val RMS_TOLERANCE_DB = 0.3

    /** Максимум фильтров в каскаде (по умолчанию). */
    private const val DEFAULT_MAX_FILTERS = 16

    /** Количество точек интерполяции целевой АЧХ. */
    private const val NUM_POINTS = 512

    /** Минимальная/максимальная частота диапазона (Гц). */
    private const val F_MIN = 20.0
    private const val F_MAX = 20000.0

    /** Частота дискретизации для расчёта biquad (не влияет на результат, т.к.
     *  частотный отклик нормирован). */
    private const val SAMPLE_RATE = 48000.0

    /** Границы параметров для оптимизатора. */
    private val FC_MIN = F_MIN
    private val FC_MAX = SAMPLE_RATE / 2.0 * 0.98
    private const val Q_MIN = 0.2
    private const val Q_MAX = 10.0
    private const val GAIN_MIN = -24.0
    private const val GAIN_MAX = 24.0

    /** Вес штрафа за высокую добротность. */
    private const val Q_PENALTY_WEIGHT = 0.5

    // ============================= Результат =============================

    /** Результат конвертации. */
    data class Result(
        /** Список параметрических полос (без Preamp). */
        val bands: List<ParametricEqBand>,
        /** Preamp-усиление (дБ), компенсирующее пик целевой АЧХ. */
        val preampDb: Double,
        /** Максимальное абсолютное отклонение (дБ). */
        val maxErrorDb: Double,
        /** RMS-ошибка (дБ). */
        val rmsErrorDb: Double,
        /** Строка пресета в формате Equalizer APO. */
        val apoString: String,
    )

    // ============================= Biquad (RBJ Cookbook) =============================

    /**
     * Biquad-коэффициенты (нормализованные по a0).
     * Совпадает с C-кодом biquad_init() в peq_loudness.c.
     */
    private data class BiquadCoeffs(
        val b0: Double, val b1: Double, val b2: Double,
        val a1: Double, val a2: Double, // a0 = 1
    )

    /**
     * Расчёт biquad-коэффициентов по RBJ Audio EQ Cookbook.
     *
     * @param type   тип фильтра (PEAKING, LOW_SHELF, HIGH_SHELF)
     * @param dbGain усиление (дБ)
     * @param freq   центральная частота (Гц)
     * @param q      добротность
     * @return нормализованные коэффициенты
     */
    private fun computeBiquad(
        type: ParametricEqFilterType,
        dbGain: Double,
        freq: Double,
        q: Double,
    ): BiquadCoeffs {
        // Кламп частоты
        val nyquist = SAMPLE_RATE * 0.5
        val f = freq.coerceIn(1.0, nyquist * 0.98)
        val qClamped = q.coerceIn(0.1, 24.0)

        // Для Peaking/Shelf: A = 10^(gain/40); для остальных: 10^(gain/20)
        val A = if (type == ParametricEqFilterType.PEAKING ||
            type == ParametricEqFilterType.LOW_SHELF ||
            type == ParametricEqFilterType.HIGH_SHELF
        ) {
            10.0.pow(dbGain / 40.0)
        } else {
            10.0.pow(dbGain / 20.0)
        }

        val omega = 2.0 * PI * f / SAMPLE_RATE
        val sn = sin(omega)
        val cs = cos(omega)
        val alpha = sn / (2.0 * qClamped)
        val beta = 2.0 * sqrt(A) * alpha

        // Расчёт по RBJ Cookbook
        val (b0, b1, b2, a0, a1, a2) = when (type) {
            ParametricEqFilterType.PEAKING -> {
                val b0 = 1.0 + alpha * A
                val b1 = -2.0 * cs
                val b2 = 1.0 - alpha * A
                val a0 = 1.0 + alpha / A
                val a1 = -2.0 * cs
                val a2 = 1.0 - alpha / A
                SixVals(b0, b1, b2, a0, a1, a2)
            }
            ParametricEqFilterType.LOW_SHELF -> {
                val b0 = A * ((A + 1.0) - (A - 1.0) * cs + beta)
                val b1 = 2.0 * A * ((A - 1.0) - (A + 1.0) * cs)
                val b2 = A * ((A + 1.0) - (A - 1.0) * cs - beta)
                val a0 = (A + 1.0) + (A - 1.0) * cs + beta
                val a1 = -2.0 * ((A - 1.0) + (A + 1.0) * cs)
                val a2 = (A + 1.0) + (A - 1.0) * cs - beta
                SixVals(b0, b1, b2, a0, a1, a2)
            }
            ParametricEqFilterType.HIGH_SHELF -> {
                val b0 = A * ((A + 1.0) + (A - 1.0) * cs + beta)
                val b1 = -2.0 * A * ((A - 1.0) + (A + 1.0) * cs)
                val b2 = A * ((A + 1.0) + (A - 1.0) * cs - beta)
                val a0 = (A + 1.0) - (A - 1.0) * cs + beta
                val a1 = 2.0 * ((A - 1.0) - (A + 1.0) * cs)
                val a2 = (A + 1.0) - (A - 1.0) * cs - beta
                SixVals(b0, b1, b2, a0, a1, a2)
            }
            else -> SixVals(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        }

        // Нормализация по a0
        return BiquadCoeffs(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    /** Вспомогательная структура для возврата 6 значений. */
    private data class SixVals(
        val b0: Double, val b1: Double, val b2: Double,
        val a0: Double, val a1: Double, val a2: Double,
    )

    /**
     * Частотный отклик одного biquad на заданной частоте (дБ).
     *
     * H(z) = (b0 + b1*z^-1 + b2*z^-2) / (1 + a1*z^-1 + a2*z^-2)
     * где z = e^(j*omega), omega = 2*pi*f/Fs
     *
     * |H(e^jw)| = |b0 + b1*e^-jw + b2*e^-2jw| / |1 + a1*e^-jw + a2*e^-2jw|
     *
     * @return усиление в дБ = 20*log10(|H|)
     */
    private fun biquadResponseDb(coeffs: BiquadCoeffs, freq: Double): Double {
        val omega = 2.0 * PI * freq / SAMPLE_RATE
        val cosw = cos(omega)
        val sinw = sin(omega)
        // e^-jw = cosw - j*sinw
        // e^-2jw = cos(2w) - j*sin(2w)
        val cos2w = cos(2.0 * omega)
        val sin2w = sin(2.0 * omega)

        // Числитель: b0 + b1*e^-jw + b2*e^-2jw
        val numRe = coeffs.b0 + coeffs.b1 * cosw + coeffs.b2 * cos2w
        val numIm = -coeffs.b1 * sinw - coeffs.b2 * sin2w

        // Знаменатель: 1 + a1*e^-jw + a2*e^-2jw
        val denRe = 1.0 + coeffs.a1 * cosw + coeffs.a2 * cos2w
        val denIm = -coeffs.a1 * sinw - coeffs.a2 * sin2w

        val numMag = sqrt(numRe * numRe + numIm * numIm)
        val denMag = sqrt(denRe * denRe + denIm * denIm)

        return 20.0 * log10(numMag / denMag)
    }

    // ============================= Интерполяция GraphEQ =============================

    /**
     * Интерполяция узлов GraphEQ в логарифмическом масштабе частот.
     *
     * @param nodes список пар (частота Гц, усиление дБ), отсортированный по частоте
     * @return массив частот (Гц) и массив значений (дБ) — NUM_POINTS точек
     */
    private fun interpolateTarget(nodes: List<Pair<Double, Double>>): Pair<DoubleArray, DoubleArray> {
        if (nodes.isEmpty()) {
            return DoubleArray(NUM_POINTS) { F_MIN } to DoubleArray(NUM_POINTS) { 0.0 }
        }

        val freqs = DoubleArray(NUM_POINTS)
        val gains = DoubleArray(NUM_POINTS)

        // Логарифмическая сетка частот
        val logFMin = ln(F_MIN)
        val logFMax = ln(F_MAX)
        for (i in 0 until NUM_POINTS) {
            freqs[i] = exp(logFMin + (logFMax - logFMin) * i / (NUM_POINTS - 1))
        }

        // Линейная интерполяция в лог-масштабе частот
        val nodeLogFreqs = nodes.map { ln(it.first) }
        val nodeGains = nodes.map { it.second }

        for (i in 0 until NUM_POINTS) {
            val logF = ln(freqs[i])
            gains[i] = interpLinear(nodeLogFreqs, nodeGains, logF)
        }

        return freqs to gains
    }

    /** Линейная интерполяция между точками. Экстраполяция — константой. */
    private fun interpLinear(xs: List<Double>, ys: List<Double>, x: Double): Double {
        if (xs.isEmpty()) return 0.0
        if (xs.size == 1) return ys[0]
        if (x <= xs[0]) return ys[0]
        if (x >= xs.last()) return ys.last()

        // Бинарный поиск
        var lo = 0
        var hi = xs.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (xs[mid] <= x) lo = mid else hi = mid
        }

        val t = (x - xs[lo]) / (xs[hi] - xs[lo])
        return ys[lo] + t * (ys[hi] - ys[lo])
    }

    // ============================= Каскад PEQ =============================

    /**
     * Параметры одного фильтра для оптимизатора.
     * Хранится в лог-масштабе частоты для лучшей сходимости.
     */
    private data class FilterParams(
        var logFc: Double,  // ln(Fc)
        var q: Double,
        var gain: Double,
        var type: ParametricEqFilterType = ParametricEqFilterType.PEAKING,
    ) {
        val fc: Double get() = exp(logFc).coerceIn(FC_MIN, FC_MAX)
    }

    /**
     * Суммарный отклик каскада PEQ на заданной частоте (дБ).
     * Все фильтры суммируются по амплитуде (в дБ — складываются).
     */
    private fun cascadeResponseDb(filters: List<FilterParams>, freq: Double): Double {
        var total = 0.0
        for (f in filters) {
            val coeffs = computeBiquad(f.type, f.gain, f.fc, f.q)
            total += biquadResponseDb(coeffs, freq)
        }
        return total
    }

    /**
     * Вычисление остаточной ошибки: Target(f) - CascadeResponse(f).
     * @return массив ошибок по всем точкам
     */
    private fun computeResiduals(
        filters: List<FilterParams>,
        freqs: DoubleArray,
        target: DoubleArray,
    ): DoubleArray {
        return DoubleArray(freqs.size) { i ->
            target[i] - cascadeResponseDb(filters, freqs[i])
        }
    }

    // ============================= Оптимизатор Nelder-Mead =============================

    /**
     * Nelder-Mead Simplex оптимизация (чистый Kotlin, без внешних библиотек).
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
        maxIter: Int = 500,
    ): DoubleArray {
        val n = initial.size
        // Коэффициенты Nelder-Mead
        val alpha = 1.0   // reflection
        val gamma = 2.0   // expansion
        val rho = 0.5     // contraction
        val sigma = 0.5   // shrink

        // Построение симплекса: начальная точка + n вершин
        val simplex = ArrayList<DoubleArray>(n + 1)
        simplex.add(initial.copyOf())

        for (i in 0 until n) {
            val v = initial.copyOf()
            val range = bounds[i].second - bounds[i].first
            v[i] = (initial[i] + range * 0.05).coerceIn(bounds[i].first, bounds[i].second)
            simplex.add(v)
        }

        // Значения функции в вершинах симплекса
        val fvals = DoubleArray(n + 1) { i -> fn(simplex[i]) }

        for (iter in 0 until maxIter) {
            // Сортировка по значению функции
            val order = (0..n).sortedBy { fvals[it] }
            val bestIdx = order[0]
            val worstIdx = order[n]
            val secondWorstIdx = order[n - 1]

            // Критерий останова: разброс значений мал
            val fRange = fvals[order[n]] - fvals[order[0]]
            if (fRange < 1e-10) break

            // Центроид всех вершин кроме худшей
            val centroid = DoubleArray(n)
            for (i in 0..n) {
                if (i == worstIdx) continue
                for (j in 0 until n) centroid[j] += simplex[i][j]
            }
            for (j in 0 until n) centroid[j] /= n

            // Отражение (reflection)
            val reflected = DoubleArray(n) { j ->
                (centroid[j] + alpha * (centroid[j] - simplex[worstIdx][j]))
                    .coerceIn(bounds[j].first, bounds[j].second)
            }
            val fReflected = fn(reflected)

            if (fReflected < fvals[bestIdx]) {
                // Попытка расширения (expansion)
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
                // Сжатие (contraction)
                val contracted = DoubleArray(n) { j ->
                    (centroid[j] + rho * (simplex[worstIdx][j] - centroid[j]))
                        .coerceIn(bounds[j].first, bounds[j].second)
                }
                val fContracted = fn(contracted)
                if (fContracted < fvals[worstIdx]) {
                    simplex[worstIdx] = contracted
                    fvals[worstIdx] = fContracted
                } else {
                    // Сжатие всего симплекса к лучшей точке (shrink)
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

        // Возвращаем лучшую вершину
        val bestIdx = (0..n).minByOrNull { fvals[it] } ?: 0
        return simplex[bestIdx]
    }

    // ============================= Целевая функция =============================

    /**
     * Целевая функция для совместной оптимизации:
     * Loss = MSE(Target, PEQ) + Penalty(Q)
     *
     * Penalty штрафует за Q > 4 (экспоненциально), чтобы предотвратить звон.
     */
    private fun lossFunction(
        filters: List<FilterParams>,
        freqs: DoubleArray,
        target: DoubleArray,
    ): Double {
        var mse = 0.0
        var penalty = 0.0

        for (i in freqs.indices) {
            val response = cascadeResponseDb(filters, freqs[i])
            val diff = target[i] - response
            mse += diff * diff
        }
        mse /= freqs.size

        // Штраф за высокую добротность
        for (f in filters) {
            if (f.q > 4.0) {
                // Экспоненциальный штраф за Q > 4
                penalty += Q_PENALTY_WEIGHT * (f.q - 4.0).pow(2)
            }
        }

        return mse + penalty
    }

    // ============================= Главный алгоритм =============================

    /**
     * Конвертация узлов GraphEQ в минимальный каскад PEQ.
     *
     * @param geqNodes     список пар (частота Гц, усиление дБ)
     * @param targetFilters желаемое количество фильтров (алгоритм использует ровно N)
     * @return результат конвертации
     */
    fun convert(
        geqNodes: List<Pair<Double, Double>>,
        targetFilters: Int = DEFAULT_MAX_FILTERS,
    ): Result {
        // 1. Интерполяция целевой АЧХ
        val (freqs, target) = interpolateTarget(geqNodes)

        // Preamp: компенсация пика (чтобы не было клиппинга)
        val maxTarget = target.maxOrNull() ?: 0.0
        val preampDb = if (maxTarget > 0.0) -maxTarget else 0.0

        // Сдвиг целевой кривой на preamp (т.к. preamp понижает весь сигнал)
        val targetShifted = DoubleArray(target.size) { target[it] + preampDb }

        val filters = mutableListOf<FilterParams>()

        // 2. Жадный итеративный поиск — используем ровно targetFilters фильтров
        for (iteration in 0 until targetFilters) {
            // Вычисление остаточной ошибки
            val residuals = computeResiduals(filters, freqs, targetShifted)

            // Поиск области с максимальной абсолютной ошибкой
            var maxErrIdx = 0
            var maxAbsErr = abs(residuals[0])
            for (i in residuals.indices) {
                if (abs(residuals[i]) > maxAbsErr) {
                    maxAbsErr = abs(residuals[i])
                    maxErrIdx = i
                }
            }

            // Оценка ширины пика/провала ошибки для инициализации Q
            val errorAtPeak = residuals[maxErrIdx]
            val halfLevel = errorAtPeak / 2.0
            var loIdx = maxErrIdx
            var hiIdx = maxErrIdx
            // Идём влево, пока ошибка выше половины пика
            while (loIdx > 0 && abs(residuals[loIdx]) > abs(halfLevel)) loIdx--
            // Идём вправо
            while (hiIdx < residuals.size - 1 && abs(residuals[hiIdx]) > abs(halfLevel)) hiIdx++

            val bandwidthHz = freqs[hiIdx] - freqs[loIdx]
            val centerFreq = freqs[maxErrIdx]
            // Q = center / bandwidth (примерная оценка)
            val initQ = (centerFreq / bandwidthHz.coerceAtLeast(1.0)).coerceIn(Q_MIN, Q_MAX)
            val initGain = errorAtPeak.coerceIn(GAIN_MIN, GAIN_MAX)

            // Добавляем новый Peaking-фильтр
            val newFilter = FilterParams(
                logFc = ln(centerFreq.coerceIn(FC_MIN, FC_MAX)),
                q = initQ,
                gain = initGain,
                type = ParametricEqFilterType.PEAKING,
            )
            filters.add(newFilter)

            // 3. Совместная оптимизация всех фильтров
            jointlyOptimize(filters, freqs, targetShifted)
        }

        // Финальная оценка ошибки
        val finalResiduals = computeResiduals(filters, freqs, targetShifted)
        val maxError = finalResiduals.maxOf { abs(it) }
        val rmsError = sqrt(finalResiduals.map { it * it }.average())

        // 4. Сортировка фильтров по частоте (от низких к высоким)
        filters.sortBy { it.fc }

        // 5. Формирование выходных данных
        val bands = filters.map { f ->
            ParametricEqBand(
                frequency = f.fc,
                gain = f.gain,
                q = f.q,
                filterType = f.type,
                channel = ParametricEqChannel.LEFT_RIGHT,
            )
        }

        val apoString = buildApoString(bands, preampDb)

        return Result(bands, preampDb, maxError, rmsError, apoString)
    }

    /**
     * Совместная оптимизация параметров всех фильтров (Fc, Q, Gain).
     * Каждый фильтр — 3 параметра: logFc, Q, Gain.
     */
    private fun jointlyOptimize(
        filters: MutableList<FilterParams>,
        freqs: DoubleArray,
        target: DoubleArray,
    ) {
        val nFilters = filters.size
        if (nFilters == 0) return

        // Сборка начального вектора параметров
        val initial = DoubleArray(nFilters * 3)
        val bounds = ArrayList<Pair<Double, Double>>(nFilters * 3)

        for (i in 0 until nFilters) {
            initial[i * 3] = filters[i].logFc
            initial[i * 3 + 1] = filters[i].q
            initial[i * 3 + 2] = filters[i].gain
            bounds.add(ln(FC_MIN) to ln(FC_MAX))      // logFc
            bounds.add(Q_MIN to Q_MAX)                  // Q
            bounds.add(GAIN_MIN to GAIN_MAX)            // gain
        }

        // Целевая функция: распаковывает вектор в фильтры и считает loss
        val fn: (DoubleArray) -> Double = { params ->
            val tempFilters = (0 until nFilters).map { i ->
                FilterParams(
                    logFc = params[i * 3],
                    q = params[i * 3 + 1],
                    gain = params[i * 3 + 2],
                    type = filters[i].type,
                )
            }
            lossFunction(tempFilters, freqs, target)
        }

        // Запуск Nelder-Mead
        val optimized = nelderMead(initial, bounds, fn, maxIter = 300)

        // Запись результатов обратно
        for (i in 0 until nFilters) {
            filters[i].logFc = optimized[i * 3]
            filters[i].q = optimized[i * 3 + 1]
            filters[i].gain = optimized[i * 3 + 2]
        }
    }

    // ============================= Форматирование вывода =============================

    /**
     * Формирование строки пресета в формате Equalizer APO.
     * Формат совпадает с ParametricEqBandList.toApoString():
     *   Preamp: 0 dB
     *   Channel: all
     *   Filter 1: ON PK Fc 1000 Hz Gain 0 dB Q 1.41
     */
    private fun buildApoString(bands: List<ParametricEqBand>, preampDb: Double): String {
        val sb = StringBuilder()
        sb.append("Preamp: ")
        sb.append(String.format(java.util.Locale.US, "%.1f", preampDb))
        sb.append(" dB\n")

        // Все конвертированные фильтры — channel: all (L+R)
        sb.append("Channel: all\n")

        for ((i, band) in bands.withIndex()) {
            val typeLabel = when (band.filterType) {
                ParametricEqFilterType.PEAKING -> "PK"
                ParametricEqFilterType.LOW_SHELF -> "LSC"
                ParametricEqFilterType.HIGH_SHELF -> "HSC"
                else -> "PK"
            }
            sb.append("Filter ${i + 1}: ON ")
            sb.append(typeLabel)
            sb.append(" Fc ")
            sb.append(String.format(java.util.Locale.US, "%.1f", band.frequency))
            sb.append(" Hz Gain ")
            sb.append(String.format(java.util.Locale.US, "%.1f", band.gain))
            sb.append(" dB Q ")
            sb.append(String.format(java.util.Locale.US, "%.2f", band.q))
            sb.append('\n')
        }

        return sb.toString().trimEnd('\n')
    }
}
