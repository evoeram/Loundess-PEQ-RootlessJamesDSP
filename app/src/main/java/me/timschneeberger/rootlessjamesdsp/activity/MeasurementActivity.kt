package me.timschneeberger.rootlessjamesdsp.activity

import android.os.Bundle
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.databinding.ActivityMeasurementBinding
import me.timschneeberger.rootlessjamesdsp.fragment.MeasurementFragment

/**
 * Host activity for the acoustic measurement and Auto-EQ fragment.
 *
 * Follows the same pattern as [ParametricEqualizerActivity]: a thin wrapper
 * that hosts a single fragment and provides a toolbar with back navigation.
 */
class MeasurementActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityMeasurementBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.params, MeasurementFragment.newInstance())
                .commit()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }
}
