package com.js8call.core

import android.content.Context
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager

/** Dedicated USB CDC transport for the QRP Labs QMX CAT interface. */
class QmxDirectSerial(context: Context) {
    interface Listener {
        fun onData(data: ByteArray)
        fun onRunError(message: String)
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val lock = Any()
    @Volatile private var port: UsbSerialPort? = null
    @Volatile private var ioManager: SerialInputOutputManager? = null
    @Volatile private var listener: Listener? = null

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun open(deviceId: Int, portIndex: Int, baudRate: Int): Boolean = synchronized(lock) {
        closeLocked()
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            .firstOrNull { it.device.deviceId == deviceId } ?: return false
        if (!usbManager.hasPermission(driver.device)) return false
        val connection = usbManager.openDevice(driver.device) ?: return false
        val selectedPort = driver.ports.getOrNull(portIndex) ?: driver.ports.firstOrNull()
        if (selectedPort == null) {
            connection.close()
            return false
        }
        try {
            selectedPort.open(connection)
            selectedPort.setParameters(baudRate, 8, 1, UsbSerialPort.PARITY_NONE)
            val manager = SerialInputOutputManager(selectedPort, object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) = listener?.onData(data) ?: Unit
                override fun onRunError(e: Exception) {
                    listener?.onRunError(e.message ?: "QMX CAT serial I/O error")
                }
            })
            manager.start()
            ioManager = manager
            port = selectedPort
            true
        } catch (error: Throwable) {
            Log.w(TAG, "QMX USB serial open failed: ${error.message}")
            try { selectedPort.close() } catch (_: Throwable) {}
            try { connection.close() } catch (_: Throwable) {}
            false
        }
    }

    fun write(data: ByteArray, timeoutMs: Int = WRITE_TIMEOUT_MS): Boolean {
        val activePort = port ?: return false
        return try {
            activePort.write(data, timeoutMs)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun close() = synchronized(lock) { closeLocked() }

    private fun closeLocked() {
        ioManager?.setListener(null)
        ioManager?.stop()
        ioManager = null
        val activePort = port
        port = null
        try { activePort?.close() } catch (_: Throwable) {}
    }

    private companion object {
        const val TAG = "QmxDirectSerial"
        const val WRITE_TIMEOUT_MS = 1_000
    }
}
