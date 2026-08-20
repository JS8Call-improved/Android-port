package com.js8call.example.data

import android.content.Context
import androidx.lifecycle.LiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository for message operations, providing a clean API for ViewModels.
 */
class MessageRepository(context: Context) {

    private val database = MessageDatabase.getInstance(context)
    private val messageDao = database.messageDao()

    // ========== Conversation List ==========

    fun getConversations(): LiveData<List<ConversationSummary>> {
        return messageDao.getConversations()
    }

    suspend fun getConversationsSync(): List<ConversationSummary> {
        return withContext(Dispatchers.IO) {
            messageDao.getConversationsSync()
        }
    }

    fun getConversationCallsigns(): LiveData<List<String>> {
        return messageDao.getConversationCallsigns()
    }

    // ========== Messages ==========

    fun getMessagesForConversation(callsign: String): LiveData<List<MessageEntity>> {
        return messageDao.getMessagesForConversation(normalizeCallsign(callsign))
    }

    suspend fun getMessagesForConversationSync(callsign: String): List<MessageEntity> {
        return withContext(Dispatchers.IO) {
            messageDao.getMessagesForConversationSync(normalizeCallsign(callsign))
        }
    }

    fun getAllMessages(): LiveData<List<MessageEntity>> {
        return messageDao.getAllMessages()
    }

    // ========== Insert/Update ==========

    suspend fun insertMessage(message: MessageEntity): Long {
        return withContext(Dispatchers.IO) {
            messageDao.insertMessage(message)
        }
    }

    /**
     * Insert an incoming message from another station.
     * @param conversationId The conversation ID (callsign or @GROUP name)
     * @param from The sender's callsign
     * @param text The message text
     * @param snr Signal-to-noise ratio
     * @param frequency Frequency offset
     * @param relayPath Relay path if relayed
     */
    suspend fun insertIncomingMessage(
        conversationId: String,
        from: String,
        text: String,
        snr: Int?,
        frequency: Float?,
        relayPath: String? = null
    ): Long {
        val message = MessageEntity(
            conversationId = if (conversationId.startsWith("@")) conversationId.uppercase() else normalizeCallsign(conversationId),
            direction = MessageEntity.DIRECTION_INCOMING,
            senderCallsign = normalizeCallsign(from),
            text = text,
            timestamp = System.currentTimeMillis(),
            snr = snr,
            frequency = frequency,
            status = MessageEntity.STATUS_SENT,
            isRead = false,
            relayPath = relayPath
        )
        return insertMessage(message)
    }

    /**
     * Insert an outgoing message being sent to another station.
     */
    suspend fun insertOutgoingMessage(
        to: String,
        text: String,
        status: Int = MessageEntity.STATUS_PENDING
    ): Long {
        val message = MessageEntity(
            conversationId = normalizeCallsign(to),
            direction = MessageEntity.DIRECTION_OUTGOING,
            text = text,
            timestamp = System.currentTimeMillis(),
            status = status,
            isRead = true // Outgoing messages are always "read"
        )
        return insertMessage(message)
    }

    suspend fun updateMessage(message: MessageEntity) {
        withContext(Dispatchers.IO) {
            messageDao.updateMessage(message)
        }
    }

    suspend fun updateMessageStatus(messageId: Long, status: Int) {
        withContext(Dispatchers.IO) {
            messageDao.updateMessageStatus(messageId, status)
        }
    }

    // ========== Read Status ==========

    suspend fun markConversationAsRead(callsign: String) {
        withContext(Dispatchers.IO) {
            messageDao.markConversationAsRead(normalizeCallsign(callsign))
        }
    }

    suspend fun markMessageAsRead(messageId: Long) {
        withContext(Dispatchers.IO) {
            messageDao.markMessageAsRead(messageId)
        }
    }

    // ========== Unread Counts ==========

    fun getTotalUnreadCount(): LiveData<Int> {
        return messageDao.getTotalUnreadCount()
    }

    suspend fun getTotalUnreadCountSync(): Int {
        return withContext(Dispatchers.IO) {
            messageDao.getTotalUnreadCountSync()
        }
    }

    // ========== Delete ==========

    suspend fun deleteMessage(messageId: Long) {
        withContext(Dispatchers.IO) {
            messageDao.deleteMessageById(messageId)
        }
    }

    suspend fun deleteConversation(callsign: String) {
        withContext(Dispatchers.IO) {
            messageDao.deleteConversation(normalizeCallsign(callsign))
        }
    }

    suspend fun deleteAllMessages() {
        withContext(Dispatchers.IO) {
            messageDao.deleteAllMessages()
        }
    }

    // ========== Search ==========

    fun searchMessages(query: String): LiveData<List<MessageEntity>> {
        return messageDao.searchMessages(query)
    }

    // ========== Utility ==========

    private fun normalizeCallsign(callsign: String): String {
        return callsign.trim().uppercase()
    }

    companion object {
        @Volatile
        private var INSTANCE: MessageRepository? = null

        fun getInstance(context: Context): MessageRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MessageRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
