package com.js8call.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.js8call.example.data.ConversationSummary
import com.js8call.example.data.MessageEntity
import com.js8call.example.data.MessageRepository
import kotlinx.coroutines.launch

/**
 * ViewModel for managing messages and conversations.
 */
class MessagesViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MessageRepository.getInstance(application)

    // Conversation list
    val conversations: LiveData<List<ConversationSummary>> = repository.getConversations()

    // Total unread count (for badge on Messages tab)
    val totalUnreadCount: LiveData<Int> = repository.getTotalUnreadCount()

    // Currently selected conversation
    private val _currentCallsign = MutableLiveData<String?>()
    val currentCallsign: LiveData<String?> = _currentCallsign

    // Messages for current conversation
    private var currentMessagesLiveData: LiveData<List<MessageEntity>>? = null

    /**
     * Get messages for a specific conversation.
     */
    fun getMessagesForConversation(callsign: String): LiveData<List<MessageEntity>> {
        _currentCallsign.value = callsign
        return repository.getMessagesForConversation(callsign)
    }

    /**
     * Mark all messages in a conversation as read.
     */
    fun markConversationAsRead(callsign: String) {
        viewModelScope.launch {
            repository.markConversationAsRead(callsign)
        }
    }

    /**
     * Insert an incoming message.
     * @param conversationId The callsign or group to associate with (for threading)
     * @param from The sender's callsign
     * @param text The message text
     * @param snr Signal-to-noise ratio
     * @param frequency Frequency offset
     * @param relayPath Relay path if relayed
     */
    fun insertIncomingMessage(
        conversationId: String,
        from: String,
        text: String,
        snr: Int? = null,
        frequency: Float? = null,
        relayPath: String? = null
    ) {
        viewModelScope.launch {
            repository.insertIncomingMessage(conversationId, from, text, snr, frequency, relayPath)
        }
    }

    /**
     * Insert an outgoing message (when user sends).
     */
    fun insertOutgoingMessage(to: String, text: String): LiveData<Long> {
        val result = MutableLiveData<Long>()
        viewModelScope.launch {
            val id = repository.insertOutgoingMessage(to, text, MessageEntity.STATUS_PENDING)
            result.postValue(id)
        }
        return result
    }

    /**
     * Update message status (e.g., when TX completes or ACK received).
     */
    fun updateMessageStatus(messageId: Long, status: Int) {
        viewModelScope.launch {
            repository.updateMessageStatus(messageId, status)
        }
    }

    /**
     * Delete a conversation and all its messages.
     */
    fun deleteConversation(callsign: String) {
        viewModelScope.launch {
            repository.deleteConversation(callsign)
        }
    }

    /**
     * Delete a single message.
     */
    fun deleteMessage(messageId: Long) {
        viewModelScope.launch {
            repository.deleteMessage(messageId)
        }
    }

    /**
     * Clear all messages.
     */
    fun deleteAllMessages() {
        viewModelScope.launch {
            repository.deleteAllMessages()
        }
    }

    /**
     * Search messages.
     */
    fun searchMessages(query: String): LiveData<List<MessageEntity>> {
        return repository.searchMessages(query)
    }
}
