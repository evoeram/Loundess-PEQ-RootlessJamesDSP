package me.timschneeberger.rootlessjamesdsp.api

import me.timschneeberger.rootlessjamesdsp.model.squig.FrequencyResponse
import me.timschneeberger.rootlessjamesdsp.model.squig.SquigLinkBrand
import me.timschneeberger.rootlessjamesdsp.model.squig.SquigLinkInstance
import me.timschneeberger.rootlessjamesdsp.model.squig.SquigLinkPhone
import me.timschneeberger.rootlessjamesdsp.model.squig.SquigLinkPhoneDeserializer
import me.timschneeberger.rootlessjamesdsp.model.squig.SquigSite
import me.timschneeberger.rootlessjamesdsp.BuildConfig
import okhttp3.OkHttpClient
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import com.google.gson.GsonBuilder
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * HTTP-клиент для SquigLink legacy API.
 *
 * Оборачивает Retrofit с OkHttp, Gson (для phone_book.json) и Scalars (для TSV файлов).
 * Использует таймаут 15 секунд для сетевых запросов.
 *
 * URL конструкция: {baseUrl}/{dir}{fileName}.txt
 * Пробелы в именах файлов URL-кодируются как %20.
 * Retrofit @Path параметры уже кодируются автоматически, но пробелы
 * в @Path заменяются на %20 только при использовании encoded=true.
 * Здесь мы кодируем вручную для надёжности.
 *
 * @param instance конфигурация инстанса SquigLink
 */
class SquigLinkClient(private val instance: SquigLinkInstance) {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor(UserAgentInterceptor("RootlessJamesDSP v${BuildConfig.VERSION_NAME}"))
        .build()

    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(instance.baseUrl)
        .client(httpClient)
        .addConverterFactory(ScalarsConverterFactory.create())
        .addConverterFactory(GsonConverterFactory.create(
            GsonBuilder()
                .registerTypeAdapter(SquigLinkPhone::class.java, SquigLinkPhoneDeserializer())
                .create()
        ))
        .build()

    private val service: SquigLinkService = retrofit.create(SquigLinkService::class.java)

    /**
     * Кэш каталога phone_book (TTL 24 часа).
     */
    private var databaseCache: List<SquigLinkBrand>? = null
    private var databaseCacheTimestamp: Long = 0L

    /**
     * Загрузка каталога phone_book.json с кэшированием (24 часа TTL).
     *
     * @param onResult колбэк с результатом: (список брендов, ошибка)
     *                 при успехе ошибка = null, при ошибке список = null
     */
    fun loadDatabase(onResult: (List<SquigLinkBrand>?, error: String?) -> Unit) {
        // Проверка кэша (24 часа = 86400000 мс)
        val now = System.currentTimeMillis()
        if (databaseCache != null && (now - databaseCacheTimestamp) < CACHE_TTL_MS) {
            Timber.d("loadDatabase: возврат из кэша (${databaseCache!!.size} брендов)")
            onResult(databaseCache, null)
            return
        }

        Timber.d("loadDatabase: загрузка phone_book.json с ${instance.baseUrl}${instance.fullDataPath}")
        val call = service.getPhoneBook(instance.fullDataPath)
        call.enqueue(object : Callback<List<SquigLinkBrand>> {
            override fun onResponse(
                call: Call<List<SquigLinkBrand>>,
                response: Response<List<SquigLinkBrand>>
            ) {
                if (response.code() == 200) {
                    val body = response.body()
                    if (body != null) {
                        databaseCache = body
                        databaseCacheTimestamp = System.currentTimeMillis()
                        Timber.i("loadDatabase: загружено ${body.size} брендов")
                        onResult(body, null)
                    } else {
                        val err = "Пустой ответ сервера"
                        Timber.e("loadDatabase: $err")
                        onResult(null, err)
                    }
                } else {
                    val err = "Ошибка сервера: ${response.code()}"
                    Timber.e("loadDatabase: $err (${response.errorBody()?.string()})")
                    onResult(null, err)
                }
            }

            override fun onFailure(call: Call<List<SquigLinkBrand>>, t: Throwable) {
                val err = "Сетевая ошибка: ${t.localizedMessage}"
                Timber.e(t, "loadDatabase: $err")
                onResult(null, err)
            }
        })
    }

    /**
     * Загрузка файла АЧХ для телефона/IEM и канала.
     *
     * URL: {baseUrl}/{dir}{file} {channel}.txt
     * Например: data/7Hz Zero 2 L.txt
     *
     * @param phone модель телефона/IEM
     * @param channel канал ("L" или "R")
     * @param onResult колбэк с результатом: (FrequencyResponse, ошибка)
     */
    fun loadFrequencyResponse(
        phone: SquigLinkPhone,
        channel: String,
        onResult: (FrequencyResponse?, error: String?) -> Unit
    ) {
        // Конструкция имени файла: "{file} {channel}" (без скобок)
        // SquigLink использует формат: data/{fileName} {channel}.txt
        // например: data/7Hz Zero 2 L.txt
        // Retrofit @Path сам URL-кодирует пробелы, поэтому передаём как есть
        val fileName = "${phone.file} $channel"

        Timber.d("loadFrequencyResponse: загрузка $fileName с ${instance.baseUrl}${instance.fullDataPath}")
        val call = service.getFrequencyResponse(instance.fullDataPath, fileName)
        call.enqueue(object : Callback<String> {
            override fun onResponse(call: Call<String>, response: Response<String>) {
                if (response.code() == 200) {
                    val tsv = response.body()
                    if (tsvValid(tsv)) {
                        val fr = SquigLinkParser.parseFrequencyResponse(tsv!!)
                        val channelEnum = if (channel == "L")
                            FrequencyResponse.Channel.LEFT
                        else
                            FrequencyResponse.Channel.RIGHT
                        val result = FrequencyResponse(fr.frequencies, fr.spl, channelEnum)
                        Timber.i("loadFrequencyResponse: загружено ${result.frequencies.size} точек для $fileName")
                        onResult(result, null)
                    } else {
                        val err = "Пустой или некорректный файл АЧХ"
                        Timber.e("loadFrequencyResponse: $err для $fileName")
                        onResult(null, err)
                    }
                } else {
                    val err = "Ошибка сервера: ${response.code()}"
                    Timber.e("loadFrequencyResponse: $err для $fileName")
                    onResult(null, err)
                }
            }

            override fun onFailure(call: Call<String>, t: Throwable) {
                val err = "Сетевая ошибка: ${t.localizedMessage}"
                Timber.e(t, "loadFrequencyResponse: $err для $fileName")
                onResult(null, err)
            }
        })
    }

    /**
     * Загрузка целевой кривой (target curve) по имени.
     *
     * URL: {baseUrl}/{dir}{targetName}.txt
     *
     * @param targetName имя целевой кривой без расширения (например "Super 22 Target")
     * @param onResult колбэк с результатом: (FrequencyResponse, ошибка)
     */
    fun loadTargetCurve(
        targetName: String,
        onResult: (FrequencyResponse?, error: String?) -> Unit
    ) {
        Timber.d("loadTargetCurve: загрузка $targetName с ${instance.baseUrl}${instance.fullDataPath}")
        val call = service.getFrequencyResponse(instance.fullDataPath, targetName)
        call.enqueue(object : Callback<String> {
            override fun onResponse(call: Call<String>, response: Response<String>) {
                if (response.code() == 200) {
                    val tsv = response.body()
                    if (tsvValid(tsv)) {
                        val fr = SquigLinkParser.parseFrequencyResponse(tsv!!)
                        Timber.i("loadTargetCurve: загружено ${fr.frequencies.size} точек для $targetName")
                        onResult(fr, null)
                    } else {
                        val err = "Пустой или некорректный файл целевой кривой"
                        Timber.e("loadTargetCurve: $err для $targetName")
                        onResult(null, err)
                    }
                } else {
                    val err = "Ошибка сервера: ${response.code()}"
                    Timber.e("loadTargetCurve: $err для $targetName")
                    onResult(null, err)
                }
            }

            override fun onFailure(call: Call<String>, t: Throwable) {
                val err = "Сетевая ошибка: ${t.localizedMessage}"
                Timber.e(t, "loadTargetCurve: $err для $targetName")
                onResult(null, err)
            }
        })
    }

    /**
     * Принудительная очистка кэша phone_book.
     * Используется при pull-to-refresh или смене инстанса.
     */
    fun clearCache() {
        databaseCache = null
        databaseCacheTimestamp = 0L
    }

    /**
     * URL-кодирование имени файла: пробелы → %20.
     * Retrofit @Path с encoded=false кодирует большинство символов,
     * но для надёжности кодируем вручную, оставляя буквенно-цифровые и базовые символы.
     */
    // Метод удалён — Retrofit @Path сам URL-кодирует path-параметры.
    // Оставлено как комментарий для документирования поведения.

    /** Проверка, что TSV ответ не пустой и содержит данные. */
    private fun tsvValid(tsv: String?): Boolean {
        if (tsv.isNullOrBlank()) return false
        // Должен содержать хотя бы одну строку с таб-разделителем
        return tsv.lines().any { it.contains("\t") && !it.startsWith("*") && !it.startsWith("Freq") }
    }

    companion object {
        /** TTL кэша phone_book: 24 часа в миллисекундах. */
        private const val CACHE_TTL_MS = 86_400_000L
    }

    // ── Suspend-обёртки для линейного использования в корутинах ────────────

    /**
     * Загрузка каталога squigsites.json — список всех squig.link инстансов.
     * Грузится всегда с https://squig.link/squigsites.json?squig
     * Возвращает список инстансов или бросает Exception.
     */
    suspend fun loadSquigSitesAsync(): List<SquigSite> = suspendCancellableCoroutine { cont ->
        Timber.d("loadSquigSitesAsync: загрузка squigsites.json")
        // Используем отдельный Retrofit с baseUrl squig.link
        val sitesRetrofit = Retrofit.Builder()
            .baseUrl("https://squig.link/")
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        val sitesService = sitesRetrofit.create(SquigLinkService::class.java)
        sitesService.getSquigSites().enqueue(object : Callback<List<SquigSite>> {
            override fun onResponse(call: Call<List<SquigSite>>, response: Response<List<SquigSite>>) {
                if (response.code() == 200) {
                    val body = response.body()
                    if (body != null) {
                        Timber.i("loadSquigSitesAsync: загружено ${body.size} инстансов")
                        cont.resume(body)
                    } else {
                        cont.resumeWithException(Exception("Пустой ответ сервера"))
                    }
                } else {
                    cont.resumeWithException(Exception("Ошибка сервера: ${response.code()}"))
                }
            }

            override fun onFailure(call: Call<List<SquigSite>>, t: Throwable) {
                Timber.e(t, "loadSquigSitesAsync: сетевая ошибка")
                cont.resumeWithException(t)
            }
        })
    }

    /**
     * Загрузка списка target curves из config.js.
     * Парсит JS-массив `const targets = [...]` и добавляет " Target" к каждому имени.
     * Грузится с https://squig.link/config.js
     * @return список имён target curves (с суффиксом " Target")
     */
    suspend fun loadTargetCurvesAsync(): List<String> = suspendCancellableCoroutine { cont ->
        Timber.d("loadTargetCurvesAsync: загрузка config.js с ${instance.baseUrl}")
        val cfgRetrofit = Retrofit.Builder()
            .baseUrl(instance.baseUrl)
            .client(httpClient)
            .addConverterFactory(ScalarsConverterFactory.create())
            .build()
        val cfgService = cfgRetrofit.create(SquigLinkService::class.java)
        cfgService.getConfigJs().enqueue(object : Callback<String> {
            override fun onResponse(call: Call<String>, response: Response<String>) {
                if (response.code() == 200) {
                    val js = response.body() ?: ""
                    val targets = parseTargetsFromConfigJs(js)
                    Timber.i("loadTargetCurvesAsync: найдено ${targets.size} target curves")
                    cont.resume(targets)
                } else {
                    cont.resumeWithException(Exception("Ошибка сервера: ${response.code()}"))
                }
            }

            override fun onFailure(call: Call<String>, t: Throwable) {
                Timber.e(t, "loadTargetCurvesAsync: сетевая ошибка")
                cont.resumeWithException(t)
            }
        })
    }

    /**
     * Парсинг массива targets из config.js.
     * Извлекает имена файлов из `files: [...]` и добавляет " Target".
     * Комментарии (строки с //) пропускаются.
     */
    private fun parseTargetsFromConfigJs(js: String): List<String> {
        val targets = mutableListOf<String>()
        val targetsBlockRegex = Regex("""const\s+targets\s*=\s*\[(.*?)\];""", RegexOption.DOT_MATCHES_ALL)
        val block = targetsBlockRegex.find(js)?.groupValues?.get(1) ?: return emptyList()

        // Извлекаем массивы files: [...] — но только если строка с files: не закомментирована
        val lines = block.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            // Пропускаем закомментированные строки (с // в начале)
            if (line.trim().startsWith("//")) {
                i++
                continue
            }

            // Ищем files: [ в этой строке
            if (line.contains("files:") && line.contains("[")) {
                // Собираем содержимое массива — может быть многострочным
                val sb = StringBuilder()
                // Часть после [ в текущей строке
                val bracketStart = line.indexOf("[")
                if (bracketStart >= 0) {
                    sb.append(line.substring(bracketStart + 1))
                }
                // Если нет ] в текущей строке — читаем дальше
                if (!line.substring(bracketStart).contains("]")) {
                    i++
                    while (i < lines.size) {
                        val contLine = lines[i]
                        // Проверяем — если строка закомментирована, пропускаем её содержимое
                        if (contLine.trim().startsWith("//")) {
                            i++
                            continue
                        }
                        sb.append("\n").append(contLine)
                        if (contLine.contains("]")) break
                        i++
                    }
                }

                // Парсим строки в кавычках, пропуская закомментированные
                for (contentLine in sb.toString().lines()) {
                    if (contentLine.trim().startsWith("//")) continue
                    val stringRegex = Regex(""""([^"]+)"""")
                    for (strMatch in stringRegex.findAll(contentLine)) {
                        val name = strMatch.groupValues[1].trim()
                        if (name.isNotEmpty()) {
                            targets.add("$name Target")
                        }
                    }
                }
            }
            i++
        }

        return if (targets.isEmpty()) {
            listOf("Super 22 Target", "Harman IE 2019 Target")
        } else {
            targets.distinct()
        }
    }

    /** Suspend-версия loadDatabase: возвращает бренды или бросает Exception. */
    suspend fun loadDatabaseAsync(): List<SquigLinkBrand> = suspendCancellableCoroutine { cont ->
        loadDatabase { brands, error ->
            if (error != null) {
                cont.resumeWithException(Exception(error))
            } else if (brands != null) {
                cont.resume(brands)
            } else {
                cont.resumeWithException(Exception("Пустой ответ сервера"))
            }
        }
    }

    /**
     * Suspend-версия loadDatabase с offline fallback.
     *
     * Сначала пытается загрузить с сервера. При успехе — кэширует в постоянное хранилище.
     * При неудаче — пытается вернуть кэшированные данные (даже если устаревшие).
     *
     * @param instance инстанс для ключа кэша
     * @return список брендов
     * @throws Exception если нет сети и нет кэша
     */
    suspend fun loadDatabaseWithCache(instance: SquigLinkInstance): List<SquigLinkBrand> {
        return try {
            val brands = loadDatabaseAsync()
            SquigLinkCacheManager.savePhoneBook(instance, brands)
            Timber.d("loadDatabaseWithCache: loaded from network, cached ${brands.size} brands")
            brands
        } catch (e: Exception) {
            val cached = SquigLinkCacheManager.loadPhoneBook(instance)
            if (cached != null) {
                Timber.w("loadDatabaseWithCache: network failed, using cached (${cached.size} brands): ${e.message}")
                cached
            } else {
                throw e
            }
        }
    }

    /**
     * Suspend-загрузка raw TSV целевой кривой (для кэширования).
     * Возвращает raw TSV строку или null при ошибке.
     */
    suspend fun loadTargetCurveRawAsync(targetName: String): String? = suspendCancellableCoroutine { cont ->
        Timber.d("loadTargetCurveRawAsync: загрузка $targetName")
        val call = service.getFrequencyResponse(instance.fullDataPath, targetName)
        call.enqueue(object : Callback<String> {
            override fun onResponse(call: Call<String>, response: Response<String>) {
                if (response.code() == 200) {
                    val tsv = response.body()
                    if (tsvValid(tsv)) {
                        cont.resume(tsv)
                    } else {
                        cont.resume(null)
                    }
                } else {
                    cont.resume(null)
                }
            }
            override fun onFailure(call: Call<String>, t: Throwable) {
                cont.resume(null)
            }
        })
    }

    /** Suspend-версия loadFrequencyResponse: возвращает FR или бросает Exception. */
    suspend fun loadFrequencyResponseAsync(
        phone: SquigLinkPhone,
        channel: String
    ): FrequencyResponse = suspendCancellableCoroutine { cont ->
        loadFrequencyResponse(phone, channel) { fr, error ->
            if (error != null) {
                cont.resumeWithException(Exception(error))
            } else if (fr != null) {
                cont.resume(fr)
            } else {
                cont.resumeWithException(Exception("Пустой ответ сервера"))
            }
        }
    }

    /** Suspend-версия loadTargetCurve: возвращает FR или бросает Exception. */
    suspend fun loadTargetCurveAsync(targetName: String): FrequencyResponse =
        suspendCancellableCoroutine { cont ->
            loadTargetCurve(targetName) { fr, error ->
                if (error != null) {
                    cont.resumeWithException(Exception(error))
                } else if (fr != null) {
                    cont.resume(fr)
                } else {
                    cont.resumeWithException(Exception("Пустой ответ сервера"))
                }
            }
        }

    // ── Offline-cache-aware methods ───────────────────────────────────────

    /**
     * Загрузка squigsites.json с offline fallback.
     * Сначала пытается загрузить с сервера. При успехе — кэширует.
     * При неудаче — возвращает кэшированные данные.
     *
     * @return список SquigSite или бросает Exception если нет сети и нет кэша
     */
    suspend fun loadSquigSitesWithCache(): List<SquigSite> {
        return try {
            val sites = loadSquigSitesAsync()
            SquigLinkCacheManager.saveSquigSites(sites)
            Timber.d("loadSquigSitesWithCache: loaded from network, cached ${sites.size} sites")
            sites
        } catch (e: Exception) {
            val cached = SquigLinkCacheManager.loadSquigSites()
            if (cached != null) {
                Timber.w("loadSquigSitesWithCache: network failed, using cached (${cached.size} sites): ${e.message}")
                cached
            } else {
                throw e
            }
        }
    }

    /**
     * Загрузка списка target curves с offline fallback.
     * Сначала пытается загрузить config.js с сервера. При успехе — кэширует.
     * При неудаче — возвращает кэшированные данные.
     *
     * @param instance инстанс для ключа кэша
     * @return список имён target curves или бросает Exception если нет сети и нет кэша
     */
    suspend fun loadTargetCurvesWithCache(instance: SquigLinkInstance): List<String> {
        return try {
            val curves = loadTargetCurvesAsync()
            SquigLinkCacheManager.saveTargetCurveNames(instance, curves)
            Timber.d("loadTargetCurvesWithCache: loaded from network, cached ${curves.size} curves")
            curves
        } catch (e: Exception) {
            val cached = SquigLinkCacheManager.loadTargetCurveNames(instance)
            if (cached != null) {
                Timber.w("loadTargetCurvesWithCache: network failed, using cached (${cached.size} curves): ${e.message}")
                cached
            } else {
                throw e
            }
        }
    }

    /**
     * Загрузка целевой кривой с offline fallback.
     * Сначала пытается загрузить TSV с сервера. При успехе — кэширует.
     * При неудаче — парсит кэшированный TSV.
     *
     * @param targetName имя целевой кривой
     * @param instance инстанс для ключа кэша
     * @return FrequencyResponse или бросает Exception если нет сети и нет кэша
     */
    suspend fun loadTargetCurveWithCache(
        targetName: String,
        instance: SquigLinkInstance
    ): FrequencyResponse {
        // Try network first
        val rawTsv = loadTargetCurveRawAsync(targetName)
        if (rawTsv != null) {
            SquigLinkCacheManager.saveTargetCurve(instance, targetName, rawTsv)
            Timber.d("loadTargetCurveWithCache: loaded from network, cached '$targetName'")
            return SquigLinkParser.parseFrequencyResponse(rawTsv)
        }
        // Fallback to cache
        val cachedTsv = SquigLinkCacheManager.loadTargetCurve(instance, targetName)
        if (cachedTsv != null) {
            Timber.w("loadTargetCurveWithCache: network failed, using cached '$targetName'")
            return SquigLinkParser.parseFrequencyResponse(cachedTsv)
        }
        throw Exception("No network and no cached data for target '$targetName'")
    }
}
