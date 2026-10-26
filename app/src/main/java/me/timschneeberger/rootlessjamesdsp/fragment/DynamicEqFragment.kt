package me.timschneeberger.rootlessjamesdsp.fragment

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.activity.DynamicEqActivity
import me.timschneeberger.rootlessjamesdsp.adapter.DynamicEqBandAdapter
import me.timschneeberger.rootlessjamesdsp.databinding.FragmentDynamicEqBinding
import me.timschneeberger.rootlessjamesdsp.model.DynamicEqBand
import me.timschneeberger.rootlessjamesdsp.model.DynamicEqBandList
import me.timschneeberger.rootlessjamesdsp.model.DynamicEqMode
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqBand
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqBandList
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqChannel
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqFilterType
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.showYesNoAlert
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.toast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import timber.log.Timber
import java.util.UUID

class DynamicEqFragment : Fragment() {
    private lateinit var binding: FragmentDynamicEqBinding

    private val adapter: DynamicEqBandAdapter
        get() = binding.bandList.adapter as DynamicEqBandAdapter

    private var editorBandBackup: DynamicEqBand? = null
    private var editorBandUuid: UUID? = null
    private var editorActive = false
        set(value) {
            field = value
            binding.add.isEnabled = !value
            binding.reset.isEnabled = !value
        }

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Constants.ACTION_PRESET_LOADED) {
                activity?.finish()
                startActivity(Intent(requireContext(), DynamicEqActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                })
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        requireContext().registerLocalReceiver(broadcastReceiver, IntentFilter(Constants.ACTION_PRESET_LOADED))
        super.onCreate(savedInstanceState)
    }

    override fun onDestroy() {
        requireContext().unregisterLocalReceiver(broadcastReceiver)
        super.onDestroy()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentDynamicEqBinding.inflate(inflater, container, false)

        binding.freqInput.apply { min = 20f; max = 20000f }
        binding.qInput.apply { min = 0.1f; max = 30f }
        binding.thresholdInput.apply { min = -60f; max = 0f }
        binding.ratioInput.apply { min = 1f; max = 20f }
        binding.attackInput.apply { min = 0f; max = 2000f }
        binding.releaseInput.apply { min = 0f; max = 5000f }
        binding.rangeInput.apply { min = -48f; max = 48f }

        binding.previewCard.setOnClickListener {
            if (resources.configuration.orientation != ORIENTATION_LANDSCAPE) {
                collapsePreview(!binding.equalizerSurface.isVisible)
            }
        }

        binding.reset.setOnClickListener {
            requireContext().showYesNoAlert(
                R.string.dyneq_reset_confirm_title,
                R.string.dyneq_reset_confirm,
            ) {
                if (it) {
                    adapter.bands.deserialize(Constants.DEFAULT_DYNAMICEQ)
                    updateGraph()
                    updateViewState()
                    editorDiscard()
                    save()
                }
            }
        }

        binding.add.setOnClickListener {
            if (editorActive) return@setOnClickListener

            if (adapter.bands.size >= DynamicEqBandList.MAX_BANDS) {
                requireContext().toast(R.string.dyneq_max_bands_reached)
                return@setOnClickListener
            }

            editorBandBackup = null
            editorBandUuid = null
            editorActive = true

            binding.freqInput.value = 1000f
            binding.qInput.value = 1f
            binding.thresholdInput.value = -20f
            binding.ratioInput.value = 3f
            binding.attackInput.value = 10f
            binding.releaseInput.value = 100f
            binding.rangeInput.value = -6f
            binding.modeSpinner.setSelection(DynamicEqMode.COMPRESS.ordinal)
            updateViewState()
            editorApply()
        }

        binding.freqInput.setOnValueChangedListener { editorApply() }
        binding.qInput.setOnValueChangedListener { editorApply() }
        binding.thresholdInput.setOnValueChangedListener { editorApply() }
        binding.ratioInput.setOnValueChangedListener { editorApply() }
        binding.attackInput.setOnValueChangedListener { editorApply() }
        binding.releaseInput.setOnValueChangedListener { editorApply() }
        binding.rangeInput.setOnValueChangedListener { editorApply() }

        val modeLabels = DynamicEqMode.entries.map { mode ->
            getString(
                if (mode == DynamicEqMode.COMPRESS) R.string.dyneq_mode_compressor
                else R.string.dyneq_mode_expander
            )
        }
        binding.modeSpinner.adapter = android.widget.ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, modeLabels
        )
        binding.modeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                editorApply()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        binding.freqInput.customStepScale = { value: Float, _: Boolean ->
            when (value) {
                in 0f..400f -> 10f
                in 400f..600f -> 20f
                in 600f..1000f -> 50f
                in 1000f..5000f -> 100f
                in 5000f..Float.MAX_VALUE -> 500f
                else -> 10f
            }
        }

        binding.qInput.customStepScale = { value: Float, _: Boolean ->
            when (value) {
                in 0f..1f -> 0.05f
                in 1f..5f -> 0.1f
                in 5f..10f -> 0.5f
                in 10f..Float.MAX_VALUE -> 1f
                else -> 0.1f
            }
        }

        binding.confirm.setOnClickListener { editorSave() }
        binding.cancel.setOnClickListener { editorDiscard() }

        binding.bandList.layoutManager = LinearLayoutManager(requireContext())
        loadBands(savedInstanceState)

        updateViewState()
        return binding.root
    }

    private fun loadBands(savedInstanceState: Bundle?) {
        val bands = DynamicEqBandList()
        val prefs = requireContext().getSharedPreferences(Constants.PREF_DYNAMICEQ, Context.MODE_PRIVATE)
        val dataSaved = savedInstanceState?.getBundle(STATE_BANDS)
        if (dataSaved != null) {
            bands.fromBundle(dataSaved)
        } else {
            val bandString = prefs.getString(
                getString(R.string.key_dynamiceq_bands),
                Constants.DEFAULT_DYNAMICEQ
            )!!
            bands.deserialize(bandString)
        }

        updateGraph(bands)

        binding.bandList.adapter = DynamicEqBandAdapter(bands).apply {
            onItemsChanged = {
                updateGraph(it.bands)
                updateViewState()
                save()
            }

            onItemClicked = { band: DynamicEqBand, _: Int ->
                editorBandBackup = band
                editorBandUuid = band.uuid
                editorActive = true

                binding.freqInput.value = band.frequency.toFloat()
                binding.qInput.value = band.q.toFloat()
                binding.thresholdInput.value = band.thresholdDb.toFloat()
                binding.ratioInput.value = band.ratio.toFloat()
                binding.attackInput.value = band.attackMs.toFloat()
                binding.releaseInput.value = band.releaseMs.toFloat()
                binding.rangeInput.value = band.rangeDb.toFloat()
                binding.modeSpinner.setSelection(band.mode.ordinal)
                updateViewState()
            }
        }
    }

    /**
     * The preview reuses the PEQ graph: each band is drawn as a peaking
     * filter at its range, which is the maximum depth of its dynamics.
     */
    private fun updateGraph(bands: DynamicEqBandList = adapter.bands) {
        val preview = ParametricEqBandList()
        for (band in bands) {
            preview.add(
                ParametricEqBand(
                    band.frequency,
                    band.rangeDb,
                    band.q,
                    ParametricEqFilterType.PEAKING,
                    ParametricEqChannel.LEFT_RIGHT
                )
            )
        }
        binding.equalizerSurface.setBands(preview, 0.0)
    }

    private fun getSelectedMode(): DynamicEqMode =
        DynamicEqMode.entries.getOrElse(binding.modeSpinner.selectedItemPosition) { DynamicEqMode.COMPRESS }

    private fun updateViewState() {
        val empty = adapter.bands.isEmpty()
        binding.emptyView.isVisible = empty && !editorActive
        binding.bandList.isVisible = !empty && !editorActive
        binding.bandEdit.isVisible = editorActive
        binding.bandDetailContextButtons.visibility = if (editorActive) View.VISIBLE else View.INVISIBLE
        binding.editCardTitle.text =
            getString(if (editorActive) R.string.dyneq_band_editor else R.string.dyneq_band_list)
    }

    override fun onStop() {
        if (editorActive) {
            Timber.d("onStop: discarding unsaved Dynamic EQ changes")
            editorDiscard()
        }
        super.onStop()
    }

    private fun editorCanSave(): Boolean =
        binding.freqInput.isCurrentValueValid() &&
                binding.qInput.isCurrentValueValid() &&
                binding.thresholdInput.isCurrentValueValid() &&
                binding.ratioInput.isCurrentValueValid() &&
                binding.attackInput.isCurrentValueValid() &&
                binding.releaseInput.isCurrentValueValid() &&
                binding.rangeInput.isCurrentValueValid()

    private fun editorApply() {
        if (!editorActive || !editorCanSave()) return

        val uuid = editorBandUuid
        val band = DynamicEqBand(
            binding.freqInput.value.toDouble(),
            binding.qInput.value.toDouble(),
            binding.thresholdInput.value.toDouble(),
            binding.ratioInput.value.toDouble(),
            binding.attackInput.value.toDouble(),
            binding.releaseInput.value.toDouble(),
            binding.rangeInput.value.toDouble(),
            getSelectedMode(),
            uuid ?: UUID.randomUUID()
        )

        if (uuid == null) {
            adapter.bands.add(band)
            editorBandUuid = band.uuid
            Timber.d("editorApply: adding band ${band.uuid}")
        } else {
            val index = adapter.bands.indexOfFirst { it.uuid == uuid }
            if (index < 0) {
                Timber.e("editorApply: failed to find matching band UUID")
            } else {
                adapter.bands[index] = band
            }
        }
    }

    private fun editorDiscard() {
        val uuid = editorBandUuid
        if (editorBandBackup != null && uuid != null) {
            Timber.d("editorDiscard: reverting modifications to band $uuid")
            val index = adapter.bands.indexOfFirst { it.uuid == uuid }
            if (index < 0) {
                Timber.e("editorDiscard: failed to find matching band UUID")
            } else {
                adapter.bands[index] = editorBandBackup!!
            }
        } else if (uuid != null) {
            Timber.d("editorDiscard: reverting addition of band $uuid")
            adapter.bands.removeAll { it.uuid == uuid }
        }

        editorBandBackup = null
        editorBandUuid = null
        editorActive = false
        updateViewState()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun editorSave() {
        if (!editorCanSave()) {
            requireContext().showYesNoAlert(
                R.string.dyneq_discard_changes_title,
                R.string.dyneq_discard_changes
            ) {
                if (it) {
                    editorDiscard()
                }
            }
            return
        }

        if (editorBandUuid == null) {
            editorApply()
        }

        Timber.d("editorSave: confirming changes to band $editorBandUuid")
        editorBandBackup = null
        editorBandUuid = null
        editorActive = false

        adapter.notifyDataSetChanged()
        updateViewState()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        if (newConfig.orientation == ORIENTATION_LANDSCAPE) {
            collapsePreview(false)
        }
        super.onConfigurationChanged(newConfig)
    }

    private fun collapsePreview(collapsed: Boolean) {
        binding.equalizerSurface.isVisible = collapsed
        binding.previewTitle.text =
            getString(if (collapsed) R.string.dyneq_preview else R.string.dyneq_preview_collapsed)
    }

    @SuppressLint("ApplySharedPref")
    private fun save() {
        requireContext().getSharedPreferences(Constants.PREF_DYNAMICEQ, Context.MODE_PRIVATE)
            .edit()
            .putString(getString(R.string.key_dynamiceq_bands), adapter.bands.serialize())
            .commit()
        requireContext().sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (editorActive)
            editorDiscard()

        outState.putBundle(STATE_BANDS, adapter.bands.toBundle())
        super.onSaveInstanceState(outState)
    }

    companion object {
        const val STATE_BANDS = "deq_bands"

        fun newInstance(): DynamicEqFragment {
            return DynamicEqFragment()
        }
    }
}
