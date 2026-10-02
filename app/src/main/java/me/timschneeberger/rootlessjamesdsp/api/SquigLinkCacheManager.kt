package me.timschneeberger.rootlessjamesdsp.api

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import me.timschneeberger.rootlessjamesdsp.model.squig.SquigLinkBrand
import me.timschneeberger.rootlessjamesdsp.model.squig.SquigLinkInstance
import me.timschneeberger.rootlessjamesdsp.model.squig.SquigLinkPhoneDeserializer
import me.timschneeberger.rootlessjamesdsp.model.squig.SquigSite
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Persistent offline cache for SquigLink data.
 *
 * Stores on disk in app's internal storage under `squig_cache/`:
 * - `squigsites.json` — list of all squig.link instances
 * - `config_{instanceKey}.js` — target curve names per instance
 * - `phonebook_{instanceKey}.json` — phone book per instance
 * - `target_{instanceKey}_{sanitizedTargetName}.tsv` — target curve TSV per instance
 * - `metadata.json` — timestamps for staleness checks
 *
 * All methods are thread-safe (synchronized on internal lock).
 * Designed for offline use: if network is unavailable, cached data is returned
 * even if stale.
 *
 * TTL defaults:
 * - squigSites: 7 days
 * - targetCurvesList: 7 days
 * - phoneBook: 24 hours
 * - targetCurve: 30 days (rarely change)
 */
object SquigLinkCacheManager {

    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(
            me.timschneeberger.rootlessjamesdsp.model.squig.SquigLinkPhone::class.java,
            SquigLinkPhoneDeserializer()
        )
        .create()

    private const val CACHE_DIR = "squig_cache"
    private const val META_FILE = "metadata.json"
    private const val SQUIGSITES_FILE = "squigsites.json"

    private val TTL_SQUIGSITES = TimeUnit.DAYS.toMillis(7)
    private val TTL_TARGET_CURVES = TimeUnit.DAYS.toMillis(7)
    private val TTL_PHONE_BOOK = TimeUnit.HOURS.toMillis(24)
    private val TTL_TARGET_CURVE = TimeUnit.DAYS.toMillis(30)

    private val lock = Any()

    // ── Metadata ──────────────────────────────────────────────────────────

    private data class CacheMeta(
        @SerializedName("key") val key: String,
        @SerializedName("timestamp") val timestamp: Long
    )

    private data class MetaFile(
        @SerializedName("entries") val entries: MutableMap<String, Long> = mutableMapOf()
    )

    private var meta: MetaFile? = null
    private var metaLoaded = false
    private var context: Context? = null

    /**
     * Initialize with application context. Must be called once before use.
     * Safe to call multiple times.
     */
    fun init(context: Context) {
        if (this.context != null) return
        this.context = context.applicationContext
        Timber.d("SquigLinkCacheManager initialized, cache dir: ${getCacheDir().absolutePath}")
    }

    private fun ctx(): Context =
        context ?: throw IllegalStateException("SquigLinkCacheManager not initialized. Call init(context) first.")

    private fun getCacheDir(): File {
        val dir = File(ctx().filesDir, CACHE_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun loadMeta(): MetaFile {
        if (metaLoaded) return meta ?: MetaFile()
        synchronized(lock) {
            if (metaLoaded) return meta ?: MetaFile()
            val file = File(getCacheDir(), META_FILE)
            meta = try {
                if (file.exists()) {
                    gson.fromJson(file.readText(), MetaFile::class.java) ?: MetaFile()
                } else {
                    MetaFile()
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to load cache metadata, starting fresh")
                MetaFile()
            }
            metaLoaded = true
            return meta!!
        }
    }

    private fun saveMeta() {
        synchronized(lock) {
            val m = meta ?: return
            try {
                File(getCacheDir(), META_FILE).writeText(gson.toJson(m))
            } catch (e: IOException) {
                Timber.w(e, "Failed to save cache metadata")
            }
        }
    }

    private fun putTimestamp(key: String) {
        val m = loadMeta()
        m.entries[key] = System.currentTimeMillis()
        saveMeta()
    }

    private fun getTimestamp(key: String): Long {
        return loadMeta().entries[key] ?: 0L
    }

    // ── Key helpers ────────────────────────────────────────────────────────

    /** Sanitize instance name into a safe filename component. */
    private fun instanceKey(instance: SquigLinkInstance): String {
        return instance.name
            .replace(Regex("[^a-zA-Z0-9]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .ifEmpty { "default" }
    }

    /** Sanitize target name into a safe filename component. */
    private fun targetKey(targetName: String): String {
        return targetName
            .replace(Regex("[^a-zA-Z0-9]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .ifEmpty { "target" }
    }

    // ── SquigSites ─────────────────────────────────────────────────────────

    private fun squigSitesFile() = File(getCacheDir(), SQUIGSITES_FILE)

    /**
     * Save squigsites.json to cache.
     */
    fun saveSquigSites(sites: List<SquigSite>) {
        synchronized(lock) {
            try {
                squigSitesFile().writeText(gson.toJson(sites))
                putTimestamp("squigsites")
                Timber.i("Cached squigsites: ${sites.size} entries")
            } catch (e: IOException) {
                Timber.w(e, "Failed to cache squigsites")
            }
        }
    }

    /**
     * Load squigsites from cache.
     * @return cached list or null if not cached.
     */
    fun loadSquigSites(): List<SquigSite>? {
        synchronized(lock) {
            val file = squigSitesFile()
            if (!file.exists()) return null
            return try {
                val type = object : TypeToken<List<SquigSite>>() {}.type
                gson.fromJson<List<SquigSite>>(file.readText(), type)
            } catch (e: Exception) {
                Timber.w(e, "Failed to read cached squigsites")
                null
            }
        }
    }

    /**
     * Check if cached squigsites are fresh (within TTL).
     */
    fun isSquigSitesFresh(): Boolean {
        return System.currentTimeMillis() - getTimestamp("squigsites") < TTL_SQUIGSITES
    }

    /** Age of cached squigsites in milliseconds, or Long.MAX_VALUE if not cached. */
    fun squigSitesAge(): Long {
        if (!squigSitesFile().exists()) return Long.MAX_VALUE
        return System.currentTimeMillis() - getTimestamp("squigsites")
    }

    // ── Target curves list (config.js) ──────────────────────────────────────

    private fun configJsFile(instance: SquigLinkInstance) =
        File(getCacheDir(), "config_${instanceKey(instance)}.txt")

    private fun configJsMetaKey(instance: SquigLinkInstance) =
        "config_${instanceKey(instance)}"

    /**
     * Save parsed target curve names for an instance.
     */
    fun saveTargetCurveNames(instance: SquigLinkInstance, names: List<String>) {
        synchronized(lock) {
            try {
                configJsFile(instance).writeText(gson.toJson(names))
                putTimestamp(configJsMetaKey(instance))
                Timber.i("Cached target names for ${instance.name}: ${names.size} entries")
            } catch (e: IOException) {
                Timber.w(e, "Failed to cache target names")
            }
        }
    }

    /**
     * Load cached target curve names for an instance.
     * @return cached list or null if not cached.
     */
    fun loadTargetCurveNames(instance: SquigLinkInstance): List<String>? {
        synchronized(lock) {
            val file = configJsFile(instance)
            if (!file.exists()) return null
            return try {
                val type = object : TypeToken<List<String>>() {}.type
                gson.fromJson<List<String>>(file.readText(), type)
            } catch (e: Exception) {
                Timber.w(e, "Failed to read cached target names")
                null
            }
        }
    }

    fun isTargetCurveNamesFresh(instance: SquigLinkInstance): Boolean {
        return System.currentTimeMillis() - getTimestamp(configJsMetaKey(instance)) < TTL_TARGET_CURVES
    }

    fun targetCurveNamesAge(instance: SquigLinkInstance): Long {
        if (!configJsFile(instance).exists()) return Long.MAX_VALUE
        return System.currentTimeMillis() - getTimestamp(configJsMetaKey(instance))
    }

    // ── Phone book ──────────────────────────────────────────────────────────

    private fun phoneBookFile(instance: SquigLinkInstance) =
        File(getCacheDir(), "phonebook_${instanceKey(instance)}.json")

    private fun phoneBookMetaKey(instance: SquigLinkInstance) =
        "phonebook_${instanceKey(instance)}"

    /**
     * Save phone book for an instance.
     */
    fun savePhoneBook(instance: SquigLinkInstance, brands: List<SquigLinkBrand>) {
        synchronized(lock) {
            try {
                phoneBookFile(instance).writeText(gson.toJson(brands))
                putTimestamp(phoneBookMetaKey(instance))
                Timber.i("Cached phonebook for ${instance.name}: ${brands.size} brands")
            } catch (e: IOException) {
                Timber.w(e, "Failed to cache phonebook")
            }
        }
    }

    /**
     * Load cached phone book for an instance.
     * @return cached list or null if not cached.
     */
    fun loadPhoneBook(instance: SquigLinkInstance): List<SquigLinkBrand>? {
        synchronized(lock) {
            val file = phoneBookFile(instance)
            if (!file.exists()) return null
            return try {
                val type = object : TypeToken<List<SquigLinkBrand>>() {}.type
                gson.fromJson<List<SquigLinkBrand>>(file.readText(), type)
            } catch (e: Exception) {
                Timber.w(e, "Failed to read cached phonebook")
                null
            }
        }
    }

    fun isPhoneBookFresh(instance: SquigLinkInstance): Boolean {
        return System.currentTimeMillis() - getTimestamp(phoneBookMetaKey(instance)) < TTL_PHONE_BOOK
    }

    fun phoneBookAge(instance: SquigLinkInstance): Long {
        if (!phoneBookFile(instance).exists()) return Long.MAX_VALUE
        return System.currentTimeMillis() - getTimestamp(phoneBookMetaKey(instance))
    }

    // ── Target curve TSV ────────────────────────────────────────────────────

    private fun targetCurveFile(instance: SquigLinkInstance, targetName: String) =
        File(getCacheDir(), "target_${instanceKey(instance)}_${targetKey(targetName)}.tsv")

    private fun targetCurveMetaKey(instance: SquigLinkInstance, targetName: String) =
        "target_${instanceKey(instance)}_${targetKey(targetName)}"

    /**
     * Save target curve TSV data for an instance.
     */
    fun saveTargetCurve(instance: SquigLinkInstance, targetName: String, tsv: String) {
        synchronized(lock) {
            try {
                targetCurveFile(instance, targetName).writeText(tsv)
                putTimestamp(targetCurveMetaKey(instance, targetName))
                Timber.d("Cached target '$targetName' for ${instance.name}")
            } catch (e: IOException) {
                Timber.w(e, "Failed to cache target curve")
            }
        }
    }

    /**
     * Load cached target curve TSV for an instance.
     * @return cached TSV string or null if not cached.
     */
    fun loadTargetCurve(instance: SquigLinkInstance, targetName: String): String? {
        synchronized(lock) {
            val file = targetCurveFile(instance, targetName)
            if (!file.exists()) return null
            return try {
                file.readText()
            } catch (e: Exception) {
                Timber.w(e, "Failed to read cached target curve")
                null
            }
        }
    }

    fun isTargetCurveFresh(instance: SquigLinkInstance, targetName: String): Boolean {
        return System.currentTimeMillis() - getTimestamp(targetCurveMetaKey(instance, targetName)) < TTL_TARGET_CURVE
    }

    fun targetCurveAge(instance: SquigLinkInstance, targetName: String): Long {
        if (!targetCurveFile(instance, targetName).exists()) return Long.MAX_VALUE
        return System.currentTimeMillis() - getTimestamp(targetCurveMetaKey(instance, targetName))
    }

    // ── Freshness report ───────────────────────────────────────────────────

    /**
     * Comprehensive freshness report for all cached data.
     *
     * @param instances list of instances to check (from cached squigsites or DEFAULT)
     * @return report with per-item freshness status
     */
    data class FreshnessReport(
        val hasSquigSites: Boolean,
        val squigSitesFresh: Boolean,
        val squigSitesAgeMs: Long,
        val perInstance: List<InstanceFreshness>
    )

    data class InstanceFreshness(
        val instanceName: String,
        val hasPhoneBook: Boolean,
        val phoneBookFresh: Boolean,
        val phoneBookAgeMs: Long,
        val hasTargetNames: Boolean,
        val targetNamesFresh: Boolean,
        val targetNamesAgeMs: Long,
        val cachedTargetCount: Int
    )

    /**
     * Check freshness of all cached data.
     *
     * @param instances list of instances to check. If empty, uses cached squigsites or DEFAULT.
     */
    fun checkFreshness(instances: List<SquigLinkInstance>): FreshnessReport {
        val checkInstances = if (instances.isNotEmpty()) instances
            else loadSquigSites()?.let { SquigLinkInstance.fromSquigSites(it) }
                ?: SquigLinkInstance.DEFAULT

        val perInstance = checkInstances.map { inst ->
            val pbAge = phoneBookAge(inst)
            val tnAge = targetCurveNamesAge(inst)
            val cachedTargets = countCachedTargets(inst)
            InstanceFreshness(
                instanceName = inst.name,
                hasPhoneBook = pbAge != Long.MAX_VALUE,
                phoneBookFresh = isPhoneBookFresh(inst),
                phoneBookAgeMs = pbAge,
                hasTargetNames = tnAge != Long.MAX_VALUE,
                targetNamesFresh = isTargetCurveNamesFresh(inst),
                targetNamesAgeMs = tnAge,
                cachedTargetCount = cachedTargets
            )
        }

        val ssAge = squigSitesAge()
        return FreshnessReport(
            hasSquigSites = ssAge != Long.MAX_VALUE,
            squigSitesFresh = isSquigSitesFresh(),
            squigSitesAgeMs = ssAge,
            perInstance = perInstance
        )
    }

    /** Count how many target curve TSV files are cached for an instance. */
    private fun countCachedTargets(instance: SquigLinkInstance): Int {
        val prefix = "target_${instanceKey(instance)}_"
        return try {
            getCacheDir().listFiles { f -> f.name.startsWith(prefix) && f.name.endsWith(".tsv") }
                ?.size ?: 0
        } catch (e: Exception) { 0 }
    }

    // ── Dump all ────────────────────────────────────────────────────────────

    /**
     * Dump all instances: download and cache phone_book + all target curves for every instance.
     * Also caches squigsites.json and config.js per instance.
     *
     * This is a long-running operation — call on a background thread.
     *
     * @param clientFactory creates a SquigLinkClient for a given instance
     * @param instances list of instances to dump (if empty, fetches squigsites first)
     * @param onProgress callback (current, total, message) for UI progress
     * @return dump result with counts
     */
    suspend fun dumpAll(
        clientFactory: (SquigLinkInstance) -> SquigLinkClient,
        instances: List<SquigLinkInstance> = emptyList(),
        onProgress: ((current: Int, total: Int, message: String) -> Unit)? = null
    ): DumpResult {
        var totalInstances = instances
        var total = 0
        var current = 0
        var phoneBooksSaved = 0
        var targetsSaved = 0
        var errors = 0
        val errorMessages = mutableListOf<String>()

        // Step 1: Fetch squigsites if not provided
        if (totalInstances.isEmpty()) {
            onProgress?.invoke(0, 1, "Fetching squig.link site list...")
            try {
                val tmpClient = clientFactory(SquigLinkInstance.DEFAULT.first())
                val sites = tmpClient.loadSquigSitesAsync()
                saveSquigSites(sites)
                totalInstances = SquigLinkInstance.fromSquigSites(sites)
                Timber.i("dumpAll: fetched ${totalInstances.size} instances from squigsites.json")
            } catch (e: Exception) {
                Timber.w(e, "dumpAll: failed to fetch squigsites, trying cached")
                totalInstances = loadSquigSites()?.let { SquigLinkInstance.fromSquigSites(it) }
                    ?: SquigLinkInstance.DEFAULT
            }
        } else {
            // Still try to refresh squigsites
            try {
                val tmpClient = clientFactory(totalInstances.first())
                val sites = tmpClient.loadSquigSitesAsync()
                saveSquigSites(sites)
                val fresh = SquigLinkInstance.fromSquigSites(sites)
                if (fresh.isNotEmpty()) totalInstances = fresh
            } catch (e: Exception) {
                Timber.w(e, "dumpAll: failed to refresh squigsites during dump")
            }
        }

        // Count total work: 1 (squigsites) + per instance (phonebook + config + targets)
        // We don't know target count ahead, so estimate
        total = totalInstances.size * 2 + 1

        // Step 2: For each instance, fetch phone_book + config.js + all targets
        for (inst in totalInstances) {
            current++
            val client = clientFactory(inst)
            onProgress?.invoke(current, total, "Loading ${inst.name}...")

            // Phone book
            try {
                val brands = client.loadDatabaseAsync()
                savePhoneBook(inst, brands)
                phoneBooksSaved++
            } catch (e: Exception) {
                errors++
                errorMessages.add("${inst.name}: phonebook - ${e.message}")
                Timber.w(e, "dumpAll: failed phonebook for ${inst.name}")
            }

            // Target curve names (config.js)
            var targetNames: List<String> = emptyList()
            try {
                targetNames = client.loadTargetCurvesAsync()
                saveTargetCurveNames(inst, targetNames)
            } catch (e: Exception) {
                errors++
                errorMessages.add("${inst.name}: config.js - ${e.message}")
                Timber.w(e, "dumpAll: failed config.js for ${inst.name}")
                // Try cached
                targetNames = loadTargetCurveNames(inst) ?: emptyList()
            }

            // All target curves
            for (tName in targetNames) {
                current++
                if (current > total) total = current
                onProgress?.invoke(current, total, "Target: $tName (${inst.name})")
                try {
                    val tsv = client.loadTargetCurveRawAsync(tName)
                    if (tsv != null) {
                        saveTargetCurve(inst, tName, tsv)
                        targetsSaved++
                    }
                } catch (e: Exception) {
                    errors++
                    errorMessages.add("${inst.name}: target $tName - ${e.message}")
                    Timber.w(e, "dumpAll: failed target '$tName' for ${inst.name}")
                }
            }
        }

        onProgress?.invoke(total, total, "Done: $phoneBooksSaved phonebooks, $targetsSaved targets, $errors errors")
        return DumpResult(
            instancesProcessed = totalInstances.size,
            phoneBooksSaved = phoneBooksSaved,
            targetsSaved = targetsSaved,
            errors = errors,
            errorMessages = errorMessages
        )
    }

    data class DumpResult(
        val instancesProcessed: Int,
        val phoneBooksSaved: Int,
        val targetsSaved: Int,
        val errors: Int,
        val errorMessages: List<String>
    ) {
        val success: Boolean get() = errors == 0
        val summary: String
            get() = "$instancesProcessed instances, $phoneBooksSaved phonebooks, " +
                    "$targetsSaved targets, $errors errors"
    }

    // ── Cache management ────────────────────────────────────────────────────

    /**
     * Clear all cached data.
     */
    fun clearAll() {
        synchronized(lock) {
            getCacheDir().listFiles()?.forEach { it.delete() }
            meta = MetaFile()
            metaLoaded = true
            saveMeta()
            Timber.i("SquigLink cache cleared")
        }
    }

    /**
     * Clear cached data for a specific instance.
     */
    fun clearInstance(instance: SquigLinkInstance) {
        synchronized(lock) {
            val key = instanceKey(instance)
            getCacheDir().listFiles()?.forEach { f ->
                if (f.name.contains(key)) f.delete()
            }
            val m = loadMeta()
            m.entries.keys.filter { it.contains(key) }.forEach { m.entries.remove(it) }
            saveMeta()
            Timber.i("Cleared cache for ${instance.name}")
        }
    }

    /**
     * Total size of cache in bytes.
     */
    fun cacheSizeBytes(): Long {
        return try {
            getCacheDir().walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } catch (e: Exception) { 0L }
    }

    /**
     * Format cache size as human-readable string.
     */
    fun cacheSizeFormatted(): String {
        val bytes = cacheSizeBytes()
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }
}
