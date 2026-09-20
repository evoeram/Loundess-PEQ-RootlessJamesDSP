package me.timschneeberger.rootlessjamesdsp.fragment

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.measurement.TargetCurve

/**
 * Dialog fragment for editing a target curve (house curve) for Auto-EQ.
 *
 * Allows the user to:
 *  - Add, remove, and reorder control points (frequency, gain)
 *  - Select from preset curves (Flat, Harman)
 *  - Preview the curve on a small graph
 *
 * The result is returned via [OnTargetCurveSelectedListener].
 */
class TargetCurveEditorFragment : DialogFragment() {

    interface OnTargetCurveSelectedListener {
        fun onTargetCurveSelected(curve: TargetCurve)
    }

    private var listener: OnTargetCurveSelectedListener? = null
    private val points = mutableListOf<TargetCurve.Point>()
    private var currentName = "Custom"

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: PointAdapter

    fun setListener(l: OnTargetCurveSelectedListener) {
        listener = l
    }

    fun setInitialCurve(curve: TargetCurve) {
        currentName = curve.name
        points.clear()
        points.addAll(curve.points)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = requireActivity().layoutInflater.inflate(R.layout.fragment_target_curve_editor, null)

        recyclerView = view.findViewById(R.id.point_list)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        adapter = PointAdapter(points) { position ->
            points.removeAt(position)
            adapter.notifyItemRemoved(position)
        }
        recyclerView.adapter = adapter

        // Swipe-to-delete
        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = vh.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                java.util.Collections.swap(points, from, to)
                adapter.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val pos = viewHolder.bindingAdapterPosition
                points.removeAt(pos)
                adapter.notifyItemRemoved(pos)
            }
        })
        touchHelper.attachToRecyclerView(recyclerView)

        view.findViewById<MaterialButton>(R.id.btn_add_point).setOnClickListener {
            showAddPointDialog()
        }

        view.findViewById<MaterialButton>(R.id.btn_preset_flat).setOnClickListener {
            points.clear()
            points.addAll(TargetCurve.flat().points)
            currentName = "Flat"
            adapter.notifyDataSetChanged()
        }

        view.findViewById<MaterialButton>(R.id.btn_preset_harman).setOnClickListener {
            points.clear()
            points.addAll(TargetCurve.harman().points)
            currentName = "Harman"
            adapter.notifyDataSetChanged()
        }

        view.findViewById<MaterialButton>(R.id.btn_done).setOnClickListener {
            val sorted = points.sortedBy { it.frequency }
            listener?.onTargetCurveSelected(TargetCurve(currentName, sorted))
            dismiss()
        }

        view.findViewById<MaterialButton>(R.id.btn_cancel).setOnClickListener {
            dismiss()
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.target_curve_editor)
            .setView(view)
            .create()
    }

    private fun showAddPointDialog() {
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        val freqLayout = TextInputLayout(requireContext()).apply {
            hint = getString(R.string.target_curve_frequency)
        }
        val freqInput = TextInputEditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        freqLayout.addView(freqInput)
        container.addView(freqLayout)

        val gainLayout = TextInputLayout(requireContext()).apply {
            hint = getString(R.string.target_curve_gain)
        }
        val gainInput = TextInputEditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                    android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        gainLayout.addView(gainInput)
        container.addView(gainLayout)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.target_curve_add_point)
            .setView(container)
            .setPositiveButton(R.string.peq_done) { _, _ ->
                val freq = freqInput.text?.toString()?.toDoubleOrNull()
                val gain = gainInput.text?.toString()?.toDoubleOrNull()
                if (freq != null && gain != null && freq > 0) {
                    points.add(TargetCurve.Point(freq, gain))
                    points.sortBy { it.frequency }
                    adapter.notifyDataSetChanged()
                }
            }
            .setNegativeButton(R.string.peq_cancel, null)
            .show()
    }

    /** RecyclerView adapter for target curve control points. */
    private class PointAdapter(
        private val points: List<TargetCurve.Point>,
        private val onDelete: (Int) -> Unit
    ) : RecyclerView.Adapter<PointAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val text: TextView = view.findViewById(android.R.id.text1)
            val deleteBtn: MaterialButton = view.findViewById(android.R.id.button1)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_2, parent, false)
            // Replace with a custom layout containing a text and delete button
            val container = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(32, 16, 32, 16)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val textView = TextView(parent.context).apply {
                id = android.R.id.text1
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val delBtn = MaterialButton(parent.context).apply {
                id = android.R.id.button1
                text = "✕"
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            container.addView(textView)
            container.addView(delBtn)
            return ViewHolder(container)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val p = points[position]
            holder.text.text = String.format("%.0f Hz  →  %.1f dB", p.frequency, p.gainDb)
            holder.deleteBtn.setOnClickListener { onDelete(position) }
        }

        override fun getItemCount() = points.size
    }

    companion object {
        fun newInstance(curve: TargetCurve): TargetCurveEditorFragment {
            return TargetCurveEditorFragment().apply {
                setInitialCurve(curve)
            }
        }
    }
}
