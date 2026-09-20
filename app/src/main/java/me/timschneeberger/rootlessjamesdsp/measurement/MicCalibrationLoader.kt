package me.timschneeberger.rootlessjamesdsp.measurement

import java.io.File

/**
 * Загрузчик файлов калибровки микрофона.
 *
 * Поддерживаемые форматы:
 *  - UMIK-1 .cal файл (частота, дБ, optionally phase)
 *  - Простые .txt файлы (freq, dB)
 *  - REW calibration file format
 *
 * Формат UMIK-1 .cal:
 *   -- CAL file for UMIK-1
 *   20.0  -50.0
 *   25.0  -49.5
 *   ...
 *   20000.0 -70.0
 *
 * Строки начиная с -- — комментарии.
 * Колонки: частота (Гц), калибровочное смещение (дБ), [опционально фаза (градусы)].
 *
 * После загрузки калибровка применяется как:
 *   SPL_calibrated(f) = SPL_raw(f) - MicCalibration(f)
 */
class MicCalibrationLoader {

    /** Результат загрузки калибровки. */
    data class CalibrationData(
        val frequencies: DoubleArray,
        val gains: DoubleArray,   // дБ смещение (вычитается из raw SPL)
        val sourceFile: String?
    ) {
        companion object {
            /** Тривиальная калибровка: 0 dB на всех частотах. */
            fun identity(): CalibrationData = CalibrationData(
                doubleArrayOf(20.0, 20000.0),
                doubleArrayOf(0.0, 0.0),
                null
            )
        }

        /**
         * Получить калибровочное смещение на заданной частоте.
         * Линейная интерполяция в log-freq пространстве.
         */
        fun gainAt(freq: Double): Double {
            if (frequencies.isEmpty()) return 0.0
            if (freq <= frequencies[0]) return gains[0]
            if (freq >= frequencies.last()) return gains.last()

            var lo = 0
            var hi = frequencies.size - 1
            while (hi - lo > 1) {
                val mid = (lo + hi) / 2
                if (frequencies[mid] <= freq) lo = mid else hi = mid
            }

            val f0 = frequencies[lo]
            val f1 = frequencies[hi]
            val t = (freq - f0) / (f1 - f0)
            return gains[lo] + t * (gains[hi] - gains[lo])
        }

        /**
         * Применить калибровку к массиву SPL.
         * SPL_calibrated[i] = SPL_raw[i] - gainAt(freqs[i])
         */
        fun apply(freqs: FloatArray, spl: FloatArray): FloatArray {
            require(freqs.size == spl.size)
            return FloatArray(spl.size) { i ->
                spl[i] - gainAt(freqs[i].toDouble()).toFloat()
            }
        }

        /** Версия для DoubleArray. */
        fun apply(freqs: DoubleArray, spl: DoubleArray): DoubleArray {
            require(freqs.size == spl.size)
            return DoubleArray(spl.size) { i ->
                spl[i] - gainAt(freqs[i])
            }
        }
    }

    /**
     * Загрузить калибровочный файл.
     *
     * @param file .cal или .txt файл
     * @return CalibrationData или null при ошибке
     */
    fun load(file: File): CalibrationData? {
        if (!file.exists() || !file.isFile) return null

        val freqs = mutableListOf<Double>()
        val gains = mutableListOf<Double>()

        try {
            file.useLines { lines ->
                lines.forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) return@forEach
                    if (trimmed.startsWith("--") || trimmed.startsWith("#") || trimmed.startsWith(";")) return@forEach
                    if (trimmed.startsWith("*")) return@forEach

                    // Разбор колонок
                    val parts = trimmed.split(Regex("\\s+"))
                        .mapNotNull { it.toDoubleOrNull() }

                    if (parts.size >= 2) {
                        freqs.add(parts[0])
                        gains.add(parts[1])
                        // parts[2] — фаза, игнорируется (опционально)
                    }
                }
            }
        } catch (e: Exception) {
            return null
        }

        if (freqs.isEmpty()) return null

        return CalibrationData(
            frequencies = freqs.toDoubleArray(),
            gains = gains.toDoubleArray(),
            sourceFile = file.absolutePath
        )
    }
}
