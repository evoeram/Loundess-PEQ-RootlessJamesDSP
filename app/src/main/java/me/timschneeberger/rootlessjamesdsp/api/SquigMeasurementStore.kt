package me.timschneeberger.rootlessjamesdsp.api

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import me.timschneeberger.rootlessjamesdsp.model.squig.FrequencyResponse
import me.timschneeberger.rootlessjamesdsp.model.squig.SquigLinkInstance
import me.timschneeberger.rootlessjamesdsp.model.squig.SquigLinkPhone
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Локальная база данных замеров SquigLink.
 *
 * Хранит загруженные АЧХ-файлы (FrequencyResponse) на диске в формате JSON:
 * squig_cache/measurements/{instanceKey}/{sanitizedFileName}_{channel}.json
 *
 * Отслеживает статус каждого замера в phone_book:
 * - DOWNLOAD_STATUS_DOWNLOADED — АЧХ-файл уже загружен и кэширован
 * - DOWNLOAD_STATUS_NOT_DOWNLOADED — АЧХ-файл ещё не загружен
 *
 * Индекс загруженных замеров хранится в measurements_index.json для быстрой проверки.
 *
 * Многопоточная загрузка: [downloadMeasurementsParallel] загружает несколько АЧХ-файлов
 * параллельно через корутины с ограничением параллелизма.
 */
object SquigMeasurementStore {

    private val gson = Gson()

    private const val MEASUREMENTS_DIR = "measurements"
    private const val INDEX_FILE = "measurements_index.json"

    /** Максимальное количество параллельных HTTP-запросов при массовой загрузке. */
    const val MAX_PARALLEL_DOWNLOADS = 8

    // ── Index ────────────────────────────────────────────────────────────

    /**
     * Индекс загруженных замеров.
     * Key: "{instanceKey}/{sanitizedFile}_{channel}"
     * Value: timestamp загрузки
     */
    data class MeasurementIndex(
        @SerializedName("entries")
        val entries: MutableMap<String, Long> = mutableMapOf()
    )

    private var index: MeasurementIndex? = null
    private var indexLoaded = false
    private var context: Context? = null
    private val lock = Any()

    /**
     * Initialize with application context. Must be called once before use.
     */
    fun init(context: Context) {
        if (this.context != null) return
        this.context = context.applicationContext
        loadIndex()
        Timber.d("SquigMeasurementStore initialized, ${getIndex().entries.size} measurements cached")
    }

    private fun ctx(): Context =
        context ?: throw IllegalStateException("SquigMeasurementStore not initialized. Call init(context) first.")

    private fun getCacheDir(): File {
        val dir = File(ctx().filesDir, "squig_cache")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getMeasurementsDir(): File {
        val dir = File(getCacheDir(), MEASUREMENTS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getInstanceDir(instance: SquigLinkInstance): File {
        val dir = File(getMeasurementsDir(), instanceKey(instance))
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // ── Key helpers ──────────────────────────────────────────────────────

    /** Sanitize instance name into a safe directory name. */
    private fun instanceKey(instance: SquigLinkInstance): String {
        return instance.name
            .replace(Regex("[^a-zA-Z0-9]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .ifEmpty { "default" }
    }

    /** Sanitize file name into a safe filename component. */
    private fun sanitizeFileName(fileName: String): String {
        return fileName
            .replace(Regex("[^a-zA-Z0-9]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .ifEmpty { "unknown" }
    }

    /** Полный ключ замера в индексе. */
    private fun measurementKey(instance: SquigLinkInstance, file: String, channel: String): String {
        return "${instanceKey(instance)}/${sanitizeFileName(file)}_$channel"
    }

    /** Файл для хранения АЧХ замера. */
    private fun measurementFile(instance: SquigLinkInstance, file: String, channel: String): File {
        return File(getInstanceDir(instance), "${sanitizeFileName(file)}_$channel.json")
    }

    // ── Index load/save ──────────────────────────────────────────────────

    private fun loadIndex() {
        synchronized(lock) {
            if (indexLoaded) return
            val file = File(getCacheDir(), INDEX_FILE)
            index = try {
                if (file.exists()) {
                    gson.fromJson(file.readText(), MeasurementIndex::class.java) ?: MeasurementIndex()
                } else {
                    MeasurementIndex()
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to load measurement index, starting fresh")
                MeasurementIndex()
            }
            indexLoaded = true
        }
    }

    private fun saveIndex() {
        synchronized(lock) {
            val idx = index ?: return
            try {
                File(getCacheDir(), INDEX_FILE).writeText(gson.toJson(idx))
            } catch (e: IOException) {
                Timber.w(e, "Failed to save measurement index")
            }
        }
    }

    private fun getIndex(): MeasurementIndex {
        if (!indexLoaded) loadIndex()
        return index ?: MeasurementIndex().also { index = it }
    }

    // ── Public API: single measurement ───────────────────────────────────

    /**
     * Проверить, загружен ли замер (АЧХ-файл) для данного телефона и канала.
     */
    fun isDownloaded(instance: SquigLinkInstance, phone: SquigLinkPhone, channel: String): Boolean {
        val key = measurementKey(instance, phone.file, channel)
        return getIndex().entries.containsKey(key)
    }

    /**
     * Проверить, загружен ли хотя бы один канал замера для данного телефона.
     */
    fun isAnyChannelDownloaded(
        instance: SquigLinkInstance,
        phone: SquigLinkPhone,
        channels: List<String>
    ): Boolean {
        return channels.any { isDownloaded(instance, phone, it) }
    }

    /**
     * Сохранить АЧХ замера в локальную БД.
     *
     * @param instance инстанс SquigLink
     * @param phone модель телефона/IEM
     * @param channel канал ("L" или "R")
     * @param fr частотная характеристика
     */
    fun saveMeasurement(
        instance: SquigLinkInstance,
        phone: SquigLinkPhone,
        channel: String,
        fr: FrequencyResponse
    ) {
        val key = measurementKey(instance, phone.file, channel)
        val file = measurementFile(instance, phone.file, channel)
        try {
            // Сериализация FrequencyResponse в JSON
            val json = gson.toJson(FrequencyResponseJson(
                frequencies = fr.frequencies,
                spl = fr.spl,
                channel = fr.channel.name
            ))
            file.writeText(json)
            synchronized(lock) {
                getIndex().entries[key] = System.currentTimeMillis()
            }
            saveIndex()
            Timber.d("Saved measurement: $key (${fr.frequencies.size} points)")
        } catch (e: IOException) {
            Timber.w(e, "Failed to save measurement: $key")
        }
    }

    /**
     * Загрузить АЧХ замера из локальной БД.
     *
     * @param instance инстанс SquigLink
     * @param phone модель телефона/IEM
     * @param channel канал ("L" или "R")
     * @return FrequencyResponse или null, если замер не найден в кэше
     */
    fun loadMeasurement(
        instance: SquigLinkInstance,
        phone: SquigLinkPhone,
        channel: String
    ): FrequencyResponse? {
        val key = measurementKey(instance, phone.file, channel)
        if (!getIndex().entries.containsKey(key)) return null

        val file = measurementFile(instance, phone.file, channel)
        return try {
            val json = gson.fromJson(file.readText(), FrequencyResponseJson::class.java)
            val channelEnum = when (json.channel) {
                "LEFT" -> FrequencyResponse.Channel.LEFT
                "RIGHT" -> FrequencyResponse.Channel.RIGHT
                else -> FrequencyResponse.Channel.AVERAGE
            }
            FrequencyResponse(json.frequencies, json.spl, channelEnum)
        } catch (e: Exception) {
            Timber.w(e, "Failed to load measurement: $key")
            null
        }
    }

    /**
     * Удалить замер из локальной БД.
     */
    fun removeMeasurement(
        instance: SquigLinkInstance,
        phone: SquigLinkPhone,
        channel: String
    ) {
        val key = measurementKey(instance, phone.file, channel)
        val file = measurementFile(instance, phone.file, channel)
        synchronized(lock) {
            getIndex().entries.remove(key)
            if (file.exists()) file.delete()
        }
        saveIndex()
    }

    // ── Public API: batch queries ────────────────────────────────────────

    /**
     * Подсчитать количество загруженных замеров для инстанса.
     */
    fun countDownloadedForInstance(instance: SquigLinkInstance): Int {
        val prefix = "${instanceKey(instance)}/"
        return getIndex().entries.keys.count { it.startsWith(prefix) }
    }

    /**
     * Получить множество ключей файлов (без канала), которые загружены для инстанса.
     * Key = sanitizedFileName, Value = set of channels
     */
    fun getDownloadedFilesForInstance(instance: SquigLinkInstance): Map<String, Set<String>> {
        val prefix = "${instanceKey(instance)}/"
        val result = mutableMapOf<String, MutableSet<String>>()
        for (key in getIndex().entries.keys) {
            if (!key.startsWith(prefix)) continue
            // key = "instanceKey/sanitizedFile_channel"
            val remainder = key.substringAfter(prefix)
            val lastUnderscore = remainder.lastIndexOf('_')
            if (lastUnderscore > 0) {
                val filePart = remainder.substring(0, lastUnderscore)
                val channelPart = remainder.substring(lastUnderscore + 1)
                result.getOrPut(filePart) { mutableSetOf() }.add(channelPart)
            }
        }
        return result
    }

    /**
     * Получить общее количество загруженных замеров по всем инстансам.
     */
    fun totalDownloadedCount(): Int = getIndex().entries.size

    /**
     * Получить возраст замера в миллисекундах, или Long.MAX_VALUE если не загружен.
     */
    fun measurementAge(
        instance: SquigLinkInstance,
        phone: SquigLinkPhone,
        channel: String
    ): Long {
        val key = measurementKey(instance, phone.file, channel)
        val ts = getIndex().entries[key] ?: return Long.MAX_VALUE
        return System.currentTimeMillis() - ts
    }

    // ── Cache management ─────────────────────────────────────────────────

    /**
     * Очистить все замеры для конкретного инстанса.
     */
    fun clearInstance(instance: SquigLinkInstance) {
        val key = instanceKey(instance)
        synchronized(lock) {
            // Удалить файлы
            val dir = File(getMeasurementsDir(), key)
            if (dir.exists()) dir.deleteRecursively()
            // Удалить из индекса
            val prefix = "$key/"
            getIndex().entries.keys.filter { it.startsWith(prefix) }.forEach {
                getIndex().entries.remove(it)
            }
        }
        saveIndex()
        Timber.i("Cleared measurements for ${instance.name}")
    }

    /**
     * Очистить все замеры.
     */
    fun clearAll() {
        synchronized(lock) {
            getMeasurementsDir().deleteRecursively()
            getMeasurementsDir().mkdirs()
            getIndex().entries.clear()
        }
        saveIndex()
        Timber.i("Cleared all measurements")
    }

    /**
     * Размер всех замеров в байтах.
     */
    fun cacheSizeBytes(): Long {
        return try {
            getMeasurementsDir().walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } catch (e: Exception) { 0L }
    }

    // ── JSON serialization helper ────────────────────────────────────────

    private data class FrequencyResponseJson(
        @SerializedName("frequencies") val frequencies: DoubleArray,
        @SerializedName("spl") val spl: DoubleArray,
        @SerializedName("channel") val channel: String
    ) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = frequencies.contentHashCode()
    }
}
