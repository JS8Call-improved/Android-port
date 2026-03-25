package com.js8call.core

import android.content.Context
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber

class TruSdxDirectSerial(context: Context) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val lock = Any()

    @Volatile private var port: UsbSerialPort? = null

    fun open(
        deviceId: Int,
        portIndex: Int,
        baudRate: Int,
        dataBits: Int,
        stopBits: Int,
        parity: Int
    ): Boolean {
        synchronized(lock) {
            closeLocked()
            val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            val driver = drivers.firstOrNull { it.device.deviceId == deviceId }
            if (driver == null) {
                Log.w(TAG, "No USB serial driver for deviceId=$deviceId")
                return false
            }
            if (!usbManager.hasPermission(driver.device)) {
                Log.w(TAG, "Missing USB permission for deviceId=$deviceId")
                return false
            }

            val connection = usbManager.openDevice(driver.device)
            if (connection == null) {
                Log.w(TAG, "Failed to open USB device deviceId=$deviceId")
                return false
            }

            val resolvedPort = if (portIndex in driver.ports.indices) portIndex else 0
            val selectedPort = driver.ports.getOrNull(resolvedPort)
            if (selectedPort == null) {
                Log.w(TAG, "No USB serial port index=$resolvedPort deviceId=$deviceId")
                connection.close()
                return false
            }

            return try {
                selectedPort.open(connection)
                selectedPort.setParameters(baudRate, dataBits, stopBits, parity)
                try {
                    selectedPort.dtr = true
                } catch (_: Throwable) {
                }
                try {
                    selectedPort.rts = false
                } catch (_: Throwable) {
                }
                port = selectedPort
                true
            } catch (t: Throwable) {
                Log.w(TAG, "USB serial open failed: ${t.message}")
                try {
                    selectedPort.close()
                } catch (_: Throwable) {
                }
                try {
                    connection.close()
                } catch (_: Throwable) {
                }
                false
            }
        }
    }

    fun read(buffer: ByteArray, timeoutMs: Int): Int {
        val active = port ?: return -1
        return try {
            active.read(buffer, timeoutMs)
        } catch (_: Throwable) {
            -1
        }
    }

    fun write(buffer: ByteArray, length: Int, timeoutMs: Int): Int {
        val active = port ?: return -1
        val safeLength = length.coerceAtLeast(0).coerceAtMost(buffer.size)
        val payload = if (safeLength == buffer.size) buffer else buffer.copyOf(safeLength)
        return try {
            active.write(payload, timeoutMs)
            safeLength
        } catch (_: Throwable) {
            -1
        }
    }

    fun setRts(enabled: Boolean): Boolean {
        val active = port ?: return false
        return try {
            active.rts = enabled
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun setDtr(enabled: Boolean): Boolean {
        val active = port ?: return false
        return try {
            active.dtr = enabled
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun purge(): Boolean {
        val active = port ?: return false
        return try {
            active.purgeHwBuffers(true, true)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun close() {
        synchronized(lock) {
            closeLocked()
        }
    }

    private fun closeLocked() {
        val active = port
        port = null
        if (active != null) {
            try {
                active.close()
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        private const val TAG = "TruSdxDirectSerial"
    }
}
