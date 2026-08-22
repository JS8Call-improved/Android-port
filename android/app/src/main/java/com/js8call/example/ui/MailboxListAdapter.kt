package com.js8call.example.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.js8call.example.R
import com.js8call.example.data.MailboxEntity

/**
 * The Held messages list: a header row per destination, its messages below.
 */
class MailboxListAdapter : ListAdapter<MailboxListAdapter.Row, RecyclerView.ViewHolder>(DIFF) {

    var onMessageClick: ((MailboxViewModel.MailboxRow) -> Unit)? = null

    sealed class Row {
        data class Header(val destination: String, val count: Int, val oldestAt: Long) : Row()
        data class Message(val row: MailboxViewModel.MailboxRow) : Row()
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is Row.Header -> TYPE_HEADER
        is Row.Message -> TYPE_MESSAGE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderHolder(
                inflater.inflate(R.layout.item_mailbox_header, parent, false)
            )
            else -> MessageHolder(
                inflater.inflate(R.layout.item_mailbox_message, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is Row.Header -> (holder as HeaderHolder).bind(row)
            is Row.Message -> (holder as MessageHolder).bind(row.row)
        }
    }

    inner class HeaderHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val destinationText: TextView = itemView.findViewById(R.id.destination_text)
        private val summaryText: TextView = itemView.findViewById(R.id.summary_text)

        fun bind(header: Row.Header) {
            destinationText.text = header.destination
            summaryText.text = itemView.resources.getQuantityString(
                R.plurals.mailbox_destination_summary,
                header.count, header.count, formatAge(header.oldestAt)
            )
        }
    }

    inner class MessageHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val originatorText: TextView = itemView.findViewById(R.id.originator_text)
        private val stateText: TextView = itemView.findViewById(R.id.state_text)
        private val previewText: TextView = itemView.findViewById(R.id.preview_text)

        fun bind(row: MailboxViewModel.MailboxRow) {
            val msg = row.message
            originatorText.text = itemView.resources.getString(
                R.string.mailbox_from_age, msg.originator, formatAge(msg.receivedAt)
            )
            previewText.text = msg.text
            stateText.text = when {
                msg.destination.startsWith("@") -> itemView.resources.getQuantityString(
                    R.plurals.mailbox_collected_by, row.collectedBy, row.collectedBy
                )
                msg.state == MailboxEntity.STATE_DELIVERED ->
                    itemView.resources.getString(R.string.mailbox_state_delivered)
                else -> itemView.resources.getString(R.string.mailbox_state_held)
            }
            itemView.setOnClickListener { onMessageClick?.invoke(row) }
        }
    }

    private fun formatAge(timestamp: Long): String {
        val seconds = (System.currentTimeMillis() - timestamp) / 1000
        return when {
            seconds < 60 -> "now"
            seconds < 3600 -> "${seconds / 60}m"
            seconds < 86400 -> "${seconds / 3600}h"
            else -> "${seconds / 86400}d"
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_MESSAGE = 1

        /** Destinations by newest mail first, each destination's mail newest first. */
        fun buildRows(rows: List<MailboxViewModel.MailboxRow>): List<Row> {
            val byDestination = rows.groupBy { it.message.destination }
                .entries
                .sortedByDescending { entry -> entry.value.maxOf { it.message.receivedAt } }
            val out = mutableListOf<Row>()
            for ((destination, messages) in byDestination) {
                out.add(
                    Row.Header(
                        destination,
                        messages.size,
                        messages.minOf { it.message.receivedAt }
                    )
                )
                messages.sortedByDescending { it.message.receivedAt }
                    .forEach { out.add(Row.Message(it)) }
            }
            return out
        }

        private val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(oldItem: Row, newItem: Row): Boolean = when {
                oldItem is Row.Header && newItem is Row.Header ->
                    oldItem.destination == newItem.destination
                oldItem is Row.Message && newItem is Row.Message ->
                    oldItem.row.message.id == newItem.row.message.id
                else -> false
            }

            override fun areContentsTheSame(oldItem: Row, newItem: Row): Boolean =
                oldItem == newItem
        }
    }
}
