package me.timschneeberger.rootlessjamesdsp.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.squig.SquigLinkSearchEngine

/**
 * RecyclerView-адаптер для отображения результатов поиска SquigLink.
 *
 * Поддерживает два режима отображения:
 *
 * 1. **Expanded** — показываются все результаты поиска.
 *    Выбранный элемент подсвечивается (radio-индикатор).
 *
 * 2. **Collapsed** — показывается только выбранный замер + пункт "Other (N)".
 *    Тап на "Other" переключает обратно в expanded-режим.
 *    Это поведение аналогично preset-селектору: активный элемент
 *    отображается prominently, остальные скрыты.
 *
 * Использует DiffUtil для эффективного обновления списка.
 *
 * @param results список результатов поиска (изначально пустой)
 */
class SquigPhoneAdapter(
    private var results: List<SquigLinkSearchEngine.SearchResult> = emptyList()
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** Колбэк при нажатии на результат поиска. */
    var onClickListener: ((SquigLinkSearchEngine.SearchResult) -> Unit)? = null

    /** Колбэк при нажатии на "Other" — переключение в expanded-режим. */
    var onOtherClickListener: (() -> Unit)? = null

    /** Выбранный результат (или null, если ничего не выбрано). */
    private var selectedResult: SquigLinkSearchEngine.SearchResult? = null

    /** Признак свёрнутого режима (показываем только выбранный + "Other"). */
    private var collapsed: Boolean = false

    // ── View types ───────────────────────────────────────────────────────

    private companion object {
        const val TYPE_PHONE = 0
        const val TYPE_OTHER = 1
    }

    override fun getItemViewType(position: Int): Int {
        if (collapsed && position > 0) return TYPE_OTHER
        return TYPE_PHONE
    }

    // ── Публичный API ────────────────────────────────────────────────────

    /**
     * Обновление списка результатов через DiffUtil.
     * Вычисляет разницу между старым и новым списком —
     * только изменившиеся элементы обновляются с анимацией.
     */
    fun updateResults(newResults: List<SquigLinkSearchEngine.SearchResult>) {
        val diffCallback = SquigDiffCallback(results, newResults, selectedResult, collapsed)
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        results = newResults
        diffResult.dispatchUpdatesTo(this)
    }

    /**
     * Установить выбранный результат и переключиться в collapsed-режим.
     * Аналогично preset-селектору: активный элемент показывается,
     * остальные скрываются в "Other".
     */
    fun setSelectedAndCollapse(result: SquigLinkSearchEngine.SearchResult) {
        selectedResult = result
        collapsed = true
        notifyDataSetChanged()
    }

    /**
     * Развернуть список (показать все результаты).
     * Выбранный элемент остаётся подсвеченным.
     */
    fun expand() {
        collapsed = false
        notifyDataSetChanged()
    }

    /**
     * Сбросить выбор (очистить selectedResult и развернуть список).
     */
    fun clearSelection() {
        selectedResult = null
        collapsed = false
        notifyDataSetChanged()
    }

    // ── itemCount ────────────────────────────────────────────────────────

    override fun getItemCount(): Int {
        if (results.isEmpty()) return 0

        if (!collapsed) return results.size

        // Collapsed: выбранный элемент + "Other" (если есть ещё результаты)
        val selectedIndex = indexOfSelected()
        val otherCount = results.size - 1
        return if (selectedIndex >= 0 && otherCount > 0) {
            2 // выбранный + Other
        } else {
            results.size // нет выбора или нет остальных — показываем всё
        }
    }

    /** Индекс выбранного результата в results. */
    private fun indexOfSelected(): Int {
        val sel = selectedResult ?: return -1
        return results.indexOfFirst {
            it.brand == sel.brand && it.phone.name == sel.phone.name
        }
    }

    // ── onCreateViewHolder ───────────────────────────────────────────────

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_OTHER -> {
                val view = inflater.inflate(R.layout.item_squig_other, parent, false)
                OtherViewHolder(view)
            }
            else -> {
                val view = inflater.inflate(R.layout.item_squig_phone, parent, false)
                PhoneViewHolder(view)
            }
        }
    }

    // ── onBindViewHolder ─────────────────────────────────────────────────

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is PhoneViewHolder -> bindPhone(holder, position)
            is OtherViewHolder -> bindOther(holder)
        }
    }

    /**
     * Привязка элемента телефона.
     * В collapsed-режиме на position=0 — выбранный элемент.
     * В expanded-режиме — обычный элемент по позиции.
     */
    private fun bindPhone(holder: PhoneViewHolder, position: Int) {
        val actualPosition = if (collapsed) indexOfSelected() else position
        if (actualPosition < 0 || actualPosition >= results.size) return

        val result = results[actualPosition]
        holder.phoneName?.text = result.phone.name
        holder.brandName?.text = result.brand

        // Подсветка выбранного элемента (radio-индикатор)
        val isSelected = selectedResult != null &&
            result.brand == selectedResult!!.brand &&
            result.phone.name == selectedResult!!.phone.name
        holder.container.isActivated = isSelected
        holder.radioIndicator?.isActivated = isSelected
        holder.radioIndicator?.visibility = View.VISIBLE

        holder.container.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                val actualPos = if (collapsed) indexOfSelected() else pos
                if (actualPos >= 0 && actualPos < results.size) {
                    onClickListener?.invoke(results[actualPos])
                }
            }
        }
    }

    /**
     * Привязка элемента "Other (N)".
     */
    private fun bindOther(holder: OtherViewHolder) {
        val otherCount = results.size - 1
        holder.otherLabel?.text = holder.itemView.context.getString(
            R.string.squig_other_count, otherCount
        )
        holder.container.setOnClickListener {
            onOtherClickListener?.invoke()
        }
    }

    // ── ViewHolders ──────────────────────────────────────────────────────

    /**
     * ViewHolder для карточки результата поиска.
     */
    inner class PhoneViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        var container: LinearLayout = itemView as LinearLayout
        var brandName: TextView? = itemView.findViewById(R.id.brandName)
        var phoneName: TextView? = itemView.findViewById(R.id.phoneName)
        var radioIndicator: View? = itemView.findViewById(R.id.radioIndicator)
    }

    /**
     * ViewHolder для элемента "Other (N more)".
     */
    inner class OtherViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        var container: LinearLayout = itemView as LinearLayout
        var otherLabel: TextView? = itemView.findViewById(R.id.otherLabel)
    }

    /** DiffUtil callback для эффективного обновления списка. */
    private class SquigDiffCallback(
        private val oldList: List<SquigLinkSearchEngine.SearchResult>,
        private val newList: List<SquigLinkSearchEngine.SearchResult>,
        private val oldSelected: SquigLinkSearchEngine.SearchResult?,
        private val oldCollapsed: Boolean
    ) : DiffUtil.Callback() {

        override fun getOldListSize(): Int = oldList.size
        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
            return oldList[oldPos].brand == newList[newPos].brand &&
                   oldList[oldPos].phone.name == newList[newPos].phone.name
        }

        override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
            return oldList[oldPos] == newList[newPos] && oldCollapsed
        }
    }
}
