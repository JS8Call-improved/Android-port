package com.js8call.core

/**
 * JNI wrapper for Hamlib rig control on Android.
 */
class HamlibRigControl {
    companion object {
        init {
            System.loadLibrary("js8core-jni")
        }
    }

    @Volatile
    private var handle: Long = 0
    @Volatile
    private var closedForGood = false

    @Synchronized
    fun open(
        rigModel: Int,
        deviceId: Int,
        portIndex: Int,
        baudRate: Int,
        dataBits: Int,
        stopBits: Int,
        parity: String
    ): Boolean {
        if (closedForGood) return false
        close()
        val nativeHandle = nativeOpen(
            rigModel,
            deviceId,
            portIndex,
            baudRate,
            dataBits,
            stopBits,
            parity
        )
        if (nativeHandle == 0L) {
            return false
        }
        handle = nativeHandle
        return true
    }

    @Synchronized
    fun openSerialPath(
        rigModel: Int,
        serialPath: String,
        baudRate: Int,
        dataBits: Int,
        stopBits: Int,
        parity: String
    ): Boolean {
        if (closedForGood) return false
        close()
        val nativeHandle = nativeOpenWithPath(
            rigModel,
            serialPath,
            baudRate,
            dataBits,
            stopBits,
            parity
        )
        if (nativeHandle == 0L) {
            return false
        }
        handle = nativeHandle
        return true
    }

    @Synchronized
    fun close() {
        val active = handle
        if (active != 0L) {
            nativeClose(active)
            handle = 0
        }
    }

    @Synchronized
    fun shutdown() {
        closedForGood = true
        close()
    }

    @Synchronized
    fun setFrequency(frequencyHz: Long): Boolean {
        val active = handle
        if (active == 0L) return false
        return nativeSetFrequency(active, frequencyHz)
    }

    @Synchronized
    fun setPtt(enabled: Boolean): Boolean {
        val active = handle
        if (active == 0L) return false
        return nativeSetPtt(active, enabled)
    }

    @Synchronized
    fun setMode(mode: String, passband: Int): Boolean {
        val active = handle
        if (active == 0L) return false
        return nativeSetMode(active, mode, passband)
    }

    fun isOpen(): Boolean = handle != 0L

    fun getLastError(): String {
        return nativeGetLastError()
    }

    private external fun nativeOpen(
        rigModel: Int,
        deviceId: Int,
        portIndex: Int,
        baudRate: Int,
        dataBits: Int,
        stopBits: Int,
        parity: String
    ): Long
    private external fun nativeOpenWithPath(
        rigModel: Int,
        serialPath: String,
        baudRate: Int,
        dataBits: Int,
        stopBits: Int,
        parity: String
    ): Long

    private external fun nativeClose(handle: Long)
    private external fun nativeSetFrequency(handle: Long, frequencyHz: Long): Boolean
    private external fun nativeSetPtt(handle: Long, enabled: Boolean): Boolean
    private external fun nativeSetMode(handle: Long, mode: String, passband: Int): Boolean
    private external fun nativeGetLastError(): String
}
