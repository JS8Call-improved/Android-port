package com.js8call.example.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.js8call.example.R
import com.js8call.example.data.MessageEntity
import com.js8call.example.util.RelayPath
import java.text.SimpleDateFormat
import java.util.*

/**
 * RecyclerView adapter for message bubbles in a conversation.
 */
class MessageBubbleAdapter : ListAdapter<MessageEntity, RecyclerView.ViewHolder>(
    MessageDiffCallback()
) {

    companion object {
        private const val VIEW_TYPE_INCOMING = 0
        private const val VIEW_TYPE_OUTGOING = 1

        /** Partial-bind payload: update only the status line, no full rebind. */
        val PAYLOAD_STATUS = Any()
    }

    var onMessageLongClick: ((MessageEntity) -> Boolean)? = null

    /** The sender's name on a group message opens their contact card. */
    var onSenderClick: ((String) -> Unit)? = null

    /** Message id of the bubble currently transmitting, if any. */
    var sendingMessageId: Long? = null

    /** Live status line for the sending bubble, e.g. "Sending 1/2 · 12s". */
    var sendingLabel: String? = null

    override fun getItemViewType(position: Int): Int {
        return if (getItem(position).isIncoming()) VIEW_TYPE_INCOMING else VIEW_TYPE_OUTGOING
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_INCOMING -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_message_incoming, parent, false)
                IncomingMessageViewHolder(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_message_outgoing, parent, false)
                OutgoingMessageViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.contains(PAYLOAD_STATUS) && holder is OutgoingMessageViewHolder) {
            val message = getItem(position)
            holder.bindStatus(message, labelFor(message))
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = getItem(position)
        when (holder) {
            is IncomingMessageViewHolder -> holder.bind(message, onSenderClick)
            is OutgoingMessageViewHolder -> holder.bind(message, labelFor(message))
        }

        holder.itemView.setOnLongClickListener {
            onMessageLongClick?.invoke(message) ?: false
        }
    }

    private fun labelFor(message: MessageEntity): String? {
        return if (message.id == sendingMessageId) sendingLabel else null
    }


    class IncomingMessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val senderText: TextView = itemView.findViewById(R.id.sender_text)
        private val messageText: TextView = itemView.findViewById(R.id.message_text)
        private val timestampText: TextView = itemView.findViewById(R.id.timestamp_text)
        private val snrText: TextView = itemView.findViewById(R.id.snr_text)
        private val relayText: TextView = itemView.findViewById(R.id.relay_text)

        private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

        fun bind(message: MessageEntity, onSenderClick: ((String) -> Unit)?) {
            messageText.text = message.text
            timestampText.text = timeFormat.format(Date(message.timestamp))
            bindRelayPath(relayText, message)

            // Show sender callsign for group conversations
            val sender = message.senderCallsign
            if (sender != null && message.conversationId.startsWith("@")) {
                senderText.visibility = View.VISIBLE
                senderText.text = sender
                senderText.setOnClickListener { onSenderClick?.invoke(sender) }
            } else {
                senderText.visibility = View.GONE
                senderText.setOnClickListener(null)
            }

            // Show SNR if available
            if (message.snr != null) {
                snrText.visibility = View.VISIBLE
                snrText.text = String.format("%+d dB", message.snr)
            } else {
                snrText.visibility = View.GONE
            }
        }
    }

    class OutgoingMessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val messageText: TextView = itemView.findViewById(R.id.message_text)
        private val timestampText: TextView = itemView.findViewById(R.id.timestamp_text)
        private val statusIcon: ImageView = itemView.findViewById(R.id.status_icon)
        private val statusText: TextView = itemView.findViewById(R.id.status_text)
        private val relayText: TextView = itemView.findViewById(R.id.relay_text)

        private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

        fun bind(message: MessageEntity, sendingLabel: String?) {
            messageText.text = message.text
            timestampText.text = timeFormat.format(Date(message.timestamp))
            bindStatus(message, sendingLabel)

            bindRelayPath(relayText, message)

            // Update status icon based on message status
            val (iconRes, tintColor) = when (message.status) {
                MessageEntity.STATUS_PENDING -> {
                    R.drawable.ic_schedule to R.color.message_pending
                }
                MessageEntity.STATUS_SENT -> {
                    R.drawable.ic_check to R.color.message_sent
                }
                MessageEntity.STATUS_ACKED -> {
                    R.drawable.ic_done_all to R.color.message_acked
                }
                MessageEntity.STATUS_FAILED -> {
                    R.drawable.ic_error_outline to R.color.message_failed
                }
                else -> {
                    R.drawable.ic_check to R.color.message_sent
                }
            }

            statusIcon.setImageResource(iconRes)
            statusIcon.setColorFilter(
                ContextCompat.getColor(itemView.context, tintColor),
                android.graphics.PorterDuff.Mode.SRC_IN
            )
        }

        fun bindStatus(message: MessageEntity, sendingLabel: String?) {
            val context = itemView.context
            when {
                sendingLabel != null -> {
                    statusText.visibility = View.VISIBLE
                    statusText.text = sendingLabel
                }
                message.isPending() -> {
                    statusText.visibility = View.VISIBLE
                    statusText.text = context.getString(R.string.msg_status_queued)
                }
                message.isFailed() -> {
                    statusText.visibility = View.VISIBLE
                    statusText.text = context.getString(R.string.messages_failed)
                }
                else -> statusText.visibility = View.GONE
            }
        }
    }

    private class MessageDiffCallback : DiffUtil.ItemCallback<MessageEntity>() {
        override fun areItemsTheSame(
            oldItem: MessageEntity,
            newItem: MessageEntity
        ): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(
            oldItem: MessageEntity,
            newItem: MessageEntity
        ): Boolean {
            return oldItem == newItem
        }
    }
}

/**
 * The stations that carried this message, shown on the message itself. The
 * thread's path can change after the fact, so the bubble is the only honest
 * record of how this one travelled.
 */
private fun bindRelayPath(view: TextView, message: MessageEntity) {
    val hops = RelayPath.parse(message.relayPath)
    if (hops.isEmpty()) {
        view.visibility = View.GONE
    } else {
        view.visibility = View.VISIBLE
        view.text = view.context.getString(R.string.relay_via, hops.joinToString(" › "))
    }
}
