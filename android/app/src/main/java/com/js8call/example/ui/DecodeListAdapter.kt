package com.js8call.example.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.js8call.example.R
import com.js8call.example.model.DecodedMessage

/**
 * RecyclerView adapter for decoded messages.
 */
class DecodeListAdapter : ListAdapter<DecodedMessage, DecodeListAdapter.DecodeViewHolder>(DecodeDiffCallback()) {

    var myGroups: Set<String> = emptySet()

    var onItemClick: ((DecodedMessage) -> Unit)? = null
    var onItemLongClick: ((DecodedMessage) -> Boolean)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DecodeViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_decode, parent, false)
        return DecodeViewHolder(view)
    }

    override fun onBindViewHolder(holder: DecodeViewHolder, position: Int) {
        val decode = getItem(position)
        holder.bind(decode, myGroups)

        holder.itemView.setOnClickListener {
            onItemClick?.invoke(decode)
        }

        holder.itemView.setOnLongClickListener {
            onItemLongClick?.invoke(decode) ?: false
        }
    }

    class DecodeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val snrIndicator: View = itemView.findViewById(R.id.snr_indicator)
        private val timeText: TextView = itemView.findViewById(R.id.time_text)
        private val snrText: TextView = itemView.findViewById(R.id.snr_text)
        private val dtText: TextView = itemView.findViewById(R.id.dt_text)
        private val freqText: TextView = itemView.findViewById(R.id.freq_text)
        private val messageText: TextView = itemView.findViewById(R.id.message_text)
        private val defaultTextColor = messageText.currentTextColor

        fun bind(decode: DecodedMessage, myGroups: Set<String>) {
            // Set SNR indicator color
            val color = ContextCompat.getColor(itemView.context, decode.snrColorRes)
            snrIndicator.setBackgroundColor(color)

            // Set text values
            timeText.text = decode.formattedTime()
            snrText.text = String.format("%+d dB", decode.snr)
            dtText.text = String.format("%+.1f s", decode.dt)
            freqText.text = String.format("%.1f Hz", decode.frequency)
            messageText.text = decode.text

            // Highlight group messages
            val isGroupMsg = myGroups.any { it.isNotEmpty() && decode.text.contains(it, ignoreCase = true) }
            if (isGroupMsg) {
                messageText.setTextColor(ContextCompat.getColor(itemView.context, R.color.highlight_group))
            } else {
                messageText.setTextColor(defaultTextColor)
            }
        }
    }

    private class DecodeDiffCallback : DiffUtil.ItemCallback<DecodedMessage>() {
        override fun areItemsTheSame(
            oldItem: DecodedMessage,
            newItem: DecodedMessage
        ): Boolean {
            return oldItem.timestamp == newItem.timestamp
        }

        override fun areContentsTheSame(
            oldItem: DecodedMessage,
            newItem: DecodedMessage
        ): Boolean {
            return oldItem == newItem
        }
    }
}
