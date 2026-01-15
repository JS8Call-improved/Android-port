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
    }

    var onMessageLongClick: ((MessageEntity) -> Boolean)? = null

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

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = getItem(position)
        when (holder) {
            is IncomingMessageViewHolder -> holder.bind(message)
            is OutgoingMessageViewHolder -> holder.bind(message)
        }

        holder.itemView.setOnLongClickListener {
            onMessageLongClick?.invoke(message) ?: false
        }
    }

    class IncomingMessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val senderText: TextView = itemView.findViewById(R.id.sender_text)
        private val messageText: TextView = itemView.findViewById(R.id.message_text)
        private val timestampText: TextView = itemView.findViewById(R.id.timestamp_text)
        private val snrText: TextView = itemView.findViewById(R.id.snr_text)

        private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

        fun bind(message: MessageEntity) {
            messageText.text = message.text
            timestampText.text = timeFormat.format(Date(message.timestamp))

            // Show sender callsign for group conversations
            val sender = message.senderCallsign
            if (sender != null && message.conversationId.startsWith("@")) {
                senderText.visibility = View.VISIBLE
                senderText.text = sender
            } else {
                senderText.visibility = View.GONE
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

        private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

        fun bind(message: MessageEntity) {
            messageText.text = message.text
            timestampText.text = timeFormat.format(Date(message.timestamp))

            // Update status icon based on message status
            val (iconRes, tintColor) = when (message.status) {
                MessageEntity.STATUS_PENDING -> {
                    android.R.drawable.ic_menu_recent_history to R.color.message_pending
                }
                MessageEntity.STATUS_SENT -> {
                    android.R.drawable.ic_menu_send to R.color.message_sent
                }
                MessageEntity.STATUS_ACKED -> {
                    android.R.drawable.checkbox_on_background to R.color.message_acked
                }
                MessageEntity.STATUS_FAILED -> {
                    android.R.drawable.ic_delete to R.color.message_failed
                }
                else -> {
                    android.R.drawable.ic_menu_send to R.color.message_sent
                }
            }

            statusIcon.setImageResource(iconRes)
            statusIcon.setColorFilter(
                ContextCompat.getColor(itemView.context, tintColor),
                android.graphics.PorterDuff.Mode.SRC_IN
            )
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
