package com.js8call.core

import android.content.Context
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Minimal USB serial bridge used by native code via JNI.
 * The native layer registers this instance and calls open/read/write/close.
 */
class UsbSerialBridge(private val context: Context) {
    companion object {
        init {
            System.loadLibrary("js8core-jni")
        }

        private const val TAG = "UsbSerialBridge"
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var port: UsbSerialPort? = null
    private val portLock = ReentrantReadWriteLock()
    private var lastReadErrorLogMs = 0L
    private var lastWriteErrorLogMs = 0L
    private var activeDeviceId: Int? = null
    private var activePortIndex: Int? = null
    private var activeBaudRate: Int? = null
    private var activeDataBits: Int? = null
    private var activeStopBits: Int? = null
    private var activeParity: Int? = null

    private inline fun <T> withReadLock(action: () -> T): T {
        val lock = portLock.readLock()
        lock.lock()
        return try {
            action()
        } finally {
            lock.unlock()
        }
    }

    private inline fun <T> withWriteLock(action: () -> T): T {
        val lock = portLock.writeLock()
        lock.lock()
        return try {
            action()
        } finally {
            lock.unlock()
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

    fun getActivePortIndex(): Int? {
        return withReadLock { activePortIndex }
    }

    fun open(
        deviceId: Int,
        portIndex: Int,
        baudRate: Int,
        dataBits: Int,
        stopBits: Int,
        parity: Int
    ): Boolean {
        return withWriteLock {
            val existing = port
            if (existing != null &&
                activeDeviceId == deviceId &&
                activePortIndex == portIndex &&
                activeBaudRate == baudRate &&
                activeDataBits == dataBits &&
                activeStopBits == stopBits &&
                activeParity == parity) {
                Log.i(TAG, "USB serial already open deviceId=$deviceId port=$portIndex")
                return@withWriteLock true
            }

            closeLocked()

            Log.i(TAG, "Opening USB serial deviceId=$deviceId port=$portIndex baud=$baudRate data=$dataBits stop=$stopBits parity=$parity")

            val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            val driver = drivers.firstOrNull { it.device.deviceId == deviceId }
            if (driver == null) {
                Log.w(TAG, "No USB serial driver for deviceId=$deviceId")
                return@withWriteLock false
            }

            if (!usbManager.hasPermission(driver.device)) {
                Log.w(TAG, "Missing USB permission for deviceId=$deviceId")
                return@withWriteLock false
            }

            val connection = usbManager.openDevice(driver.device)
            if (connection == null) {
                Log.w(TAG, "Failed to open USB device deviceId=$deviceId")
                return@withWriteLock false
            }

            val resolvedPortIndex = when {
                portIndex >= 0 && portIndex < driver.ports.size -> portIndex
                else -> 0
            }

            if (resolvedPortIndex != portIndex) {
                Log.i(TAG, "Requested port $portIndex unavailable, using port $resolvedPortIndex")
            }

            val selectedPort = driver.ports.getOrNull(resolvedPortIndex)
            if (selectedPort == null) {
                Log.w(TAG, "No USB serial port index=$resolvedPortIndex deviceId=$deviceId")
                connection.close()
                return@withWriteLock false
            }

            try {
                selectedPort.open(connection)
                selectedPort.setParameters(baudRate, dataBits, stopBits, parity)
                try {
                    selectedPort.dtr = true
                } catch (e: Exception) {
                    Log.d(TAG, "Failed to set DTR: ${e.message}")
                }
                try {
                    selectedPort.rts = false
                } catch (e: Exception) {
                    Log.d(TAG, "Failed to set RTS: ${e.message}")
                }
                port = selectedPort
                activeDeviceId = deviceId
                activePortIndex = resolvedPortIndex
                activeBaudRate = baudRate
                activeDataBits = dataBits
                activeStopBits = stopBits
                activeParity = parity
                lastReadErrorLogMs = 0L
                lastWriteErrorLogMs = 0L
                Log.i(TAG, "USB serial opened deviceId=$deviceId port=$resolvedPortIndex")
                true
            } catch (e: Exception) {
                Log.e(TAG, "USB serial open failed: ${e.message}", e)
                try {
                    selectedPort.close()
                } catch (_: Exception) {
                }
                false
            }
        }
    }

    fun read(buffer: ByteArray, timeoutMs: Int): Int {
        return withReadLock<Int> {
            val active = port ?: return@withReadLock -1
            try {
                val bytes = active.read(buffer, timeoutMs)
                bytes
            } catch (e: Exception) {
                if (shouldLogReadError()) {
                    Log.w(TAG, "USB serial read failed: ${e.message}")
                }
                -1
            }
        }
    }

    fun write(buffer: ByteArray, length: Int, timeoutMs: Int): Int {
        return withReadLock<Int> {
            val active = port ?: return@withReadLock -1
            val safeLength = length.coerceAtLeast(0).coerceAtMost(buffer.size)
            val payload = if (safeLength == buffer.size) buffer else buffer.copyOf(safeLength)
            try {
                active.write(payload, timeoutMs)
                safeLength
            } catch (e: Exception) {
                if (shouldLogWriteError()) {
                    Log.w(TAG, "USB serial write failed: ${e.message}")
                }
                -1
            }
        }
    }

    fun setRts(enabled: Boolean): Boolean {
        return withReadLock {
            val active = port ?: return@withReadLock false
            try {
                active.rts = enabled
                true
            } catch (e: Exception) {
                Log.d(TAG, "USB serial setRts failed: ${e.message}")
                false
            }
        }
    }

    fun setDtr(enabled: Boolean): Boolean {
        return withReadLock {
            val active = port ?: return@withReadLock false
            try {
                active.dtr = enabled
                true
            } catch (e: Exception) {
                Log.d(TAG, "USB serial setDtr failed: ${e.message}")
                false
            }
        }
    }

    fun purge(): Boolean {
        return withReadLock {
            val active = port ?: return@withReadLock false
            try {
                active.purgeHwBuffers(true, true)
                true
            } catch (e: Exception) {
                Log.d(TAG, "USB serial purge failed: ${e.message}")
                false
            }
        }
    }

    fun close() {
        withWriteLock {
            closeLocked()
        }
    }

    private fun closeLocked() {
        try {
            port?.close()
        } catch (e: Exception) {
            Log.d(TAG, "USB serial close failed: ${e.message}")
        }
        port = null
        activeDeviceId = null
        activePortIndex = null
        activeBaudRate = null
        activeDataBits = null
        activeStopBits = null
        activeParity = null
    }

    private external fun nativeRegister()
    private external fun nativeUnregister()
}
