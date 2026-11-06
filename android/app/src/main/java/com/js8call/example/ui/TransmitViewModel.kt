package com.js8call.example.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.js8call.example.MainActivity
import com.js8call.example.data.MessageEntity
import com.js8call.example.data.MessageRepository
import com.js8call.example.model.TransmitMessage
import com.js8call.example.model.TransmitState
import com.js8call.example.util.Js8Commands
import kotlinx.coroutines.launch

/**
 * ViewModel for the TX queue and transmit state.
 */
class TransmitViewModel(application: Application) : AndroidViewModel(application) {

    private val _txState = MutableLiveData<TransmitState>(TransmitState.IDLE)
    val txState: LiveData<TransmitState> = _txState

    private var txOffsetHz = 1500f

    private val txQueue = mutableListOf<TransmitMessage>()
    private var activeMessage: TransmitMessage? = null
    private val repository by lazy { MessageRepository.getInstance(getApplication()) }

    /**
     * Queue a message for transmission.
     */
    fun queueMessage(text: String, directed: String? = null, priority: Int = 0, messageId: Long? = null) {
        if (text.isBlank()) return

        val message = TransmitMessage(
            text = text.trim(),
            directed = directed?.takeIf { it.isNotBlank() },
            priority = priority,
            messageId = messageId
        )

        txQueue.add(message)
        txQueue.sortByDescending { it.priority }

        if (activeMessage == null) _txState.value = TransmitState.QUEUED
    }

    /** Queue a typed broadcast, recording direct MSG commands. */
    fun queueManualMessage(text: String) {
        val saved = outgoingSavedMessage(text, null)
        if (saved == null) {
            queueMessage(text)
            processQueue()
            return
        }
        viewModelScope.launch {
            val id = repository.insertOutgoingMessage(saved.first, saved.second)
            queueMessage(text, messageId = id)
            processQueue()
        }
    }

    /** Queue a send typed in [callsign]'s thread, saving it there first. */
    fun queueThreadMessage(callsign: String, text: String) {
        // The engine drops the selected call before CQ, HB or another callsign.
        val addressed = "$callsign $text"
        val body = outgoingSavedMessage(addressed, callsign)?.second ?: text
        viewModelScope.launch {
            val id = repository.insertOutgoingMessage(callsign, body)
            queueMessage(addressed, callsign, messageId = id)
            processQueue()
        }
    }

    private fun processQueue() {
        LocalBroadcastManager.getInstance(getApplication())
            .sendBroadcast(Intent(MainActivity.ACTION_PROCESS_TX_QUEUE))
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
     * Set the TX offset frequency in Hz.
     */
    fun setTxOffset(offsetHz: Float) {
        txOffsetHz = offsetHz
    }

    /**
     * Get the current TX offset frequency in Hz.
     */
    fun getTxOffset(): Float {
        return txOffsetHz
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
        if (completed != null) txQueue.remove(completed)

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
