package com.js8call.example.network

import android.util.Log
import android.os.Handler
import android.os.HandlerThread
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.charset.Charset
import java.util.Locale
import kotlin.random.Random

class PskReporterClient(
    private val programId: String
) {
    private data class Spot(
        val call: String,
        val grid: String,
        val snr: Int,
        val frequencyHz: Long,
        val mode: String,
        val timestampSeconds: Long
    )

    private val lock = Any()
    private val spots = mutableListOf<Spot>()
    private val callsCache = HashMap<String, Long>()

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var socket: DatagramSocket? = null
    private var started = false

    private var rxCall = ""
    private var rxGrid = ""
    private var rxAntenna = ""

    private var observationId: Int = Random.nextInt()
    private var sequenceNumber: Int = 0
    private var sendDescriptors: Int = 0
    private var flushCounter: Int = 0

    private val reportRunnable = object : Runnable {
        override fun run() {
            sendReport(false)
            scheduleReport()
        }
    }

    private val descriptorRunnable = object : Runnable {
        override fun run() {
            sendDescriptors = 3
            scheduleDescriptorRefresh()
        }
    }

    fun start() {
        if (started) return
        started = true
        handlerThread = HandlerThread("PskReporter").apply { start() }
        handler = Handler(handlerThread!!.looper)
        handler?.post { ensureSocket() }
        scheduleReport()
        scheduleDescriptorRefresh()
    }

    fun stop(flush: Boolean, discardPending: Boolean = false) {
        if (discardPending) {
            synchronized(lock) {
                spots.clear()
                callsCache.clear()
            }
        }
        val localHandler = handler
        if (localHandler == null) {
            started = false
            return
        }
        localHandler.post {
            if (flush) {
                sendReport(true)
            }
            closeSocket()
        }
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
        started = false
    }

    fun setLocalStation(call: String, grid: String, antenna: String) {
        val normalizedCall = call.trim().uppercase(Locale.US)
        val normalizedGrid = grid.trim().uppercase(Locale.US)
        val normalizedAntenna = antenna.trim()
        handler?.post {
            rxCall = normalizedCall
            rxGrid = normalizedGrid
            rxAntenna = normalizedAntenna
        }
    }

    fun addSpot(call: String, grid: String, snr: Int, frequencyHz: Long, mode: String, timestampSeconds: Long) {
        if (!started) return
        val normalizedCall = call.trim().uppercase(Locale.US)
        if (normalizedCall.isBlank()) return
        val normalizedGrid = grid.trim().uppercase(Locale.US)
        val normalizedMode = mode.trim().uppercase(Locale.US)
        val band = bandKeyForFrequency(frequencyHz)
        val cacheKey = "${normalizedCall}_$band"
        val now = System.currentTimeMillis() / 1000
        val expiration = now - CACHE_TIMEOUT_SECONDS

        synchronized(lock) {
            val previous = callsCache[cacheKey]
            val expired = previous == null || previous < expiration
            if (expired) {
                spots.add(Spot(normalizedCall, normalizedGrid, snr, frequencyHz, normalizedMode, timestampSeconds))
                callsCache[cacheKey] = now
            } else {
                for (i in spots.indices.reversed()) {
                    val existing = spots[i]
                    if (existing.call == normalizedCall && bandKeyForFrequency(existing.frequencyHz) == band) {
                        spots[i] = Spot(normalizedCall, normalizedGrid, snr, frequencyHz, normalizedMode, timestampSeconds)
                        callsCache[cacheKey] = now
                        break
                    }
                }
            }

            val iterator = callsCache.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value > CACHE_TIMEOUT_SECONDS * 2) {
                    iterator.remove()
                }
            }
        }
    }

    private fun scheduleReport() {
        val intervalSec = MIN_SEND_INTERVAL_SECONDS + Random.nextInt(JITTER_MAX_SECONDS + 1)
        handler?.postDelayed(reportRunnable, intervalSec * 1000L)
    }

    private fun scheduleDescriptorRefresh() {
        handler?.postDelayed(descriptorRunnable, DESCRIPTOR_REFRESH_MS)
    }

    private fun ensureSocket() {
        if (socket != null) return
        try {
            val udpSocket = DatagramSocket()
            udpSocket.connect(InetSocketAddress(HOST, PORT))
            socket = udpSocket
            sendDescriptors = 3
            Log.d(TAG, "PSKReporter socket opened to $HOST:$PORT")
        } catch (e: Exception) {
            Log.w(TAG, "PSKReporter socket error", e)
            socket = null
        }
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }

    private fun sendReport(flush: Boolean) {
        ensureSocket()
        val udpSocket = socket ?: return
        if (rxCall.isBlank() || rxGrid.isBlank()) return
        if (!flush && synchronized(lock) { spots.isEmpty() }) return

        while (true) {
            val header = createBaseMessage()
            val baseMessage = header.writer
            val txData = PacketWriter()
            val txHeaderSize = 4
            txData.writeShort(TEMPLATE_ID_SENDER)
            val txLengthOffset = txData.position
            txData.writeShort(0)

            val addedSpots = mutableListOf<Spot>()
            while (true) {
                val spot = synchronized(lock) { spots.firstOrNull() } ?: break
                val before = txData.position
                writeSpot(txData, spot)
                val len = estimatedMessageLength(baseMessage.size, txData.size)
                if (len > MAX_PAYLOAD_LENGTH) {
                    txData.position = before
                    break
                }
                synchronized(lock) { if (spots.isNotEmpty()) spots.removeAt(0) }
                addedSpots.add(spot)
            }

            if (addedSpots.isEmpty()) {
                if (!flush) return
                if (txData.size <= txHeaderSize) {
                    finalizeAndSend(baseMessage, header, udpSocket, 0)
                    return
                }
            }

            val messageLength = estimatedMessageLength(baseMessage.size, txData.size)
            val shouldSend = flush || messageLength > MIN_PAYLOAD_LENGTH || synchronized(lock) { spots.isEmpty() }
            if (!shouldSend && addedSpots.isNotEmpty()) {
                synchronized(lock) {
                    for (i in addedSpots.indices.reversed()) {
                        spots.add(0, addedSpots[i])
                    }
                }
                return
            }

            if (txData.size > txHeaderSize) {
                txData.padTo4()
                val txDataLength = txData.size
                txData.setShort(txLengthOffset, txDataLength)
                baseMessage.writeBytes(txData.toByteArray())
            }

            finalizeAndSend(baseMessage, header, udpSocket, addedSpots.size)

            if (!flush && synchronized(lock) { spots.isEmpty() }) return
            if (!flush && ++flushCounter % FLUSH_INTERVAL == 0) {
                continue
            }
            if (flush && synchronized(lock) { spots.isEmpty() }) return
        }
    }

    private fun createBaseMessage(): MessageHeader {
        val message = PacketWriter()
        message.writeShort(IPFIX_VERSION)
        val lengthOffset = message.position
        message.writeShort(0)
        val exportTimeOffset = message.position
        message.writeInt(0)
        val sequenceOffset = message.position
        message.writeInt(0)
        message.writeInt(observationId)

        val includesDescriptors = sendDescriptors > 0
        if (includesDescriptors) {
            val sid = buildSenderInfoDescriptor()
            message.writeBytes(sid)
            val rid = buildReceiverInfoDescriptor()
            message.writeBytes(rid)
        }

        val receiver = buildReceiverRecord()
        message.writeBytes(receiver)
        return MessageHeader(message, lengthOffset, exportTimeOffset, sequenceOffset, includesDescriptors)
    }

    private fun finalizeAndSend(
        message: PacketWriter,
        header: MessageHeader,
        udpSocket: DatagramSocket,
        reportCount: Int
    ): Boolean {
        message.padTo4()
        val length = message.size
        message.setShort(header.lengthOffset, length)
        message.setInt(header.sequenceOffset, sequenceNumber + reportCount)
        val exportTime = (System.currentTimeMillis() / 1000).toInt()
        message.setInt(header.exportTimeOffset, exportTime)
        val payload = message.toByteArray()
        try {
            val packet = DatagramPacket(payload, payload.size)
            udpSocket.send(packet)
            if (header.includesDescriptors) sendDescriptors -= 1
            sequenceNumber += reportCount
            Log.d(TAG, "PSKReporter sent ${payload.size} bytes")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "PSKReporter send failed", e)
            return false
        }
    }

    private fun buildSenderInfoDescriptor(): ByteArray {
        val writer = PacketWriter()
        writer.writeShort(2)
        val lengthOffset = writer.position
        writer.writeShort(0)
        writer.writeShort(TEMPLATE_ID_SENDER)
        writer.writeShort(7)
        writer.writeShort(0x8000 + 1)
        writer.writeShort(0xFFFF)
        writer.writeInt(ENTERPRISE_NUMBER)
        writer.writeShort(0x8000 + 5)
        writer.writeShort(5)
        writer.writeInt(ENTERPRISE_NUMBER)
        writer.writeShort(0x8000 + 6)
        writer.writeShort(1)
        writer.writeInt(ENTERPRISE_NUMBER)
        writer.writeShort(0x8000 + 10)
        writer.writeShort(0xFFFF)
        writer.writeInt(ENTERPRISE_NUMBER)
        writer.writeShort(0x8000 + 3)
        writer.writeShort(0xFFFF)
        writer.writeInt(ENTERPRISE_NUMBER)
        writer.writeShort(0x8000 + 11)
        writer.writeShort(1)
        writer.writeInt(ENTERPRISE_NUMBER)
        writer.writeShort(150)
        writer.writeShort(4)
        writer.padTo4()
        writer.setShort(lengthOffset, writer.size)
        return writer.toByteArray()
    }

    private fun buildReceiverInfoDescriptor(): ByteArray {
        val writer = PacketWriter()
        writer.writeShort(3)
        val lengthOffset = writer.position
        writer.writeShort(0)
        writer.writeShort(TEMPLATE_ID_RECEIVER)
        writer.writeShort(4)
        writer.writeShort(0)
        writer.writeShort(0x8000 + 2)
        writer.writeShort(0xFFFF)
        writer.writeInt(ENTERPRISE_NUMBER)
        writer.writeShort(0x8000 + 4)
        writer.writeShort(0xFFFF)
        writer.writeInt(ENTERPRISE_NUMBER)
        writer.writeShort(0x8000 + 8)
        writer.writeShort(0xFFFF)
        writer.writeInt(ENTERPRISE_NUMBER)
        writer.writeShort(0x8000 + 9)
        writer.writeShort(0xFFFF)
        writer.writeInt(ENTERPRISE_NUMBER)
        writer.padTo4()
        writer.setShort(lengthOffset, writer.size)
        return writer.toByteArray()
    }

    private fun buildReceiverRecord(): ByteArray {
        val writer = PacketWriter()
        writer.writeShort(TEMPLATE_ID_RECEIVER)
        val lengthOffset = writer.position
        writer.writeShort(0)
        writeUtfString(writer, rxCall)
        writeUtfString(writer, rxGrid)
        writeUtfString(writer, programId)
        writeUtfString(writer, rxAntenna)
        writer.padTo4()
        writer.setShort(lengthOffset, writer.size)
        return writer.toByteArray()
    }

    private fun writeSpot(writer: PacketWriter, spot: Spot) {
        writeUtfString(writer, spot.call)
        writeFrequency(writer, spot.frequencyHz)
        writer.writeByte(spot.snr)
        writeUtfString(writer, spot.mode)
        writeUtfString(writer, spot.grid)
        writer.writeByte(REPORTER_SOURCE_AUTOMATIC)
        writer.writeInt(spot.timestampSeconds.toInt())
    }

    private fun writeFrequency(writer: PacketWriter, frequencyHz: Long) {
        writer.writeByte((frequencyHz shr 32).toInt())
        writer.writeByte((frequencyHz shr 24).toInt())
        writer.writeByte((frequencyHz shr 16).toInt())
        writer.writeByte((frequencyHz shr 8).toInt())
        writer.writeByte(frequencyHz.toInt())
    }

    private fun writeUtfString(writer: PacketWriter, value: String) {
        val bytes = value.toByteArray(Charset.forName("UTF-8"))
        val trimmed = if (bytes.size <= MAX_STRING_LENGTH) bytes else safeTruncate(bytes)
        writer.writeByte(trimmed.size)
        writer.writeBytes(trimmed)
    }

    private fun safeTruncate(bytes: ByteArray): ByteArray {
        var idx = MAX_STRING_LENGTH
        while (idx > 0) {
            val byte = bytes[idx].toInt() and 0xFF
            if ((byte and 0xC0) != 0x80) {
                return bytes.copyOf(idx)
            }
            idx -= 1
        }
        return ByteArray(0)
    }

    private fun estimatedMessageLength(baseSize: Int, txDataSize: Int): Int {
        var len = baseSize + txDataSize
        len += padBytes(txDataSize)
        len += padBytes(len)
        return len
    }

    private fun padBytes(size: Int): Int {
        val rem = size % 4
        return if (rem == 0) 0 else 4 - rem
    }

    private fun bandKeyForFrequency(frequencyHz: Long): String {
        val mhz = frequencyHz.toDouble() / 1_000_000.0
        return when {
            mhz in 1.8..2.0 -> "160m"
            mhz in 3.5..4.0 -> "80m"
            mhz in 5.0..5.5 -> "60m"
            mhz in 7.0..7.3 -> "40m"
            mhz in 10.0..10.2 -> "30m"
            mhz in 14.0..14.35 -> "20m"
            mhz in 18.0..18.2 -> "17m"
            mhz in 21.0..21.45 -> "15m"
            mhz in 24.8..25.0 -> "12m"
            mhz in 28.0..29.7 -> "10m"
            mhz in 50.0..54.0 -> "6m"
            mhz in 144.0..148.0 -> "2m"
            mhz in 420.0..450.0 -> "70cm"
            else -> String.format(Locale.US, "%.1fMHz", mhz)
        }
    }

    private class PacketWriter(initialSize: Int = 1024) {
        private var buffer = ByteArray(initialSize)
        var position: Int = 0
            set(value) {
                field = value
            }

        val size: Int
            get() = position

        fun writeByte(value: Int) {
            ensure(1)
            buffer[position++] = value.toByte()
        }

        fun writeShort(value: Int) {
            ensure(2)
            buffer[position++] = ((value shr 8) and 0xFF).toByte()
            buffer[position++] = (value and 0xFF).toByte()
        }

        fun writeInt(value: Int) {
            ensure(4)
            buffer[position++] = ((value shr 24) and 0xFF).toByte()
            buffer[position++] = ((value shr 16) and 0xFF).toByte()
            buffer[position++] = ((value shr 8) and 0xFF).toByte()
            buffer[position++] = (value and 0xFF).toByte()
        }

        fun writeBytes(data: ByteArray) {
            ensure(data.size)
            data.copyInto(buffer, position, 0, data.size)
            position += data.size
        }

        fun padTo4() {
            val pad = (4 - (position % 4)) % 4
            repeat(pad) { writeByte(0) }
        }

        fun setShort(offset: Int, value: Int) {
            buffer[offset] = ((value shr 8) and 0xFF).toByte()
            buffer[offset + 1] = (value and 0xFF).toByte()
        }

        fun setInt(offset: Int, value: Int) {
            buffer[offset] = ((value shr 24) and 0xFF).toByte()
            buffer[offset + 1] = ((value shr 16) and 0xFF).toByte()
            buffer[offset + 2] = ((value shr 8) and 0xFF).toByte()
            buffer[offset + 3] = (value and 0xFF).toByte()
        }

        fun toByteArray(): ByteArray {
            return buffer.copyOf(position)
        }

        private fun ensure(extra: Int) {
            if (position + extra <= buffer.size) return
            var newSize = buffer.size * 2
            while (newSize < position + extra) {
                newSize *= 2
            }
            buffer = buffer.copyOf(newSize)
        }
    }

    private data class MessageHeader(
        val writer: PacketWriter,
        val lengthOffset: Int,
        val exportTimeOffset: Int,
        val sequenceOffset: Int,
        val includesDescriptors: Boolean
    )

    companion object {
        private const val TAG = "PskReporter"
        private const val HOST = "report.pskreporter.info"
        private const val PORT = 4739
        private const val IPFIX_VERSION = 10
        private const val TEMPLATE_ID_SENDER = 0x50e3
        private const val TEMPLATE_ID_RECEIVER = 0x50e2
        private const val ENTERPRISE_NUMBER = 30351
        private const val REPORTER_SOURCE_AUTOMATIC = 1
        private const val MIN_SEND_INTERVAL_SECONDS = 600
        private const val JITTER_MAX_SECONDS = 5
        private const val FLUSH_INTERVAL = 125
        private const val MAX_STRING_LENGTH = 254
        private const val CACHE_TIMEOUT_SECONDS = 3600L
        private const val MIN_PAYLOAD_LENGTH = 508
        private const val MAX_PAYLOAD_LENGTH = 10000
        private const val DESCRIPTOR_REFRESH_MS = 60L * 60L * 1000L
    }
}
