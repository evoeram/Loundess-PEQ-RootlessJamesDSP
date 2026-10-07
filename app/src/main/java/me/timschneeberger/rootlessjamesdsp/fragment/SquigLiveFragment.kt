package me.timschneeberger.rootlessjamesdsp.fragment

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.adapter.SquigPhoneAdapter
import me.timschneeberger.rootlessjamesdsp.api.SquigLinkCacheManager
import me.timschneeberger.rootlessjamesdsp.api.SquigLinkClient
import me.timschneeberger.rootlessjamesdsp.api.SquigMeasurementStore
import me.timschneeberger.rootlessjamesdsp.api.SquigBatchDownloader
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqBand
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqBandList
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqChannel
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqFilterType
import me.timschneeberger.rootlessjamesdsp.model.squig.FrequencyResponse
import me.timschneeberger.rootlessjamesdsp.model.squig.SquigLinkBrand
import me.timschneeberger.rootlessjamesdsp.model.squig.SquigLinkInstance
import me.timschneeberger.rootlessjamesdsp.model.squig.SquigLinkPhone
import me.timschneeberger.rootlessjamesdsp.squig.SquigAutoEqEngine
import me.timschneeberger.rootlessjamesdsp.squig.SquigLinkSearchEngine
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.ParametricEqResponseCalculator
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.toast
import me.timschneeberger.rootlessjamesdsp.view.ParametricEqSurface
import timber.log.Timber
import kotlin.math.roundToInt

/**
 * Главный фрагмент модуля Squig Live.
 *
 * Объединяет:
 * 1. Выбор squig.link инстанса (spinner)
 * 2. Поиск IEM/наушников по каталогу phone_book (живой поиск с debounce)
 * 3. Загрузку АЧХ измерения и целевой кривой
 * 4. Визуализацию: measurement (L+R) + target + filter response + corrected
 * 5. AutoEQ — автоматический подбор PEQ-полос
 * 6. Live EQ — интерактивное редактирование через LiveEqBottomSheet
 * 7. Apply — сохранение полос в настройки PEQ
 *
 * UI описан в XML-макете fragment_squig_live.xml.
 * Использует viewLifecycleOwner.lifecycleScope для корутин —
 * все корутины автоматически отменяются при уничтожении view.
 *
 * Сетевые запросы — через suspend-обёртки SquigLinkClient
 * (линейный код вместо вложенных callback'ов).
 */
class SquigLiveFragment : Fragment() {

    // ── UI элементы ────────────────────────────────────────────────────────

    private lateinit var instanceSpinner: Spinner
    private lateinit var targetSpinner: Spinner
    private lateinit var targetInstanceSpinner: Spinner
    private lateinit var searchInput: TextInputEditText
    private lateinit var searchLayout: TextInputLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var catalogInfo: TextView
    private lateinit var selectedPhoneText: TextView
    private lateinit var graphSurface: ParametricEqSurface
    private lateinit var resultsRecyclerView: RecyclerView
    private lateinit var emptyStateText: TextView
    private lateinit var autoEqButton: MaterialButton
    private lateinit var liveEqButton: MaterialButton
    private lateinit var applyButton: MaterialButton
    private lateinit var resetButton: MaterialButton
    private lateinit var importTargetButton: MaterialButton

    private lateinit var resultsAdapter: SquigPhoneAdapter

    // ── Состояние ────────────────────────────────────────────────────────

    /** Список инстансов SquigLink (загружается из squigsites.json). */
    private var instances: List<SquigLinkInstance> = SquigLinkInstance.DEFAULT

    /** Текущий выбранный инстанс. */
    private var currentInstance: SquigLinkInstance = instances.first()

    /** Текущий клиент для инстанса. */
    private var currentClient: SquigLinkClient = SquigLinkClient(currentInstance)

    /** Инстанс для загрузки target curves (отдельный от инстанса замеров). */
    private var targetInstance: SquigLinkInstance = instances.first()

    /** Клиент для инстанса таргета. */
    private var targetClient: SquigLinkClient = SquigLinkClient(targetInstance)

    /** Доступные целевые кривые (загружается из config.js). */
    private var targetCurves: List<String> = listOf(
        "Super 22 Target",
        "Harman IE 2019 Target",
        "Harman Target",
        "Diffuse Field Target",
        "Etymotic Target",
        "∆ 5128DF Target",
        "∆ 5128DF - 10dB Tilt Target",
        "∆ JM-1 - 10dB Tilt Target"
    )

    /** Имя выбранной целевой кривой. */
    private var selectedTargetName: String = targetCurves.first()

    /** Загруженный каталог phone_book. */
    private var database: List<SquigLinkBrand> = emptyList()

    /** Текущая выбранная модель. */
    private var selectedPhone: SquigLinkPhone? = null

    /** Загруженная АЧХ измерения левого канала (нормализованная). */
    private var measurementFR_L: FrequencyResponse? = null

    /** Загруженная АЧХ измерения правого канала (нормализованная). */
    private var measurementFR_R: FrequencyResponse? = null

    /** Усреднённая АЧХ измерения (L+R)/2 или только L/R. */
    private var measurementFR: FrequencyResponse? = null

    /** Загруженная целевая кривая. */
    private var targetFR: FrequencyResponse? = null

    /** Вычисленные AutoEQ-полосы. */
    private var autoEqBands: List<ParametricEqBand> = emptyList()
    private var autoEqPreamp: Double = 0.0

    /** Рабочий список PEQ-полос для Live EQ. */
    private val workingBands = ParametricEqBandList()
    private var workingPreamp: Double = 0.0

    /** Джоб debounce-поиска. */
    private var searchJob: Job? = null

    /** ActivityResult launcher для выбора TSV-файла custom target. */
    private lateinit var targetFilePicker: androidx.activity.result.ActivityResultLauncher<Array<String>>

    /** Флаг: идёт загрузка данных. */
    private var isLoadingData = false

    /** Флаг: выбран ли инстанс замеров (false = "—" в spinner). */
    private var instanceSelected = false

    /** Флаг: выбран ли инстанс таргетов (false = "—" в spinner). */
    private var targetInstanceSelected = false

    /** Метка пустого выбора в спиннерах. */
    private val noSelectionLabel = "—"

    /** Конфигурация AutoEQ (настраиваемая пользователем). */
    private var autoEqConfig = SquigAutoEqEngine.Config()

    // ── Persistence + real-time preview state ────────────────────────────

    /** SharedPreferences namespace for Squig Live plugin state. */
    private val squigPrefs: SharedPreferences?
        get() = context?.getSharedPreferences(Constants.PREF_SQUIG, Context.MODE_MULTI_PROCESS)

    /** Saved instance/target/phone names restored from prefs; applied once the
     *  async spinner loaders finish populating their adapters. */
    private var pendingInstanceName: String? = null
    private var pendingTargetName: String? = null
    private var pendingPhoneName: String? = null
    private var pendingPhoneBrand: String? = null
    private var pendingPhoneFile: String? = null

    // ── Жизненный цикл ────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_squig_live, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize offline cache manager
        SquigLinkCacheManager.init(requireContext().applicationContext)
        // Initialize measurement store (local DB of downloaded АЧХ files)
        SquigMeasurementStore.init(requireContext().applicationContext)

        // Register file picker for custom target import
        targetFilePicker = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                handleImportedTarget(uri)
            }
        }

        // Привязка UI элементов из XML
        instanceSpinner = view.findViewById(R.id.instanceSpinner)
        targetSpinner = view.findViewById(R.id.targetSpinner)
        targetInstanceSpinner = view.findViewById(R.id.targetInstanceSpinner)
        searchInput = view.findViewById(R.id.searchInput)
        searchLayout = view.findViewById(R.id.searchLayout)
        progressBar = view.findViewById(R.id.loadingProgress)
        statusText = view.findViewById(R.id.statusText)
        catalogInfo = view.findViewById(R.id.catalogInfo)
        selectedPhoneText = view.findViewById(R.id.selectedPhoneText)
        graphSurface = view.findViewById(R.id.squigSurface)
        resultsRecyclerView = view.findViewById(R.id.resultsList)
        emptyStateText = view.findViewById(R.id.emptyStateText)
        autoEqButton = view.findViewById(R.id.autoeqButton)
        liveEqButton = view.findViewById(R.id.liveEqButton)
        applyButton = view.findViewById(R.id.applyButton)
        resetButton = view.findViewById(R.id.resetButton)
        importTargetButton = view.findViewById(R.id.importTargetButton)

        // Offline cache button
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.cacheButton)
            ?.setOnClickListener { showCacheMenu() }

        // Download all measurements button
        view.findViewById<com.google.android.material.button.MaterialButton>(R.id.downloadAllButton)
            ?.setOnClickListener { showDownloadAllDialog() }

        // Живой поиск: TextWatcher с debounce 350ms
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: ""
                if (query.isEmpty()) {
                    searchJob?.cancel()
                    // При пустом запросе показываем полный список замеров инстанса
                    // (только если инстанс выбран и каталог загружен)
                    if (instanceSelected && database.isNotEmpty()) {
                        showAllMeasurements()
                    } else {
                        resultsAdapter.updateResults(emptyList())
                        showEmptyState(true)
                    }
                    statusText.isVisible = false
                    return
                }
                // Debounce 350ms
                searchJob?.cancel()
                searchJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(350)
                    performSearch(query)
                }
            }
        })

        // Enter = мгновенный поиск без debounce
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                searchJob?.cancel()
                val query = searchInput.text?.toString()?.trim() ?: ""
                if (query.isNotEmpty()) {
                    viewLifecycleOwner.lifecycleScope.launch { performSearch(query) }
                }
                true
            } else false
        }

        // Иконка поиска в поле = тоже поиск
        searchLayout.setEndIconOnClickListener {
            searchJob?.cancel()
            val query = searchInput.text?.toString()?.trim() ?: ""
            if (query.isNotEmpty()) {
                viewLifecycleOwner.lifecycleScope.launch { performSearch(query) }
            }
        }

        // Настройка списка результатов
        resultsAdapter = SquigPhoneAdapter()
        resultsAdapter.onClickListener = { result ->
            onSearchResultClicked(result)
        }
        resultsAdapter.onOtherClickListener = {
            // Тап на "Other" — разворачивает полный список
            resultsAdapter.expand()
        }
        resultsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        // Отключаем item animator — он конфликтует с notifyDataSetChanged
        // при сворачивании/разворачивании списка (Tmp detached view crash)
        resultsRecyclerView.itemAnimator = null
        resultsRecyclerView.adapter = resultsAdapter

        // Кнопки действий
        autoEqButton.setOnClickListener { performAutoEq() }
        liveEqButton.setOnClickListener { openLiveEq() }
        applyButton.setOnClickListener { applyToPeq() }
        resetButton.setOnClickListener { resetEq() }

        // Начальное состояние — пустой список (инстанс не выбран по умолчанию)
        showEmptyState(true)
        updateActionButtons()

        setupInstanceSpinner()
        setupTargetInstanceSpinner()
        setupTargetSpinner()

        // Import custom target button
        importTargetButton.setOnClickListener { openTargetFilePicker() }

        loadSquigSites()

        // Не загружаем catalog / target curves по умолчанию —
        // только после выбора инстанса в spinner.
        // loadDatabase() → вызывается из setupInstanceSpinner() при выборе.
        // loadTargetCurves() → вызывается из setupTargetInstanceSpinner() при выборе.
        // loadTargetCurveData() → вызывается из loadTargetCurves() / setupTargetSpinner().

        // Restore persisted user state (autoeq config first; instance/target/phone
        // are restored inside the async load callbacks once spinners are populated).
        restorePersistedState()

        // Load target curve after restorePersistedState so workingPreamp is
        // already restored — the target display is shifted by preamp when bands exist.
        // loadTargetCurveData() — уже вызван выше, повторный вызов не нужен:
        // restorePersistedState мог восстановить selectedTargetName, но если
        // target instance не выбран, target всё равно не загрузится.

        // Re-evaluate button states after restoring working bands — restorePersistedState
        // may have loaded saved Live EQ bands, which should enable Apply/Reset.
        updateActionButtons()
    }

    override fun onDestroyView() {
        super.onDestroyView()
    }

    /**
     * Загрузка каталога squigsites.json — список всех инстансов.
     * Использует offline cache: при отсутствии сети — кэшированные данные.
     */
    private fun loadSquigSites() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val sites = currentClient.loadSquigSitesWithCache()
                val newInstances = SquigLinkInstance.fromSquigSites(sites)
                if (newInstances.isNotEmpty()) {
                    instances = newInstances
                    val instanceNames = listOf(noSelectionLabel) + instances.map { it.name }
                    instanceSpinner.adapter = ArrayAdapter(
                        requireContext(),
                        android.R.layout.simple_spinner_dropdown_item,
                        instanceNames
                    )
                    // Apply pending instance selection restored from prefs
                    var restoredInstanceIdx = 0 // default: "—"
                    pendingInstanceName?.let { name ->
                        val idx = instances.indexOfFirst { it.name == name }
                        if (idx >= 0) {
                            // Не устанавливаем currentInstance здесь — onItemSelected
                            // callback в setupInstanceSpinner обработает смену и вызовет loadDatabase()
                            restoredInstanceIdx = idx + 1 // +1 for "—" at position 0
                        }
                    }
                    instanceSpinner.setSelection(restoredInstanceIdx)
                    instanceSelected = restoredInstanceIdx > 0
                    // Каталог загружается через onItemSelected callback при setSelection
                    // (если восстановлен — position > 0, если нет — position=0, ничего не грузится)

                    // Обновляем target instance spinner тоже
                    targetInstanceSpinner.adapter = ArrayAdapter(
                        requireContext(),
                        android.R.layout.simple_spinner_dropdown_item,
                        instanceNames
                    )
                    // Apply saved target instance selection
                    var restoredTargetInstIdx = 0 // default: "—"
                    val savedTargetInst = squigPrefs?.getString(getString(R.string.key_squig_target_instance), null)
                    if (!savedTargetInst.isNullOrEmpty()) {
                        val idx = instances.indexOfFirst { it.name == savedTargetInst }
                        if (idx >= 0) {
                            // Не устанавливаем targetInstance здесь — onItemSelected
                            // callback в setupTargetInstanceSpinner обработает смену
                            restoredTargetInstIdx = idx + 1 // +1 for "—"
                        }
                    }
                    targetInstanceSpinner.setSelection(restoredTargetInstIdx)
                    targetInstanceSelected = restoredTargetInstIdx > 0
                    // Target curves загружаются через onItemSelected callback при setSelection
                    // (если восстановлен — position > 0, если нет — position=0, ничего не грузится)
                    Timber.i("squigsites загружен: ${instances.size} инстансов")
                }
            } catch (e: Exception) {
                Timber.w("squigsites недоступен (нет сети и кэша), используется DEFAULT: ${e.message}")
            }
        }
    }

    /**
     * Загрузка списка target curves из config.js.
     * Использует offline cache: при отсутствии сети — кэшированные данные.
     */
    private fun loadTargetCurves() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val curves = targetClient.loadTargetCurvesWithCache(targetInstance)
                if (curves.isNotEmpty()) {
                    targetCurves = curves
                    val currentSelection = selectedTargetName
                    // Добавляем "—" первым элементом
                    val spinnerItems = listOf(noSelectionLabel) + targetCurves
                    targetSpinner.adapter = ArrayAdapter(
                        requireContext(),
                        android.R.layout.simple_spinner_dropdown_item,
                        spinnerItems
                    )
                    if (currentSelection != noSelectionLabel && targetCurves.contains(currentSelection)) {
                        val idx = targetCurves.indexOf(currentSelection) + 1 // +1 for "—"
                        targetSpinner.setSelection(idx)
                        loadTargetCurveData()
                    } else {
                        selectedTargetName = targetCurves.first()
                        targetSpinner.setSelection(1) // first real target
                        loadTargetCurveData()
                    }
                    Timber.i("config.js загружен: ${targetCurves.size} target curves (from ${targetInstance.name})")
                }
            } catch (e: Exception) {
                Timber.w("config.js недоступен для ${targetInstance.name} (нет сети и кэша), используется fallback targets: ${e.message}")
            }
        }
    }

    // ── Логика ────────────────────────────────────────────────────────────

    /**
     * Настройка spinner для выбора инстанса SquigLink.
     */
    private fun setupInstanceSpinner() {
        val instanceNames = listOf(noSelectionLabel) + instances.map { it.name }
        instanceSpinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            instanceNames
        )
        // По умолчанию — пустой выбор (позиция 0 = "—")
        instanceSpinner.setSelection(0)
        instanceSelected = false
        instanceSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (position == 0) {
                    // "—" — ничего не выбрано, не загружаем каталог
                    instanceSelected = false
                    database = emptyList()
                    resultsAdapter.updateResults(emptyList())
                    updateCatalogInfo()
                    showEmptyState(true)
                    statusText.isVisible = false
                    return
                }
                val newInstance = instances[position - 1]
                instanceSelected = true
                if (newInstance != currentInstance) {
                    currentInstance = newInstance
                    currentClient = SquigLinkClient(currentInstance)
                    Timber.i("SquigLive: instance changed → ${currentInstance.name} (baseUrl=${currentInstance.baseUrl}, dataPath=${currentInstance.fullDataPath}, channels=${currentInstance.channels}, normHz=${currentInstance.defaultNormHz})")
                    // Persist instance selection
                    saveInstanceState()
                    loadDatabase()
                    // Target curves загружаются из отдельного инстанса (targetInstanceSpinner)
                    // — не перезагружаем их при смене инстанса замеров.
                    // Обновляем индикаторы загруженных замеров для нового инстанса
                    refreshDownloadedKeys()
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    /**
     * Настройка spinner для выбора инстанса, из которого грузятся target curves.
     * Независим от инстанса замеров — можно выбрать любой инстанс для таргетов.
     * При смене — перезагружает список target curves и саму target.
     */
    private fun setupTargetInstanceSpinner() {
        val instanceNames = listOf(noSelectionLabel) + instances.map { it.name }
        targetInstanceSpinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            instanceNames
        )
        // По умолчанию — пустой выбор (позиция 0 = "—")
        targetInstanceSpinner.setSelection(0)
        targetInstanceSelected = false
        targetInstanceSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (position == 0) {
                    // "—" — ничего не выбрано, не загружаем target curves
                    targetInstanceSelected = false
                    targetSpinner.adapter = ArrayAdapter(
                        requireContext(),
                        android.R.layout.simple_spinner_dropdown_item,
                        listOf(noSelectionLabel)
                    )
                    targetFR = null
                    graphSurface.clearTargetCurve()
                    updateActionButtons()
                    return
                }
                val newTargetInstance = instances[position - 1]
                targetInstanceSelected = true
                if (newTargetInstance != targetInstance) {
                    targetInstance = newTargetInstance
                    targetClient = SquigLinkClient(targetInstance)
                    Timber.i("SquigLive: target instance changed → ${targetInstance.name}")
                    // Persist target instance selection
                    saveTargetInstanceState()
                    // Перезагружаем список target curves и саму target для нового инстанса таргета
                    loadTargetCurves()
                    loadTargetCurveData()
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    /**
     * Открытие файлового пикера для выбора TSV-файла custom target curve.
     * Использует ActivityResultContracts.OpenDocument.
     */
    private fun openTargetFilePicker() {
        targetFilePicker.launch(arrayOf("text/*", "application/octet-stream", "*/*"))
    }

    /**
     * Обработка выбранного TSV-файла: парсинг, нормализация, отображение на графике.
     */
    private fun handleImportedTarget(uri: android.net.Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val tsv = withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                        ?: throw Exception("Cannot read file")
                }
                val fr = me.timschneeberger.rootlessjamesdsp.api.SquigLinkParser.parseFrequencyResponse(tsv)
                if (fr.frequencies.isEmpty()) {
                    throw Exception("No valid data in file")
                }
                val normHz = targetInstance.defaultNormHz
                targetFR = fr.normalize(normHz)
                selectedTargetName = getString(R.string.squig_custom_target)

                // Добавляем "Custom Target" в spinner если его там нет
                if (!targetCurves.contains(selectedTargetName)) {
                    targetCurves = listOf(selectedTargetName) + targetCurves
                    targetSpinner.adapter = ArrayAdapter(
                        requireContext(),
                        android.R.layout.simple_spinner_dropdown_item,
                        listOf(noSelectionLabel) + targetCurves
                    )
                }
                targetSpinner.setSelection(targetCurves.indexOf(selectedTargetName) + 1) // +1 for "—"

                // Отображаем target на графике
                val displayTarget = if (workingPreamp != 0.0) targetFR!!.applyPreamp(workingPreamp) else targetFR!!
                val (freqsT, splT) = displayTarget.toFloatArrays()
                graphSurface.setTargetCurve(freqsT, splT)

                hideLoading()
                updateActionButtons()
                statusText.text = getString(R.string.squig_import_target_done, selectedTargetName)
                statusText.isVisible = true
                Timber.i("SquigLive: custom target imported — ${fr.frequencies.size} points, normalized at ${normHz}Hz")
            } catch (e: Exception) {
                if (!isAdded || view == null) return@launch
                showError(getString(R.string.squig_import_target_failed, e.message ?: "unknown"))
                Timber.e(e, "handleImportedTarget failed")
            }
        }
    }

    /**
     * Сохранить выбор инстанса таргета в SharedPreferences.
     */
    private fun saveTargetInstanceState() {
        val prefs = squigPrefs ?: return
        prefs.edit()
            .putString(getString(R.string.key_squig_target_instance), targetInstance.name)
            .apply()
    }

    /**
     * Настройка spinner для выбора целевой кривой (target curve).
     * При смене target — всегда перезагружает target curve (даже без замера).
     */
    private fun setupTargetSpinner() {
        // По умолчанию — только "—"; список заполняется после выбора target instance
        val targetNames = listOf(noSelectionLabel)
        targetSpinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            targetNames
        )
        targetSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (position == 0) return // "—" — ничего не выбрано
                val newTarget = targetCurves[position - 1]
                if (newTarget != selectedTargetName) {
                    selectedTargetName = newTarget
                    // Persist target selection
                    saveTargetState()
                    Timber.i("SquigLive: target changed → $selectedTargetName")
                    // Всегда перезагружаем target curve
                    reloadTargetCurve()
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    /**
     * Загрузка каталога phone_book для текущего инстанса.
     * Использует offline cache: при отсутствии сети — кэшированные данные.
     */
    private fun loadDatabase() {
        showLoading(getString(R.string.squig_loading))
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val brands = currentClient.loadDatabaseWithCache(currentInstance)
                database = brands
                Timber.i("Каталог загружен: ${brands.size} брендов")
                if (isAdded && view != null) {
                    updateCatalogInfo()
                    refreshDownloadedKeys()
                    hideLoading()
                    // Показываем полный список всех замеров инстанса
                    showAllMeasurements()
                    // Auto-load saved phone measurement if a phone file was restored
                    pendingPhoneFile?.let { file -> autoLoadSavedPhone(file) }
                }
            } catch (e: Exception) {
                if (isAdded && view != null) {
                    hideLoading()
                    showError(getString(R.string.squig_load_error, e.message ?: "unknown"))
                }
                Timber.e(e, "loadDatabase failed")
            }
        }
    }

    /**
     * Показать полный список всех замеров инстанса (все бренды → все телефоны).
     * Вызывается после загрузки каталога и при очистке поля поиска.
     * Тяжёлые операции (построение списка, DiffUtil, проверка кэша) — в фоне.
     */
    private fun showAllMeasurements() {
        if (database.isEmpty()) {
            showEmptyState(true)
            return
        }

        val db = database
        val instance = currentInstance
        viewLifecycleOwner.lifecycleScope.launch {
            // Фоновое построение списка + проверка кэша
            val (allResults, keys) = withContext(Dispatchers.Default) {
                val allResults = db.flatMap { brand ->
                    brand.phones.map { phone ->
                        SquigLinkSearchEngine.SearchResult(
                            brand = brand.name,
                            phone = phone,
                            score = 0.0
                        )
                    }
                }
                // Параллельно строим множество загруженных ключей
                val channels = instance.channels
                val keysSet = mutableSetOf<String>()
                for (brand in db) {
                    for (phone in brand.phones) {
                        if (SquigMeasurementStore.isAnyChannelDownloaded(instance, phone, channels)) {
                            keysSet.add("${brand.name}::${phone.name}")
                        }
                    }
                }
                allResults to keysSet
            }
            if (!isAdded || view == null) return@launch

            resultsAdapter.updateResults(allResults)
            resultsAdapter.updateDownloadedKeys(keys)
            updateCatalogInfo()

            if (allResults.isEmpty()) {
                showEmptyState(true)
            } else {
                showEmptyState(false)
                statusText.text = getString(R.string.squig_results_count, allResults.size)
                statusText.isVisible = true
            }
        }
    }

    /**
     * Выполнение поиска по введённому запросу.
     * @param query поисковый запрос (уже обрезанный)
     */
    private suspend fun performSearch(query: String) {
        if (database.isEmpty()) {
            statusText.text = getString(R.string.squig_no_results)
            statusText.isVisible = true
            return
        }

        val searchEngine = SquigLinkSearchEngine()
        val results = withContext(Dispatchers.Default) {
            searchEngine.search(query, database)
        }
        // Проверка: пока искали, view могли уничтожить
        if (!isAdded || view == null) return

        resultsAdapter.updateResults(results)
        if (results.isEmpty()) {
            statusText.text = getString(R.string.squig_no_results)
            statusText.isVisible = true
            showEmptyState(true)
        } else {
            statusText.text = getString(R.string.squig_results_count, results.size)
            statusText.isVisible = true
            showEmptyState(false)
        }
    }

    /**
     * Обработка нажатия на результат поиска.
     * Загружает АЧХ измерения (L, R) и целевую кривую —
     * линейный код через suspend-функции.
     *
     * Фикс: measurementFR_L и measurementFR_R хранятся отдельно,
     * на графике L = measurementFR_L (не усреднённый).
     * measurementFR = усреднённый для AutoEQ.
     */
    private fun onSearchResultClicked(result: SquigLinkSearchEngine.SearchResult) {
        selectedPhone = result.phone
        Timber.i("SquigLive: phone selected → ${result.brand} ${result.phone.name} (file='${result.phone.file}', score=${"%.1f".format(result.score)}, channels=${currentInstance.channels})")
        selectedPhoneText.text = getString(R.string.squig_selected_phone, result.brand, result.phone.name)
        selectedPhoneText.isVisible = true

        // Persist phone selection
        savePhoneState(result.brand, result.phone)

        // Сворачиваем список: показываем выбранный замер + "Other"
        resultsAdapter.setSelectedAndCollapse(result)

        showLoading(getString(R.string.squig_loading))

        // Очистка предыдущих данных
        measurementFR_L = null
        measurementFR_R = null
        measurementFR = null
        // targetFR не очищаем — target не зависит от выбранного phone
        // и может быть уже загружен. Будет перезагружен ниже если null.
        autoEqBands = emptyList()
        autoEqPreamp = 0.0
        // Не очищаем workingBands/workingPreamp — Live EQ это независимый PEQ,
        // который должен сохраняться при смене phone. Пользователь может
        // сбросить через Reset, если хочет.
        graphSurface.clearMeasurementData()
        graphSurface.clearMeasurementDataR()
        graphSurface.clearCorrectedFR()
        // Очищаем filter curves только если нет workingBands
        if (workingBands.isEmpty()) {
            graphSurface.setBands(ParametricEqBandList(), 0.0)
        }

        val channels = currentInstance.channels

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                isLoadingData = true
                val normHz = currentInstance.defaultNormHz

                // Загрузка первого канала (кэш-первичная: сначала локальная БД, потом сеть)
                val firstChannel = channels.first()
                Timber.i("SquigLive: loading FR channel '$firstChannel' for ${result.phone.name}")
                val frL = try {
                    currentClient.loadFrequencyResponseWithStore(result.phone, firstChannel, currentInstance)
                } catch (e: Exception) {
                    // First channel failed (404) — try the other channel
                    val fallbackChannel = if (firstChannel == "R") "L" else "R"
                    Timber.w("SquigLive: channel '$firstChannel' failed (404), trying '$fallbackChannel': ${e.message}")
                    currentClient.loadFrequencyResponseWithStore(result.phone, fallbackChannel, currentInstance)
                }
                // frL уже нормализован в loadFrequencyResponseWithStore
                measurementFR_L = frL
                Timber.i("SquigLive: FR L loaded — ${frL.frequencies.size} points, normalized at ${normHz}Hz")

                // Отображение L на графике
                val (freqsL, splL) = frL.toFloatArrays()
                graphSurface.setMeasurementData(freqsL, splL)

                // Обновляем индикатор статуса загрузки для выбранного замера
                refreshDownloadedKeys()

                // Если есть второй канал, загружаем (необязательно — R может отсутствовать)
                if (channels.size > 1) {
                    try {
                        Timber.i("SquigLive: loading FR channel '${channels[1]}' for ${result.phone.name}")
                        val frR = currentClient.loadFrequencyResponseWithStore(result.phone, channels[1], currentInstance)
                        measurementFR_R = frR
                        Timber.i("SquigLive: FR R loaded — ${frR.frequencies.size} points")
                        val (freqsR, splR) = frR.toFloatArrays()
                        graphSurface.setMeasurementDataR(freqsR, splR)

                        // Усреднённая АЧХ для AutoEQ
                        measurementFR = frL.average(frR)
                        Timber.i("SquigLive: averaged FR (L+R)/2 computed for AutoEQ")
                    } catch (e: Exception) {
                        Timber.w("SquigLive: R channel failed, using L only: ${e.message}")
                        measurementFR = frL
                    }
                } else {
                    // Только один канал
                    measurementFR = frL
                }

                // Загрузка целевой кривой (если ещё не загружена — загружаем)
                ensureActive()
                if (targetFR == null) {
                    try {
                        val target = targetClient.loadTargetCurveWithCache(selectedTargetName, targetInstance)
                        targetFR = target.normalize(normHz)
                    } catch (e: Exception) {
                        Timber.w("Целевая кривая '$selectedTargetName' не загружена: ${e.message}")
                    }
                }
                // Отображаем target (со сдвигом preamp если есть активные полосы)
                if (targetFR != null) {
                    val displayTarget = if (workingPreamp != 0.0) targetFR!!.applyPreamp(workingPreamp) else targetFR!!
                    val (freqsT, splT) = displayTarget.toFloatArrays()
                    graphSurface.setTargetCurve(freqsT, splT)
                }

                if (!isAdded || view == null) return@launch

                hideLoading()
                isLoadingData = false

                // If Live EQ bands were restored from a previous session, draw
                // them now (measurement is available) and compute corrected FR.
                if (workingBands.isNotEmpty()) {
                    graphSurface.setBands(workingBands, workingPreamp)
                    updateCorrectedFR()
                }

                updateActionButtons()
                statusText.text = getString(R.string.squig_measurement_loaded, result.phone.name)
                statusText.isVisible = true
            } catch (e: Exception) {
                if (!isAdded || view == null) return@launch
                hideLoading()
                isLoadingData = false
                showError(getString(R.string.squig_load_error, e.message ?: "unknown"))
                Timber.e(e, "onSearchResultClicked failed")
            }
        }
    }

    /**
     * Включение/выключение кнопок действий в зависимости от состояния.
     */
    private fun updateActionButtons() {
        val hasMeasurement = measurementFR != null
        val hasTarget = targetFR != null
        val hasBands = workingBands.isNotEmpty() || autoEqBands.isNotEmpty()

        autoEqButton.isEnabled = hasMeasurement && hasTarget && !isLoadingData
        // Live EQ is an independent PEQ plugin — available regardless of
        // whether a measurement is loaded. The user can add/edit bands and
        // hear them in real time even without a squig.link measurement.
        liveEqButton.isEnabled = !isLoadingData
        applyButton.isEnabled = hasBands && !isLoadingData
        resetButton.isEnabled = hasBands && !isLoadingData
    }

    /**
     * Перезагрузка целевой кривой при смене target в spinner.
     * Используется, когда замер уже загружен и пользователь сменил target.
     * Очищает старые AutoEQ-полоса и corrected FR.
     */
    private fun reloadTargetCurve() {
        showLoading(getString(R.string.squig_loading))

        // Очистка старых AutoEQ-полос и corrected FR (AutoEQ зависит от target).
        // workingBands (Live EQ) не очищаем — это независимый PEQ.
        autoEqBands = emptyList()
        autoEqPreamp = 0.0
        graphSurface.clearCorrectedFR()

        loadTargetCurveData()
    }

    /**
     * Загрузка данных целевой кривой и отображение на графике.
     * Target рисуется всегда — даже без замера.
     * Использует offline cache: при отсутствии сети — кэшированный TSV.
     * Сдвигается на preamp если есть активные полосы.
     */
    private fun loadTargetCurveData() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val normHz = targetInstance.defaultNormHz
                val target = targetClient.loadTargetCurveWithCache(selectedTargetName, targetInstance)
                targetFR = target.normalize(normHz)

                // Сдвигаем target на preamp если есть активные полосы
                val displayTarget = if (workingPreamp != 0.0) targetFR!!.applyPreamp(workingPreamp) else targetFR!!
                val (freqsT, splT) = displayTarget.toFloatArrays()
                graphSurface.setTargetCurve(freqsT, splT)

                if (!isAdded || view == null) return@launch

                hideLoading()
                updateActionButtons()
                if (measurementFR == null) {
                    statusText.text = getString(R.string.squig_target_loaded, selectedTargetName)
                    statusText.isVisible = true
                }
            } catch (e: Exception) {
                if (!isAdded || view == null) return@launch
                hideLoading()
                // Target может быть недоступен на данном инстансе (404) или нет сети.
                // Не показываем ошибку — замер всё равно можно загрузить без target.
                targetFR = null
                Timber.w("loadTargetCurveData: target '$selectedTargetName' unavailable: ${e.message}")
                updateActionButtons()
            }
        }
    }

    /**
     * Запуск алгоритма AutoEQ.
     * Сначала показывает диалог настройки параметров, затем вычисляет PEQ-полосы.
     * Отображает corrected FR на графике.
     */
    private var autoEqRunning = false

    private fun performAutoEq() {
        if (autoEqRunning) return
        val measurement = measurementFR ?: return
        val target = targetFR ?: run {
            requireContext().toast(getString(R.string.squig_target_label))
            return
        }

        showAutoEqConfigDialog(measurement, target)
    }

    /**
     * Диалог настройки параметров AutoEQ.
     * Позволяет настроить: max bands, max boost, flatness target, match range, Q limits, Nelder-Mead.
     * Кнопка «Run» запускает алгоритм с выбранными параметрами.
     */
    private fun showAutoEqConfigDialog(
        measurement: FrequencyResponse,
        target: FrequencyResponse
    ) {
        val ctx = requireContext()
        val keys = arrayOf(
            getString(R.string.squig_autoeq_cfg_max_bands),
            getString(R.string.squig_autoeq_cfg_max_boost),
            getString(R.string.squig_autoeq_cfg_flatness),
            getString(R.string.squig_autoeq_cfg_range_start),
            getString(R.string.squig_autoeq_cfg_range_end),
            getString(R.string.squig_autoeq_cfg_nelder_mead)
        )
        val values = arrayOf(
            autoEqConfig.maxFilters.toString(),
            autoEqConfig.individualMaxBoost.toString(),
            autoEqConfig.flatnessTarget.toString(),
            autoEqConfig.matchRangeStart.toInt().toString(),
            autoEqConfig.matchRangeEnd.toInt().toString(),
            if (autoEqConfig.useNelderMead) "ON" else "OFF"
        )

        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.squig_autoeq_config)
            .setItems(keys.zip(values).map { "${it.first}: ${it.second}" }.toTypedArray()) { _, which ->
                if (which == 5) {
                    // Toggle Nelder-Mead
                    autoEqConfig = autoEqConfig.copy(useNelderMead = !autoEqConfig.useNelderMead)
                    showAutoEqConfigDialog(measurement, target)
                } else {
                    showConfigValueEditor(keys[which], which, measurement, target)
                }
            }
            .setPositiveButton(R.string.squig_autoeq_run) { _, _ ->
                runAutoEq(measurement, target)
            }
            .setNegativeButton(R.string.peq_cancel, null)
            .show()
    }

    /**
     * Редактор одного числового параметра AutoEQ.
     */
    private fun showConfigValueEditor(
        label: String,
        index: Int,
        measurement: FrequencyResponse,
        target: FrequencyResponse
    ) {
        val current = when (index) {
            0 -> autoEqConfig.maxFilters.toString()
            1 -> autoEqConfig.individualMaxBoost.toString()
            2 -> autoEqConfig.flatnessTarget.toString()
            3 -> autoEqConfig.matchRangeStart.toInt().toString()
            4 -> autoEqConfig.matchRangeEnd.toInt().toString()
            else -> ""
        }

        val input = com.google.android.material.textfield.TextInputEditText(requireContext()).apply {
            setText(current)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(label)
            .setView(input)
            .setPositiveButton(R.string.peq_done) { _, _ ->
                val v = input.text?.toString()?.toDoubleOrNull() ?: return@setPositiveButton
                when (index) {
                    0 -> autoEqConfig = autoEqConfig.copy(maxFilters = v.toInt().coerceIn(1, 64))
                    1 -> autoEqConfig = autoEqConfig.copy(individualMaxBoost = v.coerceIn(0.0, 36.0))
                    2 -> autoEqConfig = autoEqConfig.copy(flatnessTarget = v.coerceIn(0.1, 10.0))
                    3 -> autoEqConfig = autoEqConfig.copy(matchRangeStart = v.coerceIn(10.0, 1000.0))
                    4 -> autoEqConfig = autoEqConfig.copy(matchRangeEnd = v.coerceIn(1000.0, 24000.0))
                }
                showAutoEqConfigDialog(measurement, target)
            }
            .setNegativeButton(R.string.peq_cancel, null)
            .show()
    }

    /**
     * Запуск алгоритма AutoEQ с текущей конфигурацией.
     */
    private fun runAutoEq(measurement: FrequencyResponse, target: FrequencyResponse) {
        if (autoEqRunning) return
        autoEqRunning = true
        showLoading(getString(R.string.squig_loading))
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                Timber.i("SquigLive: AutoEQ started — measurement=${measurement.frequencies.size}pts, target='$selectedTargetName' (${target.frequencies.size}pts), config: maxFilters=${autoEqConfig.maxFilters}, flatness=${autoEqConfig.flatnessTarget}, matchRange=${autoEqConfig.matchRangeStart}-${autoEqConfig.matchRangeEnd}Hz")
                val engine = SquigAutoEqEngine()
                val result = withContext(Dispatchers.Default) {
                    engine.run(measurement, target, autoEqConfig)
                }
                if (!isAdded || view == null) return@launch

                Timber.i("SquigLive: AutoEQ result — ${result.bands.size} bands, preamp=${"%.2f".format(result.preampDb)}dB, maxDev=${"%.2f".format(result.maxDeviation)}dB")

                autoEqBands = result.bands
                autoEqPreamp = result.preampDb
                workingPreamp = autoEqPreamp

                // Обновление рабочих полос
                workingBands.clear()
                workingBands.addAll(result.bands)

                // Persist AutoEQ config
                saveAutoEqConfig()

                // Отображение фильтра на графике
                graphSurface.setBands(workingBands, workingPreamp)

                // Вычисление и отображение corrected FR (включая сдвиг target)
                updateCorrectedFR()

                // Real-time PEQ preview: apply bands to DSP engine immediately
                applyRealTimePeqPreview()

                hideLoading()
                statusText.text = getString(
                    R.string.squig_autoeq_done,
                    result.bands.size,
                    result.maxDeviation
                )
                statusText.isVisible = true

                updateActionButtons()
                applyButton.isEnabled = true

                Timber.i("AutoEQ выполнен: ${result.bands.size} полос")
            } catch (e: Exception) {
                if (!isAdded || view == null) return@launch
                hideLoading()
                showError(e.message ?: "unknown")
                Timber.e(e, "AutoEQ failed")
            } finally {
                autoEqRunning = false
            }
        }
    }

    /**
     * Вычисление corrected FR (measurement + filter response + preamp) и отображение на графике.
     * Также сдвигает target на preamp, чтобы corrected FR и target были на одном уровне.
     * Вызывается после AutoEQ и после редактирования в Live EQ.
     */
    private fun updateCorrectedFR() {
        val measurement = measurementFR ?: return
        if (workingBands.isEmpty()) {
            graphSurface.clearCorrectedFR()
            // Восстанавливаем target без сдвига
            restoreTargetCurve()
            return
        }

        val calculator = ParametricEqResponseCalculator()
        val filterResponse = calculator.compute(workingBands.toList(), workingPreamp)
        val correctedFR = measurement.applyCorrection(
            filterResponse.leftResponseDb,
            filterResponse.frequencies
        ).applyPreamp(workingPreamp)
        val (freqs, spls) = correctedFR.toFloatArrays()
        graphSurface.setCorrectedFR(freqs, spls)

        // Сдвигаем target на preamp для совпадения с corrected FR
        val targetShifted = targetFR?.applyPreamp(workingPreamp)
        if (targetShifted != null) {
            val (tFreqs, tSpl) = targetShifted.toFloatArrays()
            graphSurface.setTargetCurve(tFreqs, tSpl)
        }
    }

    /**
     * Восстановление target без сдвига preamp (preamp = 0).
     */
    private fun restoreTargetCurve() {
        val target = targetFR ?: return
        val (tFreqs, tSpl) = target.toFloatArrays()
        graphSurface.setTargetCurve(tFreqs, tSpl)
    }

    /**
     * Сброс PEQ-полос и corrected FR.
     */
    private fun resetEq() {
        workingBands.clear()
        workingPreamp = 0.0
        autoEqBands = emptyList()
        autoEqPreamp = 0.0
        graphSurface.setBands(ParametricEqBandList(), 0.0)
        graphSurface.clearCorrectedFR()
        // Восстанавливаем target без сдвига preamp
        restoreTargetCurve()
        updateActionButtons()
        statusText.text = getString(R.string.squig_reset_done)
        statusText.isVisible = true
        // Real-time PEQ preview: disable squig PEQ and clear bands in DSP engine.
        // Also sync key_squig_enable so the card switch turns off.
        val ctx = context ?: return
        try {
            val squigPref = ctx.getSharedPreferences(Constants.PREF_SQUIG, Context.MODE_MULTI_PROCESS)
            squigPref.edit()
                .putBoolean(getString(R.string.key_squig_peq_enable), false)
                .putBoolean(getString(R.string.key_squig_enable), false)
                .apply()
            ctx.sendLocalBroadcast(Intent(Constants.ACTION_SQUIG_PEQ_CHANGED))
        } catch (e: Exception) {
            Timber.e(e, "resetEq: failed to sync squig enable state")
        }
    }

    /**
     * Открытие LiveEqBottomSheet для интерактивного редактирования PEQ-полос.
     * Передаёт оверлеи (measurement L/R, target) для отображения на графике Live EQ.
     * Колбэк onCorrectedUpdate обновляет corrected FR на главном графике в реальном времени.
     */
    private fun openLiveEq() {
        if (workingBands.isEmpty()) {
            // Создаём одну полосу по умолчанию, если нет ни рабочих, ни AutoEQ полос
            if (autoEqBands.isNotEmpty()) {
                workingBands.clear()
                workingBands.addAll(autoEqBands)
            } else {
                workingBands.add(ParametricEqBand(
                    frequency = 1000.0,
                    gain = 0.0,
                    q = 1.0,
                    filterType = ParametricEqFilterType.PEAKING,
                    channel = ParametricEqChannel.LEFT_RIGHT
                ))
            }
        }

        // Подготовка оверлеев для графика Live EQ
        val (mFreqs, mSpl) = measurementFR_L?.toFloatArrays() ?: (null to null)
        val (mRFreqs, mRSpl) = measurementFR_R?.toFloatArrays() ?: (null to null)
        val (tFreqs, tSpl) = targetFR?.toFloatArrays() ?: (null to null)

        LiveEqBottomSheet.newInstance(
            bands = workingBands,
            preampDb = workingPreamp,
            onLiveUpdate = { bands ->
                if (isAdded && view != null) {
                    graphSurface.setBands(bands, workingPreamp)
                    // Real-time DSP preview on every slider drag — not just on release.
                    // Pass the live copy directly; do NOT mutate workingBands here
                    // (it is the same object as BottomSheet.source, and mutating it
                    // would make commitChanges detect no diff and skip onCommit).
                    applyRealTimePeqPreview(bands = bands)
                }
            },
            onPreampUpdate = { newPreamp ->
                workingPreamp = newPreamp
                // Real-time DSP preview when preamp slider moves
                applyRealTimePeqPreview(preamp = newPreamp)
            },
            onCommit = {
                if (isAdded && view != null) {
                    graphSurface.setBands(workingBands, workingPreamp)
                    updateCorrectedFR()
                    updateActionButtons()
                    // Real-time PEQ preview: apply bands to DSP engine on LiveEQ commit
                    applyRealTimePeqPreview()
                }
            },
            overlayMeasurementFreqs = mFreqs,
            overlayMeasurementSpl = mSpl,
            overlayMeasurementRFreqs = mRFreqs,
            overlayMeasurementRSpl = mRSpl,
            overlayTargetFreqs = tFreqs,
            overlayTargetSpl = tSpl,
            onCorrectedUpdate = { bands, preamp ->
                if (isAdded && view != null) {
                    graphSurface.setBands(bands, preamp)
                    val measurement = measurementFR
                    if (measurement != null) {
                        val calculator = ParametricEqResponseCalculator()
                        val filterResponse = calculator.compute(bands.toList(), preamp)
                        val correctedFR = measurement.applyCorrection(
                            filterResponse.leftResponseDb,
                            filterResponse.frequencies
                        ).applyPreamp(preamp)
                        val (freqs, spls) = correctedFR.toFloatArrays()
                        graphSurface.setCorrectedFR(freqs, spls)
                    }
                    // Сдвигаем target на preamp для совпадения с corrected FR
                    val targetShifted = targetFR?.applyPreamp(preamp)
                    if (targetShifted != null) {
                        val (tFreqs2, tSpl2) = targetShifted.toFloatArrays()
                        graphSurface.setTargetCurve(tFreqs2, tSpl2)
                    }
                }
            }
        ).show(parentFragmentManager, "squig_live_eq")
    }

    /**
     * Применение вычисленных PEQ-полос к настройкам параметрического эквалайзера.
     * Сохраняет полосы и preamp в SharedPreferences и отправляет broadcast.
     *
     * Фикс: preamp берётся из workingPreamp напрямую (если пользователь
     * изменил его в Live EQ, это учитывается; если не менял — это autoEqPreamp).
     * apply() вместо commit() — не блокирует UI поток.
     */
    @SuppressLint("ApplySharedPref")
    private fun applyToPeq() {
        val bands = if (workingBands.isNotEmpty()) workingBands else {
            if (autoEqBands.isEmpty()) return
            workingBands.clear()
            workingBands.addAll(autoEqBands)
            workingBands
        }

        if (bands.isEmpty()) {
            requireContext().toast(getString(R.string.squig_no_results))
            return
        }

        try {
            // preamp: workingPreamp если был Live EQ, иначе autoEqPreamp
            val preamp = workingPreamp
            val prefs = requireContext().getSharedPreferences(Constants.PREF_SQUIG, Context.MODE_MULTI_PROCESS)
            prefs.edit()
                .putString(getString(R.string.key_squig_peq_bands), bands.serialize())
                .putFloat(getString(R.string.key_squig_peq_preamp), preamp.toFloat())
                .putBoolean(getString(R.string.key_squig_peq_enable), true)
                .putBoolean(getString(R.string.key_squig_enable), true)
                .apply()

            requireContext().sendLocalBroadcast(Intent(Constants.ACTION_SQUIG_PEQ_CHANGED))
            requireContext().toast(getString(R.string.squig_applied_to_peq, bands.size))
            Timber.i("SquigLive: applied ${bands.size} bands to Squig PEQ (preamp=${"%.2f".format(preamp)}dB), bands=[${bands.joinToString { "${"%.0f".format(it.frequency)}Hz ${"%+.1f".format(it.gain)}dB Q${"%.1f".format(it.q)}" }}]")
        } catch (e: Exception) {
            requireContext().toast(e.message ?: "error")
            Timber.e(e, "applyToPeq failed")
        }
    }

    // ── Вспомогательные методы UI ─────────────────────────────────────────

    /** Показать индикатор загрузки с текстом. */
    private fun showLoading(message: String) {
        progressBar.visibility = View.VISIBLE
        statusText.text = message
        statusText.visibility = View.VISIBLE
    }

    /** Скрыть индикатор загрузки. */
    private fun hideLoading() {
        progressBar.visibility = View.GONE
    }

    /** Показать сообщение об ошибке. */
    private fun showError(message: String) {
        statusText.text = message
        statusText.visibility = View.VISIBLE
        Timber.e(message)
    }

    /** Показать/скрыть пустое состояние в списке результатов. */
    private fun showEmptyState(show: Boolean) {
        emptyStateText.isVisible = show
        resultsRecyclerView.isVisible = !show
    }

    // ── Offline cache: dump & freshness ───────────────────────────────────

    /**
     * Показать диалог управления offline-кэшем.
     *
     * Позволяет:
     * - Dump all: скачать и сохранить все инстансы (phone_book + targets)
     * - Check freshness: проверить актуальность кэшированных данных
     * - Clear cache: очистить все кэшированные данные
     *
     * Показывает текущий размер кэша.
     */
    private fun showCacheMenu() {
        val cacheSize = SquigLinkCacheManager.cacheSizeFormatted()
        val measSize = SquigMeasurementStore.cacheSizeBytes()
        val measSizeStr = when {
            measSize < 1024 -> "$measSize B"
            measSize < 1024 * 1024 -> "${measSize / 1024} KB"
            else -> String.format("%.1f MB", measSize / (1024.0 * 1024.0))
        }
        val items = arrayOf(
            getString(R.string.squig_cache_dump),
            getString(R.string.squig_cache_freshness),
            getString(R.string.squig_cache_clear, "$cacheSize + $measSizeStr")
        )
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.squig_cache_menu)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> performDumpAll()
                    1 -> showFreshnessReport()
                    2 -> performClearCache()
                }
            }
            .setNegativeButton(R.string.peq_cancel, null)
            .show()
    }

    /**
     * Dump all: скачать и кэшировать все инстансы, phone_books и target curves.
     * Долгая операция — выполняется в фоне с прогресс-индикатором.
     */
    private fun performDumpAll() {
        showLoading(getString(R.string.squig_cache_dump_title))
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    SquigLinkCacheManager.dumpAll(
                        clientFactory = { inst -> SquigLinkClient(inst) },
                        instances = instances,
                        onProgress = { current, total, msg ->
                            if (isAdded && view != null) {
                                requireActivity().runOnUiThread {
                                    if (isAdded && view != null) {
                                        progressBar.visibility = View.VISIBLE
                                        statusText.text = getString(R.string.squig_cache_dump_progress, msg, current, total)
                                        statusText.visibility = View.VISIBLE
                                    }
                                }
                            }
                        }
                    )
                }
                if (!isAdded || view == null) return@launch
                hideLoading()
                val msg = if (result.success) {
                    getString(R.string.squig_cache_dump_done, result.summary)
                } else {
                    getString(R.string.squig_cache_dump_done, result.summary) +
                            "\n" + result.errorMessages.take(5).joinToString("\n")
                }
                statusText.text = msg
                statusText.visibility = View.VISIBLE
                Timber.i("Dump all: $msg")
            } catch (e: Exception) {
                if (!isAdded || view == null) return@launch
                hideLoading()
                showError(getString(R.string.squig_cache_dump_failed, e.message ?: "unknown"))
                Timber.e(e, "Dump all failed")
            }
        }
    }

    /**
     * Показать отчёт об актуальности кэшированных данных.
     */
    private fun showFreshnessReport() {
        viewLifecycleOwner.lifecycleScope.launch {
            val report = withContext(Dispatchers.Default) {
                SquigLinkCacheManager.checkFreshness(instances)
            }
            if (!isAdded || view == null) return@launch

            val sb = StringBuilder()
            sb.append("Cache size: ${SquigLinkCacheManager.cacheSizeFormatted()}\n\n")

            // SquigSites
            sb.append("Site list: ")
            sb.append(when {
                !report.hasSquigSites -> "NOT CACHED"
                report.squigSitesFresh -> "FRESH (${formatAge(report.squigSitesAgeMs)})"
                else -> "STALE (${formatAge(report.squigSitesAgeMs)})"
            })
            sb.append("\n\n")

            // Per-instance
            sb.append("Instances (${report.perInstance.size}):\n")
            for (inst in report.perInstance) {
                sb.append("  ${inst.instanceName}:\n")
                sb.append("    Phonebook: ")
                sb.append(when {
                    !inst.hasPhoneBook -> "NOT CACHED"
                    inst.phoneBookFresh -> "FRESH (${formatAge(inst.phoneBookAgeMs)})"
                    else -> "STALE (${formatAge(inst.phoneBookAgeMs)})"
                })
                sb.append("\n")
                sb.append("    Targets: ")
                sb.append(when {
                    !inst.hasTargetNames -> "NOT CACHED"
                    inst.targetNamesFresh -> "FRESH (${formatAge(inst.targetNamesAgeMs)})"
                    else -> "STALE (${formatAge(inst.targetNamesAgeMs)})"
                })
                sb.append(" (${inst.cachedTargetCount} curves cached)\n")
            }

            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.squig_cache_freshness_title)
                .setMessage(sb.toString())
                .setPositiveButton(R.string.peq_done, null)
                .show()
        }
    }

    /** Очистить весь offline-кэш. */
    private fun performClearCache() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.squig_cache_clear_title)
            .setMessage(getString(R.string.squig_cache_clear_msg, SquigLinkCacheManager.cacheSizeFormatted()))
            .setPositiveButton(R.string.peq_done) { _, _ ->
                SquigLinkCacheManager.clearAll()
                SquigMeasurementStore.clearAll()
                requireContext().toast(getString(R.string.squig_cache_cleared))
                statusText.text = getString(R.string.squig_cache_cleared)
                statusText.visibility = View.VISIBLE
                refreshDownloadedKeys()
                updateCatalogInfo()
            }
            .setNegativeButton(R.string.peq_cancel, null)
            .show()
    }

    /** Форматировать возраст в человекочитаемый вид. */
    private fun formatAge(ageMs: Long): String {
        if (ageMs == Long.MAX_VALUE) return "never"
        val hours = ageMs / 3_600_000
        return when {
            hours < 1 -> "${ageMs / 60_000}m ago"
            hours < 24 -> "${hours}h ago"
            else -> "${hours / 24}d ago"
        }
    }

    // ── Persistence + real-time preview helpers ──────────────────────────

    /**
     * Save the current instance selection to SharedPreferences.
     */
    private fun saveInstanceState() {
        val prefs = squigPrefs ?: return
        prefs.edit()
            .putString(getString(R.string.key_squig_instance), currentInstance.name)
            .apply()
    }

    /**
     * Save the current target selection to SharedPreferences.
     */
    private fun saveTargetState() {
        val prefs = squigPrefs ?: return
        prefs.edit()
            .putString(getString(R.string.key_squig_target), selectedTargetName)
            .apply()
    }

    /**
     * Save the selected phone (name, brand, file) to SharedPreferences.
     */
    private fun savePhoneState(brand: String, phone: me.timschneeberger.rootlessjamesdsp.model.squig.SquigLinkPhone) {
        val prefs = squigPrefs ?: return
        prefs.edit()
            .putString(getString(R.string.key_squig_phone_name), phone.name)
            .putString(getString(R.string.key_squig_phone_brand), brand)
            .putString(getString(R.string.key_squig_phone_file), phone.file)
            .apply()
    }

    /**
     * Save the AutoEQ configuration as JSON to SharedPreferences.
     */
    private fun saveAutoEqConfig() {
        val prefs = squigPrefs ?: return
        try {
            val json = Gson().toJson(autoEqConfig)
            prefs.edit()
                .putString(getString(R.string.key_squig_autoeq_config), json)
                .apply()
        } catch (e: Exception) {
            Timber.w(e, "Failed to save AutoEQ config")
        }
    }

    /**
     * Restore persisted user state (autoeq config + instance/target/phone
     * names) after view recreation. The instance/target/phone names are
     * stored in the pending* fields and applied inside the async
     * loadSquigSites/loadTargetCurves/loadDatabase callbacks once the
     * spinners are actually populated — restoring here directly would be a
     * no-op because the adapters are still empty.
     */
    private fun restorePersistedState() {
        val prefs = squigPrefs ?: return

        // Restore AutoEQ config
        try {
            val configJson = prefs.getString(getString(R.string.key_squig_autoeq_config), null)
            if (!configJson.isNullOrEmpty()) {
                autoEqConfig = Gson().fromJson(configJson, SquigAutoEqEngine.Config::class.java)
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to restore AutoEQ config")
        }

        // Restore working bands + preamp from PREF_SQUIG so the last Live EQ
        // / AutoEQ session survives fragment recreation and app restart.
        // This keeps the graph, filters, and DSP state consistent across sessions.
        try {
            val savedBandsStr = prefs.getString(getString(R.string.key_squig_peq_bands), null)
            val savedPreamp = prefs.getFloat(getString(R.string.key_squig_peq_preamp), 0f)
            val savedEnabled = prefs.getBoolean(getString(R.string.key_squig_peq_enable), false)
            if (!savedBandsStr.isNullOrEmpty() && savedEnabled) {
                workingBands.deserialize(savedBandsStr)
                workingPreamp = savedPreamp.toDouble()
                Timber.i("SquigLive: restored ${workingBands.size} working bands (preamp=${"%.2f".format(workingPreamp)}dB) from prefs")
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to restore working bands")
        }

        // Stash saved names; applied once async loaders finish populating spinners
        pendingInstanceName = prefs.getString(getString(R.string.key_squig_instance), null)
        pendingTargetName = prefs.getString(getString(R.string.key_squig_target), null)
        pendingPhoneName = prefs.getString(getString(R.string.key_squig_phone_name), null)
        pendingPhoneBrand = prefs.getString(getString(R.string.key_squig_phone_brand), null)
        pendingPhoneFile = prefs.getString(getString(R.string.key_squig_phone_file), null)

        // Restore target instance selection (separate from measurement instance)
        // Note: actual spinner selection is applied in loadSquigSites() callback
        // where the adapter is populated. onItemSelected callback will set
        // targetInstance/targetClient and trigger loadTargetCurves().
        // Here we just leave the saved value in prefs for loadSquigSites() to read.

        // If target name restored, set selectedTargetName so loadTargetCurves
        // picks it up when it builds the adapter.
        pendingTargetName?.let { selectedTargetName = it }

        // Show saved phone label immediately (measurement auto-loaded later)
        if (!pendingPhoneName.isNullOrEmpty() && !pendingPhoneBrand.isNullOrEmpty()) {
            selectedPhoneText.text = getString(R.string.squig_selected_phone, pendingPhoneBrand, pendingPhoneName)
            selectedPhoneText.isVisible = true
        }

        // If we restored working bands, draw them on the graph immediately.
        // The filter curve renders even before measurement/target are loaded.
        if (workingBands.isNotEmpty()) {
            graphSurface.setBands(workingBands, workingPreamp)
        }
    }

    /**
     * Auto-load the saved phone's FR measurement after the database load
     * completes. Reconstructs a SquigLinkPhone from the saved file/name and
     * triggers the same loading path as a manual tap on a search result.
     */
    private fun autoLoadSavedPhone(file: String) {
        val name = pendingPhoneName ?: return
        val brand = pendingPhoneBrand ?: return
        if (file.isEmpty()) return

        val phone = me.timschneeberger.rootlessjamesdsp.model.squig.SquigLinkPhone(
            name = name,
            file = file
        )
        val result = SquigLinkSearchEngine.SearchResult(
            brand = brand,
            phone = phone,
            score = 0.0
        )
        // Clear pending so a subsequent instance change doesn't re-trigger
        pendingPhoneFile = null
        pendingPhoneName = null
        pendingPhoneBrand = null
        onSearchResultClicked(result)
    }

    /**
     * Real-time PEQ preview: write the current working bands + preamp to the
     * PREF_SQUIG namespace (independent from main PEQ), force
     * key_squig_peq_enable=true, and broadcast ACTION_SQUIG_PEQ_CHANGED so the
     * DSP engine applies them immediately. This lets the user hear changes as
     * they edit, before pressing Apply. Does NOT touch the main PREF_PEQ
     * namespace at all.
     */
    @SuppressLint("ApplySharedPref")
    private fun applyRealTimePeqPreview(enable: Boolean = true, bands: ParametricEqBandList? = null, preamp: Double? = null) {
        val ctx = context ?: return
        try {
            val useBands = bands ?: workingBands
            val usePreamp = preamp ?: workingPreamp
            val effectiveBands = if (enable && useBands.isNotEmpty()) useBands else ParametricEqBandList()
            val effectivePreamp = if (enable) usePreamp else 0.0
            val squigPref = ctx.getSharedPreferences(Constants.PREF_SQUIG, Context.MODE_MULTI_PROCESS)
            // Sync both keys: key_squig_enable (card switch) and key_squig_peq_enable (engine)
            val effectiveEnable = enable && effectiveBands.isNotEmpty()
            squigPref.edit()
                .putString(getString(R.string.key_squig_peq_bands), effectiveBands.serialize())
                .putFloat(getString(R.string.key_squig_peq_preamp), effectivePreamp.toFloat())
                .putBoolean(getString(R.string.key_squig_peq_enable), effectiveEnable)
                .putBoolean(getString(R.string.key_squig_enable), effectiveEnable)
                .apply()

            ctx.sendLocalBroadcast(Intent(Constants.ACTION_SQUIG_PEQ_CHANGED))
            Timber.d("SquigLive: real-time Squig PEQ preview applied (enable=$enable, ${effectiveBands.size} bands, preamp=${"%.2f".format(effectivePreamp)}dB)")
        } catch (e: Exception) {
            Timber.e(e, "SquigLive: failed to apply real-time Squig PEQ preview")
        }
    }

    // ── Measurement store / batch download ───────────────────────────────

    /**
     * Обновить catalogInfo с количеством брендов и загруженных замеров.
     */
    private fun updateCatalogInfo() {
        val brandCount = database.size
        val downloadedCount = SquigMeasurementStore.countDownloadedForInstance(currentInstance)
        if (downloadedCount > 0) {
            catalogInfo.text = getString(R.string.squig_catalog_info, brandCount) +
                " · $downloadedCount ✓"
        } else {
            catalogInfo.text = getString(R.string.squig_catalog_info, brandCount)
        }
    }

    /**
     * Обновить индикаторы загруженных замеров в адаптере.
     * Строит множество ключей "${brand}::${phone.name}" для всех загруженных
     * замеров текущего инстанса.
     */
    /**
     * Обновить индикаторы загруженных замеров в адаптере.
     * Проверка кэша для каждого телефона — в фоновом потоке.
     */
    private fun refreshDownloadedKeys() {
        val db = database
        val instance = currentInstance
        viewLifecycleOwner.lifecycleScope.launch {
            val keys = withContext(Dispatchers.Default) {
                val channels = instance.channels
                val keysSet = mutableSetOf<String>()
                for (brand in db) {
                    for (phone in brand.phones) {
                        if (SquigMeasurementStore.isAnyChannelDownloaded(instance, phone, channels)) {
                            keysSet.add("${brand.name}::${phone.name}")
                        }
                    }
                }
                keysSet
            }
            if (!isAdded || view == null) return@launch
            resultsAdapter.updateDownloadedKeys(keys)
            updateCatalogInfo()
        }
    }

    /**
     * Диалог выбора типа пакетной загрузки:
     * - Download all measurements (this instance)
     * - Download all measurements (all instances)
     */
    private fun showDownloadAllDialog() {
        val items = arrayOf(
            getString(R.string.squig_download_instance),
            getString(R.string.squig_download_all_instances)
        )
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.squig_download_choice_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> performDownloadInstanceMeasurements()
                    1 -> performDownloadAllInstances()
                }
            }
            .setNegativeButton(R.string.peq_cancel, null)
            .show()
    }

    /**
     * Многопоточная загрузка всех замеров текущего инстанса.
     * Загружает все АЧХ-файлы (L, R) параллельно с ограничением параллелизма,
     * а также все target curves. Уже загруженные замеры пропускаются.
     */
    private fun performDownloadInstanceMeasurements() {
        if (database.isEmpty()) {
            requireContext().toast(getString(R.string.squig_loading))
            return
        }

        showLoading(getString(R.string.squig_download_running))
        val instance = currentInstance
        val brands = database
        val targetNames = targetCurves

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    SquigBatchDownloader.downloadInstanceMeasurements(
                        clientFactory = { inst -> SquigLinkClient(inst) },
                        instance = instance,
                        brands = brands,
                        targetNames = targetNames,
                        onProgress = { current, total, msg ->
                            if (isAdded && view != null) {
                                requireActivity().runOnUiThread {
                                    if (isAdded && view != null) {
                                        progressBar.visibility = View.VISIBLE
                                        statusText.text = getString(
                                            R.string.squig_download_progress, msg, current, total
                                        )
                                        statusText.visibility = View.VISIBLE
                                    }
                                }
                            }
                        }
                    )
                }
                if (!isAdded || view == null) return@launch

                hideLoading()
                refreshDownloadedKeys()
                updateCatalogInfo()

                val msg = getString(R.string.squig_download_done, result.summary)
                statusText.text = msg
                statusText.visibility = View.VISIBLE
                Timber.i("DownloadInstance: $msg")
            } catch (e: Exception) {
                if (!isAdded || view == null) return@launch
                hideLoading()
                showError(getString(R.string.squig_download_failed, e.message ?: "unknown"))
                Timber.e(e, "DownloadInstance failed")
            }
        }
    }

    /**
     * Многопоточная загрузка всех замеров всех инстансов.
     * Для каждого инстанса загружает phone_book (если нужно), все АЧХ-файлы
     * и все target curves. Долгая операция с прогресс-индикатором.
     */
    private fun performDownloadAllInstances() {
        showLoading(getString(R.string.squig_download_running))
        val allInstances = instances

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Сначала загружаем phone_book для каждого инстанса (если ещё не загружен)
                val brandsByInstance = mutableMapOf<SquigLinkInstance, List<me.timschneeberger.rootlessjamesdsp.model.squig.SquigLinkBrand>>()
                for (inst in allInstances) {
                    try {
                        val client = SquigLinkClient(inst)
                        val brands = client.loadDatabaseWithCache(inst)
                        brandsByInstance[inst] = brands
                    } catch (e: Exception) {
                        Timber.w("DownloadAll: failed to load phone_book for ${inst.name}: ${e.message}")
                    }
                }

                // Загружаем target names для каждого инстанса
                val targetsByInstance = mutableMapOf<SquigLinkInstance, List<String>>()
                for (inst in allInstances) {
                    try {
                        val client = SquigLinkClient(inst)
                        val targets = client.loadTargetCurvesWithCache(inst)
                        targetsByInstance[inst] = targets
                    } catch (e: Exception) {
                        Timber.w("DownloadAll: failed to load targets for ${inst.name}: ${e.message}")
                    }
                }

                val result = withContext(Dispatchers.IO) {
                    SquigBatchDownloader.downloadAllInstances(
                        clientFactory = { inst -> SquigLinkClient(inst) },
                        instances = brandsByInstance.keys.toList(),
                        brandsByInstance = brandsByInstance,
                        targetsByInstance = targetsByInstance,
                        onProgress = { current, total, msg ->
                            if (isAdded && view != null) {
                                requireActivity().runOnUiThread {
                                    if (isAdded && view != null) {
                                        progressBar.visibility = View.VISIBLE
                                        statusText.text = getString(
                                            R.string.squig_download_progress, msg, current, total
                                        )
                                        statusText.visibility = View.VISIBLE
                                    }
                                }
                            }
                        }
                    )
                }
                if (!isAdded || view == null) return@launch

                hideLoading()
                refreshDownloadedKeys()
                updateCatalogInfo()

                val msg = getString(R.string.squig_download_done, result.summary)
                statusText.text = msg
                statusText.visibility = View.VISIBLE
                Timber.i("DownloadAll: $msg")
            } catch (e: Exception) {
                if (!isAdded || view == null) return@launch
                hideLoading()
                showError(getString(R.string.squig_download_failed, e.message ?: "unknown"))
                Timber.e(e, "DownloadAll failed")
            }
        }
    }

    companion object {
        /** Создание нового экземпляра фрагмента. */
        fun newInstance(): SquigLiveFragment = SquigLiveFragment()
    }
}
