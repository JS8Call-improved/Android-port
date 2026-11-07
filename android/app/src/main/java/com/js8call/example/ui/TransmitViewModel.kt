package com.js8call.example.ui

import android.app.Application
import android.content.Intent
import android.database.sqlite.SQLiteException
import android.util.Log
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
import com.js8call.example.util.CallsignValidator
import com.js8call.example.util.Js8Commands
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

/**
 * ViewModel for the TX queue and transmit state.
 */
class TransmitViewModel(application: Application) : AndroidViewModel(application) {

    /** A queue entry as the queue sheet shows it. */
    data class QueueItem(val message: TransmitMessage, val sending: Boolean)

    private val _txState = MutableLiveData<TransmitState>(TransmitState.IDLE)
    val txState: LiveData<TransmitState> = _txState

    private val _queue = MutableLiveData<List<QueueItem>>(emptyList())
    val queue: LiveData<List<QueueItem>> = _queue

    private val txQueue = mutableListOf<TransmitMessage>()
    private var activeMessage: TransmitMessage? = null
    private var activeOnAir = false
    // Failed thread sends by message id, kept for Retry
    private val failedMessages = mutableMapOf<Long, TransmitMessage>()
    private val sendOrder = Mutex()
    private val repository by lazy { MessageRepository.getInstance(getApplication()) }

    /**
     * Queue a message for transmission.
     */
    fun queueMessage(text: String, directed: String? = null, priority: Int = 0, messageId: Long? = null) {
        if (text.isBlank()) return

        enqueue(
            TransmitMessage(
                text = text.trim(),
                directed = directed?.takeIf { it.isNotBlank() },
                priority = priority,
                messageId = messageId
            )
        )
    }

    private fun enqueue(message: TransmitMessage) {
        txQueue.add(message)
        txQueue.sortByDescending { it.priority }
        if (_txState.value == TransmitState.IDLE) _txState.value = TransmitState.QUEUED
        publishQueue()
    }

    /** Queue a typed broadcast, recording direct MSG commands. */
    fun queueManualMessage(text: String) {
        val upper = text.trim().uppercase(Locale.ROOT)
        saveAndQueue(upper, null, outgoingSavedMessage(upper, null))
    }

    /** Queue a send typed in [callsign]'s thread, saving it there first. */
    fun queueThreadMessage(callsign: String, text: String) {
        val addressed = threadText(callsign, text)
        saveAndQueue(addressed, callsign, callsign to threadBubbleText(callsign, addressed))
    }

    // One at a time, so a send waiting on its insert is not overtaken
    private fun saveAndQueue(text: String, directed: String?, saved: Pair<String, String>?) {
        viewModelScope.launch {
            sendOrder.withLock {
                val id = saved?.let { (to, body) -> insertOutgoing(to, body) }
                queueMessage(text, directed, messageId = id)
            }
            processQueue()
        }
    }

    private suspend fun insertOutgoing(to: String, body: String): Long? = try {
        repository.insertOutgoingMessage(to, body)
    } catch (e: SQLiteException) {
        // Still send it; it just won't show in the thread
        Log.e(TAG, "Couldn't save outgoing message", e)
        null
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

    private fun publishQueue() {
        _queue.value = txQueue.map { QueueItem(it, it === activeMessage) }
    }

    // A heartbeat the service started on its own can be on the air
    private fun settleUnlessOnAir() {
        if (_txState.value == TransmitState.TRANSMITTING) publishQueue() else settleState()
    }

    private fun settleState() {
        _txState.value = if (txQueue.isEmpty()) TransmitState.IDLE else TransmitState.QUEUED
        publishQueue()
    }

    /** Drops a waiting send and its bubble. The one handed to the engine stays. */
    fun cancel(message: TransmitMessage) {
        if (message === activeMessage || !txQueue.removeAll { it === message }) return
        message.messageId?.let { deleteBubble(it) }
        if (activeMessage == null) settleUnlessOnAir() else publishQueue()
    }

    fun cancelAll() {
        txQueue.filter { it !== activeMessage }.forEach { cancel(it) }
    }

    /** Drops a thread send by message id; false when it is already with the engine. */
    fun discard(messageId: Long): Boolean {
        if (activeMessage?.messageId == messageId) return false
        failedMessages.remove(messageId)
        val queued = txQueue.firstOrNull { it.messageId == messageId }
        if (queued != null) cancel(queued) else deleteBubble(messageId)
        return true
    }

    private fun deleteBubble(messageId: Long) {
        viewModelScope.launch { repository.deleteMessage(messageId) }
    }

    fun canRetry(messageId: Long): Boolean = messageId in failedMessages

    fun retry(messageId: Long) {
        if (messageId !in failedMessages) return
        viewModelScope.launch {
            sendOrder.withLock {
                repository.updateMessageStatus(messageId, MessageEntity.STATUS_PENDING)
                // Deleted or already retried while the status was saving
                val message = failedMessages.remove(messageId) ?: return@withLock
                enqueue(message.copy(timestamp = System.currentTimeMillis()))
            }
            processQueue()
        }
    }

    fun beginTransmission(message: TransmitMessage) {
        activeMessage = message
        activeOnAir = false
        publishQueue()
    }

    fun hasActiveTransmission(): Boolean = activeMessage != null

    /**
     * Start transmitting (called when engine starts TX).
     */
    fun startTransmitting() {
        if (activeMessage != null) activeOnAir = true
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
        if (completed != null) txQueue.removeAll { it === completed }
        settleState()
    }

    /** Drops a send cut off on the air or [refused]. Any other stays at the head and returns false. */
    fun transmissionFailed(refused: Boolean = false): Boolean {
        val failed = activeMessage
        activeMessage = null
        if (failed != null && !activeOnAir && !refused) {
            settleState()
            return false
        }
        updateStatus(failed, MessageEntity.STATUS_FAILED)
        if (failed != null) {
            txQueue.removeAll { it === failed }
            failed.messageId?.let { failedMessages[it] = failed }
        }
        settleState()
        return true
    }

    fun handedBack() {
        activeMessage = null
        settleUnlessOnAir()
    }

    /** TX broadcasts stop reaching us; a send in flight may go out unseen. */
    fun stopListening() {
        if (activeMessage != null) activeOnAir = true
    }

    /** Monitoring stopped. A send that never reached the air waits for the next start. */
    fun engineStopped() {
        transmissionFailed()
    }

    /**
     * Get next message to transmit.
     */
    fun getNextMessage(): TransmitMessage? {
        return txQueue.firstOrNull()
    }

    companion object {
        private const val TAG = "TransmitViewModel"
    }
}

/** What goes on the air for [text] typed in [callsign]'s thread. */
internal fun threadText(callsign: String, text: String): String {
    // The engine matches the selected call case-sensitively
    val trimmed = text.trim().uppercase(Locale.ROOT)
    // Raw text and text already addressed to the station get no prefix
    if (trimmed.startsWith("`")) return trimmed
    if (trimmed.substringBefore(' ') == callsign.uppercase(Locale.ROOT)) return trimmed
    // The engine drops the selected call before CQ, HB or another callsign.
    return "$callsign $trimmed"
}

/** What [callsign]'s thread shows for [addressed], the text [threadText] sends. */
internal fun threadBubbleText(callsign: String, addressed: String): String {
    outgoingSavedMessage(addressed, callsign)?.let { return it.second }
    val prefix = "$callsign "
    return if (addressed.startsWith(prefix, ignoreCase = true)) addressed.drop(prefix.length) else addressed
}

private val whitespace = Regex("\\s+")
private val callsignAddress = Regex("[A-Z0-9/]*[0-9][A-Z0-9/]*", RegexOption.IGNORE_CASE)

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
    val parts = trimmed.split(whitespace, limit = 2)
    if (parts.size != 2) return null
    val address = parts[0]
    if (!CallsignValidator.isGroup(address) &&
        !callsignAddress.matches(address)
    ) return null
    val command = Js8Commands.matchAt(parts[1]) ?: return null
    if (command.command != Js8Commands.CMD_MSG || command.payload.isBlank()) return null
    return address to command.payload
}
