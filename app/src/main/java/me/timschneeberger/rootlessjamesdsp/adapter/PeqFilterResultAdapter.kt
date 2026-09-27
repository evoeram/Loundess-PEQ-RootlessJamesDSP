package me.timschneeberger.rootlessjamesdsp.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import me.timschneeberger.rootlessjamesdsp.databinding.ItemPeqFilterResultBinding
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqBand
import me.timschneeberger.rootlessjamesdsp.model.ParametricEqFilterType
import java.util.Locale

/**
 * Адаптер для отображения списка PEQ-фильтров в диалоге результатов конвертации.
 * Каждая строка: № | Тип | Freq | Gain | Q
 */
class PeqFilterResultAdapter(
    private val bands: List<ParametricEqBand>,
) : RecyclerView.Adapter<PeqFilterResultAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemPeqFilterResultBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPeqFilterResultBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val band = bands[position]
        val b = holder.binding

        b.filterIndex.text = (position + 1).toString()

        val typeLabel = when (band.filterType) {
            ParametricEqFilterType.PEAKING -> "PK"
            ParametricEqFilterType.LOW_SHELF -> "LS"
            ParametricEqFilterType.HIGH_SHELF -> "HS"
            else -> "PK"
        }
        b.filterType.text = typeLabel

        b.filterFreq.text = String.format(Locale.US, "%.1f Hz", band.frequency)

        val gainStr = String.format(Locale.US, "%+.1f dB", band.gain)
        b.filterGain.text = gainStr

        b.filterQ.text = String.format(Locale.US, "%.2f", band.q)
    }

    override fun getItemCount() = bands.size
}
