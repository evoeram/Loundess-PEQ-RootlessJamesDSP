package me.timschneeberger.rootlessjamesdsp.activity

import android.os.Bundle
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.databinding.ActivitySquigBinding
import me.timschneeberger.rootlessjamesdsp.fragment.SquigLiveFragment

/**
 * Host activity для Squig Live фрагмента.
 *
 * Тонкая обёртка с toolbar и back-кнопкой — аналогично [MeasurementActivity].
 * Открывается из карточки Squig на главном экране через PreferenceGroupFragment.
 */
class SquigActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivitySquigBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.squig_fragment_container, SquigLiveFragment.newInstance())
                .commit()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }
}
