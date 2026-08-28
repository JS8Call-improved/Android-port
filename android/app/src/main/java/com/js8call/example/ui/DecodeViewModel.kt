package com.js8call.example.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import com.js8call.example.model.DecodedMessage
import com.js8call.example.model.MessageBuffer
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

/** A decoded frame reaching the list: its rendered text and JS8 type bits. */
internal data class DecodeFrame(val text: String, val type: Int)

private fun isDataFrame(type: Int): Boolean = (type and 0x4) != 0

internal fun assembleMultipartDecodeText(frames: List<DecodeFrame>): String = buildString {
    frames.forEachIndexed { index, frame ->
        if (index > 0 && needsSpaceBefore(this, frames[index - 1].type, frame.text)) {
            append(' ')
        }
        append(frame.text)
    }
}

// Data frames split mid-word and carry their own spaces; only a directed header
// can leave a flush boundary that needs one.
private fun needsSpaceBefore(soFar: CharSequence, prevType: Int, next: String): Boolean {
    if (isDataFrame(prevType)) return false
    if (soFar.isEmpty() || next.isEmpty()) return false
    return !soFar.last().isWhitespace() && !next.first().isWhitespace()
}

/**
 * ViewModel for the Decodes screen.
 * Manages the list of decoded messages.
 */
class DecodeViewModel(application: Application) : AndroidViewModel(application) {

    private val _decodes = MutableLiveData<List<DecodedMessage>>(emptyList())
    val decodes: LiveData<List<DecodedMessage>> = _decodes

    private val _filterText = MutableLiveData<String>("")
    val filterText: LiveData<String> = _filterText

    private val allDecodes = mutableListOf<DecodedMessage>()
    private val maxDecodes = 1000 // Keep last 1000 decodes
    private var hasLoadedPersistedDecodes = false

    // Message buffering for multipart messages
    private val messageBuffers = mutableMapOf<Int, MessageBuffer>()
    private val bufferTimeoutMs = 90_000L // 90 seconds
    private val frequencyToleranceHz = 10.0f // Match JS8 rx threshold for frame grouping

    /**
     * Add a new decoded message.
     */
    fun addDecode(message: DecodedMessage) {
        allDecodes.add(0, message) // Add to beginning

        // Limit size
        if (allDecodes.size > maxDecodes) {
            allDecodes.removeAt(allDecodes.size - 1)
        }

        applyFilter()
    }

    fun loadPersistedDecodesIfEnabled() {
        if (hasLoadedPersistedDecodes) return
        hasLoadedPersistedDecodes = true

        val app = getApplication<Application>()
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        if (!prefs.getBoolean(PREF_PERSIST_DECODES, false)) return

        val file = File(app.filesDir, PERSISTED_DECODE_FILE)
        if (!file.exists()) return

        val contents = try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading persisted decodes", e)
            return
        }
        if (contents.isBlank()) return

        val loaded = mutableListOf<DecodedMessage>()
        try {
            val arr = JSONArray(contents)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                loaded.add(
                    DecodedMessage(
                        utc = obj.optInt("utc"),
                        snr = obj.optInt("snr"),
                        dt = obj.optDouble("dt").toFloat(),
                        frequency = obj.optDouble("frequency").toFloat(),
                        text = obj.optString("text"),
                        type = obj.optInt("type"),
                        quality = obj.optDouble("quality").toFloat(),
                        mode = obj.optInt("mode"),
                        driftMs = obj.optInt("driftMs"),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed parsing persisted decodes", e)
            return
        }

        if (loaded.isEmpty()) return
        allDecodes.clear()
        allDecodes.addAll(loaded.take(maxDecodes))
        applyFilter()
    }

    /**
     * Add a decoded message from engine callback.
     */
    fun addDecode(
        utc: Int,
        snr: Int,
        dt: Float,
        freq: Float,
        text: String,
        type: Int,
        quality: Float,
        mode: Int,
        driftMs: Int = 0
    ) {
        val message = DecodedMessage(utc, snr, dt, freq, text, type, quality, mode, driftMs)

        // Clean up stale buffers before processing new message
        cleanupStaleBuffers()

        // Find matching buffer within frequency tolerance
        val matchingBufferKey = findMatchingBufferKey(freq)

        // Handle complete single-frame messages (first + last)
        if (message.isFirstFrame() && message.isLastFrame()) {
            if (matchingBufferKey != null) {
                messageBuffers.remove(matchingBufferKey)
            }
            addDecodeToDisplay(message)
            return
        }

        // Handle first frame (clear existing buffer and create new one)
        if (message.isFirstFrame()) {
            // Remove any existing buffer at matching frequency
            if (matchingBufferKey != null) {
                messageBuffers.remove(matchingBufferKey)
            }
            // Create new buffer with current frequency as key
            val newKey = freq.roundToInt()
            messageBuffers[newKey] = MessageBuffer(
                frames = mutableListOf(message),
                firstTimestamp = message.timestamp,
                frequencyKey = newKey
            )
            return
        }

        // Handle middle/last frames (including unflagged frames when buffering)
        if (matchingBufferKey != null) {
            val buffer = messageBuffers[matchingBufferKey]!!
            // Add frame to existing buffer
            buffer.frames.add(message)

            // If this is the last frame, assemble and display
            if (message.isLastFrame()) {
                val assembled = assembleMessage(buffer)
                addDecodeToDisplay(assembled)
                messageBuffers.remove(matchingBufferKey)
            }
            return
        }

        // Handle single-frame messages (display immediately)
        if (message.isSingleFrame()) {
            addDecodeToDisplay(message)
            return
        } else {
            // No buffer exists - this might be a missed first frame
            // Create a buffer anyway and add this frame
            val newKey = freq.roundToInt()
            messageBuffers[newKey] = MessageBuffer(
                frames = mutableListOf(message),
                firstTimestamp = message.timestamp,
                frequencyKey = newKey
            )

            // If it's also a last frame, assemble immediately
            if (message.isLastFrame()) {
                val assembled = assembleMessage(messageBuffers[newKey]!!)
                addDecodeToDisplay(assembled)
                messageBuffers.remove(newKey)
            }
        }
    }

    /**
     * Add a decoded message directly to the display list.
     */
    private fun addDecodeToDisplay(message: DecodedMessage) {
        allDecodes.add(0, message) // Add to beginning

        // Limit size
        if (allDecodes.size > maxDecodes) {
            allDecodes.removeAt(allDecodes.size - 1)
        }

        applyFilter()
    }

    /**
     * Assemble a complete message from buffered frames.
     */
    private fun assembleMessage(buffer: MessageBuffer): DecodedMessage {
        val frameTexts = buffer.frames.map { it.text }.toMutableList()
        normalizeCompoundDirectedHelpers(buffer.frames, frameTexts)

        val frames = buffer.frames.zip(frameTexts) { frame, text -> DecodeFrame(text, frame.type) }
        val assembledText = assembleMultipartDecodeText(frames)

        // Use the last frame's metadata (most recent)
        val lastFrame = buffer.frames.last()

        return DecodedMessage(
            utc = lastFrame.utc,
            snr = lastFrame.snr,
            dt = lastFrame.dt,
            frequency = lastFrame.frequency,
            text = assembledText,
            type = lastFrame.type,
            quality = lastFrame.quality,
            mode = lastFrame.mode,
            driftMs = lastFrame.driftMs,
            timestamp = lastFrame.timestamp
        )
    }

    private fun normalizeCompoundDirectedHelpers(
        frames: List<DecodedMessage>,
        frameTexts: MutableList<String>
    ) {
        if (frameTexts.size < 2 || frames.size < 2) return

        val firstFrame = frames[0]
        val secondFrame = frames[1]
        if (isDataFrame(firstFrame.type) || isDataFrame(secondFrame.type)) return
        if (!firstFrame.isFirstFrame() || firstFrame.isLastFrame()) return
        if (secondFrame.isFirstFrame()) return

        val first = frameTexts[0].trim()
        val second = frameTexts[1].trim()
        if (!isCompoundDeHelperFrame(first)) return

        val fromCall = first.substringBefore(' ').trim()
        if (fromCall.isEmpty()) return

        val rewrittenDirected = rewriteDirectedPlaceholder(second, fromCall)
        if (rewrittenDirected != null) {
            frameTexts[0] = ""
            frameTexts[1] = rewrittenDirected
            return
        }

        if (isDirectedCompoundHeader(second)) {
            frameTexts[0] = ""
            frameTexts[1] = "$fromCall: $second"
        }
    }

    private fun rewriteDirectedPlaceholder(text: String, fromCall: String): String? {
        val match = directedPlaceholderRegex.matchEntire(text.trim()) ?: return null
        val tail = match.groupValues.getOrElse(1) { "" }.trim()
        if (tail.isEmpty()) return null
        return "$fromCall: $tail"
    }

    private fun isCompoundDeHelperFrame(text: String): Boolean {
        val parts = text.trim().split(Regex("\\s+"))
        if (parts.size != 2) return false
        val call = parts[0].uppercase()
        val grid = parts[1].uppercase()
        return isCallsignLike(call) && gridRegex.matches(grid)
    }

    private fun isCallsignLike(token: String): Boolean {
        val upper = token.trim().uppercase()
        if (upper.length !in 3..12) return false
        if (!callsignRegex.matches(upper)) return false
        if (!upper.any { it.isLetter() } || !upper.any { it.isDigit() }) return false
        return true
    }

    private fun isDirectedCompoundHeader(text: String): Boolean {
        val tokens = text.trim().split(Regex("\\s+"))
        if (tokens.isEmpty()) return false
        if (!isCallsignLike(tokens[0])) return false
        if (tokens.size == 1) return true

        val tail = tokens.drop(1).joinToString(" ").uppercase()
        return directedCommandTailRegex.matches(tail)
    }

    /**
     * Find a buffer key that matches the given frequency within tolerance.
     * Returns null if no matching buffer is found.
     */
    private fun findMatchingBufferKey(freq: Float): Int? {
        for ((key, _) in messageBuffers) {
            // Check if frequency is within tolerance of buffer's frequency key
            if (kotlin.math.abs(freq - key) <= frequencyToleranceHz) {
                return key
            }
        }
        return null
    }

    /**
     * Clean up stale message buffers (older than timeout).
     */
    private fun cleanupStaleBuffers() {
        val currentTime = System.currentTimeMillis()
        val keysToRemove = mutableListOf<Int>()

        for ((key, buffer) in messageBuffers) {
            val age = currentTime - buffer.firstTimestamp
            if (age > bufferTimeoutMs) {
                // Buffer timed out - display incomplete message
                val assembled = assembleMessage(buffer)
                addDecodeToDisplay(assembled)
                keysToRemove.add(key)
            }
        }

        keysToRemove.forEach { messageBuffers.remove(it) }
    }

    /**
     * Clear all decodes.
     */
    fun clearDecodes() {
        allDecodes.clear()
        _decodes.value = emptyList()
    }

    fun persistDecodesOnStop() {
        val app = getApplication<Application>()
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        val file = File(app.filesDir, PERSISTED_DECODE_FILE)
        if (!prefs.getBoolean(PREF_PERSIST_DECODES, false)) {
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "Failed to delete persisted decodes")
            }
            return
        }

        val arr = JSONArray()
        allDecodes.take(maxDecodes).forEach { decode ->
            val obj = JSONObject()
            obj.put("utc", decode.utc)
            obj.put("snr", decode.snr)
            obj.put("dt", decode.dt)
            obj.put("frequency", decode.frequency)
            obj.put("text", decode.text)
            obj.put("type", decode.type)
            obj.put("quality", decode.quality)
            obj.put("mode", decode.mode)
            obj.put("driftMs", decode.driftMs)
            obj.put("timestamp", decode.timestamp)
            arr.put(obj)
        }

        try {
            file.writeText(arr.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Failed writing persisted decodes", e)
        }
    }

    /**
     * Sender callsigns from the in-memory decode list, newest first.
     */
    fun heardCallsigns(): List<String> {
        return allDecodes.mapNotNull { senderCallsign(it.text) }.distinct()
    }

    private fun senderCallsign(text: String): String? {
        val firstToken = text.trim().split(Regex("\\s+"), limit = 2).firstOrNull() ?: return null
        val callsign = firstToken.trimEnd(':').uppercase()
        return callsign.takeIf { isCallsignLike(it) }
    }

    /**
     * Set filter text.
     */
    fun setFilter(text: String) {
        _filterText.value = text
        applyFilter()
    }

    /**
     * Apply current filter to decode list.
     */
    private fun applyFilter() {
        val filter = _filterText.value ?: ""
        val filtered = if (filter.isBlank()) {
            allDecodes.toList()
        } else {
            allDecodes.filter { decode ->
                decode.text.contains(filter, ignoreCase = true)
            }
        }
        _decodes.value = filtered
    }

    /**
     * Get decode count.
     */
    fun getDecodeCount(): Int = allDecodes.size

    /**
     * Export decodes as text.
     */
    fun exportDecodes(): String {
        return allDecodes.joinToString("\n") { it.toDisplayString() }
    }

    companion object {
        private const val TAG = "DecodeViewModel"
        private const val PREF_PERSIST_DECODES = "persist_decodes"
        private const val PERSISTED_DECODE_FILE = "decoded_messages.json"
        private val gridRegex = Regex("^[A-R]{2}[0-9]{2}([A-X]{2})?$")
        private val callsignRegex = Regex("^[A-Z0-9/]+$")
        private val directedCommandTailRegex = Regex(
            "^(?:AGN\\?|QSL\\?|HW CPY\\?|MSG TO:|SNR\\?|INFO\\?|GRID\\?|STATUS\\?|QUERY MSGS\\?|HEARING\\?|STATUS|HEARING|QUERY CALL|QUERY MSGS|QUERY|CMD|MSG|NACK|ACK|73|YES|NO|HEARTBEAT SNR|SNR|QSL|RR|SK|FB|INFO|GRID|DIT DIT|>|\\?)(?:\\s+[+-]?\\d{1,3})?$"
        )
        private val directedPlaceholderRegex = Regex("^<\\.{4}>:\\s*(.+)$")
    }
}
