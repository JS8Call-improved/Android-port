package com.js8call.core

import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Native JS8Call engine wrapper for Android.
 *
 * This class provides a Kotlin-friendly interface to the native JS8 engine.
 * It handles lifecycle management, audio processing, and event callbacks.
 */
class JS8Engine private constructor(
    private var nativeHandle: Long,
    private val callbackHandler: CallbackHandler
) : AutoCloseable {

    private val lifecycleLock = ReentrantReadWriteLock(true)

    companion object {
        init {
            System.loadLibrary("js8core-jni")
        }

        /**
         * Create a new JS8 engine instance.
         *
         * @param sampleRateHz Audio sample rate (typically 12000 or 48000)
         * @param submodes Bitmask of enabled submodes
         * @param callbackHandler Handler for engine events
         */
        fun create(
            sampleRateHz: Int = 12000,
            submodes: Int = 0x1F, // default to A/B/C/E/I like desktop
            callbackHandler: CallbackHandler,
            enableTxAudioTap: Boolean = false
        ): JS8Engine {
            val handle = nativeCreate(callbackHandler, sampleRateHz, submodes, enableTxAudioTap)
            if (handle == 0L) {
                throw RuntimeException("Failed to create native JS8 engine")
            }
            return JS8Engine(handle, callbackHandler)
        }

        @JvmStatic
        private external fun nativeCreate(
            callbackHandler: CallbackHandler,
            sampleRateHz: Int,
            submodes: Int,
            enableTxAudioTap: Boolean
        ): Long
    }

    /**
     * Start the engine. Must be called before submitting audio.
     */
    fun start(): Boolean {
        return withNativeHandle { nativeStart(it) }
    }

    /**
     * Stop the engine. Can be restarted later.
     */
    fun stop() {
        withNativeHandleOr(Unit) { nativeStop(it) }
    }

    /**
     * Submit audio samples for processing.
     *
     * @param samples Audio samples (16-bit PCM, mono)
     * @param timestampNs Capture timestamp in nanoseconds
     * @return true if successful
     */
    fun submitAudio(samples: ShortArray, timestampNs: Long = System.nanoTime()): Boolean {
        return withNativeHandle { nativeSubmitAudio(it, samples, samples.size, timestampNs) }
    }

    /**
     * Submit raw audio at a higher rate; native code will decimate to the engine rate.
     *
     * @param samples   Audio samples (16-bit PCM, mono)
     * @param numSamples Number of valid samples in the array
     * @param inputSampleRateHz Sample rate of the input data (e.g., 48000)
     * @param timestampNs Capture timestamp in nanoseconds
     */
    fun submitAudioRaw(
        samples: ShortArray,
        numSamples: Int,
        inputSampleRateHz: Int,
        timestampNs: Long = System.nanoTime()
    ): Boolean {
        return withNativeHandle {
            nativeSubmitAudioRaw(it, samples, numSamples, inputSampleRateHz, timestampNs)
        }
    }

    /**
     * Set the operating frequency.
     *
     * @param frequencyHz Frequency in Hz
     */
    fun setFrequency(frequencyHz: Long) {
        withNativeHandle { nativeSetFrequency(it, frequencyHz) }
    }

    /**
     * Set enabled submodes.
     *
     * @param submodes Bitmask of enabled submodes
     */
    fun setSubmodes(submodes: Int) {
        withNativeHandle { nativeSetSubmodes(it, submodes) }
    }

    /**
     * Enable or disable TX output boost (+10 dB).
     *
     * @param enabled true to enable boost, false to disable
     */
    fun setTxBoostEnabled(enabled: Boolean) {
        withNativeHandle { nativeSetTxBoostEnabled(it, enabled) }
    }

    /**
     * Set preferred output audio device ID (0 or negative for default).
     */
    fun setOutputDevice(deviceId: Int) {
        withNativeHandle { nativeSetOutputDevice(it, deviceId) }
    }

    /**
     * Check if the engine is running.
     */
    fun isRunning(): Boolean {
        return withNativeHandleOr(false) { nativeIsRunning(it) }
    }

    /**
     * Transmit a text message by building JS8 frames and scheduling audio output.
     */
    fun transmitMessage(
        text: String,
        myCall: String,
        myGrid: String,
        selectedCall: String = "",
        submode: Int = 0,
        audioFrequencyHz: Double,
        txDelaySec: Double = 0.0,
        forceIdentify: Boolean = false,
        forceData: Boolean = false
    ): Boolean {
        return withNativeHandle { handle ->
            nativeTransmitMessage(
                handle,
                text,
                myCall,
                myGrid,
                selectedCall,
                submode,
                audioFrequencyHz,
                txDelaySec,
                forceIdentify,
                forceData
            )
        }
    }

    /**
     * Transmit a pre-encoded 12-character frame with JS8 bit flags.
     */
    fun transmitFrame(
        frame: String,
        bits: Int,
        submode: Int,
        audioFrequencyHz: Double,
        txDelaySec: Double = 0.0
    ): Boolean {
        return withNativeHandle { handle ->
            nativeTransmitFrame(
                handle,
                frame,
                bits,
                submode,
                audioFrequencyHz,
                txDelaySec
            )
        }
    }

    /**
     * Start a tuning tone at the given audio frequency.
     */
    fun startTune(
        audioFrequencyHz: Double,
        submode: Int,
        txDelaySec: Double = 0.0
    ): Boolean {
        return withNativeHandle { handle ->
            nativeStartTune(
                handle,
                audioFrequencyHz,
                submode,
                txDelaySec
            )
        }
    }

    /**
     * Stop any active transmission or tuning tone.
     */
    fun stopTransmit() {
        withNativeHandleOr(Unit) { nativeStopTransmit(it) }
    }

    /**
     * Check if a transmission is currently active.
     */
    fun isTransmitting(): Boolean {
        return withNativeHandleOr(false) { nativeIsTransmitting(it) }
    }

    /**
     * Check if the TX modulator is actively producing audio.
     */
    fun isTransmittingAudio(): Boolean {
        return withNativeHandleOr(false) { nativeIsTransmittingAudio(it) }
    }

    /**
     * Milliseconds until scheduled TX audio begins, or -1 when no TX is active.
     */
    fun txMillisecondsUntilAudio(): Int {
        return withNativeHandleOr(-1) { nativeTxMillisecondsUntilAudio(it) }
    }

    /** Controls whether scheduled modulation may advance and reach the output. */
    fun setTransmitReady(ready: Boolean) {
        withNativeHandle { nativeSetTxReady(it, ready) }
    }

    /**
     * Set the clock drift offset (ms) applied to all cycle timing. Positive
     * means the engine's clock runs ahead of the system clock.
     */
    fun setTimeDriftMs(driftMs: Long) {
        withNativeHandle { nativeSetTimeDriftMs(it, driftMs) }
    }

    /** Current clock drift offset in milliseconds. */
    fun timeDriftMs(): Long {
        return withNativeHandleOr(0L) { nativeGetTimeDriftMs(it) }
    }

    /**
     * Close and destroy the engine. After calling this, the engine cannot be used.
     */
    override fun close() {
        val writeLock = lifecycleLock.writeLock()
        writeLock.lock()
        val handle = try {
            nativeHandle.also { nativeHandle = 0L }
        } finally {
            writeLock.unlock()
        }

        if (handle != 0L) nativeDestroy(handle)
    }

    private inline fun <T> withNativeHandle(action: (Long) -> T): T {
        val readLock = lifecycleLock.readLock()
        readLock.lock()
        return try {
            val handle = nativeHandle
            check(handle != 0L) { "Engine has been closed" }
            action(handle)
        } finally {
            readLock.unlock()
        }
    }

    private inline fun <T> withNativeHandleOr(defaultValue: T, action: (Long) -> T): T {
        val readLock = lifecycleLock.readLock()
        readLock.lock()
        return try {
            val handle = nativeHandle
            if (handle == 0L) defaultValue else action(handle)
        } finally {
            readLock.unlock()
        }
    }

    // Native methods
    private external fun nativeStart(handle: Long): Boolean
    private external fun nativeStop(handle: Long)
    private external fun nativeDestroy(handle: Long)
    private external fun nativeSubmitAudio(
        handle: Long,
        samples: ShortArray,
        numSamples: Int,
        timestampNs: Long
    ): Boolean
    private external fun nativeSubmitAudioRaw(
        handle: Long,
        samples: ShortArray,
        numSamples: Int,
        inputSampleRateHz: Int,
        timestampNs: Long
    ): Boolean
    private external fun nativeSetFrequency(handle: Long, frequencyHz: Long)
    private external fun nativeSetSubmodes(handle: Long, submodes: Int)
    private external fun nativeSetOutputDevice(handle: Long, deviceId: Int)
    private external fun nativeSetTxBoostEnabled(handle: Long, enabled: Boolean)
    private external fun nativeIsRunning(handle: Long): Boolean
    private external fun nativeTransmitMessage(
        handle: Long,
        text: String,
        myCall: String,
        myGrid: String,
        selectedCall: String,
        submode: Int,
        audioFrequencyHz: Double,
        txDelaySec: Double,
        forceIdentify: Boolean,
        forceData: Boolean
    ): Boolean
    private external fun nativeTransmitFrame(
        handle: Long,
        frame: String,
        bits: Int,
        submode: Int,
        audioFrequencyHz: Double,
        txDelaySec: Double
    ): Boolean
    private external fun nativeStartTune(
        handle: Long,
        audioFrequencyHz: Double,
        submode: Int,
        txDelaySec: Double
    ): Boolean
    private external fun nativeStopTransmit(handle: Long)
    private external fun nativeIsTransmitting(handle: Long): Boolean
    private external fun nativeIsTransmittingAudio(handle: Long): Boolean
    private external fun nativeTxMillisecondsUntilAudio(handle: Long): Int
    private external fun nativeSetTxReady(handle: Long, ready: Boolean)
    private external fun nativeSetTimeDriftMs(handle: Long, driftMs: Long)
    private external fun nativeGetTimeDriftMs(handle: Long): Long

    /**
     * Callback interface for engine events.
     * All callbacks are invoked on a native thread.
     */
    interface CallbackHandler {
        /**
         * Called when a message is decoded.
         */
        fun onDecoded(
            utc: Int,
            snr: Int,
            dt: Float,
            freq: Float,
            text: String,
            type: Int,
            quality: Float,
            mode: Int,
            driftMs: Int
        )

        /**
         * Called with spectrum/waterfall data.
         */
        fun onSpectrum(
            bins: FloatArray,
            binHz: Float,
            powerDb: Float,
            peakDb: Float
        )

        /**
         * Called when decode cycle starts.
         */
        fun onDecodeStarted(submodes: Int)

        /**
         * Called when decode cycle finishes.
         */
        fun onDecodeFinished(count: Int)

        /**
         * Called on engine errors.
         */
        fun onError(message: String)

        /**
         * Called for log messages.
         * @param level 0=Trace, 1=Debug, 2=Info, 3=Warn, 4=Error
         */
        fun onLog(level: Int, message: String)

        /**
         * Called with TX audio PCM samples when enabled.
         */
        fun onTxAudio(samples: ShortArray, sampleRateHz: Int) {}
    }
}
