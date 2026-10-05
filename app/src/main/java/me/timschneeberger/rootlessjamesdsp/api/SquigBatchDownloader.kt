package me.timschneeberger.rootlessjamesdsp.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.timschneeberger.rootlessjamesdsp.model.squig.SquigLinkBrand
import me.timschneeberger.rootlessjamesdsp.model.squig.SquigLinkInstance
import timber.log.Timber

/**
 * Многопоточная пакетная загрузка замеров SquigLink.
 *
 * Загружает АЧХ-файлы (L, R каналы) для всех телефонов из phone_book инстанса
 * параллельно с ограничением параллелизма [SquigMeasurementStore.MAX_PARALLEL_DOWNLOADS].
 *
 * Каждый загруженный замер сохраняется в [SquigMeasurementStore] — локальную БД замеров.
 * Уже загруженные замеры пропускаются (не загружаются повторно).
 *
 * Также загружает все target curves инстанса (если ещё не загружены).
 *
 * Прогресс сообщается через [onProgress].
 */
object SquigBatchDownloader {

    /**
     * Результат пакетной загрузки.
     *
     * @param totalPhones всего телефонов в phone_book
     * @param downloadedNew сколько АЧХ-замеров загружено (новых)
     * @param skippedAlreadyCached сколько замеров пропущено (уже в кэше)
     * @param failed сколько замеров не удалось загрузить
     * @param targetsDownloaded сколько target curves загружено
     * @param targetsFailed сколько target curves не удалось загрузить
     * @param errors список сообщений об ошибках (первые N)
     */
    data class BatchResult(
        val totalPhones: Int,
        val downloadedNew: Int,
        val skippedAlreadyCached: Int,
        val failed: Int,
        val targetsDownloaded: Int,
        val targetsFailed: Int,
        val errors: List<String>
    ) {
        val success: Boolean get() = errors.isEmpty() && failed == 0
        val summary: String
            get() = "$downloadedNew new, $skippedAlreadyCached cached, $failed failed " +
                    "(${totalPhones} total phones); $targetsDownloaded targets loaded, $targetsFailed targets failed"
    }

    /**
     * Многопоточная загрузка всех замеров одного инстанса.
     *
     * @param clientFactory фабрика SquigLinkClient для инстанса
     * @param instance инстанс SquigLink
     * @param brands список брендов из phone_book (уже загруженный)
     * @param targetNames список имён target curves (уже загруженный, может быть пустым)
     * @param onProgress колбэк прогресса (current, total, message)
     * @return результат пакетной загрузки
     */
    suspend fun downloadInstanceMeasurements(
        clientFactory: (SquigLinkInstance) -> SquigLinkClient,
        instance: SquigLinkInstance,
        brands: List<SquigLinkBrand>,
        targetNames: List<String> = emptyList(),
        onProgress: ((current: Int, total: Int, message: String) -> Unit)? = null
    ): BatchResult = withContext(Dispatchers.IO) {
        val client = clientFactory(instance)
        val channels = instance.channels
        val allPhones = brands.flatMap { brand -> brand.phones.map { brand to it } }
        val totalPhones = allPhones.size
        var downloadedNew = 0
        var skippedAlreadyCached = 0
        var failed = 0
        val errors = mutableListOf<String>()

        Timber.i("BatchDownloader: starting download for ${instance.name} — $totalPhones phones, channels=$channels")

        // Оценка общего количества задач: телефоны * каналы + targets
        val totalTasks = totalPhones * channels.size + targetNames.size
        var currentTask = 0

        // Semaphore для ограничения параллелизма
        val semaphore = Semaphore(SquigMeasurementStore.MAX_PARALLEL_DOWNLOADS)

        // Многопоточная загрузка замеров
        coroutineScope {
            allPhones.forEachIndexed { phoneIdx, (brand, phone) ->
                // Пропускаем телефоны с пустым file
                if (phone.file.isEmpty()) {
                    currentTask += channels.size
                    return@forEachIndexed
                }

                // Проверяем, нужно ли загружать хотя бы один канал
                val allChannelsCached = channels.all { ch ->
                    SquigMeasurementStore.isDownloaded(instance, phone, ch)
                }
                if (allChannelsCached) {
                    skippedAlreadyCached += channels.size
                    currentTask += channels.size
                    onProgress?.invoke(currentTask, totalTasks,
                        "Skip ${brand.name} ${phone.name} (cached)")
                    return@forEachIndexed
                }

                // Запускаем загрузку каналов параллельно
                for (channel in channels) {
                    launch {
                        semaphore.withPermit {
                            // Проверяем кэш ещё раз (race condition guard)
                            if (SquigMeasurementStore.isDownloaded(instance, phone, channel)) {
                                skippedAlreadyCached++
                                return@withPermit
                            }

                            onProgress?.invoke(currentTask, totalTasks,
                                "Loading ${brand.name} ${phone.name} [$channel]")

                            try {
                                val fr = client.loadFrequencyResponseAsync(phone, channel)
                                val normalized = fr.normalize(instance.defaultNormHz)
                                SquigMeasurementStore.saveMeasurement(instance, phone, channel, normalized)
                                downloadedNew++
                                Timber.d("BatchDownloader: downloaded ${phone.name} [$channel]")
                            } catch (e: Exception) {
                                failed++
                                val errMsg = "${brand.name} ${phone.name} [$channel]: ${e.message}"
                                synchronized(errors) {
                                    if (errors.size < 20) errors.add(errMsg)
                                }
                                Timber.w("BatchDownloader: failed ${phone.name} [$channel]: ${e.message}")
                            } finally {
                                currentTask++
                            }
                        }
                    }
                }
            }
        }

        // Загрузка target curves
        var targetsDownloaded = 0
        var targetsFailed = 0
        for (targetName in targetNames) {
            currentTask++
            onProgress?.invoke(currentTask, totalTasks, "Target: $targetName")
            try {
                val rawTsv = client.loadTargetCurveRawAsync(targetName)
                if (rawTsv != null) {
                    SquigLinkCacheManager.saveTargetCurve(instance, targetName, rawTsv)
                    targetsDownloaded++
                } else {
                    targetsFailed++
                }
            } catch (e: Exception) {
                targetsFailed++
                Timber.w("BatchDownloader: target '$targetName' failed: ${e.message}")
            }
        }

        onProgress?.invoke(totalTasks, totalTasks,
            "Done: $downloadedNew new, $skippedAlreadyCached cached, $failed failed, $targetsDownloaded targets")

        Timber.i("BatchDownloader: ${instance.name} done — $downloadedNew new, $skippedAlreadyCached cached, $failed failed, $targetsDownloaded targets")

        BatchResult(
            totalPhones = totalPhones,
            downloadedNew = downloadedNew,
            skippedAlreadyCached = skippedAlreadyCached,
            failed = failed,
            targetsDownloaded = targetsDownloaded,
            targetsFailed = targetsFailed,
            errors = errors
        )
    }

    /**
     * Многопоточная загрузка замеров всех инстансов.
     *
     * @param clientFactory фабрика SquigLinkClient для инстанса
     * @param instances список инстансов
     * @param brandsByInstance карта: инстанс → список брендов (phone_book)
     * @param targetsByInstance карта: инстанс → список имён target curves
     * @param onProgress колбэк прогресса (current, total, message)
     * @return агрегированный результат всех инстансов
     */
    suspend fun downloadAllInstances(
        clientFactory: (SquigLinkInstance) -> SquigLinkClient,
        instances: List<SquigLinkInstance>,
        brandsByInstance: Map<SquigLinkInstance, List<SquigLinkBrand>>,
        targetsByInstance: Map<SquigLinkInstance, List<String>> = emptyMap(),
        onProgress: ((current: Int, total: Int, message: String) -> Unit)? = null
    ): BatchResult = withContext(Dispatchers.IO) {
        var totalDownloaded = 0
        var totalSkipped = 0
        var totalFailed = 0
        var totalTargetsDownloaded = 0
        var totalTargetsFailed = 0
        val allErrors = mutableListOf<String>()
        var totalPhones = 0

        for ((idx, instance) in instances.withIndex()) {
            val brands = brandsByInstance[instance] ?: continue
            val targets = targetsByInstance[instance] ?: emptyList()
            val progressPrefix = "[${idx + 1}/${instances.size}] "

            val result = downloadInstanceMeasurements(
                clientFactory = clientFactory,
                instance = instance,
                brands = brands,
                targetNames = targets,
                onProgress = { current, total, msg ->
                    onProgress?.invoke(current, total, "$progressPrefix$msg")
                }
            )

            totalDownloaded += result.downloadedNew
            totalSkipped += result.skippedAlreadyCached
            totalFailed += result.failed
            totalTargetsDownloaded += result.targetsDownloaded
            totalTargetsFailed += result.targetsFailed
            totalPhones += result.totalPhones
            allErrors.addAll(result.errors)
        }

        BatchResult(
            totalPhones = totalPhones,
            downloadedNew = totalDownloaded,
            skippedAlreadyCached = totalSkipped,
            failed = totalFailed,
            targetsDownloaded = totalTargetsDownloaded,
            targetsFailed = totalTargetsFailed,
            errors = allErrors.take(50)
        )
    }
}
