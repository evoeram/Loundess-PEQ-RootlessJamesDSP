package me.timschneeberger.rootlessjamesdsp.model.squig

import java.io.Serializable

/**
 * Конфигурация инстанса SquigLink.
 *
 * Каждый squig.link инстанс (squig.link, hbb.squig.link и т.д.) имеет:
 * - базовый URL (baseUrl)
 * - директорию данных (dir), обычно "data/"
 * - список каналов (channels): ["L", "R"] или ["R"]
 * - частоту нормализации по умолчанию (defaultNormHz): 500 или 1000 Гц
 * - флаг официального инстанса (isOfficial)
 * - отображаемое имя (displayName) — имя инстанса + тип БД
 *
 * URL конструкция: {baseUrl}{folder}data/phone_book.json
 *                  {baseUrl}{folder}data/{fileName} {channel}.txt
 *
 * @param name отображаемое имя инстанса ("Super* Review", "Hawaii Bad Boy")
 * @param baseUrl базовый URL ("https://squig.link/", "https://hbb.squig.link/")
 * @param dir директория данных ("data/" — добавляется после folder)
 * @param folder папка БД ("/" для IEMs, "/headphones/" для наушников)
 * @param channels список доступных каналов (["L", "R"] или ["R"])
 * @param defaultNormHz частота нормализации по умолчанию (Гц)
 * @param isOfficial флаг официального инстанса
 */
data class SquigLinkInstance(
    val name: String,
    val baseUrl: String,
    val dir: String = "data/",
    val folder: String = "/",
    val channels: List<String>,
    val defaultNormHz: Double,
    val isOfficial: Boolean = false
) : Serializable {

    /**
     * Полный путь к данным: {folder}{dir}
     * Например: "/" + "data/" = "/data/"
     *           "/headphones/" + "data/" = "/headphones/data/"
     *           "/iems/" + "data/" = "/iems/data/"
     */
    val fullDataPath: String
        get() {
            // folder обычно "/" или "/headphones/" → trim('/') убирает все слеши
            // dir обычно "data/" → результат "data/" или "headphones/data/"
            val folderTrimmed = folder.trim('/')
            return if (folderTrimmed.isEmpty()) dir else "$folderTrimmed/$dir"
        }

    companion object {
        private const val serialVersionUID = 1L

        /**
         * Fallback-список инстансов, если squigsites.json недоступен.
         */
        val DEFAULT: List<SquigLinkInstance> = listOf(
            SquigLinkInstance(
                name = "Super* Review (IEMs)",
                baseUrl = "https://squig.link/",
                dir = "data/",
                folder = "/",
                channels = listOf("L", "R"),
                defaultNormHz = 500.0,
                isOfficial = true
            ),
            SquigLinkInstance(
                name = "Hawaii Bad Boy",
                baseUrl = "https://hbb.squig.link/",
                dir = "data/",
                folder = "/",
                channels = listOf("R"),
                defaultNormHz = 1000.0,
                isOfficial = false
            )
        )

        /**
         * Построение списка инстансов из squigsites.json.
         *
         * Для каждого SquigSite создаётся один или несколько SquigLinkInstance
         * (по одному на каждую БД). altDomain инстансы пропускаются
         * (кастомные домены часто недоступны).
         *
         * URL конструкция:
         * - root:      squig.link + folder + data/
         * - subdomain: username.squig.link + folder + data/
         * - labFolder: username.squig.link + folder + data/ (работает как subdomain)
         * - altDomain: пропускается
         *
         * @param sites список из squigsites.json
         * @return список инстансов для spinner
         */
        fun fromSquigSites(sites: List<SquigSite>): List<SquigLinkInstance> {
            val instances = mutableListOf<SquigLinkInstance>()

            for (site in sites) {
                // altDomain — пропускаем (кастомные домены часто недоступны)
                if (site.urlType == "altDomain") continue

                // Базовый URL
                val baseUrl = when (site.urlType) {
                    "root" -> "https://squig.link/"
                    "subdomain", "labFolder" -> "https://${site.username}.squig.link/"
                    else -> continue
                }

                val dbs = site.dbs ?: continue
                for (db in dbs) {
                    // Только IEMs и Headphones поддерживаем (Earbuds/5128 — редко)
                    if (db.type !in listOf("IEMs", "Headphones", "Earbuds")) continue

                    val displayName = buildString {
                        append(site.name)
                        if (dbs.size > 1) append(" (${db.type})")
                    }

                    // Каналы: Super* Review имеет L+R, остальные — только R
                    // Нормализация: 500 Гц для squig.link, 1000 Гц для остальных
                    val isRoot = site.urlType == "root"
                    val channels = if (isRoot) listOf("L", "R") else listOf("R")
                    val normHz = if (isRoot) 500.0 else 1000.0

                    instances.add(SquigLinkInstance(
                        name = displayName,
                        baseUrl = baseUrl,
                        dir = "data/",
                        folder = db.folder,
                        channels = channels,
                        defaultNormHz = normHz,
                        isOfficial = isRoot
                    ))
                }
            }

            return if (instances.isEmpty()) DEFAULT else instances
        }
    }
}
