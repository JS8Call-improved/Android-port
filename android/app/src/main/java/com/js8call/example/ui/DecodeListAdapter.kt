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

    companion object {
        /** Partial-bind payload: update only the TX label, no full rebind. */
        val PAYLOAD_TX_LABEL = Any()
    }

    var myGroups: Set<String> = emptySet()

    /** Live TX label for the newest outgoing bubble, e.g. "TX 1/2 · 12s". */
    var txLabel: String? = null

    var onItemClick: ((DecodedMessage) -> Unit)? = null
    var onItemLongClick: ((DecodedMessage) -> Boolean)? = null

    fun lastOutgoingPosition(): Int = currentList.indexOfLast { it.outgoing }

    override fun onBindViewHolder(
        holder: DecodeViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.contains(PAYLOAD_TX_LABEL)) {
            holder.bindTxLabel(labelAt(position))
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    private fun labelAt(position: Int): String? {
        return if (position == lastOutgoingPosition()) txLabel else null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DecodeViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_decode, parent, false)
        return DecodeViewHolder(view)
    }

    override fun onBindViewHolder(holder: DecodeViewHolder, position: Int) {
        val decode = getItem(position)
        holder.bind(decode, myGroups, if (decode.outgoing) labelAt(position) else null)

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

        fun bindTxLabel(label: String?) {
            if (label != null) {
                snrText.text = label
            } else {
                snrText.text = itemView.context.getString(R.string.decodes_outgoing_tag)
            }
        }

        fun bind(decode: DecodedMessage, myGroups: Set<String>, txLabel: String? = null) {
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
                bindTxLabel(txLabel)
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
