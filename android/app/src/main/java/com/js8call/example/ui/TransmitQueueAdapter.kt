package com.js8call.example.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.js8call.example.R
import com.js8call.example.model.TransmitMessage
import com.js8call.example.util.TxMessageClassifier

class TransmitQueueAdapter :
    ListAdapter<TransmitMessage, TransmitQueueAdapter.QueueViewHolder>(QueueDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        return QueueViewHolder(view)
    }

    override fun onBindViewHolder(holder: QueueViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class QueueViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val line1: TextView = itemView.findViewById(android.R.id.text1)
        private val line2: TextView = itemView.findViewById(android.R.id.text2)

        fun bind(message: TransmitMessage) {
            line1.text = formatQueueText(message)
            val context = itemView.context
            line2.text = if (message.directed.isNullOrBlank()) {
                context.getString(R.string.tx_queue_broadcast)
            } else {
                context.getString(R.string.tx_queue_to, message.directed)
            }
        }

        private fun formatQueueText(message: TransmitMessage): String {
            val directed = message.directed?.trim().orEmpty()
            val trimmed = message.text.trim()
            if (directed.isEmpty()) return trimmed
            if (trimmed.startsWith("`")) return trimmed

            if (TxMessageClassifier.isBaseMessage(trimmed)) return trimmed
            if (trimmed.startsWith(directed, ignoreCase = true)) return trimmed

            val sep = if (trimmed.startsWith(" ")) "" else " "
            return directed + sep + trimmed
        }
    }

    private class QueueDiffCallback : DiffUtil.ItemCallback<TransmitMessage>() {
        override fun areItemsTheSame(oldItem: TransmitMessage, newItem: TransmitMessage): Boolean {
            return oldItem.timestamp == newItem.timestamp && oldItem.text == newItem.text
        }

        override fun areContentsTheSame(oldItem: TransmitMessage, newItem: TransmitMessage): Boolean {
            return oldItem == newItem
        }
    }
}
