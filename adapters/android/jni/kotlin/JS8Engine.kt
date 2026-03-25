package com.js8call.core

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
        checkNotClosed()
        return nativeStart(nativeHandle)
    }

    /**
     * Stop the engine. Can be restarted later.
     */
    fun stop() {
        if (nativeHandle != 0L) {
            nativeStop(nativeHandle)
        }
    }

    /**
     * Submit audio samples for processing.
     *
     * @param samples Audio samples (16-bit PCM, mono)
     * @param timestampNs Capture timestamp in nanoseconds
     * @return true if successful
     */
    fun submitAudio(samples: ShortArray, timestampNs: Long = System.nanoTime()): Boolean {
        checkNotClosed()
        return nativeSubmitAudio(nativeHandle, samples, samples.size, timestampNs)
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
        checkNotClosed()
        return nativeSubmitAudioRaw(nativeHandle, samples, numSamples, inputSampleRateHz, timestampNs)
    }

    /**
     * Set the operating frequency.
     *
     * @param frequencyHz Frequency in Hz
     */
    fun setFrequency(frequencyHz: Long) {
        checkNotClosed()
        nativeSetFrequency(nativeHandle, frequencyHz)
    }

    /**
     * Set enabled submodes.
     *
     * @param submodes Bitmask of enabled submodes
     */
    fun setSubmodes(submodes: Int) {
        checkNotClosed()
        nativeSetSubmodes(nativeHandle, submodes)
    }

    /**
     * Enable or disable TX output boost (+10 dB).
     *
     * @param enabled true to enable boost, false to disable
     */
    fun setTxBoostEnabled(enabled: Boolean) {
        checkNotClosed()
        nativeSetTxBoostEnabled(nativeHandle, enabled)
    }

    /**
     * Set preferred output audio device ID (0 or negative for default).
     */
    fun setOutputDevice(deviceId: Int) {
        checkNotClosed()
        nativeSetOutputDevice(nativeHandle, deviceId)
    }

    /**
     * Check if the engine is running.
     */
    fun isRunning(): Boolean {
        return nativeHandle != 0L && nativeIsRunning(nativeHandle)
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
        checkNotClosed()
        return nativeTransmitMessage(
            nativeHandle,
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
        checkNotClosed()
        return nativeTransmitFrame(
            nativeHandle,
            frame,
            bits,
            submode,
            audioFrequencyHz,
            txDelaySec
        )
    }

    /**
     * Start a tuning tone at the given audio frequency.
     */
    fun startTune(
        audioFrequencyHz: Double,
        submode: Int,
        txDelaySec: Double = 0.0
    ): Boolean {
        checkNotClosed()
        return nativeStartTune(
            nativeHandle,
            audioFrequencyHz,
            submode,
            txDelaySec
        )
    }

    /**
     * Stop any active transmission or tuning tone.
     */
    fun stopTransmit() {
        if (nativeHandle != 0L) {
            nativeStopTransmit(nativeHandle)
        }
    }

    /**
     * Check if a transmission is currently active.
     */
    fun isTransmitting(): Boolean {
        return nativeHandle != 0L && nativeIsTransmitting(nativeHandle)
    }

    /**
     * Check if the TX modulator is actively producing audio.
     */
    fun isTransmittingAudio(): Boolean {
        return nativeHandle != 0L && nativeIsTransmittingAudio(nativeHandle)
    }

    /**
     * Close and destroy the engine. After calling this, the engine cannot be used.
     */
    override fun close() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
    }

    private fun checkNotClosed() {
        if (nativeHandle == 0L) {
            throw IllegalStateException("Engine has been closed")
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
            mode: Int
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
