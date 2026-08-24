package com.js8call.example.service

import com.js8call.core.QmxDirectSerial
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** QMX CAT protocol only. USB audio remains under Android's normal audio routing. */
class QmxCatSession(
    private val serial: QmxDirectSerial,
    private val listener: Listener
) {
    interface Listener {
        fun onFrequency(frequencyHz: Long)
        fun onMessage(message: String)
        fun onIoError(message: String)
    }

    private val running = AtomicBoolean(false)
    private val writeLock = Any()
    private val input = StringBuilder()
    @Volatile private var connected = false
    @Volatile private var identityReply: CountDownLatch? = null

    fun start(deviceId: Int, portIndex: Int, baudRate: Int): Boolean {
        serial.setListener(object : QmxDirectSerial.Listener {
            override fun onData(data: ByteArray) {
                if (running.get()) processInput(data)
            }

            override fun onRunError(message: String) {
                running.set(false)
                connected = false
                listener.onIoError(message)
            }
        })
        connected = serial.open(deviceId, portIndex, baudRate)
        if (connected) running.set(true)
        return connected
    }

    fun initialize(): Boolean {
        if (!connected) return false
        // Query identity for diagnostics, then select the QMX single-signal Digi mode.
        val reply = CountDownLatch(1)
        identityReply = reply
        val sent = command("ID;") && command("OM;") && command("MD6;") && command("FA;")
        val identified = sent && reply.await(IDENTITY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        identityReply = null
        return identified
    }

    fun setFrequency(frequencyHz: Long): Boolean {
        if (frequencyHz <= 0) return false
        return command(String.format(Locale.US, "FA%011d;", frequencyHz))
    }

    fun setDigiMode(): Boolean = command("MD6;")

    fun setPtt(enabled: Boolean): Boolean = command(if (enabled) "TX;" else "RX;")

    fun isConnected(): Boolean = connected

    fun stop() {
        if (connected) command("RX;")
        running.set(false)
        connected = false
        synchronized(input) { input.setLength(0) }
        serial.close()
    }

    private fun command(value: String): Boolean = synchronized(writeLock) {
        connected && serial.write(value.toByteArray(StandardCharsets.US_ASCII))
    }

    private fun processInput(data: ByteArray) {
        val text = data.toString(StandardCharsets.US_ASCII)
        synchronized(input) {
            input.append(text)
            while (true) {
                val end = input.indexOf(";")
                if (end < 0) break
                val message = input.substring(0, end)
                input.delete(0, end + 1)
                handleMessage(message)
            }
            if (input.length > MAX_PENDING_CHARS) input.setLength(0)
        }
    }

    private fun handleMessage(message: String) {
        if (message.startsWith("ID") || message.startsWith("OM")) {
            identityReply?.countDown()
        }
        if (message.startsWith("FA") && message.length > 2) {
            message.substring(2).toLongOrNull()?.let(listener::onFrequency)
        }
        listener.onMessage(message)
    }

    private companion object {
        const val MAX_PENDING_CHARS = 4_096
        const val IDENTITY_TIMEOUT_MS = 1_500L
    }
}
