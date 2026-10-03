package me.timschneeberger.rootlessjamesdsp.api

import me.timschneeberger.rootlessjamesdsp.model.squig.FrequencyResponse
import timber.log.Timber

/**
 * Парсер TSV-файлов АЧХ SquigLink (формат REW).
 *
 * Формат файла:
 *   * комментарий (измерение, сглаживание, и т.д.)
 *   * Smoothing: 1/24 octave
 *   Freq(Hz)\tSPL(dB)
 *   20.000000\t-1.030
 *   20.299999\t-1.016
 *   ...
 *   20000.000000\t-12.5
 *
 * - Строки, начинающиеся с '*', — комментарии, пропускаются
 * - Заголовок "Freq(Hz)\tSPL(dB)" пропускается
 * - Строки данных: частота\tSPL (tab-separated)
 * - Частоты: 20 Hz – 20 kHz, шаг ~1/48 октавы
 */
object SquigLinkParser {

    /**
     * Парсинг FR файла SquigLink (TSV формат REW).
     *
     * Алгоритм:
     * 1. Разбить текст на строки
     * 2. Пропустить пустые строки и комментарии (начинаются с '*')
     * 3. Пропустить заголовок (начинается с "Freq")
     * 4. Распарсить tab-separated пары частота\tSPL
     * 5. Вернуть FrequencyResponse с каналом AVERAGE (по умолчанию)
     *
     * @param tsv raw TSV текст из файла SquigLink
     * @return FrequencyResponse с массивами частот и SPL
     */
    fun parseFrequencyResponse(tsv: String): FrequencyResponse {
        val freqs = mutableListOf<Double>()
        val spls = mutableListOf<Double>()

        tsv.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            // Пропуск комментариев (строки начинающиеся с '*')
            if (trimmed.startsWith("*")) return@forEach
            // Пропуск заголовка (начинается с "Freq")
            if (trimmed.startsWith("Freq", ignoreCase = true)) return@forEach

            // Разделение по табуляции
            val parts = trimmed.split("\t")
            if (parts.size >= 2) {
                val freq = parts[0].trim().toDoubleOrNull()
                val spl = parts[1].trim().toDoubleOrNull()
                if (freq != null && spl != null) {
                    freqs.add(freq)
                    spls.add(spl)
                }
            } else {
                // Попытка разделения по пробелам (некоторые файлы используют пробелы)
                val spaceParts = trimmed.split(Regex("\\s+"))
                if (spaceParts.size >= 2) {
                    val freq = spaceParts[0].trim().toDoubleOrNull()
                    val spl = spaceParts[1].trim().toDoubleOrNull()
                    if (freq != null && spl != null) {
                        freqs.add(freq)
                        spls.add(spl)
                    }
                }
            }
        }

        Timber.d("parseFrequencyResponse: распарсено ${freqs.size} точек")
        return FrequencyResponse(
            frequencies = freqs.toDoubleArray(),
            spl = spls.toDoubleArray(),
            channel = FrequencyResponse.Channel.AVERAGE
        )
    }
}
