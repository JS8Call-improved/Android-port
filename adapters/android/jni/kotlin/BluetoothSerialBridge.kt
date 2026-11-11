package com.js8call.core

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Minimal Bluetooth serial bridge used by native code via JNI.
 */
class BluetoothSerialBridge(private val context: Context) {
    companion object {
        init {
            System.loadLibrary("js8core-jni")
        }

        private const val TAG = "BluetoothSerialBridge"
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private val lock = ReentrantReadWriteLock()
    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var lastReadErrorLogMs = 0L
    private var lastWriteErrorLogMs = 0L
    private var activeAddress: String? = null
    private var activePortIndex: Int? = null
    private var activeBaudRate: Int? = null
    private var activeDataBits: Int? = null
    private var activeStopBits: Int? = null
    private var activeParity: Int? = null
    @Volatile private var lastError: String? = null
    private var closedForGood = false

    private data class SocketAttempt(
        val label: String,
        val create: () -> BluetoothSocket?
    )

    private inline fun <T> withReadLock(action: () -> T): T {
        val readLock = lock.readLock()
        readLock.lock()
        return try {
            action()
        } finally {
            readLock.unlock()
        }
    }

    private inline fun <T> withWriteLock(action: () -> T): T {
        val writeLock = lock.writeLock()
        writeLock.lock()
        return try {
            action()
        } finally {
            writeLock.unlock()
        }
    }

    private fun shouldLogReadError(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastReadErrorLogMs < 1000L) return false
        lastReadErrorLogMs = now
        return true
    }

    private fun shouldLogWriteError(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWriteErrorLogMs < 1000L) return false
        lastWriteErrorLogMs = now
        return true
    }

    fun registerNative() {
        nativeRegister()
    }

    fun unregisterNative() {
        nativeUnregister()
    }

    fun open(
        addressNoColons: String,
        portIndex: Int,
        baudRate: Int,
        dataBits: Int,
        stopBits: Int,
        parity: Int
    ): Boolean {
        return withWriteLock {
            lastError = null
            if (closedForGood) {
                Log.i(TAG, "Bluetooth serial open refused: bridge closed")
                return@withWriteLock false
            }
            if (!hasBluetoothPermission()) {
                lastError = "Bluetooth permissions not granted"
                Log.w(TAG, lastError ?: "Bluetooth permissions not granted")
                return@withWriteLock false
            }

            val normalized = BluetoothSerialPortCatalog.normalizeAddress(addressNoColons)
            if (normalized == null) {
                lastError = "Invalid Bluetooth address: $addressNoColons"
                Log.w(TAG, lastError ?: "Invalid Bluetooth address: $addressNoColons")
                return@withWriteLock false
            }

            if (socket != null &&
                activeAddress == normalized &&
                activePortIndex == portIndex &&
                activeBaudRate == baudRate &&
                activeDataBits == dataBits &&
                activeStopBits == stopBits &&
                activeParity == parity) {
                Log.i(TAG, "Bluetooth serial already open addr=$normalized port=$portIndex")
                return@withWriteLock true
            }

            closeLocked()

            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) {
                lastError = "Bluetooth adapter unavailable"
                Log.w(TAG, lastError ?: "Bluetooth adapter unavailable")
                return@withWriteLock false
            }

            val formatted = BluetoothSerialPortCatalog.formatAddress(normalized)
            if (formatted == null) {
                lastError = "Failed to format Bluetooth address: $normalized"
                Log.w(TAG, lastError ?: "Failed to format Bluetooth address: $normalized")
                return@withWriteLock false
            }

            val device = adapter.bondedDevices?.firstOrNull { it.address == formatted }
            if (device == null) {
                lastError = "Bluetooth device not paired: $formatted"
                Log.w(TAG, lastError ?: "Bluetooth device not paired: $formatted")
                return@withWriteLock false
            }

            val failures = ArrayList<String>()
            val serviceUuids = resolveServiceUuids(device, normalized, 2500L)
            val serialUuids = BluetoothSerialPortCatalog.filterSerialUuids(serviceUuids)
            if (serviceUuids.isNotEmpty()) {
                Log.i(TAG, "Bluetooth service UUIDs for $formatted: ${serviceUuids.joinToString()}")
                Log.i(TAG, "Bluetooth serial UUIDs for $formatted: ${serialUuids.joinToString()}")
                val mapped = serialUuids.getOrNull(portIndex)
                if (mapped != null) {
                    Log.i(TAG, "Bluetooth port $portIndex mapped to UUID $mapped")
                }
            } else {
                Log.i(TAG, "Bluetooth service UUIDs for $formatted: none")
            }
            val attempts = buildSocketAttempts(device, portIndex, serialUuids)
            for (attempt in attempts) {
                val sock = try {
                    attempt.create()
                } catch (e: Exception) {
                    failures.add("${attempt.label} create failed: ${e.message}")
                    continue
                }
                if (sock == null) {
                    failures.add("${attempt.label} create failed")
                    continue
                }
                try {
                    adapter.cancelDiscovery()
                    sock.connect()
                    socket = sock
                    input = sock.inputStream
                    output = sock.outputStream
                    activeAddress = normalized
                    activePortIndex = portIndex
                    activeBaudRate = baudRate
                    activeDataBits = dataBits
                    activeStopBits = stopBits
                    activeParity = parity
                    lastReadErrorLogMs = 0L
                    lastWriteErrorLogMs = 0L
                    lastError = null
                    Log.i(TAG, "Bluetooth serial opened addr=$formatted port=$portIndex via ${attempt.label}")
                    return@withWriteLock true
                } catch (e: Exception) {
                    failures.add("${attempt.label} connect failed: ${e.message}")
                    try {
                        sock.close()
                    } catch (_: Exception) {
                    }
                }
            }

            lastError = if (failures.isNotEmpty()) {
                "Bluetooth socket attempts failed: ${failures.joinToString("; ")}"
            } else {
                "Bluetooth serial open failed"
            }
            Log.w(TAG, lastError ?: "Bluetooth serial open failed")
            false
        }
    }

    fun read(buffer: ByteArray, timeoutMs: Int): Int {
        return withReadLock {
            val activeInput = input ?: return@withReadLock -1
            readWithTimeout(activeInput, buffer, timeoutMs)
        }
    }

    fun write(buffer: ByteArray, length: Int, timeoutMs: Int): Int {
        return withReadLock {
            val activeOutput = output ?: return@withReadLock -1
            val safeLength = length.coerceAtLeast(0).coerceAtMost(buffer.size)
            if (safeLength == 0) return@withReadLock 0
            try {
                activeOutput.write(buffer, 0, safeLength)
                activeOutput.flush()
                safeLength
            } catch (e: Exception) {
                if (shouldLogWriteError()) {
                    Log.w(TAG, "Bluetooth serial write failed: ${e.message}")
                }
                -1
            }
        }
    }

    private fun readWithTimeout(input: InputStream, buffer: ByteArray, timeoutMs: Int): Int {
        val length = buffer.size
        if (length == 0) return 0
        val deadline = if (timeoutMs > 0) {
            SystemClock.elapsedRealtime() + timeoutMs
        } else {
            0L
        }

        while (true) {
            try {
                val available = input.available()
                if (available > 0) {
                    val toRead = available.coerceAtMost(length)
                    return input.read(buffer, 0, toRead)
                }
                if (timeoutMs <= 0) return 0
                if (SystemClock.elapsedRealtime() >= deadline) return 0
                SystemClock.sleep(10)
            } catch (e: Exception) {
                if (shouldLogReadError()) {
                    Log.w(TAG, "Bluetooth serial read failed: ${e.message}")
                }
                return -1
            }
        }
    }

    fun setRts(enabled: Boolean): Boolean {
        return true
    }

    fun setDtr(enabled: Boolean): Boolean {
        return true
    }

    fun purge(): Boolean {
        return true
    }

    fun close() {
        withWriteLock {
            closeLocked()
        }
    }

    // Not close(): native hamlib closes through that and must be able to reopen
    fun shutdown() {
        withWriteLock {
            closedForGood = true
            closeLocked()
        }
    }

    fun getLastError(): String? {
        return lastError
    }

    private fun closeLocked() {
        try {
            input?.close()
        } catch (_: Exception) {
        }
        try {
            output?.close()
        } catch (_: Exception) {
        }
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        input = null
        output = null
        activeAddress = null
        activePortIndex = null
        activeBaudRate = null
        activeDataBits = null
        activeStopBits = null
        activeParity = null
    }

    private fun buildSocketAttempts(
        device: BluetoothDevice,
        portIndex: Int,
        serviceUuids: List<UUID>
    ): List<SocketAttempt> {
        val attempts = ArrayList<SocketAttempt>()
        attempts.addAll(buildUuidAttempts(device, portIndex, serviceUuids))
        if (serviceUuids.none { it == SPP_UUID }) {
            attempts.add(SocketAttempt("spp-secure") {
                device.createRfcommSocketToServiceRecord(SPP_UUID)
            })
            attempts.add(SocketAttempt("spp-insecure") {
                device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
            })
        }

        val channels = ArrayList<Int>()
        if (portIndex > 0 && serviceUuids.size <= 1) {
            channels.addAll(listOf(2, 3, 4, 5, 6, 7, 8, 9, 10))
        } else if (portIndex > 0) {
            channels.add(portIndex + 1)
            channels.add(portIndex)
        } else {
            channels.addAll(listOf(1, 2, 3))
        }
        for (channel in channels.distinct()) {
            attempts.add(SocketAttempt("rfcomm-$channel") {
                createRfcommSocket(device, channel, insecure = false)
            })
            attempts.add(SocketAttempt("rfcomm-insecure-$channel") {
                createRfcommSocket(device, channel, insecure = true)
            })
        }

        return attempts
    }

    private fun buildUuidAttempts(
        device: BluetoothDevice,
        portIndex: Int,
        serviceUuids: List<UUID>
    ): List<SocketAttempt> {
        if (serviceUuids.isEmpty()) return emptyList()
        val ordered = ArrayList<UUID>()
        if (portIndex in serviceUuids.indices) {
            ordered.add(serviceUuids[portIndex])
        } else if (portIndex == 0) {
            ordered.addAll(serviceUuids)
        } else {
            return emptyList()
        }
        val seen = HashSet<UUID>()
        val attempts = ArrayList<SocketAttempt>()
        for (uuid in ordered) {
            if (!seen.add(uuid)) continue
            val idx = serviceUuids.indexOf(uuid)
            val label = if (idx >= 0) "uuid-$idx" else "uuid"
            attempts.add(SocketAttempt("$label-secure") {
                device.createRfcommSocketToServiceRecord(uuid)
            })
            attempts.add(SocketAttempt("$label-insecure") {
                device.createInsecureRfcommSocketToServiceRecord(uuid)
            })
        }
        return attempts
    }

    private fun createRfcommSocket(
        device: BluetoothDevice,
        channel: Int,
        insecure: Boolean
    ): BluetoothSocket? {
        return try {
            val methodName = if (insecure) "createInsecureRfcommSocket" else "createRfcommSocket"
            val method = device.javaClass.getMethod(methodName, Int::class.javaPrimitiveType)
            method.invoke(device, channel) as? BluetoothSocket
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveServiceUuids(
        device: BluetoothDevice,
        normalizedAddress: String,
        timeoutMs: Long
    ): List<UUID> {
        val cached = BluetoothSerialPortCatalog.getCachedServiceUuids(normalizedAddress)
        if (cached.isNotEmpty()) return cached
        val deviceUuids = device.uuids?.map { it.uuid }?.distinct().orEmpty()
        if (deviceUuids.isNotEmpty()) {
            BluetoothSerialPortCatalog.cacheServiceUuids(normalizedAddress, deviceUuids)
            return deviceUuids
        }
        val fetched = fetchUuidsWithTimeout(device, timeoutMs)
        if (fetched.isNotEmpty()) {
            BluetoothSerialPortCatalog.cacheServiceUuids(normalizedAddress, fetched)
            return fetched
        }
        return emptyList()
    }

    private fun fetchUuidsWithTimeout(device: BluetoothDevice, timeoutMs: Long): List<UUID> {
        val latch = CountDownLatch(1)
        var fetched: List<UUID> = emptyList()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_UUID) return
                val eventDevice = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (eventDevice?.address != device.address) return
                val uuidArray = intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)
                fetched = uuidArray
                    ?.mapNotNull { (it as? ParcelUuid)?.uuid }
                    ?.distinct()
                    .orEmpty()
                latch.countDown()
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_UUID)
        var registered = false
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, filter)
            }
            registered = true
        } catch (e: Exception) {
            Log.w(TAG, "Bluetooth UUID receiver registration failed: ${e.message}")
        }
        try {
            device.fetchUuidsWithSdp()
        } catch (e: Exception) {
            Log.w(TAG, "Bluetooth UUID fetch failed: ${e.message}")
        }
        if (registered) {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
        }
        if (fetched.isNotEmpty()) return fetched
        return device.uuids?.map { it.uuid }?.distinct().orEmpty()
    }

    private fun hasBluetoothPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val hasConnect = context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        val hasScan = context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED
        return hasConnect && hasScan
    }

    private external fun nativeRegister()
    private external fun nativeUnregister()
}
