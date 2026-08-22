package com.js8call.example.service

import android.os.SystemClock
import android.util.Log
import com.js8call.core.TruSdxDirectSerial
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class TruSdxSerialSession(
    private val directSerial: TruSdxDirectSerial,
    private val listener: Listener
) {
    interface Listener {
        fun onCatMessage(message: String)
        fun onAudioFrame(samplesU8: ByteArray)
        fun onParserResync(reason: String)
        fun onIoError(message: String)
    }

    @Volatile private var connected = false
    private val running = AtomicBoolean(false)
    private val writeLock = Any()
    private val txWriter = TruSdxTxWriter(::writeTxAudioFrame)
    @Volatile private var txActive = false
    @Volatile private var speakerEnabled = false
    @Volatile private var rxStreaming = false
    private val serialFrameBuffer = ByteArrayOutputStream(READ_BUFFER_SIZE * 2)
    private val rxAudioBuffer = ByteArrayOutputStream(RX_AUDIO_FLUSH_BYTES * 2)
    private var parserTotalBytes = 0L
    private var parserAudioBytes = 0L
    private var parserCatBytes = 0L
    private var parserCatMessages = 0L
    private var parserLastLogNs = 0L
    private var parserResyncCount = 0L

    fun start(deviceId: Int, portIndex: Int): Boolean {
        directSerial.setListener(object : TruSdxDirectSerial.Listener {
            override fun onData(data: ByteArray) {
                if (!running.get()) return
                processIncomingBytes(data, data.size)
            }

            override fun onRunError(message: String) {
                running.set(false)
                connected = false
                listener.onIoError(message)
            }
        })
        val opened = directSerial.open(
            deviceId,
            portIndex,
            TRUSDX_BAUD_RATE,
            TRUSDX_DATA_BITS,
            TRUSDX_STOP_BITS,
            TRUSDX_PARITY
        )
        if (!opened) {
            connected = false
            return false
        }
        connected = true
        txWriter.start()
        return true
    }

    fun stop() {
        stopReadLoop()
        txWriter.close()
        connected = false
        try {
            serialCatExchange(";RX;", expectReply = false)
            serialCatExchange(";UA0;", expectReply = false)
        } catch (_: Throwable) {
        }
        directSerial.close()
    }

    fun isConnected(): Boolean = connected

    fun isTxActive(): Boolean = txActive

    fun initializeRigState(): Boolean {
        if (!connected) return false
        clearParserState()
        startReadLoop()
        SystemClock.sleep(TRUSDX_STARTUP_CONFIG_DELAY_MS)
        return synchronized(writeLock) {
            try {
                writeRawAscii("FR0;")
                writeRawAscii("MD2;")
                writeStreamingStateLocked()
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    fun setSpeakerEnabled(enabled: Boolean): Boolean {
        speakerEnabled = enabled
        if (!connected) return true
        return synchronized(writeLock) {
            try {
                writeStreamingStateLocked()
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    fun setFrequency(frequencyHz: Long): Boolean {
        if (!connected) return false
        val cmd = String.format(Locale.US, "FA%011d;", frequencyHz)
        serialCatExchange(cmd, expectReply = false)
        return true
    }

    fun setPtt(enabled: Boolean): Boolean {
        if (!connected) return false
        synchronized(writeLock) {
            try {
                if (enabled) {
                    directSerial.setRts(true)
                    // Single PTT command with leading semicolon to terminate any
                    // pending CAT exchange, matching the FT8CN truSDX protocol.
                    writeRawAscii(";TX0;")
                    // Allow the radio time to switch to TX / audio-input mode.
                    SystemClock.sleep(100)
                    txActive = true
                    txWriter.enable()
                } else {
                    txWriter.disableAndClear()
                    txActive = false
                    SystemClock.sleep(20)
                    directSerial.setRts(false)
                    directSerial.purge()
                    repeat(10) {
                        writeRawAscii(";RX;")
                        writeRawAscii("RX;")
                        SystemClock.sleep(20)
                    }
                    writeRawAscii(";RX;")
                    writeStreamingStateLocked()
                    directSerial.setRts(false)
                }
                return true
            } catch (_: Throwable) {
                return false
            }
        }
    }

    fun setUsbMode(): Boolean {
        if (!connected) return false
        serialCatExchange("MD2;", expectReply = false)
        return true
    }

    fun ensureRxStreaming(): Boolean {
        if (!connected) return false
        return synchronized(writeLock) {
            try {
                writeStreamingStateLocked()
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    fun sendRxKeepAlive(): Boolean {
        if (!connected) return false
        return synchronized(writeLock) {
            try {
                writeRawAscii(";RX;")
                writeRawAscii("FA;")
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    fun sendTxAudio(samplesPcm16: ShortArray): Boolean {
        if (!connected) return false
        if (!txActive) return true
        if (samplesPcm16.isEmpty()) return true

        val payload = ByteArray(samplesPcm16.size)
        var out = 0
        for (sample in samplesPcm16) {
            var mapped = ((sample.toInt() shr 8) + 128).coerceIn(0, 255)
            if (mapped == SEMICOLON_BYTE) {
                // Semicolon is the CAT protocol delimiter and must never appear in
                // audio data.  Round to the nearest valid neighbour using the low
                // byte of the original sample to decide direction, so the error is
                // distributed evenly instead of always biasing downward.
                mapped = if ((sample.toInt() and 0xFF) >= 128) SEMICOLON_BYTE + 1 else SEMICOLON_BYTE - 1
            }
            payload[out++] = mapped.toByte()
        }

        if (!txWriter.enqueue(payload)) {
            Log.w(TAG, "TX audio queue full: queued=${txWriter.queuedFrames()} drops=${txWriter.droppedFrames}")
            return false
        }
        return true
    }

    private fun writeTxAudioFrame(payload: ByteArray): Boolean = synchronized(writeLock) {
        if (!connected || !txActive) return@synchronized true
        var totalWritten = 0
        while (totalWritten < payload.size) {
            val chunkSize = minOf(TRUSDX_TX_CHUNK_BYTES, payload.size - totalWritten)
            val chunk = if (totalWritten == 0 && chunkSize == payload.size) {
                payload
            } else {
                payload.copyOfRange(totalWritten, totalWritten + chunkSize)
            }
            val written = directSerial.write(chunk, chunk.size, IO_TIMEOUT_MS)
            if (written != chunk.size) {
                Log.w(TAG, "TX audio write failed: bytes=${totalWritten + maxOf(written, 0)} expected=${payload.size}")
                return@synchronized false
            }
            totalWritten += written
        }
        true
    }

    private fun serialCatExchange(command: String, expectReply: Boolean): String {
        synchronized(writeLock) {
            try {
                writeRawAscii(command)
                if (expectReply) {
                    listener.onParserResync("sync-cat-read-disabled")
                }
                return ""
            } finally {
            }
        }
    }

    private fun writeRawAscii(text: String) {
        val bytes = text.toByteArray(StandardCharsets.US_ASCII)
        val written = directSerial.write(bytes, bytes.size, IO_TIMEOUT_MS)
        if (written != bytes.size) {
            throw IllegalStateException("CAT write failed: $text written=$written")
        }
    }

    private fun writeStreamingStateLocked() {
        writeRawAscii(if (speakerEnabled) TRUSDX_STREAMING_ON_SPEAKER_ON else TRUSDX_STREAMING_ON_SPEAKER_OFF)
    }

    private fun clearParserState() {
        serialFrameBuffer.reset()
        rxAudioBuffer.reset()
        rxStreaming = false
        parserTotalBytes = 0L
        parserAudioBytes = 0L
        parserCatBytes = 0L
        parserCatMessages = 0L
        parserLastLogNs = 0L
        parserResyncCount = 0L
    }

    private fun startReadLoop() {
        running.set(true)
    }

    private fun processIncomingBytes(buffer: ByteArray, length: Int) {
        if (length <= 0) return

        val now = System.nanoTime()
        parserTotalBytes += length.toLong()
        if (serialFrameBuffer.size() + length > MAX_SERIAL_FRAME_BYTES) {
            serialFrameBuffer.reset()
            parserResyncCount += 1
            listener.onParserResync("frame-overflow")
        }
        serialFrameBuffer.write(buffer, 0, length)

        val data = serialFrameBuffer.toByteArray()
        var start = 0
        var index = 0
        while (index < data.size) {
            if (data[index] == ';'.code.toByte()) {
                val segment = data.copyOfRange(start, index)
                if (rxStreaming) {
                    appendRxAudio(segment, force = true)
                    rxStreaming = false
                } else {
                    handleCatSegment(segment)
                }
                start = index + 1
            }
            index++
        }

        serialFrameBuffer.reset()
        if (start < data.size) {
            val remain = data.copyOfRange(start, data.size)
            if (rxStreaming) {
                appendRxAudio(remain, force = false)
            } else if (remain.size >= 2 && remain[0] == 'U'.code.toByte() && remain[1] == 'S'.code.toByte()) {
                rxStreaming = true
                appendRxAudio(remain.copyOfRange(2, remain.size), force = false)
            } else {
                serialFrameBuffer.write(remain)
            }
        }

        if (parserLastLogNs == 0L || now - parserLastLogNs >= PARSER_LOG_INTERVAL_NS) {
            Log.i(
                TAG,
                "RX parser: total=$parserTotalBytes audio=$parserAudioBytes catBytes=$parserCatBytes catMsgs=$parserCatMessages pending=${serialFrameBuffer.size()} resyncs=$parserResyncCount"
            )
            parserLastLogNs = now
        }
    }

    private fun handleCatSegment(frame: ByteArray) {
        if (frame.isEmpty()) return
        if (frame.size >= 2 && frame[0] == 'U'.code.toByte() && frame[1] == 'S'.code.toByte()) {
            rxStreaming = true
            parserCatMessages += 1
            parserCatBytes += 2
            if (frame.size > 2) {
                appendRxAudio(frame.copyOfRange(2, frame.size), force = false)
            }
            return
        }

        parserCatMessages += 1
        parserCatBytes += frame.size.toLong()
        listener.onCatMessage(String(frame, StandardCharsets.US_ASCII) + ";")
    }

    private fun appendRxAudio(data: ByteArray, force: Boolean) {
        if (data.isNotEmpty()) {
            rxAudioBuffer.write(data, 0, data.size)
            parserAudioBytes += data.size.toLong()
        }
        if (rxAudioBuffer.size() >= RX_AUDIO_FLUSH_BYTES || (force && rxAudioBuffer.size() > 0)) {
            val batch = rxAudioBuffer.toByteArray()
            rxAudioBuffer.reset()
            listener.onAudioFrame(batch)
        }
    }

    private fun stopReadLoop() {
        running.set(false)
    }

    companion object {
        private const val TAG = "TruSdxSerialSession"
        private const val IO_TIMEOUT_MS = 500
        private const val READ_BUFFER_SIZE = 500
        private const val MAX_SERIAL_FRAME_BYTES = 2048
        private const val PARSER_LOG_INTERVAL_NS = 2_000_000_000L
        private const val RX_AUDIO_FLUSH_BYTES = 256
        private const val TRUSDX_TX_CHUNK_BYTES = 256
        private const val TRUSDX_STARTUP_CONFIG_DELAY_MS = 1500L
        private const val TRUSDX_STREAMING_ON_SPEAKER_ON = "UA1;"
        private const val TRUSDX_STREAMING_ON_SPEAKER_OFF = "UA2;"

        private const val TRUSDX_BAUD_RATE = 115200
        private const val TRUSDX_DATA_BITS = 8
        private const val TRUSDX_STOP_BITS = 1
        private const val TRUSDX_PARITY = 0

        /** Byte value of ';' -- the CAT protocol delimiter, forbidden in audio data. */
        private const val SEMICOLON_BYTE = ';'.code  // 59
    }
}
