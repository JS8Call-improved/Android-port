package com.js8call.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a JS8 directed message stored in the database.
 */
@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["conversationId"]),
        Index(value = ["timestamp"]),
        Index(value = ["isRead"])
    ]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Callsign or @GROUP that identifies the conversation */
    val conversationId: String,

    /** 0 = incoming, 1 = outgoing */
    val direction: Int,

    /** The actual sender callsign (for group messages, different from conversationId) */
    val senderCallsign: String? = null,

    /** The message text content */
    val text: String,

    /** UTC timestamp in milliseconds */
    val timestamp: Long,

    /** Signal-to-noise ratio (incoming only) */
    val snr: Int? = null,

    /** Frequency offset in Hz (incoming only) */
    val frequency: Float? = null,

    /** Message status: 0=pending, 1=sent, 2=acked, 3=failed */
    val status: Int = STATUS_SENT,

    /** Whether the message has been read */
    val isRead: Boolean = false,

    /** Relay path if message was relayed (e.g., "CALL1>CALL2") */
    val relayPath: String? = null
) {
    companion object {
        const val DIRECTION_INCOMING = 0
        const val DIRECTION_OUTGOING = 1

        const val STATUS_PENDING = 0
        const val STATUS_SENT = 1
        const val STATUS_ACKED = 2
        const val STATUS_FAILED = 3
    }

    fun isIncoming() = direction == DIRECTION_INCOMING
    fun isOutgoing() = direction == DIRECTION_OUTGOING
    fun isPending() = status == STATUS_PENDING
    fun isAcked() = status == STATUS_ACKED
    fun isFailed() = status == STATUS_FAILED
}

/**
 * Summary data for displaying in the conversation list.
 */
data class ConversationSummary(
    val callsign: String,
    val lastMessage: String,
    val lastTimestamp: Long,
    val unreadCount: Int
)
