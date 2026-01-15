package com.js8call.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.js8call.example.model.TransmitMessage
import com.js8call.example.model.TransmitState

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

    /**
     * Queue a message for transmission.
     * @param clearComposed If true, clears the composed message field (for user-initiated sends)
     */
    fun queueMessage(text: String, directed: String? = null, priority: Int = 0, clearComposed: Boolean = true) {
        if (text.isBlank()) return

        val message = TransmitMessage(
            text = text.trim(),
            directed = directed?.takeIf { it.isNotBlank() },
            priority = priority
        )

        txQueue.add(message)
        txQueue.sortByDescending { it.priority }

        _queue.value = txQueue.toList()
        _txState.value = TransmitState.QUEUED

        // Clear composed message after queuing (only for user-initiated sends)
        if (clearComposed) {
            _composedMessage.value = ""
        }
    }

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
        _queue.value = txQueue.toList()

        if (txQueue.isEmpty()) {
            _txState.value = TransmitState.IDLE
        }
    }

    /**
     * Clear TX queue.
     */
    fun clearQueue() {
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
        // Remove first item from queue
        if (txQueue.isNotEmpty()) {
            txQueue.removeAt(0)
            _queue.value = txQueue.toList()
        }

        _txState.value = if (txQueue.isEmpty()) {
            TransmitState.IDLE
        } else {
            TransmitState.QUEUED
        }
    }

    fun transmissionFailed() {
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
