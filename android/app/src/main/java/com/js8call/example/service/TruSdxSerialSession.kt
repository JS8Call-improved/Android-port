package com.js8call.example.service

import android.os.SystemClock
import android.util.Log
import com.js8call.core.TruSdxDirectSerial
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
    @Volatile private var readThread: Thread? = null
    private val writeLock = Any()
    @Volatile private var rxPaused = false
    @Volatile private var txActive = false

    fun start(deviceId: Int, portIndex: Int): Boolean {
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
        directSerial.setDtr(true)
        directSerial.setRts(false)
        SystemClock.sleep(3000)
        synchronized(writeLock) {
            try {
                writeRawAscii("UA1;")
            } catch (_: Throwable) {
                connected = false
                return false
            }
        }
        connected = true
        return true
    }

    fun stop() {
        stopReadLoop()
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
        if (!running.get()) {
            startReadLoop()
        }
        return true
    }

    fun setSpeakerEnabled(_enabled: Boolean): Boolean {
        if (!connected) return true
        return true
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
            rxPaused = true
            try {
                if (enabled) {
                    directSerial.setRts(true)
                    writeRawAscii("TX0;")
                    writeRawAscii(";TX0;")
                    writeRawAscii("TX0;")
                    SystemClock.sleep(10)
                    txActive = true
                } else {
                    txActive = false
                    SystemClock.sleep(20)
                    directSerial.setRts(false)
                    directSerial.purge()
                    repeat(10) {
                        writeRawAscii(";RX;")
                        writeRawAscii("RX;")
                        SystemClock.sleep(20)
                    }
                    directSerial.setRts(false)
                }
                return true
            } catch (_: Throwable) {
                return false
            } finally {
                rxPaused = false
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
            rxPaused = true
            try {
                writeRawAscii("UA1;")
                true
            } catch (_: Throwable) {
                false
            } finally {
                rxPaused = false
            }
        }
    }

    fun sendRxKeepAlive(): Boolean {
        if (!connected) return false
        return synchronized(writeLock) {
            rxPaused = true
            try {
                writeRawAscii("RX;")
                true
            } catch (_: Throwable) {
                false
            } finally {
                rxPaused = false
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
            val mapped = ((sample.toInt() shr 8) + 128).coerceIn(0, 255)
            payload[out++] = mapped.toByte()
        }

        val written = synchronized(writeLock) {
            if (!connected) return@synchronized -1
            directSerial.write(payload, payload.size, IO_TIMEOUT_MS)
        }
        if (written != payload.size) {
            Log.w(TAG, "TX audio write failed: bytes=$written expected=${payload.size}")
            return false
        }
        return true
    }

    private fun serialCatExchange(
        command: String,
        expectReply: Boolean,
        timeoutMs: Int = CAT_TIMEOUT_MS
    ): String {
        synchronized(writeLock) {
            rxPaused = true
            try {
                discardPendingInput()
                writeRawAscii(command)
                if (!expectReply) {
                    return ""
                }

                val deadline = SystemClock.elapsedRealtime() + timeoutMs
                val readBuffer = ByteArray(CAT_READ_BUFFER_SIZE)
                val response = StringBuilder(64)
                while (SystemClock.elapsedRealtime() < deadline) {
                    val read = directSerial.read(readBuffer, CAT_READ_TIMEOUT_MS)
                    if (read < 0) {
                        return response.toString()
                    }
                    if (read == 0) {
                        continue
                    }
                    val chunk = String(readBuffer, 0, read, StandardCharsets.US_ASCII)
                    response.append(chunk)
                    val end = response.indexOf(";")
                    if (end >= 0) {
                        val msg = response.substring(0, end + 1)
                        listener.onCatMessage(msg)
                        return msg
                    }
                }
                return response.toString()
            } finally {
                rxPaused = false
            }
        }
    }

    private fun discardPendingInput() {
        val deadline = SystemClock.elapsedRealtime() + 80L
        val scratch = ByteArray(CAT_READ_BUFFER_SIZE)
        while (SystemClock.elapsedRealtime() < deadline) {
            val read = directSerial.read(scratch, 10)
            if (read <= 0) {
                break
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

    private fun startReadLoop() {
        if (running.getAndSet(true)) return
        val thread = Thread({
            val readBuffer = ByteArray(READ_BUFFER_SIZE)
            var lastDataNs = System.nanoTime()
            var lastIdleLogNs = 0L
            while (running.get()) {
                if (rxPaused) {
                    SystemClock.sleep(2)
                    continue
                }
                val read = directSerial.read(readBuffer, READ_TIMEOUT_MS)
                if (read < 0) {
                    listener.onIoError("TruSDX serial read failed")
                    running.set(false)
                    connected = false
                    break
                }
                if (read == 0) {
                    val now = System.nanoTime()
                    if (now - lastDataNs > 2_000_000_000L && now - lastIdleLogNs > 2_000_000_000L) {
                        Log.w(SERVICE_TAG, "TruSDX read idle ${(now - lastDataNs) / 1_000_000L}ms")
                        lastIdleLogNs = now
                    }
                    continue
                }
                lastDataNs = System.nanoTime()
                val frame = ByteArray(read)
                System.arraycopy(readBuffer, 0, frame, 0, read)
                listener.onAudioFrame(frame)
            }
        }, "TruSdxSerialRead")
        thread.isDaemon = true
        readThread = thread
        thread.start()
    }

    private fun stopReadLoop() {
        running.set(false)
        val thread = readThread
        readThread = null
        if (thread != null && thread.isAlive) {
            try {
                thread.join(500)
            } catch (_: InterruptedException) {
            }
        }
        rxPaused = false
    }

    companion object {
        private const val TAG = "TruSdxSerialSession"
        private const val SERVICE_TAG = "JS8EngineService"
        private const val IO_TIMEOUT_MS = 500
        private const val READ_TIMEOUT_MS = 50
        private const val READ_BUFFER_SIZE = 500
        private const val CAT_TIMEOUT_MS = 300
        private const val CAT_READ_TIMEOUT_MS = 30
        private const val CAT_READ_BUFFER_SIZE = 64

        private const val TRUSDX_BAUD_RATE = 115200
        private const val TRUSDX_DATA_BITS = 8
        private const val TRUSDX_STOP_BITS = 1
        private const val TRUSDX_PARITY = 0
    }
}
