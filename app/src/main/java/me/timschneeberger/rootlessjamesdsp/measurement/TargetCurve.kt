package me.timschneeberger.rootlessjamesdsp.measurement

import kotlin.math.*

/**
 * Модель целевой кривой (Target / House Curve) для Auto-EQ.
 *
 * Целевая кривая задаётся набором контрольных точек (freq, gain).
 * Между точками — линейная интерполяция в log-freq пространстве.
 *
 * Стандартные пресеты:
 *  - Flat: 0 dB на всех частотах
 *  - Harman: +1 dB bass shelf до 100 Гц, спад -1 dB/oct выше 1 кГц
 *  - Custom: пользовательские точки
 *
 * @param points список контрольных точек, отсортированных по частоте
 */
data class TargetCurve(
    val name: String,
    val points: List<Point>
) {
    /** Контрольная точка целевой кривой. */
    data class Point(val frequency: Double, val gainDb: Double)

    companion object {
        /** Flat target: 0 dB везде. */
        fun flat(): TargetCurve = TargetCurve("Flat", listOf(
            Point(20.0, 0.0),
            Point(20000.0, 0.0)
        ))

        /** Harman-like target: лёгкий басовый подъём + плавный ВЧ спад. */
        fun harman(): TargetCurve = TargetCurve("Harman", listOf(
            Point(20.0, 1.0),
            Point(100.0, 1.0),
            Point(1000.0, 0.0),
            Point(10000.0, -1.0),
            Point(20000.0, -2.0)
        ))

        /**
         * Создать целевую кривую из массива частот и усилений.
         * Точки должны быть отсортированы по частоте.
         */
        fun fromArrays(freqs: DoubleArray, gains: DoubleArray, name: String = "Custom"): TargetCurve {
            val points = freqs.zip(gains.toTypedArray()).map { (f, g) -> Point(f, g) }
            return TargetCurve(name, points)
        }
    }

    /**
     * Вычислить gain целевой кривой на заданной частоте.
     * Линейная интерполяция в log-freq пространстве между контрольными точками.
     * За пределами диапазона — экстраполяция по последнему отрезку.
     */
    fun gainAt(freq: Double): Double {
        if (points.isEmpty()) return 0.0
        if (points.size == 1) return points[0].gainDb

        // Ниже первой точки — значение первой точки
        if (freq <= points.first().frequency) return points.first().gainDb
        // Выше последней точки — значение последней точки
        if (freq >= points.last().frequency) return points.last().gainDb

        // Поиск интервала
        for (i in 0 until points.size - 1) {
            val p0 = points[i]
            val p1 = points[i + 1]
            if (freq >= p0.frequency && freq <= p1.frequency) {
                // Линейная интерполяция в log-freq пространстве
                val logF0 = log10(p0.frequency)
                val logF1 = log10(p1.frequency)
                val logF = log10(freq)
                val t = (logF - logF0) / (logF1 - logF0)
                return p0.gainDb + t * (p1.gainDb - p0.gainDb)
            }
        }

        return points.last().gainDb
    }

    /**
     * Вычислить целевую кривую на массиве частот.
     * @return DoubleArray усилений в дБ
     */
    fun gainAtAll(freqs: DoubleArray): DoubleArray = DoubleArray(freqs.size) { i -> gainAt(freqs[i]) }
}
