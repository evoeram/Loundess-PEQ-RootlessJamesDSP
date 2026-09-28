package me.timschneeberger.rootlessjamesdsp.model.squig

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import java.io.Serializable
import java.lang.reflect.Type

/**
 * Модель бренда из phone_book.json SquigLink.
 *
 * Формат phone_book.json:
 * [
 *   {
 *     "name": "64 Audio",
 *     "phones": [
 *       {
 *         "name": "Aspire 1",
 *         "file": "64 Audio Aspire 1",          // строка
 *         // или
 *         "file": ["64 Audio Aspire 1",         // массив строк (варианты)
 *                  "64 Audio Aspire 1 shallow",
 *                  "64 Audio Aspire 1 deep"],
 *         "suffix": ["", "(shallow)", "(deep)"],
 *         "reviewScore": "2",
 *         "reviewLink": "https://...",
 *         "shopLink": "https://...",
 *         "price": "$699"
 *       }
 *     ]
 *   }
 * ]
 *
 * @param name имя бренда ("64 Audio", "Moondrop")
 * @param phones список моделей данного бренда
 */
data class SquigLinkBrand(
    @SerializedName("name")
    val name: String,

    @SerializedName("phones")
    val phones: List<SquigLinkPhone> = emptyList()
) : Serializable

/**
 * Модель телефона/IEM из phone_book.json.
 *
 * Поле "file" в JSON может быть как строкой, так и массивом строк.
 * При массиве — первый элемент используется как базовое имя, остальные как варианты.
 *
 * @param name отображаемое имя модели ("Aspire 1", "Blessing 3")
 * @param file базовое имя файла для загрузки АЧХ ("64 Audio Aspire 1")
 * @param fileVariants варианты имени файла (из массива file или suffix)
 * @param suffix синоним для fileVariants (некоторые инстансы используют "suffix")
 * @param reviewScore оценка обзора (строка, может быть null)
 * @param reviewLink ссылка на обзор (может быть null)
 * @param shopLink ссылка на магазин (может быть null)
 * @param price цена (строка, может быть null)
 */
data class SquigLinkPhone(
    @SerializedName("name")
    val name: String,

    @SerializedName("file")
    val file: String = "",

    @SerializedName("fileVariants")
    val fileVariants: List<String>? = null,

    @SerializedName("suffix")
    val suffix: List<String>? = null,

    @SerializedName("reviewScore")
    val reviewScore: String? = null,

    @SerializedName("reviewLink")
    val reviewLink: String? = null,

    @SerializedName("shopLink")
    val shopLink: String? = null,

    @SerializedName("price")
    val price: String? = null
) : Serializable {

    /**
     * Возвращает список вариантов файлов для загрузки.
     * Если fileVariants или suffix есть — возвращает их (каждый как полное имя файла).
     * Иначе — один элемент (базовый file).
     */
    fun getVariants(): List<String> {
        // Если fileVariants есть — это уже полные имена файлов (из массива "file")
        if (!fileVariants.isNullOrEmpty()) return fileVariants
        // Если suffix есть — комбинируем с базовым file
        if (!suffix.isNullOrEmpty()) return suffix.map { "$file$it" }
        // Только базовый file
        return listOf(file)
    }
}

/**
 * Кастомный десериализатор для SquigLinkPhone.
 * Обрабатывает поле "file", которое может быть строкой или массивом строк.
 * При массиве: первый элемент → file, весь массив → fileVariants.
 */
class SquigLinkPhoneDeserializer : JsonDeserializer<SquigLinkPhone> {
    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext
    ): SquigLinkPhone {
        val obj = json.asJsonObject

        val name = obj.get("name")?.asString ?: ""
        val reviewScore = obj.get("reviewScore")?.takeIf { !it.isJsonNull }?.asString
        val reviewLink = obj.get("reviewLink")?.takeIf { !it.isJsonNull }?.asString
        val shopLink = obj.get("shopLink")?.takeIf { !it.isJsonNull }?.asString
        val price = obj.get("price")?.takeIf { !it.isJsonNull }?.asString

        // Поле "file" — может быть строкой или массивом
        val fileElement = obj.get("file")
        val file: String
        val fileVariants: List<String>?

        when {
            fileElement == null || fileElement.isJsonNull -> {
                file = ""
                fileVariants = null
            }
            fileElement.isJsonArray -> {
                // Массив строк: первый элемент — базовый file, все элементы — variants
                val arr = fileElement.asJsonArray
                fileVariants = arr.map { it.asString }
                file = fileVariants.firstOrNull() ?: ""
            }
            fileElement.isJsonPrimitive -> {
                file = fileElement.asString
                fileVariants = null
            }
            else -> {
                file = ""
                fileVariants = null
            }
        }

        // Поле "suffix" — массив строк (optional)
        val suffix: List<String>? = obj.get("suffix")?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asString }

        return SquigLinkPhone(
            name = name,
            file = file,
            fileVariants = fileVariants,
            suffix = suffix,
            reviewScore = reviewScore,
            reviewLink = reviewLink,
            shopLink = shopLink,
            price = price
        )
    }
}
