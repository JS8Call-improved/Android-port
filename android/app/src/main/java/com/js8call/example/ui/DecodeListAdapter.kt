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
        private val bubbleContainer: View = itemView.findViewById(R.id.bubble_container)
        private val snrIndicator: View = itemView.findViewById(R.id.snr_indicator)
        private val timeText: TextView = itemView.findViewById(R.id.time_text)
        private val snrText: TextView = itemView.findViewById(R.id.snr_text)
        private val dtText: TextView = itemView.findViewById(R.id.dt_text)
        private val freqText: TextView = itemView.findViewById(R.id.freq_text)
        private val messageText: TextView = itemView.findViewById(R.id.message_text)

        fun bind(decode: DecodedMessage, myGroups: Set<String>) {
            val context = itemView.context
            timeText.text = decode.formattedTime()
            freqText.text = String.format("%.1f Hz", decode.frequency)
            messageText.text = decode.text

            // Received on the left, own transmissions on the right, texting style
            val params = bubbleContainer.layoutParams
                as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            params.horizontalBias = if (decode.outgoing) 1f else 0f
            bubbleContainer.layoutParams = params

            if (decode.outgoing) {
                bubbleContainer.setBackgroundResource(R.drawable.bubble_outgoing)
                val textColor = ContextCompat.getColor(context, R.color.bubble_text_outgoing)
                messageText.setTextColor(textColor)
                timeText.setTextColor(textColor)
                snrText.setTextColor(textColor)
                freqText.setTextColor(textColor)
                snrText.text = context.getString(R.string.decodes_outgoing_tag)
                snrIndicator.visibility = View.GONE
                dtText.visibility = View.GONE
                return
            }

            bubbleContainer.setBackgroundResource(R.drawable.bubble_incoming)
            val textColor = ContextCompat.getColor(context, R.color.bubble_text_incoming)
            timeText.setTextColor(textColor)
            snrText.setTextColor(textColor)
            dtText.setTextColor(textColor)
            freqText.setTextColor(textColor)

            snrIndicator.visibility = View.VISIBLE
            snrIndicator.background.mutate().setTint(ContextCompat.getColor(context, decode.snrColorRes))
            snrText.text = String.format("%+d dB", decode.snr)
            dtText.visibility = View.VISIBLE
            dtText.text = String.format("%+.1f s", decode.dt)

            // Highlight group messages
            val isGroupMsg = myGroups.any { it.isNotEmpty() && decode.text.contains(it, ignoreCase = true) }
            messageText.setTextColor(
                if (isGroupMsg) ContextCompat.getColor(context, R.color.highlight_group) else textColor
            )
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
