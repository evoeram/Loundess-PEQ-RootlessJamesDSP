package me.timschneeberger.rootlessjamesdsp.model.squig

import com.google.gson.annotations.SerializedName
import java.io.Serializable

/**
 * Модель элемента из squigsites.json — каталог всех squig.link инстансов.
 *
 * squigsites.json содержит 124+ инстансов с разными urlType:
 * - "root"      → squig.link (главный сайт)
 * - "subdomain" → username.squig.link
 * - "labFolder" → squig.link/username/ (но работает как subdomain!)
 * - "altDomain" → кастомный домен (altDomain поле), часто недоступен
 *
 * Каждый инстанс имеет 1+ БД (dbs) с типом (IEMs, Headphones, Earbuds, 5128)
 * и папкой (folder). URL для phone_book.json:
 *   {baseUrl}{folder}data/phone_book.json
 *
 * @param username имя пользователя (subdomain)
 * @param name отображаемое имя
 * @param urlType тип URL (root, subdomain, labFolder, altDomain)
 * @param altDomain кастомный домен (для urlType=altDomain)
 * @param dbs список баз данных (IEMs, Headphones, Earbuds)
 */
data class SquigSite(
    @SerializedName("username") val username: String,
    @SerializedName("name") val name: String,
    @SerializedName("urlType") val urlType: String,
    @SerializedName("altDomain") val altDomain: String? = null,
    @SerializedName("dbs") val dbs: List<SquigDb>? = null
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * База данных инстанса (например IEMs, Headphones).
 *
 * @param тип тип БД (IEMs, Headphones, Earbuds, 5128)
 * @param folder папка данных (например "/", "/headphones/")
 * @param deltaReady флаг готовности к AutoEQ
 */
data class SquigDb(
    @SerializedName("type") val type: String,
    @SerializedName("folder") val folder: String,
    @SerializedName("deltaReady") val deltaReady: Boolean? = null
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}
