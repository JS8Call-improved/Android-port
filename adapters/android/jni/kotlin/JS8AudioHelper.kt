package com.js8call.core

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.HandlerThread
import android.Manifest
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Helper class to integrate Android AudioRecord with JS8Engine.
 *
 * This handles the Android audio capture API and feeds samples to the native engine.
 */
class JS8AudioHelper(
    private val engine: JS8Engine,
    private val targetSampleRate: Int = 12000,  // Target rate for engine
    private var preferredDeviceId: Int = -1,  // -1 means use default
    private var context: Context? = null,
    private var audioSourceOverride: Int = -1
) : AutoCloseable {

    companion object {
        private const val DEFAULT_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var recordingThread: HandlerThread? = null
    private var isRecording = false

    // Capture at 48kHz (native USB rate) and downsample natively to target rate
    private var captureSampleRate = 48000
    private var actualSampleRate = captureSampleRate
    private var audioSource = MediaRecorder.AudioSource.MIC
    private var agc: AutomaticGainControl? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var preferredDeviceType: Int = AudioDeviceInfo.TYPE_UNKNOWN
    private var inputChannelCount = 1
    @Volatile private var lastAbsMax = 0

    /**
     * Set preferred audio input device.
     * Call before startCapture() or while recording.
     */
    fun setPreferredDevice(deviceId: Int) {
        preferredDeviceId = deviceId

        preferredDeviceType = resolvePreferredDevice()?.type ?: AudioDeviceInfo.TYPE_UNKNOWN

        // If already recording, update the device
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && context != null) {
            audioRecord?.let { record ->
                if (deviceId >= 0) {
                    val audioManager = context!!.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                    val device = devices.find { it.id == deviceId }
                    if (device != null) {
                        val success = record.setPreferredDevice(device)
                        android.util.Log.i("JS8AudioHelper",
                            "Set preferred device: ${device.productName} (ID: $deviceId, type=${deviceTypeName(device.type)}), success=$success")
                    } else {
                        android.util.Log.w("JS8AudioHelper",
                            "Device ID $deviceId not found")
                    }
                } else {
                    record.setPreferredDevice(null)  // Use default
                }
            }
        }
    }

    /**
     * Start audio capture and feed to engine.
     *
     * @return true if capture started successfully
     */
    fun startCapture(): Boolean {
        if (isRecording) return false

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val safeContext = context ?: return false
            if (safeContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                android.util.Log.w("JS8AudioHelper", "RECORD_AUDIO permission not granted")
                return false
            }
        }

        try {
            val preferredDevice = resolvePreferredDevice()
            preferredDeviceType = preferredDevice?.type ?: AudioDeviceInfo.TYPE_UNKNOWN
            val sourceCandidates = buildAudioSourceCandidates(preferredDeviceType, audioSourceOverride)
            val candidates = buildSampleRateCandidates(preferredDeviceType, preferredDevice)
            val channelMasks = buildChannelMaskCandidates(preferredDevice)
            var bufferSizeBytes = 0
            loop@ for (source in sourceCandidates) {
                audioSource = source
                for (channelMask in channelMasks) {
                    for (rate in candidates) {
                        val minBufferSize = AudioRecord.getMinBufferSize(
                            rate,
                            channelMask,
                            AUDIO_FORMAT
                        )
                        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                            continue
                        }
                        val bufferMultiplier = if (preferredDeviceType == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) 4 else 2
                        bufferSizeBytes = minBufferSize * bufferMultiplier
                        val format = AudioFormat.Builder()
                            .setChannelMask(channelMask)
                            .setEncoding(AUDIO_FORMAT)
                            .setSampleRate(rate)
                            .build()
                        val builder = AudioRecord.Builder()
                            .setAudioSource(audioSource)
                            .setAudioFormat(format)
                            .setBufferSizeInBytes(bufferSizeBytes)
                        val record = builder.build()
                        if (record.state == AudioRecord.STATE_INITIALIZED) {
                            audioRecord = record
                            captureSampleRate = rate
                            inputChannelCount = record.channelCount
                            break@loop
                        } else {
                            record.release()
                        }
                    }
                }
            }

            if (audioRecord == null) {
                return false
            }

            // Set preferred device if specified (API 23+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && preferredDeviceId >= 0 && context != null) {
                val audioManager = context!!.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                val device = devices.find { it.id == preferredDeviceId }
                if (device != null) {
                    val success = audioRecord?.setPreferredDevice(device) ?: false
                    android.util.Log.i("JS8AudioHelper",
                        "Set preferred device on startup: ${device.productName} (ID: $preferredDeviceId, type=${deviceTypeName(device.type)}), success=$success")
                } else {
                    android.util.Log.w("JS8AudioHelper",
                        "Preferred device ID $preferredDeviceId not found, using default")
                }
            } else {
                android.util.Log.i("JS8AudioHelper", "Using default input device (no preferred device set)")
            }

            // Enable low-latency mode if available (API 26+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                try {
                    // Request low-latency performance mode for better timing accuracy
                    audioRecord?.setPreferredDevice(audioRecord?.preferredDevice)
                } catch (e: Exception) {
                    android.util.Log.w("JS8AudioHelper", "Could not set performance mode: ${e.message}")
                }
            }

            audioRecord?.startRecording()
            isRecording = true
            audioRecord?.let { configureAudioEffects(it) }

            // Record the actual sample rate returned by the device. Some devices may not honor
            // the requested rate, so use the real value when handing data to native.
            actualSampleRate = audioRecord?.sampleRate ?: captureSampleRate
            val resampleRatio = actualSampleRate.toDouble() / targetSampleRate.toDouble()

            android.util.Log.i("JS8AudioHelper",
                "Audio capture started: requested $captureSampleRate Hz, actual $actualSampleRate Hz, " +
                "target $targetSampleRate Hz, source=${audioSourceName(audioSource)}, " +
                "deviceType=${deviceTypeName(preferredDeviceType)}, " +
                "handing raw audio to native downsampler " +
                "(resample ratio: ${"%.3f".format(resampleRatio)}), " +
                "channels=$inputChannelCount, buffer size: $bufferSizeBytes bytes")

            if (actualSampleRate % targetSampleRate != 0) {
                android.util.Log.i(
                    "JS8AudioHelper",
                    "Actual sample rate $actualSampleRate not divisible by target $targetSampleRate; " +
                        "using fractional resampler"
                )
            }

            // Start recording thread
            recordingThread = HandlerThread("JS8AudioCapture", Process.THREAD_PRIORITY_AUDIO).apply {
                start()
                Handler(looper).post { recordLoop() }
            }

            return true
        } catch (e: SecurityException) {
            // Permission not granted
            audioRecord?.release()
            audioRecord = null
            return false
        }
    }

    /**
     * Stop audio capture.
     */
    fun stopCapture() {
        isRecording = false

        recordingThread?.quitSafely()
        recordingThread?.join()
        recordingThread = null

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        releaseAudioEffects()
    }

    override fun close() {
        stopCapture()
    }

    private fun recordLoop() {
        val recorder = audioRecord ?: return
        val channels = recorder.channelCount.coerceAtLeast(1)
        val bufferFrames = recorder.bufferSizeInFrames.takeIf { it > 0 } ?: 1024
        val framesPerRead = if (preferredDeviceType == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            bufferFrames.coerceIn(512, 4096)
        } else {
            (bufferFrames / 2).coerceIn(256, 2048)
        }
        val buffer = ShortArray(framesPerRead * channels)
        val monoBuffer = if (channels > 1) ShortArray(framesPerRead) else null
        var totalSamples = 0L
        var lastLogTime = System.currentTimeMillis()
        var lastReadNs = System.nanoTime()

        while (isRecording) {
            val readStartNs = System.nanoTime()
            val samplesRead = recorder.read(buffer, 0, buffer.size)
            val readEndNs = System.nanoTime()
            lastReadNs = readEndNs

            if (samplesRead > 0) {
                val timestamp = System.nanoTime()
                val success = if (channels > 1) {
                    val framesRead = samplesRead / channels
                    if (framesRead <= 0) {
                        false
                    } else {
                        val out = monoBuffer ?: ShortArray(framesRead)
                        val sums = LongArray(channels)
                        val maxAbs = IntArray(channels)
                        for (i in 0 until framesRead) {
                            val base = i * channels
                            for (ch in 0 until channels) {
                                val value = kotlin.math.abs(buffer[base + ch].toInt())
                                sums[ch] += value.toLong()
                                if (value > maxAbs[ch]) {
                                    maxAbs[ch] = value
                                }
                            }
                        }
                        var bestChannel = 0
                        for (ch in 1 until channels) {
                            if (sums[ch] > sums[bestChannel]) {
                                bestChannel = ch
                            }
                        }
                        for (i in 0 until framesRead) {
                            out[i] = buffer[i * channels + bestChannel]
                        }
                        lastAbsMax = maxAbs[bestChannel]
                        engine.submitAudioRaw(out, framesRead, actualSampleRate, timestamp)
                    }
                } else {
                    var maxAbs = 0
                    for (i in 0 until samplesRead) {
                        val value = kotlin.math.abs(buffer[i].toInt())
                        if (value > maxAbs) {
                            maxAbs = value
                        }
                    }
                    lastAbsMax = maxAbs
                    engine.submitAudioRaw(buffer, samplesRead, actualSampleRate, timestamp)
                }
                if (!success) {
                    android.util.Log.w("JS8AudioHelper", "submitAudioRaw failed for $samplesRead samples")
                }

                totalSamples += samplesRead

                // Log every second
                val now = System.currentTimeMillis()
                if (now - lastLogTime >= 1000) {
                    val sampleRate = totalSamples / ((now - lastLogTime) / 1000f)
                    android.util.Log.d("JS8AudioHelper",
                        "Audio: $samplesRead samples @ ${captureSampleRate}Hz (raw), " +
                        "success=$success, rate=${sampleRate.toInt()} samples/sec, " +
                        "channels=$channels, first3=[${buffer[0]}, ${buffer[1]}, ${buffer[2]}]")
                    lastLogTime = now
                    totalSamples = 0
                }
            } else if (samplesRead < 0) {
                // Error occurred
                when (samplesRead) {
                    AudioRecord.ERROR_INVALID_OPERATION,
                    AudioRecord.ERROR_BAD_VALUE,
                    AudioRecord.ERROR_DEAD_OBJECT -> {
                        isRecording = false
                        break
                    }
                }
            }
        }
    }

    private fun resolvePreferredDevice(): AudioDeviceInfo? {
        if (preferredDeviceId < 0 || context == null) return null
        val audioManager = context!!.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.id == preferredDeviceId }
    }

    private fun pickAudioSource(deviceType: Int): Int {
        return when (deviceType) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            else -> MediaRecorder.AudioSource.MIC
        }
    }

    private fun buildAudioSourceCandidates(deviceType: Int, overrideSource: Int): List<Int> {
        val base = when (deviceType) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> listOf(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.MIC
            )
            else -> listOf(MediaRecorder.AudioSource.MIC)
        }
        if (overrideSource <= 0) return base
        val candidates = ArrayList<Int>(base.size + 1)
        candidates.add(overrideSource)
        for (source in base) {
            if (source != overrideSource) {
                candidates.add(source)
            }
        }
        return candidates
    }

    private fun buildSampleRateCandidates(
        deviceType: Int,
        device: AudioDeviceInfo?
    ): List<Int> {
        val candidates = LinkedHashSet<Int>()
        if (deviceType == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            candidates.add(16000)
            candidates.add(8000)
        }
        device?.sampleRates?.forEach { rate ->
            if (rate > 0) candidates.add(rate)
        }
        candidates.add(48000)
        candidates.add(44100)
        return candidates.toList()
    }

    private fun buildChannelMaskCandidates(device: AudioDeviceInfo?): List<Int> {
        val candidates = LinkedHashSet<Int>()
        val channelCounts = device?.channelCounts ?: IntArray(0)
        if (channelCounts.contains(1)) {
            candidates.add(AudioFormat.CHANNEL_IN_MONO)
        }
        if (channelCounts.contains(2)) {
            candidates.add(AudioFormat.CHANNEL_IN_STEREO)
        }
        if (candidates.isEmpty()) {
            candidates.add(DEFAULT_CHANNEL_CONFIG)
            candidates.add(AudioFormat.CHANNEL_IN_STEREO)
        }
        return candidates.toList()
    }

    fun getLastAbsMax(): Int {
        return lastAbsMax
    }

    private fun audioSourceName(source: Int): String {
        return when (source) {
            MediaRecorder.AudioSource.MIC -> "MIC"
            MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
            else -> "source=$source"
        }
    }

    private fun deviceTypeName(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "builtin"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired"
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_USB_HEADSET -> "usb"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth"
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> "line"
            else -> "type=$type"
        }
    }

    private fun configureAudioEffects(record: AudioRecord) {
        val sessionId = record.audioSessionId
        if (sessionId <= 0) {
            android.util.Log.w("JS8AudioHelper", "Audio effects not configured: invalid sessionId=$sessionId")
            return
        }

        android.util.Log.i("JS8AudioHelper", "Audio effects: sessionId=$sessionId")

        configureAgc(sessionId)
        configureNoiseSuppressor(sessionId)
        configureEchoCanceler(sessionId)
    }

    private fun configureAgc(sessionId: Int) {
        if (!AutomaticGainControl.isAvailable()) {
            android.util.Log.i("JS8AudioHelper", "AGC not available")
            return
        }
        try {
            agc?.release()
            agc = AutomaticGainControl.create(sessionId)
            if (agc == null) {
                android.util.Log.w("JS8AudioHelper", "AGC available but create() failed")
                return
            }
            val wasEnabled = agc?.enabled ?: false
            agc?.enabled = false
            android.util.Log.i("JS8AudioHelper", "AGC: wasEnabled=$wasEnabled, nowEnabled=${agc?.enabled}")
        } catch (e: Exception) {
            android.util.Log.w("JS8AudioHelper", "AGC error: ${e.message}")
        }
    }

    private fun configureNoiseSuppressor(sessionId: Int) {
        if (!NoiseSuppressor.isAvailable()) {
            android.util.Log.i("JS8AudioHelper", "NoiseSuppressor not available")
            return
        }
        try {
            noiseSuppressor?.release()
            noiseSuppressor = NoiseSuppressor.create(sessionId)
            if (noiseSuppressor == null) {
                android.util.Log.w("JS8AudioHelper", "NoiseSuppressor available but create() failed")
                return
            }
            val wasEnabled = noiseSuppressor?.enabled ?: false
            noiseSuppressor?.enabled = false
            android.util.Log.i("JS8AudioHelper", "NoiseSuppressor: wasEnabled=$wasEnabled, nowEnabled=${noiseSuppressor?.enabled}")
        } catch (e: Exception) {
            android.util.Log.w("JS8AudioHelper", "NoiseSuppressor error: ${e.message}")
        }
    }

    private fun configureEchoCanceler(sessionId: Int) {
        if (!AcousticEchoCanceler.isAvailable()) {
            android.util.Log.i("JS8AudioHelper", "AcousticEchoCanceler not available")
            return
        }
        try {
            echoCanceler?.release()
            echoCanceler = AcousticEchoCanceler.create(sessionId)
            if (echoCanceler == null) {
                android.util.Log.w("JS8AudioHelper", "AcousticEchoCanceler available but create() failed")
                return
            }
            val wasEnabled = echoCanceler?.enabled ?: false
            echoCanceler?.enabled = false
            android.util.Log.i("JS8AudioHelper", "AcousticEchoCanceler: wasEnabled=$wasEnabled, nowEnabled=${echoCanceler?.enabled}")
        } catch (e: Exception) {
            android.util.Log.w("JS8AudioHelper", "AcousticEchoCanceler error: ${e.message}")
        }
    }

    private fun releaseAudioEffects() {
        agc?.release()
        agc = null
        noiseSuppressor?.release()
        noiseSuppressor = null
        echoCanceler?.release()
        echoCanceler = null
    }
}

/**
 * Helper to marshal native callbacks to Android UI thread.
 */
class UIThreadCallbackAdapter(
    private val handler: Handler,
    private val delegate: JS8Engine.CallbackHandler
) : JS8Engine.CallbackHandler {

    override fun onDecoded(
        utc: Int, snr: Int, dt: Float, freq: Float,
        text: String, type: Int, quality: Float, mode: Int, driftMs: Int
    ) {
        handler.post {
            delegate.onDecoded(utc, snr, dt, freq, text, type, quality, mode, driftMs)
        }
    }

    override fun onSpectrum(bins: FloatArray, binHz: Float, powerDb: Float, peakDb: Float) {
        handler.post {
            delegate.onSpectrum(bins, binHz, powerDb, peakDb)
        }
    }

    override fun onDecodeStarted(submodes: Int) {
        handler.post {
            delegate.onDecodeStarted(submodes)
        }
    }

    override fun onDecodeFinished(count: Int) {
        handler.post {
            delegate.onDecodeFinished(count)
        }
    }

    override fun onError(message: String) {
        handler.post {
            delegate.onError(message)
        }
    }

    override fun onLog(level: Int, message: String) {
        handler.post {
            delegate.onLog(level, message)
        }
    }
}
