package me.timschneeberger.rootlessjamesdsp.fragment

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.adapter.SquigPhoneAdapter
import me.timschneeberger.rootlessjamesdsp.api.SquigLinkClient
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

    private lateinit var resultsAdapter: SquigPhoneAdapter

    // ── Состояние ────────────────────────────────────────────────────────

    /** Список инстансов SquigLink (загружается из squigsites.json). */
    private var instances: List<SquigLinkInstance> = SquigLinkInstance.DEFAULT

    /** Текущий выбранный инстанс. */
    private var currentInstance: SquigLinkInstance = instances.first()

    /** Текущий клиент для инстанса. */
    private var currentClient: SquigLinkClient = SquigLinkClient(currentInstance)

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

    /** Флаг: идёт загрузка данных. */
    private var isLoadingData = false

    /** Конфигурация AutoEQ (настраиваемая пользователем). */
    private var autoEqConfig = SquigAutoEqEngine.Config()

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

        // Привязка UI элементов из XML
        instanceSpinner = view.findViewById(R.id.instanceSpinner)
        targetSpinner = view.findViewById(R.id.targetSpinner)
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

        // Живой поиск: TextWatcher с debounce 350ms
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: ""
                if (query.isEmpty()) {
                    searchJob?.cancel()
                    resultsAdapter.updateResults(emptyList())
                    resultsAdapter.clearSelection()
                    showEmptyState(true)
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

        // Начальное состояние
        showEmptyState(true)
        updateActionButtons()

        setupInstanceSpinner()
        setupTargetSpinner()
        loadSquigSites()
        loadTargetCurves()
        loadDatabase()
        // Загружаем target сразу — он должен рисоваться всегда
        loadTargetCurveData()
    }

    /**
     * Загрузка каталога squigsites.json — список всех инстансов.
     * Грузится с squig.link/squigsites.json?squig
     * При неудаче используется fallback DEFAULT список.
     */
    private fun loadSquigSites() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val sites = currentClient.loadSquigSitesAsync()
                val newInstances = SquigLinkInstance.fromSquigSites(sites)
                if (newInstances.isNotEmpty()) {
                    instances = newInstances
                    // Обновляем spinner без перезагрузки базы
                    val instanceNames = instances.map { it.name }
                    instanceSpinner.adapter = ArrayAdapter(
                        requireContext(),
                        android.R.layout.simple_spinner_dropdown_item,
                        instanceNames
                    )
                    Timber.i("squigsites.json загружен: ${instances.size} инстансов")
                }
            } catch (e: Exception) {
                Timber.w("squigsites.json недоступен, используется DEFAULT: ${e.message}")
            }
        }
    }

    /**
     * Загрузка списка target curves из config.js.
     * Грузится с squig.link/config.js, парсит JS-массив targets.
     * При неудаче используется fallback-список.
     */
    private fun loadTargetCurves() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val curves = currentClient.loadTargetCurvesAsync()
                if (curves.isNotEmpty()) {
                    targetCurves = curves
                    // Сохраняем текущий выбор, если он есть в новом списке
                    val currentSelection = selectedTargetName
                    targetSpinner.adapter = ArrayAdapter(
                        requireContext(),
                        android.R.layout.simple_spinner_dropdown_item,
                        targetCurves
                    )
                    // Восстанавливаем выбор или берём первый
                    val idx = targetCurves.indexOf(currentSelection)
                    if (idx >= 0) {
                        targetSpinner.setSelection(idx)
                        // Если выбор не изменился — spinner listener не сработает,
                        // перезагружаем target вручную
                        loadTargetCurveData()
                    } else {
                        selectedTargetName = targetCurves.first()
                        targetSpinner.setSelection(0)
                        // setSelection(0) сработает listener, но если previous тоже был 0 — не сработает
                        loadTargetCurveData()
                    }
                    Timber.i("config.js загружен: ${targetCurves.size} target curves")
                }
            } catch (e: Exception) {
                Timber.w("config.js недоступен, используется fallback targets: ${e.message}")
            }
        }
    }

    // ── Логика ────────────────────────────────────────────────────────────

    /**
     * Настройка spinner для выбора инстанса SquigLink.
     */
    private fun setupInstanceSpinner() {
        val instanceNames = instances.map { it.name }
        instanceSpinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            instanceNames
        )
        instanceSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                val newInstance = instances[position]
                if (newInstance != currentInstance) {
                    currentInstance = newInstance
                    currentClient = SquigLinkClient(currentInstance)
                    Timber.d("Выбран инстанс: ${currentInstance.name}")
                    loadDatabase()
                    // Перезагружаем список target curves и саму target для нового инстанса
                    loadTargetCurves()
                    loadTargetCurveData()
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    /**
     * Настройка spinner для выбора целевой кривой (target curve).
     * При смене target — всегда перезагружает target curve (даже без замера).
     */
    private fun setupTargetSpinner() {
        targetSpinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            targetCurves
        )
        targetSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                val newTarget = targetCurves[position]
                if (newTarget != selectedTargetName) {
                    selectedTargetName = newTarget
                    Timber.d("Выбран target: $selectedTargetName")
                    // Всегда перезагружаем target curve
                    reloadTargetCurve()
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    /**
     * Загрузка каталога phone_book для текущего инстанса.
     * Использует suspend-обёртку — линейный код без callback-ада.
     */
    private fun loadDatabase() {
        showLoading(getString(R.string.squig_loading))
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val brands = currentClient.loadDatabaseAsync()
                database = brands
                Timber.i("Каталог загружен: ${brands.size} брендов")
                if (isAdded && view != null) {
                    catalogInfo.text = getString(R.string.squig_catalog_info, brands.size)
                    hideLoading()
                    showEmptyState(true)
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
        selectedPhoneText.text = getString(R.string.squig_selected_phone, result.brand, result.phone.name)
        selectedPhoneText.isVisible = true

        // Сворачиваем список: показываем выбранный замер + "Other"
        resultsAdapter.setSelectedAndCollapse(result)

        showLoading(getString(R.string.squig_loading))

        // Очистка предыдущих данных
        measurementFR_L = null
        measurementFR_R = null
        measurementFR = null
        targetFR = null
        autoEqBands = emptyList()
        workingBands.clear()
        workingPreamp = 0.0
        graphSurface.clearMeasurementData()
        graphSurface.clearMeasurementDataR()
        graphSurface.clearTargetCurve()
        graphSurface.clearCorrectedFR()
        // Очищаем filter curves тоже
        graphSurface.setBands(ParametricEqBandList(), 0.0)

        val channels = currentInstance.channels

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                isLoadingData = true
                val normHz = currentInstance.defaultNormHz

                // Загрузка первого канала
                val frL = currentClient.loadFrequencyResponseAsync(result.phone, channels.first())
                val normalizedL = frL.normalize(normHz)
                measurementFR_L = normalizedL

                // Отображение L на графике
                val (freqsL, splL) = normalizedL.toFloatArrays()
                graphSurface.setMeasurementData(freqsL, splL)

                // Если есть второй канал, загружаем (необязательно — R может отсутствовать)
                if (channels.size > 1) {
                    try {
                        val frR = currentClient.loadFrequencyResponseAsync(result.phone, channels[1])
                        val normalizedR = frR.normalize(normHz)
                        measurementFR_R = normalizedR
                        val (freqsR, splR) = normalizedR.toFloatArrays()
                        graphSurface.setMeasurementDataR(freqsR, splR)

                        // Усреднённая АЧХ для AutoEQ
                        measurementFR = normalizedL.average(normalizedR)
                    } catch (e: Exception) {
                        Timber.w("R-канал не загружен, используется только L: ${e.message}")
                        measurementFR = normalizedL
                    }
                } else {
                    // Только один канал
                    measurementFR = normalizedL
                }

                // Загрузка целевой кривой (если ещё не загружена — загружаем)
                ensureActive()
                if (targetFR == null) {
                    try {
                        val target = currentClient.loadTargetCurveAsync(selectedTargetName)
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
        liveEqButton.isEnabled = hasMeasurement && !isLoadingData
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

        // Очистка старых AutoEQ-полос и corrected FR
        autoEqBands = emptyList()
        autoEqPreamp = 0.0
        workingBands.clear()
        workingPreamp = 0.0
        graphSurface.setBands(ParametricEqBandList(), 0.0)
        graphSurface.clearCorrectedFR()

        loadTargetCurveData()
    }

    /**
     * Загрузка данных целевой кривой с сервера и отображение на графике.
     * Target рисуется всегда — даже без замера.
     * Сдвигается на preamp если есть активные полосы.
     */
    private fun loadTargetCurveData() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val normHz = currentInstance.defaultNormHz
                val target = currentClient.loadTargetCurveAsync(selectedTargetName)
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
                showError(getString(R.string.squig_load_error, e.message ?: "unknown"))
                Timber.e(e, "loadTargetCurveData failed")
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
                    1 -> autoEqConfig = autoEqConfig.copy(individualMaxBoost = v.coerceIn(0.0, 30.0))
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
                val engine = SquigAutoEqEngine()
                val result = withContext(Dispatchers.Default) {
                    engine.run(measurement, target, autoEqConfig)
                }
                if (!isAdded || view == null) return@launch

                autoEqBands = result.bands
                autoEqPreamp = result.preampDb
                workingPreamp = autoEqPreamp

                // Обновление рабочих полос
                workingBands.clear()
                workingBands.addAll(result.bands)

                // Отображение фильтра на графике
                graphSurface.setBands(workingBands, workingPreamp)

                // Вычисление и отображение corrected FR (включая сдвиг target)
                updateCorrectedFR()

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
    }

    /**
     * Открытие LiveEqBottomSheet для интерактивного редактирования PEQ-полос.
     * Передаёт оверлеи (measurement L/R, target) для отображения на графике Live EQ.
     * Колбэк onCorrectedUpdate обновляет corrected FR на главном графике в реальном времени.
     */
    private fun openLiveEq() {
        if (workingBands.isEmpty() && autoEqBands.isEmpty()) {
            // Создаём одну полосу по умолчанию
            workingBands.clear()
            workingBands.add(ParametricEqBand(
                frequency = 1000.0,
                gain = 0.0,
                q = 1.0,
                filterType = ParametricEqFilterType.PEAKING,
                channel = ParametricEqChannel.LEFT_RIGHT
            ))
        } else if (workingBands.isEmpty()) {
            workingBands.clear()
            workingBands.addAll(autoEqBands)
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
                }
            },
            onPreampUpdate = { newPreamp ->
                workingPreamp = newPreamp
            },
            onCommit = {
                if (isAdded && view != null) {
                    graphSurface.setBands(workingBands, workingPreamp)
                    updateCorrectedFR()
                    updateActionButtons()
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
            val prefs = requireContext().getSharedPreferences(Constants.PREF_PEQ, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(getString(R.string.key_peq_bands), bands.serialize())
                .putFloat(getString(R.string.key_peq_preamp), preamp.toFloat())
                .apply()

            requireContext().sendLocalBroadcast(Intent(Constants.ACTION_PARAMETRIC_EQ_CHANGED))
            requireContext().toast(getString(R.string.squig_applied_to_peq, bands.size))
            Timber.i("Применено ${bands.size} полос к PEQ")
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

    companion object {
        /** Создание нового экземпляра фрагмента. */
        fun newInstance(): SquigLiveFragment = SquigLiveFragment()
    }
}
