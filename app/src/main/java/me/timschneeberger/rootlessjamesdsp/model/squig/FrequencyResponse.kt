package me.timschneeberger.rootlessjamesdsp.model.squig

import kotlin.math.abs
import kotlin.math.log10

/**
 * Данные амплитудно-частотной характеристики (АЧХ).
 *
 * Содержит массивы частот (Гц) и уровней звукового давления (дБ),
 * а также информацию о канале измерения.
 *
 * @param frequencies массив частот (Гц), обычно 20–20000 Гц
 * @param spl массив уровней SPL (дБ), соответствующих частотам
 * @param channel канал измерения (LEFT, RIGHT, AVERAGE)
 */
data class FrequencyResponse(
    val frequencies: DoubleArray,
    val spl: DoubleArray,
    val channel: Channel = Channel.AVERAGE
) {
    /** Канал измерения АЧХ. */
    enum class Channel { LEFT, RIGHT, AVERAGE }

    /**
     * Нормализация: сдвиг SPL так, чтобы значение на частоте normHz равнялось 0 дБ.
     * Использует линейную интерполяцию для точного нахождения значения на normHz.
     *
     * @param normHz частота нормализации (500 или 1000 Гц)
     * @return новая FrequencyResponse со сдвинутым SPL
     */
    fun normalize(normHz: Double): FrequencyResponse {
        if (frequencies.isEmpty()) return this

        val offset = interpolateSpl(normHz)
        val newSpl = DoubleArray(spl.size) { i -> spl[i] - offset }
        return FrequencyResponse(frequencies, newSpl, channel)
    }

    /**
     * Усреднение двух АЧХ (L + R) / 2.
     * Частоты должны совпадать. Если размеры различаются, берётся минимальный размер.
     *
     * @param other вторая АЧХ (противоположный канал)
     * @return новая FrequencyResponse с усреднённым SPL и каналом AVERAGE
     */
    fun average(other: FrequencyResponse): FrequencyResponse {
        val n = minOf(frequencies.size, other.frequencies.size)
        val newFreqs = DoubleArray(n) { i -> frequencies[i] }
        val newSpl = DoubleArray(n) { i -> (spl[i] + other.spl[i]) / 2.0 }
        return FrequencyResponse(newFreqs, newSpl, Channel.AVERAGE)
    }

    /**
     * Применить preamp: сдвиг всего SPL на фиксированное значение (дБ).
     *
     * @param preampDb усиление предусилителя (дБ, обычно отрицательное)
     * @return новая FrequencyResponse со сдвинутым SPL
     */
    fun applyPreamp(preampDb: Double): FrequencyResponse {
        if (frequencies.isEmpty()) return this
        val newSpl = DoubleArray(spl.size) { i -> spl[i] + preampDb }
        return FrequencyResponse(frequencies, newSpl, channel)
    }

    /**
     * Применить коррекцию PEQ: интерполирует ответ фильтра на частотах измерения
     * и добавляет его к SPL.
     *
     * @param filterResponseDb массив ответа фильтра в дБ
     * @param filterFreqs массив частот ответа фильтра
     * @return новая FrequencyResponse с скорректированным SPL (measurement + filter)
     */
    fun applyCorrection(filterResponseDb: DoubleArray, filterFreqs: DoubleArray): FrequencyResponse {
        if (frequencies.isEmpty() || filterFreqs.isEmpty()) return this

        val newSpl = DoubleArray(spl.size) { i ->
            val filterGain = interpolateArray(filterFreqs, filterResponseDb, frequencies[i])
            spl[i] + filterGain
        }
        return FrequencyResponse(frequencies, newSpl, channel)
    }

    /**
     * Интерполяция значения SPL на произвольной частоте.
     * Линейная интерполяция в log-freq пространстве между ближайшими точками.
     *
     * @param freq целевая частота (Гц)
     * @return интерполированное значение SPL (дБ)
     */
    fun interpolateSpl(freq: Double): Double {
        if (frequencies.isEmpty()) return 0.0
        if (frequencies.size == 1) return spl[0]

        // Ниже первой точки — значение первой точки
        if (freq <= frequencies.first()) return spl.first()
        // Выше последней точки — значение последней точки
        if (freq >= frequencies.last()) return spl.last()

        // Бинарный поиск интервала
        var lo = 0
        var hi = frequencies.size - 1
        while (lo < hi - 1) {
            val mid = (lo + hi) / 2
            if (frequencies[mid] <= freq) lo = mid else hi = mid
        }

        val f0 = frequencies[lo]
        val f1 = frequencies[hi]
        val s0 = spl[lo]
        val s1 = spl[hi]

        // Линейная интерполяция в log-freq пространстве
        val logF0 = log10(f0.coerceAtLeast(1.0))
        val logF1 = log10(f1.coerceAtLeast(1.0))
        val logF = log10(freq.coerceAtLeast(1.0))
        val t = if (logF1 - logF0 > 1e-12) (logF - logF0) / (logF1 - logF0) else 0.0
        return s0 + t * (s1 - s0)
    }

    /**
     * Преобразование в массивы Float для UI (ParametricEqSurface).
     * @return пара (freqs as FloatArray, spl as FloatArray)
     */
    fun toFloatArrays(): Pair<FloatArray, FloatArray> {
        val freqsF = FloatArray(frequencies.size) { i -> frequencies[i].toFloat() }
        val splF = FloatArray(spl.size) { i -> spl[i].toFloat() }
        return Pair(freqsF, splF)
    }

    /**
     * Интерполяция произвольного массива y(x) на заданной точке.
     * Линейная интерполяция в log-x пространстве.
     */
    private fun interpolateArray(x: DoubleArray, y: DoubleArray, target: Double): Double {
        if (x.isEmpty()) return 0.0
        if (x.size == 1) return y[0]

        if (target <= x.first()) return y.first()
        if (target >= x.last()) return y.last()

        var lo = 0
        var hi = x.size - 1
        while (lo < hi - 1) {
            val mid = (lo + hi) / 2
            if (x[mid] <= target) lo = mid else hi = mid
        }

        val x0 = x[lo]
        val x1 = x[hi]
        val y0 = y[lo]
        val y1 = y[hi]

        val logX0 = log10(x0.coerceAtLeast(1.0))
        val logX1 = log10(x1.coerceAtLeast(1.0))
        val logT = log10(target.coerceAtLeast(1.0))
        val t = if (logX1 - logX0 > 1e-12) (logT - logX0) / (logX1 - logX0) else 0.0
        return y0 + t * (y1 - y0)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FrequencyResponse) return false
        return frequencies.contentEquals(other.frequencies) &&
               spl.contentEquals(other.spl) &&
               channel == other.channel
    }

    override fun hashCode(): Int {
        var result = frequencies.contentHashCode()
        result = 31 * result + spl.contentHashCode()
        result = 31 * result + channel.hashCode()
        return result
    }
}
