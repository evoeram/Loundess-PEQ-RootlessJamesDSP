package me.timschneeberger.rootlessjamesdsp.adapter

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.databinding.ObservableArrayList
import androidx.databinding.ObservableList
import androidx.recyclerview.widget.RecyclerView
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.model.DynamicEqBand
import me.timschneeberger.rootlessjamesdsp.model.DynamicEqBandList
import me.timschneeberger.rootlessjamesdsp.model.DynamicEqMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

class DynamicEqBandAdapter(var bands: DynamicEqBandList) :
    RecyclerView.Adapter<DynamicEqBandAdapter.ViewHolder>() {

    private val dfFreq = DecimalFormat("0", DecimalFormatSymbols.getInstance(Locale.ENGLISH)).apply {
        maximumFractionDigits = 1
    }
    private val dfDb = DecimalFormat("0", DecimalFormatSymbols.getInstance(Locale.ENGLISH)).apply {
        maximumFractionDigits = 1
    }
    private val dfRatio = DecimalFormat("0", DecimalFormatSymbols.getInstance(Locale.ENGLISH)).apply {
        maximumFractionDigits = 2
    }

    var onItemsChanged: ((DynamicEqBandAdapter) -> Unit)? = null
    var onItemClicked: ((DynamicEqBand, Int) -> Unit)? = null

    private val callback = object : ObservableList.OnListChangedCallback<ObservableArrayList<DynamicEqBand>>() {
        @SuppressLint("NotifyDataSetChanged")
        override fun onChanged(sender: ObservableArrayList<DynamicEqBand>?) {
            this@DynamicEqBandAdapter.notifyDataSetChanged()
            onItemsChanged()
        }

        override fun onItemRangeChanged(
            sender: ObservableArrayList<DynamicEqBand>?,
            positionStart: Int,
            itemCount: Int,
        ) {
            this@DynamicEqBandAdapter.notifyItemRangeChanged(positionStart, itemCount)
            onItemsChanged()
        }

        override fun onItemRangeInserted(
            sender: ObservableArrayList<DynamicEqBand>?,
            positionStart: Int,
            itemCount: Int,
        ) {
            this@DynamicEqBandAdapter.notifyItemRangeInserted(positionStart, itemCount)
            onItemsChanged()
        }

        @SuppressLint("NotifyDataSetChanged")
        override fun onItemRangeMoved(
            sender: ObservableArrayList<DynamicEqBand>?,
            fromPosition: Int,
            toPosition: Int,
            itemCount: Int,
        ) {
            this@DynamicEqBandAdapter.notifyDataSetChanged()
            onItemsChanged()
        }

        override fun onItemRangeRemoved(
            sender: ObservableArrayList<DynamicEqBand>?,
            positionStart: Int,
            itemCount: Int,
        ) {
            this@DynamicEqBandAdapter.notifyItemRangeRemoved(positionStart, itemCount)
            onItemsChanged()
        }
    }

    private fun onItemsChanged() {
        this.onItemsChanged?.invoke(this)
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val mode: TextView = view.findViewById(R.id.mode)
        val freq: TextView = view.findViewById(R.id.freq)
        val threshold: TextView = view.findViewById(R.id.threshold)
        val ratio: TextView = view.findViewById(R.id.ratio)
        val range: TextView = view.findViewById(R.id.range)
        val deleteButton: Button = view.findViewById(R.id.delete)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        bands.addOnListChangedCallback(callback)
        super.onAttachedToRecyclerView(recyclerView)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        bands.removeOnListChangedCallback(callback)
        super.onDetachedFromRecyclerView(recyclerView)
    }

    override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(viewGroup.context)
            .inflate(R.layout.item_dynamic_eq_band_list, viewGroup, false)
        return ViewHolder(view)
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {
        viewHolder.deleteButton.isEnabled = true

        val band = bands[position]
        val context = viewHolder.itemView.context
        viewHolder.mode.text = context.getString(
            if (band.mode == DynamicEqMode.COMPRESS) R.string.dyneq_mode_short_compressor
            else R.string.dyneq_mode_short_expander
        )
        viewHolder.freq.text = "${dfFreq.format(band.frequency)}Hz"
        viewHolder.threshold.text = "${dfDb.format(band.thresholdDb)}dB"
        viewHolder.ratio.text = "${dfRatio.format(band.ratio)}:1"
        viewHolder.range.text = "${dfDb.format(band.rangeDb)}dB"

        viewHolder.deleteButton.setOnClickListener {
            viewHolder.bindingAdapterPosition.let { pos ->
                if (pos >= 0) {
                    bands.removeAt(pos)
                }
            }
            viewHolder.deleteButton.isEnabled = false
        }

        viewHolder.itemView.setOnClickListener {
            viewHolder.bindingAdapterPosition.let { pos ->
                bands.getOrNull(pos)?.let {
                    onItemClicked?.invoke(it, pos)
                }
            }
        }
    }

    override fun getItemCount() = bands.size
}
