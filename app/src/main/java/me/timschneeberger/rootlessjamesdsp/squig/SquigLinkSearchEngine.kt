package me.timschneeberger.rootlessjamesdsp.squig

import me.timschneeberger.rootlessjamesdsp.model.squig.SquigLinkBrand
import me.timschneeberger.rootlessjamesdsp.model.squig.SquigLinkPhone

/**
 * Fuzzy-поиск по phone_book SquigLink.
 *
 * Поиск по: brand name, phone name, комбинированный "brand model".
 * Использует case-insensitive substring matching с scoring.
 */
class SquigLinkSearchEngine {

    /** Результат поиска с оценкой релевантности. */
    data class SearchResult(
        val brand: String,
        val phone: SquigLinkPhone,
        val score: Double
    )

    /**
     * Поиск по базе phone_book.
     * @param query поисковый запрос
     * @param database список брендов из phone_book.json
     * @return отсортированный по релевантности список результатов
     */
    fun search(query: String, database: List<SquigLinkBrand>): List<SearchResult> {
        if (query.isBlank()) return emptyList()

        val q = query.trim().lowercase()
        val results = mutableListOf<SearchResult>()

        for (brand in database) {
            val brandName = brand.name.lowercase()
            for (phone in brand.phones) {
                val phoneName = phone.name.lowercase()
                val fullName = "$brandName $phoneName"

                // Вычисление оценки релевантности
                var score = 0.0

                // Точное совпадение имени — высший приоритет
                if (phoneName == q) {
                    score = 100.0
                }
                // Точное совпадение полного имени
                else if (fullName == q) {
                    score = 95.0
                }
                // Подстрока в имени телефона
                else if (phoneName.contains(q)) {
                    score = 80.0 - (phoneName.length - q.length) * 0.1
                }
                // Подстрока в полном имени
                else if (fullName.contains(q)) {
                    score = 70.0 - (fullName.length - q.length) * 0.1
                }
                // Подстрока в имени бренда
                else if (brandName.contains(q)) {
                    score = 50.0
                }
                // Все слова запроса найдены в полном имени
                else {
                    val queryWords = q.split(" ").filter { it.isNotBlank() }
                    val allWordsMatch = queryWords.all { word -> fullName.contains(word) }
                    if (allWordsMatch && queryWords.size > 1) {
                        score = 60.0
                    }
                    // Частичное совпадение слов
                    else {
                        val matchedWords = queryWords.count { word -> fullName.contains(word) }
                        if (matchedWords > 0) {
                            score = 30.0 * matchedWords / queryWords.size
                        }
                    }
                }

                if (score > 0) {
                    results.add(SearchResult(brand.name, phone, score.coerceAtLeast(0.0)))
                }
            }
        }

        return results.sortedByDescending { it.score }
    }
}
