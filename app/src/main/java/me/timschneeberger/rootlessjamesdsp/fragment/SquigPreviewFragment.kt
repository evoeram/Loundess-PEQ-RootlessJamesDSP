package me.timschneeberger.rootlessjamesdsp.fragment

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqBandList
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.view.ParametricEqSurface

/**
 * Мини-график Squig Live PEQ для главного экрана.
 *
 * Встраивается в карточку Squig на главном экране (fragment_dsp.xml).
 * Читает squig PEQ bands + preamp из PREF_SQUIG и рисует компактный
 * график отклика фильтра. Обновляется в реальном времени при изменении
 * squig PEQ через SharedPreferences listener.
 */
class SquigPreviewFragment : Fragment(), SharedPreferences.OnSharedPreferenceChangeListener {

    private var graphSurface: ParametricEqSurface? = null
    private var emptyText: View? = null
    private var squigPrefs: SharedPreferences? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_squig_preview, container, false)
        graphSurface = view.findViewById(R.id.squigPreviewSurface)
        emptyText = view.findViewById(R.id.squigPreviewEmpty)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        squigPrefs = requireContext()
            .getSharedPreferences(Constants.PREF_SQUIG, Context.MODE_MULTI_PROCESS)
        squigPrefs?.registerOnSharedPreferenceChangeListener(this)
        updateGraph()
    }

    override fun onDestroyView() {
        squigPrefs?.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroyView()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        val ctx = context ?: return
        val keyBands = ctx.getString(R.string.key_squig_peq_bands)
        val keyPreamp = ctx.getString(R.string.key_squig_peq_preamp)
        val keyEnable = ctx.getString(R.string.key_squig_peq_enable)
        val keySquigEnable = ctx.getString(R.string.key_squig_enable)
        if (key == keyBands || key == keyPreamp || key == keyEnable || key == keySquigEnable) {
            updateGraph()
        }
    }

    private fun updateGraph() {
        val ctx = context ?: return
        val prefs = squigPrefs ?: return
        val enabled = prefs.getBoolean(ctx.getString(R.string.key_squig_peq_enable), false) ||
                prefs.getBoolean(ctx.getString(R.string.key_squig_enable), false)
        val bandsStr = prefs.getString(ctx.getString(R.string.key_squig_peq_bands), null)
        val preamp = prefs.getFloat(ctx.getString(R.string.key_squig_peq_preamp), 0f)

        if (!enabled || bandsStr.isNullOrEmpty()) {
            graphSurface?.setBands(ParametricEqBandList(), 0.0)
            graphSurface?.isVisible = false
            emptyText?.isVisible = true
            return
        }

        val bands = ParametricEqBandList()
        bands.deserialize(bandsStr)
        if (bands.isEmpty()) {
            graphSurface?.setBands(ParametricEqBandList(), 0.0)
            graphSurface?.isVisible = false
            emptyText?.isVisible = true
            return
        }

        graphSurface?.isVisible = true
        emptyText?.isVisible = false
        graphSurface?.setBands(bands, preamp.toDouble())
    }

    companion object {
        fun newInstance(): SquigPreviewFragment = SquigPreviewFragment()
    }
}
