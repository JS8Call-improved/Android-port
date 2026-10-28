package com.js8call.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.js8call.example.data.MessageEntity
import com.js8call.example.data.MessageRepository
import com.js8call.example.model.TransmitMessage
import com.js8call.example.model.TransmitState
import com.js8call.example.util.Js8Commands
import kotlinx.coroutines.launch

/**
 * ViewModel for the Transmit screen.
 * Manages message composition and TX queue.
 */
class TransmitViewModel(application: Application) : AndroidViewModel(application) {

    private val _txState = MutableLiveData<TransmitState>(TransmitState.IDLE)
    val txState: LiveData<TransmitState> = _txState

    private val _queue = MutableLiveData<List<TransmitMessage>>(emptyList())
    val queue: LiveData<List<TransmitMessage>> = _queue

    private val _composedMessage = MutableLiveData<String>("")
    val composedMessage: LiveData<String> = _composedMessage

    private val _directedTo = MutableLiveData<String>("")
    val directedTo: LiveData<String> = _directedTo

    private val _txOffsetHz = MutableLiveData<Float>(1500f)
    val txOffsetHz: LiveData<Float> = _txOffsetHz

    private val txQueue = mutableListOf<TransmitMessage>()
    private var activeMessage: TransmitMessage? = null
    private val repository by lazy { MessageRepository.getInstance(getApplication()) }

    /**
     * Queue a message for transmission.
     * @param clearComposed If true, clears the composed message field (for user-initiated sends)
     */
    fun queueMessage(text: String, directed: String? = null, priority: Int = 0, clearComposed: Boolean = true, messageId: Long? = null) {
        if (text.isBlank()) return

        val message = TransmitMessage(
            text = text.trim(),
            directed = directed?.takeIf { it.isNotBlank() },
            priority = priority,
            messageId = messageId
        )

        txQueue.add(message)
        txQueue.sortByDescending { it.priority }

        _queue.value = txQueue.toList()
        if (activeMessage == null) _txState.value = TransmitState.QUEUED

        // User sends start a new composition; automated queue entries do not.
        if (clearComposed) {
            _composedMessage.value = ""
            _directedTo.value = ""
        }
    }

    /** Queue a manual Transmit-screen message, recording direct MSG commands. */
    fun queueManualMessage(text: String, directed: String?, onQueued: () -> Unit) {
        val saved = outgoingSavedMessage(text, directed)
        if (saved == null) {
            queueMessage(text, directed)
            onQueued()
            return
        }
        viewModelScope.launch {
            val id = repository.insertOutgoingMessage(saved.first, saved.second)
            queueMessage(text, directed, messageId = id)
            onQueued()
        }
    }

    private fun updateStatus(message: TransmitMessage?, status: Int) {
        message?.messageId?.let { id ->
            viewModelScope.launch { repository.updateMessageStatus(id, status) }
        }
    }

    fun beginTransmission(message: TransmitMessage) {
        activeMessage = message
    }

    fun hasActiveTransmission(): Boolean = activeMessage != null

    /**
     * Set composed message text.
     */
    fun setComposedMessage(text: String) {
        _composedMessage.value = text
    }

    /**
     * Set directed callsign.
     */
    fun setDirectedTo(callsign: String) {
        _directedTo.value = callsign.uppercase()
    }

    /**
     * Set the TX offset frequency in Hz.
     */
    fun setTxOffset(offsetHz: Float) {
        _txOffsetHz.value = offsetHz
    }

    /**
     * Get the current TX offset frequency in Hz.
     */
    fun getTxOffset(): Float {
        return _txOffsetHz.value ?: 1500f
    }

    /**
     * Send CQ.
     */
    fun sendCQ() {
        queueMessage("CQ CQ CQ", priority = 1)
    }

    /**
     * Send SNR report to specific station.
     */
    fun sendSnrReport(callsign: String, snr: Int) {
        queueMessage("$callsign SNR $snr", directed = callsign, priority = 2)
    }

    /**
     * Remove message from queue.
     */
    fun removeFromQueue(message: TransmitMessage) {
        txQueue.remove(message)
        if (message !== activeMessage) updateStatus(message, MessageEntity.STATUS_FAILED)
        _queue.value = txQueue.toList()

        if (txQueue.isEmpty()) {
            _txState.value = TransmitState.IDLE
        }
    }

    /**
     * Clear TX queue.
     */
    fun clearQueue() {
        txQueue.filter { it !== activeMessage }.forEach { updateStatus(it, MessageEntity.STATUS_FAILED) }
        txQueue.clear()
        _queue.value = emptyList()
        _txState.value = TransmitState.IDLE
    }

    /**
     * Start transmitting (called when engine starts TX).
     */
    fun startTransmitting() {
        _txState.value = TransmitState.TRANSMITTING
    }

    fun setQueued() {
        _txState.value = TransmitState.QUEUED
    }

    /**
     * Transmission complete (called when engine finishes TX).
     */
    fun transmissionComplete() {
        val completed = activeMessage
        updateStatus(completed, MessageEntity.STATUS_SENT)
        activeMessage = null
        if (completed != null) {
            txQueue.remove(completed)
            _queue.value = txQueue.toList()
        }

        _txState.value = if (txQueue.isEmpty()) {
            TransmitState.IDLE
        } else {
            TransmitState.QUEUED
        }
    }

    fun transmissionFailed() {
        updateStatus(activeMessage, MessageEntity.STATUS_FAILED)
        activeMessage = null
        _txState.value = if (txQueue.isEmpty()) {
            TransmitState.IDLE
        } else {
            TransmitState.QUEUED
        }
    }

    /**
     * Get next message to transmit.
     */
    fun getNextMessage(): TransmitMessage? {
        return txQueue.firstOrNull()
    }
}

/** Returns the destination and body for a direct saved MSG, if recognizable. */
internal fun outgoingSavedMessage(text: String, directed: String?): Pair<String, String>? {
    val trimmed = text.trim()
    val directCommand = Js8Commands.matchAt(trimmed)
    if (directCommand != null) {
        if (directCommand.command != Js8Commands.CMD_MSG || directCommand.payload.isBlank()) return null
        val target = directed?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return target to directCommand.payload
    }
    // Explicitly addressed messages take precedence over the selected destination.
    val parts = trimmed.split(Regex("\\s+"), limit = 2)
    if (parts.size != 2 || !Regex("^(?:@[A-Z0-9]+|[A-Z0-9/]*[0-9][A-Z0-9/]*)$", RegexOption.IGNORE_CASE).matches(parts[0])) return null
    val command = Js8Commands.matchAt(parts[1]) ?: return null
    if (command.command != Js8Commands.CMD_MSG || command.payload.isBlank()) return null
    return parts[0] to command.payload
}
