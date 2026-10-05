package me.timschneeberger.rootlessjamesdsp.preference

import android.content.Context
import android.content.SharedPreferences
import android.util.AttributeSet
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.interop.PreferenceCache
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.view.ExpanderSurface

/**
 * Preference that embeds a bar-chart visualization of harmonic or sub-harmonic
 * expander gain values. Listens to expander preference changes and redraws.
 *
 * Set [isSubHarmonic] = true for the sub-harmonic variant.
 */
class ExpanderGraphPreference : Preference, SharedPreferences.OnSharedPreferenceChangeListener {

    private var surface: ExpanderSurface? = null
    private val isSubHarmonic: Boolean
    private val prefsFile: String

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
        this(context, attrs, defStyleAttr, 0)

    constructor(context: Context, attrs: AttributeSet?) :
        this(context, attrs, androidx.preference.R.attr.preferenceStyle)

    constructor(context: Context) : this(context, null)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) :
        super(context, attrs, defStyleAttr, defStyleRes) {

        // Read custom attribute to determine harmonic vs sub-harmonic
        val ta = context.obtainStyledAttributes(attrs, R.styleable.ExpanderGraphPreference)
        isSubHarmonic = ta.getBoolean(R.styleable.ExpanderGraphPreference_isSubHarmonic, false)
        ta.recycle()

        prefsFile = if (isSubHarmonic) Constants.PREF_SUBHARMONIC_EXPANDER else Constants.PREF_HARMONIC_EXPANDER
        layoutResource = R.layout.preference_expander_graph
    }

    private val expanderPrefs: SharedPreferences
        get() = PreferenceCache.getPreferences(context, prefsFile)

    override fun onAttached() {
        super.onAttached()
        expanderPrefs.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onDetached() {
        expanderPrefs.unregisterOnSharedPreferenceChangeListener(this)
        super.onDetached()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        surface = holder.findViewById(R.id.expander_graph_surface) as? ExpanderSurface
        updateGraph()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        postUpdate()
    }

    private fun postUpdate() {
        surface?.post { updateGraph() }
    }

    private fun updateGraph() {
        val s = surface ?: return
        val prefs = expanderPrefs

        val enabled = prefs.getBoolean(
            context.getString(if (isSubHarmonic) R.string.key_subharmonic_expander_enable
                              else R.string.key_harmonic_expander_enable), false)
        if (!enabled) {
            s.setGains(FloatArray(9), 0f, 0f, false)
            return
        }

        // Harmonic expander has gain_2..gain_10 (9 bars), sub-harmonic has gain_1..gain_9 (9 bars)
        // THD bar (10th) is computed inside ExpanderSurface from these 9 gains.
        val gains = FloatArray(9)
        for (i in 0 until 9) {
            val keyName = if (isSubHarmonic)
                "key_subharmonic_expander_gain_${1 + i}"
            else
                "key_harmonic_expander_gain_${2 + i}"
            val resId = context.resources.getIdentifier(keyName, "string", context.packageName)
            val keyStr = if (resId != 0) context.getString(resId) else ""
            gains[i] = if (keyStr.isNotEmpty()) prefs.getFloat(keyStr, 0f) else 0f
        }

        val crossoverKeyRes = if (isSubHarmonic) R.string.key_subharmonic_expander_crossover
                              else R.string.key_harmonic_expander_crossover
        val crossover = prefs.getFloat(context.getString(crossoverKeyRes),
            if (isSubHarmonic) 120f else 2000f)

        val mixKeyRes = if (isSubHarmonic) R.string.key_subharmonic_expander_mix
                        else R.string.key_harmonic_expander_mix
        val mix = prefs.getFloat(context.getString(mixKeyRes), 50f)

        s.setGains(gains, crossover, mix, true)
    }
}
