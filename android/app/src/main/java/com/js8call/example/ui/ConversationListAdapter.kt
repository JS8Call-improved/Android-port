package com.js8call.example.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.js8call.example.R
import com.js8call.example.data.ConversationSummary
import com.js8call.example.util.AvatarColor
import com.js8call.example.util.DisplayName
import java.text.SimpleDateFormat
import java.util.*

/**
 * RecyclerView adapter for the conversation list.
 */
class ConversationListAdapter : ListAdapter<ConversationSummary, ConversationListAdapter.ConversationViewHolder>(
    ConversationDiffCallback()
) {

    var onItemClick: ((ConversationSummary) -> Unit)? = null
    var onItemLongClick: ((ConversationSummary) -> Boolean)? = null

    /**
     * Callsign to name, for the rows that have one. Conversations come from
     * the messages table and names from contacts, and the two are joined
     * here rather than in SQL so the conversation query stays as it is.
     */
    var names: Map<String, String> = emptyMap()
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConversationViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_conversation, parent, false)
        return ConversationViewHolder(view)
    }

    override fun onBindViewHolder(holder: ConversationViewHolder, position: Int) {
        val conversation = getItem(position)
        holder.bind(conversation, names[conversation.callsign.uppercase()])

        holder.itemView.setOnClickListener {
            onItemClick?.invoke(conversation)
        }

        holder.itemView.setOnLongClickListener {
            onItemLongClick?.invoke(conversation) ?: false
        }
    }

    class ConversationViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val avatarFrame: FrameLayout = itemView.findViewById(R.id.avatar_frame)
        private val avatarText: TextView = itemView.findViewById(R.id.avatar_text)
        private val callsignText: TextView = itemView.findViewById(R.id.callsign_text)
        private val timestampText: TextView = itemView.findViewById(R.id.timestamp_text)
        private val previewText: TextView = itemView.findViewById(R.id.preview_text)
        private val unreadBadge: TextView = itemView.findViewById(R.id.unread_badge)

        private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        private val dateFormat = SimpleDateFormat("MMM d", Locale.getDefault())

        fun bind(conversation: ConversationSummary, name: String?) {
            val isGroup = conversation.callsign.startsWith("@")

            // The preview holds the second line here, so a named station
            // shows only its name. The callsign is on the contact card.
            callsignText.text = DisplayName.of(conversation.callsign, name)

            avatarText.text = if (isGroup) {
                "@"
            } else {
                DisplayName.initial(conversation.callsign, name)
            }
            avatarFrame.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(
                    itemView.context, AvatarColor.forCallsign(conversation.callsign)
                )
            )

            // Set message preview
            previewText.text = conversation.lastMessage

            // Set timestamp
            timestampText.text = formatTimestamp(conversation.lastTimestamp)

            // Set unread badge
            if (conversation.unreadCount > 0) {
                unreadBadge.visibility = View.VISIBLE
                unreadBadge.text = if (conversation.unreadCount > 99) "99+" else conversation.unreadCount.toString()
            } else {
                unreadBadge.visibility = View.GONE
            }

            // Bold callsign if unread
            callsignText.paint.isFakeBoldText = conversation.unreadCount > 0
        }

        private fun formatTimestamp(timestamp: Long): String {
            val now = System.currentTimeMillis()
            val diff = now - timestamp
            val calendar = Calendar.getInstance()
            val todayStart = calendar.apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            return when {
                timestamp >= todayStart -> timeFormat.format(Date(timestamp))
                timestamp >= todayStart - 24 * 60 * 60 * 1000 -> itemView.context.getString(R.string.messages_yesterday)
                diff < 7 * 24 * 60 * 60 * 1000 -> {
                    SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(timestamp))
                }
                else -> dateFormat.format(Date(timestamp))
            }
        }
    }

    private class ConversationDiffCallback : DiffUtil.ItemCallback<ConversationSummary>() {
        override fun areItemsTheSame(
            oldItem: ConversationSummary,
            newItem: ConversationSummary
        ): Boolean {
            return oldItem.callsign == newItem.callsign
        }

        override fun areContentsTheSame(
            oldItem: ConversationSummary,
            newItem: ConversationSummary
        ): Boolean {
            return oldItem == newItem
        }
    }
}
