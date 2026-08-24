package com.js8call.example.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import com.js8call.example.model.TransmitMessage
import com.js8call.example.model.TransmitState

/**
 * ViewModel for the TX queue and transmit state.
 */
class TransmitViewModel(application: Application) : AndroidViewModel(application) {

    private val _txState = MutableLiveData<TransmitState>(TransmitState.IDLE)
    val txState: LiveData<TransmitState> = _txState

    private val _queue = MutableLiveData<List<TransmitMessage>>(emptyList())
    val queue: LiveData<List<TransmitMessage>> = _queue

    private val _txOffsetHz = MutableLiveData<Float>(1500f)
    val txOffsetHz: LiveData<Float> = _txOffsetHz

    // Seconds left in the current TX frame, or null when not transmitting.
    // The frame period comes from the selected mode (Slow/Normal/Fast/Turbo).
    private val _txCountdownSeconds = MutableLiveData<Int?>(null)
    val txCountdownSeconds: LiveData<Int?> = _txCountdownSeconds

    // (current frame, total frames) of the transmission in progress, or null when idle.
    private val _txFrameProgress = MutableLiveData<Pair<Int, Int>?>(null)
    val txFrameProgress: LiveData<Pair<Int, Int>?> = _txFrameProgress

    private val txQueue = mutableListOf<TransmitMessage>()

    private val countdownHandler = Handler(Looper.getMainLooper())
    private var txStartedAt = 0L
    private val countdownRunnable = object : Runnable {
        override fun run() {
            val periodMs = framePeriodMs()
            val elapsed = SystemClock.elapsedRealtime() - txStartedAt
            // A message can span several frames; the countdown restarts each frame.
            val leftMs = periodMs - (elapsed % periodMs)
            _txCountdownSeconds.value = ((leftMs + 999) / 1000).toInt()
            countdownHandler.postDelayed(this, 1000)
        }
    }

    /**
     * Queue a message for transmission.
     */
    fun queueMessage(
        text: String,
        directed: String? = null,
        priority: Int = 0,
        dbId: Long? = null,
        mailboxId: Long? = null,
        mailboxRecipient: String? = null
    ) {
        if (text.isBlank()) return

        val message = TransmitMessage(
            text = text.trim(),
            directed = directed?.takeIf { it.isNotBlank() },
            priority = priority,
            dbId = dbId,
            mailboxId = mailboxId,
            mailboxRecipient = mailboxRecipient
        )

        txQueue.add(message)
        txQueue.sortByDescending { it.priority }

        _queue.value = txQueue.toList()
        if (_txState.value != TransmitState.TRANSMITTING) {
            _txState.value = TransmitState.QUEUED
        }
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
        stopCountdown()
    }

    /**
     * Start transmitting (called when engine starts TX).
     */
    fun startTransmitting() {
        _txState.value = TransmitState.TRANSMITTING
        startCountdown()
    }

    fun setQueued() {
        _txState.value = TransmitState.QUEUED
    }

    /**
     * Frame progress from the engine. The index advances when the engine
     * queues the next frame, ~2s before its audio starts; the countdown
     * restarts only on the audio-start edge (TX_STATE_STARTED), so it runs
     * one full cycle per frame without a mid-gap reset.
     */
    fun setTxProgress(frameIndex: Int, frameCount: Int) {
        _txFrameProgress.value = if (frameIndex > 0 && frameCount > 0) {
            frameIndex to frameCount
        } else {
            null
        }
    }

    /**
     * Transmission complete (called when engine finishes TX).
     * @return the message that finished, or null if the queue was empty.
     */
    fun transmissionComplete(): TransmitMessage? {
        stopCountdown()
        val finished = if (txQueue.isNotEmpty()) {
            txQueue.removeAt(0).also { _queue.value = txQueue.toList() }
        } else {
            null
        }

        _txState.value = if (txQueue.isEmpty()) {
            TransmitState.IDLE
        } else {
            TransmitState.QUEUED
        }
        return finished
    }

    /**
     * Transmission failed.
     * @return the message that failed, or null if the queue was empty.
     */
    fun transmissionFailed(): TransmitMessage? {
        stopCountdown()
        val failed = if (txQueue.isNotEmpty()) {
            txQueue.removeAt(0).also { _queue.value = txQueue.toList() }
        } else {
            null
        }
        _txState.value = if (txQueue.isEmpty()) {
            TransmitState.IDLE
        } else {
            TransmitState.QUEUED
        }
        return failed
    }

    /**
     * Get next message to transmit.
     */
    fun getNextMessage(): TransmitMessage? {
        return txQueue.firstOrNull()
    }

    private fun startCountdown() {
        txStartedAt = SystemClock.elapsedRealtime()
        countdownHandler.removeCallbacks(countdownRunnable)
        countdownRunnable.run()
    }

    private fun stopCountdown() {
        countdownHandler.removeCallbacks(countdownRunnable)
        _txCountdownSeconds.value = null
        _txFrameProgress.value = null
    }

    private fun framePeriodMs(): Long {
        val prefs = PreferenceManager.getDefaultSharedPreferences(getApplication())
        return when (prefs.getInt(PREF_TX_SUBMODE, SUBMODE_NORMAL)) {
            SUBMODE_SLOW -> 30000L
            SUBMODE_FAST -> 10000L
            SUBMODE_TURBO -> 6000L
            else -> 15000L
        }
    }

    override fun onCleared() {
        countdownHandler.removeCallbacks(countdownRunnable)
        super.onCleared()
    }

    companion object {
        const val PREF_TX_SUBMODE = "tx_submode"
        const val SUBMODE_NORMAL = 0
        const val SUBMODE_FAST = 1
        const val SUBMODE_TURBO = 2
        const val SUBMODE_SLOW = 4
    }
}
