package com.js8call.example.service

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Audio link to a BTECH UV-Pro over the radio's own Bluetooth audio channel.
 *
 * The radio carries 32 kHz mono SBC audio on a vendor RFCOMM service ("BS AOC"),
 * framed as 7E <cmd> <payload> 7E with 7D escaping. It has no PTT command: it
 * keys when audio frames arrive and unkeys on an end-of-audio frame, and it
 * only streams audio while an app is also connected on its control channel. These
 * details come from HTCommander (https://github.com/Ylianst/HTCommander) and
 * benlink (https://github.com/khusmann/benlink).
 */
class UvProSession(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onRxAudio(samples: ShortArray, sampleRateHz: Int)
        fun onTxTimeout()
        fun onIoError(message: String)
    }

    private val running = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)
    private val writeLock = Any()
    private val rxLock = Any()
    @Volatile private var connected = false
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var controlSocket: BluetoothSocket? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var lastError: String? = null
    private var readerThread: Thread? = null
    private var controlThread: Thread? = null
    private var clock: ScheduledExecutorService? = null

    private val decoder = SbcDecoder()
    @Volatile private var lastRxAudioNs = 0L
    private var rxFilledUntilNs = 0L
    private val rxPending = ShortArray(RX_CHUNK_SAMPLES)
    private var rxPendingCount = 0
    private var rxPendingRate = 0

    private val encoder = SbcEncoder(bitpool = TX_BITPOOL, snrAllocation = true)
    private val txPending = ShortArray(encoder.samplesPerFrame)
    private var txPendingCount = 0
    @Volatile private var txArmed = false
    @Volatile private var txAudioStarted = false
    private var txArmedAtNs = 0L
    private var txSilenceUntilNs = 0L

    fun getLastError(): String? = lastError

    /** Connects to the first paired UV-Pro. Blocks for the RFCOMM connect. */
    fun start(): Boolean {
        lastError = null
        if (!hasBluetoothPermission()) return fail("Allow Nearby devices for JS8Call in Android Settings")
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) return fail("Bluetooth is off")
        val device = adapter.bondedDevices?.firstOrNull { isUvPro(it) }
            ?: return fail("Pair the UV-Pro in Android Bluetooth settings first")

        val control = connect(device, CONTROL_UUID, "control") ?: return false
        controlSocket = control
        if (!handshake(control)) {
            closeControl()
            return false
        }
        // Opening audio can fail just after the control channel connects.
        var audio = connect(device, AUDIO_UUID, "audio")
        var attempts = 1
        while (audio == null && attempts < AUDIO_CONNECT_ATTEMPTS) {
            Thread.sleep(AUDIO_RETRY_MS)
            audio = connect(device, AUDIO_UUID, "audio")
            attempts++
        }
        val sock = audio ?: run {
            closeControl()
            return false
        }
        val input = try {
            output = sock.outputStream
            sock.inputStream
        } catch (e: Exception) {
            closeQuietly(sock)
            closeControl()
            return fail("UV-Pro audio channel unavailable: ${e.message}")
        }
        socket = sock
        failed.set(false)
        running.set(true)
        connected = true
        lastRxAudioNs = System.nanoTime()
        rxFilledUntilNs = lastRxAudioNs

        readerThread = Thread({ readLoop(input) }, "UvProRx").also { it.start() }
        clock = Executors.newSingleThreadScheduledExecutor { Thread(it, "UvProClock") }.also {
            it.scheduleAtFixedRate({ tick() }, CLOCK_MS, CLOCK_MS, TimeUnit.MILLISECONDS)
        }
        Log.i(TAG, "Connected to ${device.name ?: device.address}")
        return true
    }

    /**
     * Arms or releases transmit. Arming sends silence until the engine's audio
     * starts, which keys the radio; releasing sends the end-of-audio frame.
     */
    fun setPtt(enabled: Boolean): Boolean {
        if (!connected) return !enabled
        return synchronized(writeLock) {
            if (enabled) {
                if (txArmed) return@synchronized true
                val now = System.nanoTime()
                txArmed = true
                txAudioStarted = false
                txArmedAtNs = now
                txSilenceUntilNs = now
                txPendingCount = 0
                sendSilenceLocked(now + SILENCE_LEAD_NS)
            } else {
                if (!txArmed) return@synchronized true
                txArmed = false
                val flushed = flushPendingLocked()
                writeLocked(END_FRAME) && flushed
            }
        }
    }

    /** Sends 32 kHz PCM to the radio. Dropped unless transmit is armed. */
    fun sendTxAudio(samples: ShortArray): Boolean {
        if (!connected) return false
        return synchronized(writeLock) {
            if (!txArmed) return@synchronized false
            txAudioStarted = true
            val packet = ByteArrayOutputStream()
            var ok = true
            for (s in samples) {
                txPending[txPendingCount++] = s
                if (txPendingCount == txPending.size) {
                    packet.write(encoder.encode(txPending))
                    txPendingCount = 0
                    if (packet.size() >= MAX_PACKET_PAYLOAD) {
                        ok = writeLocked(frame(CMD_AUDIO, packet.toByteArray())) && ok
                        packet.reset()
                    }
                }
            }
            if (packet.size() > 0) ok = writeLocked(frame(CMD_AUDIO, packet.toByteArray())) && ok
            ok
        }
    }

    fun stop() {
        if (txArmed) setPtt(false)
        running.set(false)
        connected = false
        clock?.shutdownNow()
        clock = null
        socket?.let { closeQuietly(it) }
        socket = null
        output = null
        readerThread?.let {
            it.interrupt()
            it.join(1000)
        }
        readerThread = null
        closeControl()
    }

    /** Asks for device info and waits for any reply, as the radio's own apps do. */
    private fun handshake(control: BluetoothSocket): Boolean {
        val reply = CountDownLatch(1)
        return try {
            val input = control.inputStream
            val out = control.outputStream
            controlThread = Thread({ controlLoop(input, reply) }, "UvProControl").also { it.start() }
            repeat(HANDSHAKE_ATTEMPTS) {
                out.write(GET_DEV_INFO)
                out.flush()
                if (reply.await(HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return true
            }
            fail("UV-Pro did not answer on its control channel")
        } catch (e: Exception) {
            fail("UV-Pro control channel failed: ${e.message}")
        }
    }

    // Replies and notifications are not needed yet, so they are read and dropped.
    private fun controlLoop(input: InputStream, reply: CountDownLatch) {
        val buf = ByteArray(256)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) reply.countDown()
            }
        } catch (_: Exception) {
        }
        if (running.get()) ioError("UV-Pro control channel closed")
    }

    private fun closeControl() {
        controlSocket?.let { closeQuietly(it) }
        controlSocket = null
        controlThread?.let {
            it.interrupt()
            it.join(1000)
        }
        controlThread = null
    }

    private fun connect(device: BluetoothDevice, uuid: UUID, label: String): BluetoothSocket? {
        // The radio expects no authentication, so the insecure socket is tried first.
        val failures = ArrayList<String>()
        val attempts = listOf<Pair<String, () -> BluetoothSocket>>(
            "insecure" to { device.createInsecureRfcommSocketToServiceRecord(uuid) },
            "secure" to { device.createRfcommSocketToServiceRecord(uuid) }
        )
        for ((kind, create) in attempts) {
            var sock: BluetoothSocket? = null
            try {
                sock = create()
                sock.connect()
                return sock
            } catch (e: Exception) {
                failures.add("$kind: ${e.message}")
                sock?.let { closeQuietly(it) }
            }
        }
        fail("UV-Pro $label connect failed (${failures.joinToString("; ")})")
        return null
    }

    private fun readLoop(input: InputStream) {
        val splitter = FrameSplitter()
        val buf = ByteArray(1024)
        try {
            while (running.get()) {
                val n = input.read(buf)
                if (n < 0) break
                splitter.push(buf, n) { cmd, payload ->
                    if (cmd == CMD_AUDIO || cmd == CMD_AUDIO_ALT) decodeAudio(payload)
                }
            }
        } catch (e: Exception) {
            if (running.get()) ioError("UV-Pro Bluetooth read failed: ${e.message}")
            return
        }
        if (running.get()) ioError("UV-Pro Bluetooth link closed")
    }

    private fun decodeAudio(payload: ByteArray) {
        var off = 0
        while (off < payload.size) {
            val size = Sbc.frameLength(payload, off, payload.size - off)
            if (size < 0) {
                off++
                continue
            }
            val pcm = decoder.decode(payload, off, size)
            if (pcm != null) {
                val rate = Sbc.sampleRateHz(payload, off)
                synchronized(rxLock) {
                    lastRxAudioNs = System.nanoTime()
                    rxFilledUntilNs = lastRxAudioNs
                    if (rate != rxPendingRate || rxPendingCount + pcm.size > rxPending.size) flushRxLocked()
                    rxPendingRate = rate
                    pcm.copyInto(rxPending, rxPendingCount)
                    rxPendingCount += pcm.size
                    if (rxPendingCount == rxPending.size) flushRxLocked()
                }
            }
            off += size
        }
    }

    private fun tick() {
        if (!running.get()) return
        val now = System.nanoTime()

        // The radio sends nothing while squelched or transmitting; feed silence
        // so the decoder's sample clock keeps pace with real time.
        synchronized(rxLock) {
            if (now - lastRxAudioNs > RX_GAP_NS) {
                flushRxLocked()
                val samples = ((now - rxFilledUntilNs) * SAMPLE_RATE_HZ / 1_000_000_000L).toInt()
                if (samples > 0) {
                    listener.onRxAudio(ShortArray(samples), SAMPLE_RATE_HZ)
                    rxFilledUntilNs += samples * 1_000_000_000L / SAMPLE_RATE_HZ
                }
            }
        }

        var timedOut = false
        synchronized(writeLock) {
            if (!txArmed) return@synchronized
            if (now - txArmedAtNs > TX_TIMEOUT_NS) {
                Log.w(TAG, "TX timeout, releasing")
                txArmed = false
                writeLocked(END_FRAME)
                timedOut = true
            } else if (!txAudioStarted) {
                sendSilenceLocked(now + SILENCE_LEAD_NS)
            }
        }
        if (timedOut) listener.onTxTimeout()
    }

    // The engine draws each waterfall line from a single submit and skips short
    // ones, so received frames are passed on in 100 ms chunks, like a microphone.
    private fun flushRxLocked() {
        if (rxPendingCount == 0) return
        listener.onRxAudio(rxPending.copyOf(rxPendingCount), rxPendingRate)
        rxPendingCount = 0
    }

    /** Sends silent frames until [untilNs] of audio has been queued. */
    private fun sendSilenceLocked(untilNs: Long): Boolean {
        val frameNs = encoder.samplesPerFrame * 1_000_000_000L / SAMPLE_RATE_HZ
        val packet = ByteArrayOutputStream()
        val silence = ShortArray(encoder.samplesPerFrame)
        while (txSilenceUntilNs < untilNs) {
            packet.write(encoder.encode(silence))
            txSilenceUntilNs += frameNs
        }
        return packet.size() == 0 || writeLocked(frame(CMD_AUDIO, packet.toByteArray()))
    }

    private fun flushPendingLocked(): Boolean {
        if (txPendingCount == 0) return true
        txPending.fill(0, txPendingCount)
        txPendingCount = 0
        return writeLocked(frame(CMD_AUDIO, encoder.encode(txPending)))
    }

    private fun writeLocked(bytes: ByteArray): Boolean {
        val out = output ?: return false
        return try {
            out.write(bytes)
            out.flush()
            true
        } catch (e: Exception) {
            ioError("UV-Pro Bluetooth write failed: ${e.message}")
            false
        }
    }

    private fun ioError(message: String) {
        connected = false
        txArmed = false
        if (failed.compareAndSet(false, true)) {
            Log.w(TAG, message)
            lastError = message
            listener.onIoError(message)
        }
    }

    private fun fail(message: String): Boolean {
        lastError = message
        Log.w(TAG, message)
        return false
    }

    private fun isUvPro(device: BluetoothDevice): Boolean =
        device.uuids?.any { it.uuid == AUDIO_UUID } == true ||
            device.name?.contains("UV-PRO", ignoreCase = true) == true

    private fun hasBluetoothPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun closeQuietly(sock: BluetoothSocket) {
        try {
            sock.close()
        } catch (_: Exception) {
        }
    }

    /** Collects 7E-delimited frames from the byte stream and unescapes them. */
    internal class FrameSplitter {
        private val current = ByteArrayOutputStream()
        private var escaped = false

        fun push(data: ByteArray, length: Int, onFrame: (cmd: Int, payload: ByteArray) -> Unit) {
            for (i in 0 until length) {
                val b = data[i].toInt() and 0xFF
                when {
                    b == FLAG -> {
                        if (current.size() > 0) {
                            val bytes = current.toByteArray()
                            onFrame(bytes[0].toInt() and 0xFF, bytes.copyOfRange(1, bytes.size))
                        }
                        current.reset()
                        escaped = false
                    }
                    b == ESCAPE -> escaped = true
                    escaped -> {
                        current.write(b xor 0x20)
                        escaped = false
                    }
                    else -> current.write(b)
                }
            }
        }
    }

    companion object {
        private const val TAG = "UvProSession"
        val AUDIO_UUID: UUID = UUID.fromString("39144315-32FA-40DB-85ED-FBFEBA2D86E6")
        private val CONTROL_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        // GAIA frame: FF 01, flags 00, payload length 01, group 0002 (basic),
        // command 0004 (get device info), payload 03.
        private val GET_DEV_INFO = byteArrayOf(0xFF.toByte(), 0x01, 0x00, 0x01, 0x00, 0x02, 0x00, 0x04, 0x03)
        private const val HANDSHAKE_ATTEMPTS = 3
        private const val HANDSHAKE_TIMEOUT_MS = 2000L
        private const val AUDIO_CONNECT_ATTEMPTS = 3
        private const val AUDIO_RETRY_MS = 700L

        const val CMD_AUDIO = 0x00
        const val CMD_AUDIO_ALT = 0x03
        private const val FLAG = 0x7E
        private const val ESCAPE = 0x7D

        // End of audio: tells the radio to unkey.
        val END_FRAME = byteArrayOf(0x7E, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x7E)

        // HTCommander found bitpool 40 with SNR allocation works over the air for data.
        private const val TX_BITPOOL = 40
        private const val MAX_PACKET_PAYLOAD = 256
        private const val CLOCK_MS = 20L
        private const val RX_GAP_NS = 100_000_000L
        const val SAMPLE_RATE_HZ = 32000
        private const val RX_CHUNK_SAMPLES = 3200
        private const val SILENCE_LEAD_NS = 60_000_000L
        private const val TX_TIMEOUT_NS = 45_000_000_000L

        /** Builds one 7E-delimited frame with [payload] escaped. */
        fun frame(cmd: Int, payload: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(payload.size + 8)
            out.write(FLAG)
            out.write(cmd)
            for (byte in payload) {
                val b = byte.toInt() and 0xFF
                if (b == FLAG || b == ESCAPE) {
                    out.write(ESCAPE)
                    out.write(b xor 0x20)
                } else {
                    out.write(b)
                }
            }
            out.write(FLAG)
            return out.toByteArray()
        }
    }
}
