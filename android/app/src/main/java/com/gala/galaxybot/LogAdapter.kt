package com.gala.galaxybot

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.gala.galaxybot.databinding.ItemLogBinding

/** Список строк лога. */
class LogAdapter : RecyclerView.Adapter<LogAdapter.ViewHolder>() {

    private var items: List<LogEntry> = emptyList()

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = items[position].id

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    /**
     * Обновить список. Обычный случай — в конец добавили строки (возможно, обрезав
     * начало), тогда уведомляем адаптер точечно, без полной перерисовки.
     */
    @SuppressLint("NotifyDataSetChanged")
    fun submit(newItems: List<LogEntry>) {
        val old = items
        items = newItems

        if (old.isEmpty() || newItems.isEmpty()) {
            notifyDataSetChanged()
            return
        }

        val firstId = newItems.first().id
        val removedFromStart = old.indexOfFirst { it.id == firstId }
        val kept = if (removedFromStart >= 0) old.size - removedFromStart else -1

        if (kept in 1..newItems.size && old.last().id == newItems[kept - 1].id) {
            if (removedFromStart > 0) notifyItemRangeRemoved(0, removedFromStart)
            val appended = newItems.size - kept
            if (appended > 0) notifyItemRangeInserted(kept, appended)
        } else {
            notifyDataSetChanged()
        }
    }

    class ViewHolder(private val binding: ItemLogBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: LogEntry) {
            binding.timeText.text = LogFormat.time(entry.timeMillis)
            binding.bodyText.text = entry.prefix + entry.text
            binding.bodyText.setTextColor(ContextCompat.getColor(binding.root.context, colorFor(entry.kind)))
        }

        private fun colorFor(kind: LogKind): Int = when (kind) {
            LogKind.IN -> R.color.log_in
            LogKind.OUT -> R.color.log_out
            LogKind.INFO -> R.color.log_info
            LogKind.STATE -> R.color.log_state
            LogKind.ERROR -> R.color.log_error
        }
    }
}
