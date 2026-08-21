package com.js8call.example.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import com.js8call.core.BluetoothSerialBridge
import com.js8call.core.BluetoothSerialPortCatalog
import com.js8call.core.HamlibRigControl
import com.js8call.core.JS8AudioHelper
import com.js8call.core.JS8Engine
import com.js8call.core.TruSdxDirectSerial
import com.js8call.core.UsbSerialBridge
import com.js8call.core.UsbSerialPortCatalog
import com.js8call.example.MainActivity
import com.js8call.example.MessageLogWriter
import com.js8call.example.R
import com.js8call.example.BuildConfig
import com.js8call.example.network.PskReporterClient
import com.js8call.example.util.CallsignValidator
import com.js8call.example.util.TxMessageClassifier
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

internal fun assembleMsgPayload(parts: List<String>): String = parts.joinToString(separator = "")

/**
 * Foreground service for running the JS8 engine in the background.
 *
 * This service manages the native engine lifecycle, audio capture,
 * and broadcasts decode events to the UI.
 */
class JS8EngineService : Service() {

    private var engine: JS8Engine? = null
    private var audioHelper: JS8AudioHelper? = null
    private var rigCtlClient: RigCtlClient? = null
    private var rigCtlConnected: Boolean = false
    private var rigCtlErrorShown: Boolean = false
    private var rigControlMode: String = "none"
    private var hamlibRigControl: HamlibRigControl? = null
    private var hamlibRigConnected: Boolean = false
    private var rtsPttConnected: Boolean = false
    private var trusdxConnected: Boolean = false
    private var rtsPttTransport: SerialTransport? = null
    private var usbSerialBridge: UsbSerialBridge? = null
    private var bluetoothSerialBridge: BluetoothSerialBridge? = null
    private var trusdxDirectSerial: TruSdxDirectSerial? = null
    private var trusdxSerialSession: TruSdxSerialSession? = null
    @Volatile private var trusdxInitInProgress: Boolean = false
    @Volatile private var engineStartGeneration: Int = 0
    @Volatile private var engineStartInProgress: Boolean = false
    @Volatile private var trusdxStartupWorkerActive: Boolean = false
    @Volatile private var trusdxParserResyncs: Long = 0
    @Volatile private var trusdxRxFrames: Long = 0
    @Volatile private var trusdxRxSamples: Long = 0
    @Volatile private var trusdxTxFrames: Long = 0
    @Volatile private var trusdxTxSamples: Long = 0
    @Volatile private var trusdxTxDrops: Long = 0
    @Volatile private var trusdxTxSilentFrames: Long = 0
    @Volatile private var trusdxRxUnderruns: Long = 0
    @Volatile private var trusdxRxSubmitDrops: Long = 0
    @Volatile private var trusdxLastRxAudioNs: Long = 0L
    @Volatile private var trusdxLastRxRearmNs: Long = 0L
    @Volatile private var trusdxWatchdogToken: Int = 0
    @Volatile private var spectrumEventCount: Long = 0
    @Volatile private var trusdxRxRateWindowStartNs: Long = 0L
    @Volatile private var trusdxRxRateWindowSamples: Long = 0L
    @Volatile private var trusdxRxFrameDrops: Long = 0L
    @Volatile private var trusdxRxKeepaliveToken: Int = 0
    @Volatile private var trusdxRxKeepaliveCount: Long = 0L
    private val trusdxRxFrameQueue = LinkedBlockingDeque<ByteArray>(TRUSDX_RX_FRAME_QUEUE_MAX)
    @Volatile private var trusdxRxWorkerRunning = false
    @Volatile private var trusdxRxWorkerThread: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val txHandlerThread = HandlerThread("Js8Tx")
    private lateinit var txHandler: Handler
    private lateinit var txMonitorHandler: Handler
    private var selectedAudioDeviceId: Int = -1  // -1 means use default
    private var selectedOutputDeviceId: Int = -1  // -1 means use default
    private var currentTxOffsetHz: Float = 1500f
    private var txMonitorActive = false
    private var txMonitorWasAudioActive = false
    private val pttStateLock = Any()
    @Volatile private var txPttGeneration = 0
    @Volatile private var rigPttAsserted = false
    private var rigPttDesired = false
    private var rigPttDesiredGeneration = 0
    private var rigPttCommandPending = false
    private var rigPttFailureCount = 0
    private var rigPttCompletion: ((Boolean) -> Unit)? = null
    private var pttShuttingDown = false
    @Volatile private var txPttFailed = false
    @Volatile private var txSessionActive = false
    @Volatile private var txAudioActive = false
    @Volatile private var trusdxTxIntentActive = false
    private var scoRoutingActive = false
    private var previousAudioMode = AudioManager.MODE_NORMAL
    private var scoRestartAttempts = 0
    private var scoSilenceCheckToken = 0
    private var scoStartToken = 0
    private var scoSourceIndex = 0
    private val scoSourceCandidates = intArrayOf(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        MediaRecorder.AudioSource.MIC
    )
    private var callsignWarningShown = false
    private var lastTxMessage: String = ""
    private var lastTxDirected: String = ""
    private var lastTxFrameIndex: Int = 0
    private var lastTxFrameCount: Int = 0
    private var lastTxSubmode: Int = SUBMODE_NORMAL
    private var lastTxFrequencyHz: Double = DEFAULT_AUDIO_FREQUENCY_HZ
    private var messageLogger: MessageLogWriter? = null
    private val heartbeatRegex = Regex("^\\s*([^:]+):\\s+@HB\\s+HEARTBEAT\\b", RegexOption.IGNORE_CASE)
    private val heardCallsigns = mutableMapOf<String, Long>()
    private val heardLock = Any()
    private val relayBuffers = mutableMapOf<Int, RelayBuffer>()
    private val relayLock = Any()
    private val relayTargetRegex = Regex("^\\s*([A-Z0-9/]+)([> ])", RegexOption.IGNORE_CASE)
    private val relayPathRegex = Regex("\\s(?:\\*DE\\*|VIA)\\s([A-Z0-9/]+)", RegexOption.IGNORE_CASE)
    private val gridRegex = Regex("\\b[A-R]{2}[0-9]{2}([A-X]{2})?\\b", RegexOption.IGNORE_CASE)
    private val txMonitorRunnable = object : Runnable {
        override fun run() {
            val generation = txPttGeneration
            val activeEngine = engine
            if (activeEngine == null) {
                txMonitorActive = false
                return
            }
            val sessionActive = activeEngine.isTransmitting()
            val audioActive = activeEngine.isTransmittingAudio()
            val millisecondsUntilAudio = activeEngine.txMillisecondsUntilAudio()
            txSessionActive = sessionActive
            txAudioActive = audioActive
            if (sessionActive) {
                val frameIndex = activeEngine.txFrameIndex()
                val frameCount = activeEngine.txFrameCount()
                if (frameIndex != lastTxFrameIndex || frameCount != lastTxFrameCount) {
                    lastTxFrameIndex = frameIndex
                    lastTxFrameCount = frameCount
                    broadcastTxProgress(frameIndex, frameCount)
                }
            } else {
                lastTxFrameIndex = 0
                lastTxFrameCount = 0
            }
            if (!sessionActive) {
                txMonitorActive = false
                txMonitorWasAudioActive = false
                trusdxTxIntentActive = false
                if (isRigControlConnected()) {
                    requestRigPtt(false, generation) { released ->
                        if (!isCurrentTxGeneration(generation)) return@requestRigPtt
                        if (released) {
                            broadcastTxState(TX_STATE_FINISHED)
                        } else {
                            broadcastError("Failed to release PTT after transmission")
                            broadcastTxState(TX_STATE_FAILED)
                        }
                    }
                } else {
                    if (isCurrentTxGeneration(generation)) {
                        broadcastTxState(TX_STATE_FINISHED)
                    }
                }
                return
            }

            val rigRequired = isRigPttRequired()
            val rigConnected = isRigControlConnected()
            if (rigRequired && !rigConnected) {
                failTransmitForPtt(generation, "Rig control is not connected")
                return
            }
            if (rigConnected && !audioActive && millisecondsUntilAudio in 0..PTT_LEAD_TIME_MS) {
                requestRigPtt(true, generation)
            }

            if (audioActive && rigConnected && !isRigPttReady(generation)) {
                activeEngine.setTransmitReady(false)
                txMonitorHandler.postDelayed(this, TX_PREKEY_MONITOR_INTERVAL_MS)
                return
            }

            if (audioActive && !txMonitorWasAudioActive) {
                txMonitorWasAudioActive = true
                logTxStarted()
                broadcastTxState(TX_STATE_STARTED)
            } else if (!audioActive && txMonitorWasAudioActive) {
                txMonitorWasAudioActive = false
                if (rigConnected) {
                    requestRigPtt(false, generation)
                }
                broadcastTxState(TX_STATE_QUEUED)
            }

            val nextCheckMs = if (millisecondsUntilAudio.toLong() > PTT_LEAD_TIME_MS + TX_MONITOR_INTERVAL_MS) {
                TX_MONITOR_INTERVAL_MS
            } else {
                TX_PREKEY_MONITOR_INTERVAL_MS
            }
            txMonitorHandler.postDelayed(this, nextCheckMs)
        }
    }

    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private var nextHeartbeatTime = 0L
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            checkHeartbeat()
        }
    }

    private var pskReporterClient: PskReporterClient? = null
    private var pskReporterEnabled = false
    private var currentDialHz: Long = 0
    private var currentCallsign: String = ""
    private var currentGrid: String = ""

    private val preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PREF_HEARTBEAT_INTERVAL) {
            scheduleHeartbeat(true)
        }
        if (key == PREF_PSK_REPORTER || key == "callsign" || key == "grid") {
            updatePskReporterState()
        }
        if (key == "last_frequency") {
            updateDialFromPrefs()
        }
        if (key == PREF_LOG_MESSAGES) {
            updateMessageLogging()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        usbSerialBridge = UsbSerialBridge(applicationContext)
        bluetoothSerialBridge = BluetoothSerialBridge(applicationContext)
        trusdxDirectSerial = TruSdxDirectSerial(applicationContext)
        hamlibRigControl = HamlibRigControl()
        trusdxSerialSession = trusdxDirectSerial?.let { direct ->
            TruSdxSerialSession(
                direct,
                object : TruSdxSerialSession.Listener {
                    override fun onCatMessage(message: String) {
                        if (isTruSdxDiagnosticsEnabled()) {
                            Log.v(TAG, "TruSDX CAT <= $message")
                        }
                    }

                    override fun onAudioFrame(samplesU8: ByteArray) {
                        handleTruSdxAudioFrame(samplesU8)
                    }

                    override fun onParserResync(reason: String) {
                        trusdxParserResyncs += 1
                        if (isTruSdxDiagnosticsEnabled()) {
                            Log.w(TAG, "TruSDX parser resync ($reason), count=$trusdxParserResyncs")
                        }
                    }

                    override fun onIoError(message: String) {
                        Log.w(TAG, "TruSDX I/O error: $message")
                        trusdxConnected = false
                        broadcastError("TruSDX serial link lost")
                    }
                }
            )
        }
        txHandlerThread.start()
        txHandler = Handler(txHandlerThread.looper)
        txMonitorHandler = Handler(Looper.getMainLooper())
        
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
        messageLogger = MessageLogWriter(applicationContext)
        updateMessageLogging()
        scheduleHeartbeat(true)
        initPskReporter()

        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                Log.i(TAG, "Starting engine")
                // Get preferred device ID from intent
                if (intent.hasExtra(EXTRA_AUDIO_DEVICE_ID)) {
                    selectedAudioDeviceId = intent.getIntExtra(EXTRA_AUDIO_DEVICE_ID, -1)
                    Log.i(TAG, "Start requested with device ID: $selectedAudioDeviceId")
                }
                updateDialFromPrefs()
                updatePskReporterState()
                startForegroundService()
                startEngine()
            }
            ACTION_STOP -> {
                Log.i(TAG, "Stopping engine")
                stopEngine()
                stopSelf()
            }
            ACTION_SWITCH_AUDIO_DEVICE -> {
                val deviceId = intent.getIntExtra(EXTRA_AUDIO_DEVICE_ID, -1)
                Log.i(TAG, "Switching audio device to ID: $deviceId")
                switchAudioDevice(deviceId)
            }
            ACTION_SET_FREQUENCY -> {
                val frequencyHz = intent.getLongExtra(EXTRA_FREQUENCY_HZ, 0L)
                Log.i(TAG, "Setting frequency to $frequencyHz Hz")
                if (frequencyHz > 0) {
                    currentDialHz = frequencyHz
                }
                setFrequency(frequencyHz)
            }
            ACTION_SET_TX_OFFSET -> {
                val offsetHz = intent.getFloatExtra(EXTRA_TX_OFFSET_HZ, 1500f)
                Log.i(TAG, "Setting TX offset to $offsetHz Hz")
                currentTxOffsetHz = offsetHz
            }
            ACTION_TRANSMIT_MESSAGE -> {
                val txIntent = Intent(intent)
                txHandler.post { handleTransmitMessage(txIntent) }
            }
            ACTION_TIME_SYNC_ONCE -> {
                Log.i(TAG, "One-shot time sync armed; waiting for next decode")
                timeSyncOncePending = true
            }
            ACTION_SET_TIME_DRIFT -> {
                val driftMs = intent.getLongExtra(EXTRA_TIME_DRIFT_MS, 0L)
                Log.i(TAG, "Setting time drift to $driftMs ms")
                timeSyncOncePending = false
                driftMmaMs = driftMs
                driftMmaN = if (driftMs == 0L) 0 else 1
                applyTimeDrift(driftMs)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Service destroyed")
        
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener)
        heartbeatHandler.removeCallbacksAndMessages(null)
        messageLogger?.shutdown()

        stopEngine()
        txHandlerThread.quitSafely()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "Task removed; stopping engine")
        stopEngine()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun startForegroundService() {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, JS8EngineService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_graphic_eq)
            .setContentIntent(pendingIntent)
            .addAction(
                R.drawable.ic_pause,
                getString(R.string.notification_action_stop),
                stopPendingIntent
            )
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startEngine(resumeGeneration: Int? = null) {
        val generation = if (resumeGeneration == null) {
            if (engineStartInProgress || engine != null) {
                Log.w(TAG, "Ignoring duplicate engine start request")
                return
            }
            if (trusdxStartupWorkerActive) {
                Log.w(TAG, "Ignoring engine start while TruSDX cleanup is still active")
                broadcastError("TruSDX serial initialization is still stopping. Please try again.")
                broadcastEngineState(STATE_STOPPED)
                return
            }

            engineStartInProgress = true
            val nextGeneration = ++engineStartGeneration
            try {
                initializeRigControl(nextGeneration)
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing rig control", e)
                failEngineStart(nextGeneration, "Error initializing rig control: ${e.message}")
                return
            }

            if (rigControlMode == "trusdx_serial") {
                return
            }
            nextGeneration
        } else {
            if (!isCurrentEngineStart(resumeGeneration)) {
                Log.i(TAG, "Ignoring stale engine start continuation generation=$resumeGeneration")
                return
            }
            resumeGeneration
        }

        try {
            // Create callback handler that marshals to main thread
            val callbackHandler = object : JS8Engine.CallbackHandler {
                override fun onDecoded(
                    utc: Int, snr: Int, dt: Float, freq: Float,
                    text: String, type: Int, quality: Float, mode: Int, driftMs: Int
                ) {
                    Log.d(TAG, "Decoded: $text (SNR: $snr dB)")
                    logRxDecode(text, snr, freq, mode)

                    // Broadcast on main thread
                    mainHandler.post {
                        maybeApplyTimeSync(driftMs)
                        updateHeardCallsign(text)
                        broadcastDecode(utc, snr, dt, freq, text, type, quality, mode, driftMs)
                        handleRelayFrame(text, snr, mode, freq, type)
                        maybeHandleIncomingMessage(text, snr, freq, type)
                        maybeHandleAutoReply(text, snr, mode)
                        maybeReportToPskReporter(utc, snr, freq, text)
                    }
                }

                override fun onSpectrum(
                    bins: FloatArray, binHz: Float,
                    powerDb: Float, peakDb: Float
                ) {
                    spectrumEventCount += 1
                    if (rigControlMode == "trusdx_serial" && spectrumEventCount % 20L == 0L) {
                        Log.i(TAG, "Spectrum events: count=$spectrumEventCount bins=${bins.size} binHz=$binHz power=$powerDb peak=$peakDb")
                    }
                    // Broadcast spectrum data (main thread)
                    mainHandler.post {
                        broadcastSpectrum(bins, binHz, powerDb, peakDb)
                    }
                }

                override fun onDecodeStarted(submodes: Int) {
                    Log.d(TAG, "Decode started: submodes=$submodes")
                    mainHandler.post {
                        broadcastDecodeStarted(submodes)
                    }
                }

                override fun onDecodeFinished(count: Int) {
                    Log.d(TAG, "Decode finished: count=$count")
                    mainHandler.post {
                        broadcastDecodeFinished(count)
                    }
                }

                override fun onError(message: String) {
                    Log.e(TAG, "Engine error: $message")
                    mainHandler.post {
                        broadcastError(message)
                    }
                }

                override fun onLog(level: Int, message: String) {
                    val levelStr = when (level) {
                        0 -> "TRACE"
                        1 -> "DEBUG"
                        2 -> "INFO"
                        3 -> "WARN"
                        4 -> "ERROR"
                        else -> "LOG"
                    }
                    Log.d(TAG, "[$levelStr] $message")
                }

                override fun onTxAudio(samples: ShortArray, sampleRateHz: Int) {
                    handleTruSdxTxAudio(samples, sampleRateHz)
                }
            }

            // Create engine
            engine = JS8Engine.create(
                sampleRateHz = 12000,
                submodes = configuredRxSubmodes(),
                callbackHandler = callbackHandler,
                enableTxAudioTap = rigControlMode == "trusdx_serial"
            )

            // Start engine
            if (engine?.start() == true) {
                Log.i(TAG, "Engine started successfully")
                spectrumEventCount = 0

                applyTxBoostSetting()
                applyTimeDriftSetting()

                if (rigControlMode == "trusdx_serial") {
                    Log.i(TAG, "TruSDX mode active: skipping microphone capture")
                    val label = if (selectedAudioDeviceId == TRUSDX_AUDIO_SPEAKER_ID) {
                        "TruSDX Speaker"
                    } else {
                        "TruSDX Serial"
                    }
                    broadcastAudioDevice(label)
                    broadcastEngineState(STATE_RUNNING)
                    broadcastProcessTxQueue()
                    startTruSdxRxWorker()
                    scheduleTruSdxRxKeepAlive()
                } else {
                    // Start audio capture with selected device (if any)
                    scoRestartAttempts = 0
                    scoSourceIndex = 0
                    scoSilenceCheckToken++
                    scoStartToken++
                    if (isScoInputDevice(selectedAudioDeviceId)) {
                        startAudioCaptureWithScoWait(engine!!, selectedAudioDeviceId)
                    } else {
                        startAudioCapture(engine!!, selectedAudioDeviceId)
                    }
                }
                engineStartInProgress = false
            } else {
                Log.e(TAG, "Failed to start engine")
                engine?.close()
                engine = null
                failEngineStart(generation, "Failed to start engine")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error starting engine", e)
            engine?.close()
            engine = null
            failEngineStart(generation, "Error starting engine: ${e.message}")
        }
    }

    private fun isCurrentEngineStart(generation: Int): Boolean {
        return engineStartInProgress && engineStartGeneration == generation
    }

    private fun failEngineStart(generation: Int, message: String) {
        if (!isCurrentEngineStart(generation)) return
        if (rigControlMode == "trusdx_serial" && trusdxSerialSession?.isConnected() == true) {
            trusdxStartupWorkerActive = true
            Thread {
                try {
                    trusdxSerialSession?.stop()
                } finally {
                    trusdxStartupWorkerActive = false
                }
            }.start()
        }
        engineStartInProgress = false
        trusdxInitInProgress = false
        trusdxConnected = false
        rigCtlErrorShown = true
        broadcastError(message)
        broadcastEngineState(STATE_ERROR)
    }

    private fun initializeRigControl(generation: Int) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val rigControlEnabled = prefs.getBoolean("rig_control_enabled", false)
        val rigType = prefs.getString("rig_type", "none")

        if (!rigControlEnabled || rigType == "none") {
            Log.i(TAG, "Rig control not enabled")
            rigControlMode = "none"
            rtsPttConnected = false
            trusdxConnected = false
            rtsPttTransport = null
            return
        }

        rigControlMode = rigType ?: "none"
        rtsPttConnected = false
        trusdxConnected = false
        rtsPttTransport = null
        when (rigType) {
            "network" -> initializeNetworkRigControl()
            "hamlib_usb" -> initializeHamlibUsbControl()
            "rts_ptt" -> initializeRtsPttControl()
            "trusdx_serial" -> initializeTruSdxControl(generation)
            else -> Log.w(TAG, "Unknown rig type: $rigType")
        }
    }

    private fun initializeTruSdxControl(generation: Int) {
        Log.i(TAG, "Initializing TruSDX serial control")
        trusdxInitInProgress = true
        trusdxConnected = false
        trusdxParserResyncs = 0
        trusdxRxFrames = 0
        trusdxRxSamples = 0
        trusdxTxFrames = 0
        trusdxTxSamples = 0
        trusdxTxDrops = 0
        trusdxTxSilentFrames = 0
        trusdxRxUnderruns = 0
        trusdxRxSubmitDrops = 0
        trusdxLastRxAudioNs = 0L
        trusdxLastRxRearmNs = 0L
        trusdxTxIntentActive = false
        trusdxRxRateWindowStartNs = 0L
        trusdxRxRateWindowSamples = 0L
        trusdxRxFrameDrops = 0L
        trusdxRxKeepaliveToken++
        trusdxRxKeepaliveCount = 0L
        clearTruSdxRxFrameQueue()

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val selectedPort = prefs.getString("rig_hamlib_usb_port", "auto")?.trim().orEmpty()
        val selection = resolveSerialSelection(selectedPort, prefs)
        if (selection == null) {
            Log.w(TAG, "Invalid serial port selection: $selectedPort")
            failEngineStart(generation, "Invalid serial port selection for TruSDX control.")
            return
        }

        if (selection.transport != SerialTransport.USB) {
            Log.w(TAG, "TruSDX only supports USB serial transport")
            failEngineStart(generation, "TruSDX mode supports USB serial only.")
            return
        }

        openTruSdxSerialUsb(selection, generation)
    }

    private fun openTruSdxSerialUsb(selection: SerialSelection, generation: Int) {
        var deviceId = selection.usbDeviceId
        var portIndex = selection.portIndex
        if (deviceId == null) {
            val ports = try {
                UsbSerialPortCatalog.listPorts(this)
            } catch (_: Throwable) {
                emptyList()
            }
            val firstPort = ports.firstOrNull()
            if (firstPort != null) {
                deviceId = firstPort.deviceId
                portIndex = firstPort.portIndex
                Log.i(TAG, "Auto-selected USB serial port for TruSDX: ${firstPort.label}")
            }
        }

        val usbDevice = if (deviceId != null) {
            UsbPermissionHelper.findUsbDeviceById(this, deviceId)
        } else {
            null
        }

        if (usbDevice == null) {
            Log.w(TAG, "No USB serial device found for TruSDX")
            failEngineStart(generation, "No USB serial device found for TruSDX control.")
            return
        }

        Log.i(TAG, "TruSDX USB device selected: ${usbDevice.deviceName} (id=${usbDevice.deviceId}) port=$portIndex")

        if (!UsbPermissionHelper.hasPermission(this, usbDevice)) {
            Log.i(TAG, "Requesting USB permission for TruSDX device...")
            UsbPermissionHelper.requestPermission(this, usbDevice) { granted ->
                if (!isCurrentEngineStart(generation)) {
                    Log.i(TAG, "Ignoring stale TruSDX permission result generation=$generation")
                    return@requestPermission
                }
                if (granted) {
                    Log.i(TAG, "USB permission granted for TruSDX device")
                    openTruSdxSerialUsbInternal(usbDevice.deviceId, portIndex, generation)
                } else {
                    Log.w(TAG, "USB permission denied for TruSDX device")
                    failEngineStart(generation, "USB permission denied. Please grant USB access in Settings.")
                }
            }
        } else {
            Log.i(TAG, "USB permission already granted for TruSDX device")
            openTruSdxSerialUsbInternal(usbDevice.deviceId, portIndex, generation)
        }
    }

    private fun openTruSdxSerialUsbInternal(deviceId: Int, portIndex: Int, generation: Int) {
        trusdxStartupWorkerActive = true
        Thread {
            try {
                if (!isCurrentEngineStart(generation)) {
                    trusdxStartupWorkerActive = false
                    return@Thread
                }
                val session = trusdxSerialSession
                val opened = session?.start(deviceId, portIndex) == true
                if (opened) {
                    session?.setSpeakerEnabled(selectedAudioDeviceId == TRUSDX_AUDIO_SPEAKER_ID)
                }
                val initialized = opened && session?.initializeRigState() == true
                if (!isCurrentEngineStart(generation)) {
                    if (opened) session?.stop()
                    trusdxStartupWorkerActive = false
                    return@Thread
                }
                mainHandler.post {
                    if (!isCurrentEngineStart(generation)) {
                        if (opened) session?.stop()
                        trusdxStartupWorkerActive = false
                        return@post
                    }
                    if (initialized) {
                        trusdxConnected = true
                        rigCtlErrorShown = false
                        trusdxInitInProgress = false
                        trusdxStartupWorkerActive = false
                        Log.i(TAG, "TruSDX serial control ready device=$deviceId port=$portIndex")
                        if (currentDialHz > 0L) {
                            Thread {
                                val ok = session?.setFrequency(currentDialHz) == true
                                Log.i(TAG, "TruSDX initial frequency apply: hz=$currentDialHz ok=$ok")
                            }.start()
                        }
                        startEngine(generation)
                    } else {
                        session?.stop()
                        trusdxStartupWorkerActive = false
                        trusdxConnected = false
                        Log.w(TAG, "Failed to initialize TruSDX serial control")
                        failEngineStart(generation, "Failed to initialize TruSDX serial control.")
                    }
                }
            } catch (error: Throwable) {
                Log.e(TAG, "TruSDX serial initialization failed", error)
                trusdxStartupWorkerActive = false
                mainHandler.post {
                    failEngineStart(generation, "Failed to initialize TruSDX serial control: ${error.message}")
                }
            }
        }.start()
    }

    private fun applyTxBoostSetting() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val txBoostEnabled = prefs.getBoolean("tx_boost_enabled", false)
        engine?.setTxBoostEnabled(txBoostEnabled)
        Log.i(TAG, "TX boost: ${if (txBoostEnabled) "enabled (+10 dB)" else "disabled"}")
    }

    private fun configuredRxSubmodes(): Int {
        return RX_SUBMODES_BASE or SUBMODE_TURBO
    }

    private fun initializeNetworkRigControl() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val host = prefs.getString("rigctld_host", "localhost") ?: "localhost"
        val portStr = prefs.getString("rigctld_port", "4532") ?: "4532"
        val port = portStr.toIntOrNull() ?: 4532

        Log.i(TAG, "Initializing network rig control: $host:$port")

        // Connect on background thread to avoid NetworkOnMainThreadException
        Thread {
            try {
                rigCtlClient = RigCtlClient(host, port)
                rigCtlConnected = rigCtlClient?.connect() == true
                rigCtlErrorShown = false

                mainHandler.post {
                    if (rigCtlConnected) {
                        Log.i(TAG, "Connected to rigctld at $host:$port")
                    } else {
                        Log.w(TAG, "Failed to connect to rigctld at $host:$port")
                        broadcastError("Failed to connect to rigctld. Rig control unavailable.")
                        rigCtlErrorShown = true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing network rig control", e)
                mainHandler.post {
                    broadcastError("Error connecting to rigctld: ${e.message}")
                    rigCtlErrorShown = true
                }
            }
        }.start()
    }

    private fun initializeHamlibUsbControl() {
        Log.i(TAG, "Initializing serial rig control (Hamlib)")
        hamlibRigConnected = false

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val rigModelStr = prefs.getString("rig_hamlib_model", "0")?.trim().orEmpty()
        val selectedPort = prefs.getString("rig_hamlib_usb_port", "auto")?.trim().orEmpty()

        val rigModel = rigModelStr.toIntOrNull() ?: 0
        Log.i(TAG, "Hamlib rig model selected: $rigModel")
        if (rigModel <= 0) {
            Log.w(TAG, "Hamlib rig model not selected")
            broadcastError("Select a Hamlib rig model before enabling serial control.")
            rigCtlErrorShown = true
            return
        }

        val selection = resolveSerialSelection(selectedPort, prefs)
        if (selection == null) {
            Log.w(TAG, "Invalid serial port selection: $selectedPort")
            broadcastError("Invalid serial port selection for Hamlib control.")
            rigCtlErrorShown = true
            return
        }

        when (selection.transport) {
            SerialTransport.USB -> openHamlibSerialUsb(rigModel, selection)
            SerialTransport.BLUETOOTH -> openHamlibSerialBluetooth(rigModel, selection)
        }
    }

    private fun initializeRtsPttControl() {
        Log.i(TAG, "Initializing RTS PTT control")
        rtsPttConnected = false
        rtsPttTransport = null

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val selectedPort = prefs.getString("rig_hamlib_usb_port", "auto")?.trim().orEmpty()

        val selection = resolveSerialSelection(selectedPort, prefs)
        if (selection == null) {
            Log.w(TAG, "Invalid serial port selection: $selectedPort")
            broadcastError("Invalid serial port selection for RTS PTT control.")
            rigCtlErrorShown = true
            return
        }

        when (selection.transport) {
            SerialTransport.USB -> openRtsSerialUsb(selection)
            SerialTransport.BLUETOOTH -> openRtsSerialBluetooth(selection)
        }
    }

    private fun openRtsSerialUsb(selection: SerialSelection) {
        val params = readSerialParams()

        usbSerialBridge?.registerNative()

        var deviceId = selection.usbDeviceId
        var portIndex = selection.portIndex
        if (deviceId == null) {
            val ports = try {
                UsbSerialPortCatalog.listPorts(this)
            } catch (_: Throwable) {
                emptyList()
            }
            val firstPort = ports.firstOrNull()
            if (firstPort != null) {
                deviceId = firstPort.deviceId
                portIndex = firstPort.portIndex
                Log.i(TAG, "Auto-selected USB serial port for RTS PTT: ${firstPort.label}")
            }
        }

        val usbDevice = if (deviceId != null) {
            UsbPermissionHelper.findUsbDeviceById(this, deviceId)
        } else {
            null
        }

        if (usbDevice == null) {
            if (selection.path == "auto") {
                val btPorts = try {
                    BluetoothSerialPortCatalog.listPorts(this)
                } catch (_: Throwable) {
                    emptyList()
                }
                val btPort = btPorts.firstOrNull()
                if (btPort != null) {
                    val btSelection = SerialSelection(
                        transport = SerialTransport.BLUETOOTH,
                        path = "android-bt:${btPort.address}:${btPort.portIndex}",
                        usbDeviceId = null,
                        btAddress = btPort.address,
                        portIndex = btPort.portIndex
                    )
                    Log.i(TAG, "Auto-selected Bluetooth serial port for RTS PTT: ${btPort.label}")
                    openRtsSerialBluetooth(btSelection)
                    return
                }
            }
            Log.w(TAG, "No USB serial device found for RTS PTT")
            broadcastError("No USB serial device found for RTS PTT control.")
            rigCtlErrorShown = true
            return
        }

        Log.i(TAG, "RTS PTT USB device selected: ${usbDevice.deviceName} (id=${usbDevice.deviceId}) port=$portIndex")

        if (!UsbPermissionHelper.hasPermission(this, usbDevice)) {
            Log.i(TAG, "Requesting USB permission for RTS PTT device...")
            UsbPermissionHelper.requestPermission(this, usbDevice) { granted ->
                if (granted) {
                    Log.i(TAG, "USB permission granted for RTS PTT device")
                    openRtsSerialUsbInternal(usbDevice.deviceId, portIndex, params)
                } else {
                    Log.w(TAG, "USB permission denied for RTS PTT device")
                    broadcastError("USB permission denied. Please grant USB access in Settings.")
                    rigCtlErrorShown = true
                }
            }
        } else {
            Log.i(TAG, "USB permission already granted for RTS PTT device")
            openRtsSerialUsbInternal(usbDevice.deviceId, portIndex, params)
        }
    }

    private fun openRtsSerialUsbInternal(deviceId: Int, portIndex: Int, params: SerialParams) {
        Thread {
            val ok = usbSerialBridge?.open(
                deviceId,
                portIndex,
                params.baudRate,
                params.dataBits,
                params.stopBits,
                params.parityValue
            ) == true

            mainHandler.post {
                rtsPttConnected = ok
                rigCtlErrorShown = false
                if (ok) {
                    rtsPttTransport = SerialTransport.USB
                    usbSerialBridge?.setRts(false)
                    Log.i(TAG, "RTS PTT USB serial opened device=$deviceId port=$portIndex")
                } else {
                    Log.w(TAG, "RTS PTT USB serial open failed for device=$deviceId port=$portIndex")
                    broadcastError("Failed to open USB serial port for RTS PTT control.")
                    rigCtlErrorShown = true
                }
            }
        }.start()
    }

    private fun openRtsSerialBluetooth(selection: SerialSelection) {
        val params = readSerialParams()

        bluetoothSerialBridge?.registerNative()

        val address = selection.btAddress
        if (address == null) {
            Log.w(TAG, "Bluetooth address missing for RTS PTT")
            broadcastError("No Bluetooth serial device selected for RTS PTT control.")
            rigCtlErrorShown = true
            return
        }

        if (BluetoothSerialPortCatalog.findBondedDevice(this, address) == null) {
            Log.w(TAG, "Bluetooth device not paired or unavailable: $address")
            broadcastError("Bluetooth serial device not available. Check pairing and settings.")
            rigCtlErrorShown = true
            return
        }

        Thread {
            val ok = bluetoothSerialBridge?.open(
                address,
                selection.portIndex,
                params.baudRate,
                params.dataBits,
                params.stopBits,
                params.parityValue
            ) == true

            mainHandler.post {
                rtsPttConnected = ok
                rigCtlErrorShown = false
                if (ok) {
                    rtsPttTransport = SerialTransport.BLUETOOTH
                    bluetoothSerialBridge?.setRts(false)
                    Log.i(TAG, "RTS PTT Bluetooth serial opened addr=$address port=${selection.portIndex}")
                } else {
                    val detail = bluetoothSerialBridge?.getLastError().orEmpty()
                    if (detail.isNotBlank()) {
                        Log.w(TAG, "RTS PTT Bluetooth open failed for addr=$address port=${selection.portIndex}: $detail")
                        broadcastError("Failed to open Bluetooth serial port for RTS PTT control: $detail")
                    } else {
                        Log.w(TAG, "RTS PTT Bluetooth open failed for addr=$address port=${selection.portIndex}")
                        broadcastError("Failed to open Bluetooth serial port for RTS PTT control.")
                    }
                    rigCtlErrorShown = true
                }
            }
        }.start()
    }

    private fun openHamlibSerialUsb(rigModel: Int, selection: SerialSelection) {
        val params = readSerialParams()

        usbSerialBridge?.registerNative()

        var deviceId = selection.usbDeviceId
        var portIndex = selection.portIndex
        if (deviceId == null) {
            val ports = try {
                UsbSerialPortCatalog.listPorts(this)
            } catch (_: Throwable) {
                emptyList()
            }
            val firstPort = ports.firstOrNull()
            if (firstPort != null) {
                deviceId = firstPort.deviceId
                portIndex = firstPort.portIndex
                Log.i(TAG, "Auto-selected USB serial port: ${firstPort.label}")
            }
        }

        val usbDevice = if (deviceId != null) {
            UsbPermissionHelper.findUsbDeviceById(this, deviceId)
        } else {
            null
        }

        if (usbDevice == null) {
            if (selection.path == "auto") {
                val btPorts = try {
                    BluetoothSerialPortCatalog.listPorts(this)
                } catch (_: Throwable) {
                    emptyList()
                }
                val btPort = btPorts.firstOrNull()
                if (btPort != null) {
                    val btSelection = SerialSelection(
                        transport = SerialTransport.BLUETOOTH,
                        path = "android-bt:${btPort.address}:${btPort.portIndex}",
                        usbDeviceId = null,
                        btAddress = btPort.address,
                        portIndex = btPort.portIndex
                    )
                    Log.i(TAG, "Auto-selected Bluetooth serial port: ${btPort.label}")
                    openHamlibSerialBluetooth(rigModel, btSelection)
                    return
                }
            }
            Log.w(TAG, "No USB serial device found for Hamlib")
            broadcastError("No USB serial device found for Hamlib control.")
            rigCtlErrorShown = true
            return
        }

        Log.i(TAG, "Hamlib USB device selected: ${usbDevice.deviceName} (id=${usbDevice.deviceId}) port=$portIndex")

        if (!UsbPermissionHelper.hasPermission(this, usbDevice)) {
            Log.i(TAG, "Requesting USB permission for Hamlib device...")
            UsbPermissionHelper.requestPermission(this, usbDevice) { granted ->
                if (granted) {
                    Log.i(TAG, "USB permission granted for Hamlib device")
                    openHamlibSerialUsbInternal(rigModel, usbDevice.deviceId, portIndex, params)
                } else {
                    Log.w(TAG, "USB permission denied for Hamlib device")
                    broadcastError("USB permission denied. Please grant USB access in Settings.")
                    rigCtlErrorShown = true
                }
            }
        } else {
            Log.i(TAG, "USB permission already granted for Hamlib device")
            openHamlibSerialUsbInternal(rigModel, usbDevice.deviceId, portIndex, params)
        }
    }

    private fun openHamlibSerialUsbInternal(
        rigModel: Int,
        deviceId: Int,
        portIndex: Int,
        params: SerialParams
    ) {
        Thread {
            val preopenOk = usbSerialBridge?.open(
                deviceId,
                portIndex,
                params.baudRate,
                params.dataBits,
                params.stopBits,
                params.parityValue
            ) == true

            if (!preopenOk) {
                mainHandler.post {
                    hamlibRigConnected = false
                    rigCtlErrorShown = true
                    Log.w(TAG, "Hamlib USB pre-open failed for device=$deviceId port=$portIndex")
                    broadcastError("Failed to open USB serial port for Hamlib control.")
                }
                return@Thread
            }

            val resolvedPortIndex = usbSerialBridge?.getActivePortIndex() ?: portIndex
            val serialPath = "android-usb:$deviceId:$resolvedPortIndex"

            val ok = hamlibRigControl?.openSerialPath(
                rigModel,
                serialPath,
                params.baudRate,
                params.dataBits,
                params.stopBits,
                params.parity
            ) == true

            mainHandler.post {
                hamlibRigConnected = ok
                rigCtlErrorShown = false
                if (ok) {
                    Log.i(TAG, "Hamlib rig opened (model=$rigModel path=$serialPath)")
                } else {
                    val detail = hamlibRigControl?.getLastError().orEmpty()
                    if (detail.isNotBlank()) {
                        Log.w(TAG, "Hamlib rig open failed: $detail")
                        broadcastError("Failed to open Hamlib rig: $detail")
                    } else {
                        Log.w(TAG, "Hamlib rig open failed")
                        broadcastError("Failed to open Hamlib rig. Check serial connection and settings.")
                    }
                    rigCtlErrorShown = true
                }
            }
        }.start()
    }

    private fun openHamlibSerialBluetooth(rigModel: Int, selection: SerialSelection) {
        val params = readSerialParams()

        bluetoothSerialBridge?.registerNative()

        val address = selection.btAddress
        if (address == null) {
            Log.w(TAG, "Bluetooth address missing for Hamlib")
            broadcastError("No Bluetooth serial device selected for Hamlib control.")
            rigCtlErrorShown = true
            return
        }

        if (BluetoothSerialPortCatalog.findBondedDevice(this, address) == null) {
            Log.w(TAG, "Bluetooth device not paired or unavailable: $address")
            broadcastError("Bluetooth serial device not available. Check pairing and settings.")
            rigCtlErrorShown = true
            return
        }

        Thread {
            val preopenOk = bluetoothSerialBridge?.open(
                address,
                selection.portIndex,
                params.baudRate,
                params.dataBits,
                params.stopBits,
                params.parityValue
            ) == true

            if (!preopenOk) {
                val detail = bluetoothSerialBridge?.getLastError().orEmpty()
                mainHandler.post {
                    hamlibRigConnected = false
                    rigCtlErrorShown = true
                    if (detail.isNotBlank()) {
                        Log.w(TAG, "Hamlib Bluetooth pre-open failed for addr=$address port=${selection.portIndex}: $detail")
                        broadcastError("Failed to open Bluetooth serial port for Hamlib control: $detail")
                    } else {
                        Log.w(TAG, "Hamlib Bluetooth pre-open failed for addr=$address port=${selection.portIndex}")
                        broadcastError("Failed to open Bluetooth serial port for Hamlib control.")
                    }
                }
                return@Thread
            }

            Log.i(TAG, "Hamlib Bluetooth pre-open ok for addr=$address port=${selection.portIndex}")
            val serialPath = selection.path
            Log.i(TAG, "Hamlib opening Bluetooth rig via path=$serialPath")
            val ok = hamlibRigControl?.openSerialPath(
                rigModel,
                serialPath,
                params.baudRate,
                params.dataBits,
                params.stopBits,
                params.parity
            ) == true

            mainHandler.post {
                hamlibRigConnected = ok
                rigCtlErrorShown = false
                if (ok) {
                    Log.i(TAG, "Hamlib rig opened (model=$rigModel path=$serialPath)")
                } else {
                    val detail = hamlibRigControl?.getLastError().orEmpty()
                    if (detail.isNotBlank()) {
                        Log.w(TAG, "Hamlib rig open failed: $detail")
                        broadcastError("Failed to open Hamlib rig: $detail")
                    } else {
                        Log.w(TAG, "Hamlib rig open failed")
                        broadcastError("Failed to open Hamlib rig. Check serial connection and settings.")
                    }
                    rigCtlErrorShown = true
                }
            }
        }.start()
    }

    private data class SerialParams(
        val baudRate: Int,
        val dataBits: Int,
        val stopBits: Int,
        val parity: String,
        val parityValue: Int
    )

    private fun readSerialParams(): SerialParams {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val baudRate = prefs.getString("rig_serial_baud", "9600")?.toIntOrNull() ?: 9600
        val dataBits = prefs.getString("rig_serial_data_bits", "8")?.toIntOrNull() ?: 8
        val stopBits = prefs.getString("rig_serial_stop_bits", "1")?.toIntOrNull() ?: 1
        val parity = prefs.getString("rig_serial_parity", "none") ?: "none"
        val parityValue = when (parity.lowercase(Locale.US)) {
            "odd" -> 1
            "even" -> 2
            "mark" -> 3
            "space" -> 4
            else -> 0
        }
        return SerialParams(baudRate, dataBits, stopBits, parity, parityValue)
    }

    private fun resolveSerialSelection(
        selection: String,
        prefs: android.content.SharedPreferences
    ): SerialSelection? {
        val trimmed = selection.trim()
        if (trimmed.startsWith("android-bt:")) {
            val remainder = trimmed.removePrefix("android-bt:")
            val parts = remainder.split(':', limit = 2)
            val address = BluetoothSerialPortCatalog.normalizeAddress(parts.firstOrNull().orEmpty())
                ?: return null
            val portIndex = parts.getOrNull(1)?.toIntOrNull() ?: 0
            return SerialSelection(
                transport = SerialTransport.BLUETOOTH,
                path = "android-bt:$address:$portIndex",
                usbDeviceId = null,
                btAddress = address,
                portIndex = portIndex
            )
        }

        if (trimmed.startsWith("android-usb:")) {
            val remainder = trimmed.removePrefix("android-usb:")
            val parts = remainder.split(':', limit = 2)
            val deviceId = parts.firstOrNull()?.toIntOrNull() ?: return null
            val portIndex = parts.getOrNull(1)?.toIntOrNull() ?: 0
            return SerialSelection(
                transport = SerialTransport.USB,
                path = "android-usb:$deviceId:$portIndex",
                usbDeviceId = deviceId,
                btAddress = null,
                portIndex = portIndex
            )
        }

        if (trimmed.isNotEmpty() && trimmed != "auto") {
            val parts = trimmed.split(':', limit = 2)
            val deviceId = parts.firstOrNull()?.toIntOrNull() ?: return null
            val portIndex = parts.getOrNull(1)?.toIntOrNull() ?: 0
            return SerialSelection(
                transport = SerialTransport.USB,
                path = "android-usb:$deviceId:$portIndex",
                usbDeviceId = deviceId,
                btAddress = null,
                portIndex = portIndex
            )
        }

        val deviceId = prefs.getString("rig_usb_device_id", "")?.toIntOrNull()
        val portIndex = prefs.getString("rig_usb_port_index", "0")?.toIntOrNull() ?: 0
        return if (deviceId != null) {
            SerialSelection(
                transport = SerialTransport.USB,
                path = "android-usb:$deviceId:$portIndex",
                usbDeviceId = deviceId,
                btAddress = null,
                portIndex = portIndex
            )
        } else {
            SerialSelection(
                transport = SerialTransport.USB,
                path = "auto",
                usbDeviceId = null,
                btAddress = null,
                portIndex = 0
            )
        }
    }

    private enum class SerialTransport {
        USB,
        BLUETOOTH
    }

    private data class SerialSelection(
        val transport: SerialTransport,
        val path: String,
        val usbDeviceId: Int?,
        val btAddress: String?,
        val portIndex: Int
    )

    private fun stopEngine() {
        try {
            engineStartGeneration++
            engineStartInProgress = false
            trusdxInitInProgress = false
            synchronized(pttStateLock) {
                txPttGeneration++
                rigPttDesired = rigPttAsserted
                rigPttDesiredGeneration = txPttGeneration
                rigPttFailureCount = 0
                rigPttCompletion = null
                pttShuttingDown = true
                txPttFailed = false
            }
            engine?.setTransmitReady(false)
            scoSilenceCheckToken++
            scoStartToken++
            audioHelper?.stopCapture()
            audioHelper?.close()
            audioHelper = null
            stopTxMonitor()
            disableScoRouting()

            txHandler.removeCallbacksAndMessages(null)
            synchronized(pttStateLock) {
                rigPttDesired = false
                rigPttDesiredGeneration = txPttGeneration
                rigPttCommandPending = false
                rigPttCompletion = null
            }
            if (isRigControlConnected()) {
                if (!releaseRigPttForShutdown()) {
                    Log.e(TAG, "Unable to confirm PTT release during shutdown")
                }
            }

            // Disconnect rig control on background thread
            val networkClientToDisconnect = rigCtlClient
            rigCtlClient = null
            rigCtlConnected = false
            rigCtlErrorShown = false
            hamlibRigConnected = false
            rtsPttConnected = false
            trusdxConnected = false
            trusdxWatchdogToken++
            trusdxTxIntentActive = false
            stopTruSdxRxWorker()
            clearTruSdxRxFrameQueue()
            trusdxRxKeepaliveToken++
            trusdxRxKeepaliveCount = 0L
            rtsPttTransport = null
            rigControlMode = "none"
            hamlibRigControl?.close()
            trusdxSerialSession?.stop()
            if (isTruSdxDiagnosticsEnabled() &&
                (trusdxRxFrames > 0 || trusdxTxFrames > 0 || trusdxParserResyncs > 0 || trusdxTxDrops > 0 || trusdxRxUnderruns > 0 || trusdxRxFrameDrops > 0)
            ) {
                Log.i(
                    TAG,
                    "TruSDX diagnostics: rxFrames=$trusdxRxFrames rxSamples=$trusdxRxSamples rxFrameDrops=$trusdxRxFrameDrops rxSubmitDrops=$trusdxRxSubmitDrops rxUnderruns=$trusdxRxUnderruns txFrames=$trusdxTxFrames txSamples=$trusdxTxSamples txSilent=$trusdxTxSilentFrames txDrops=$trusdxTxDrops parserResyncs=$trusdxParserResyncs"
                )
            }
            usbSerialBridge?.unregisterNative()
            usbSerialBridge?.close()
            bluetoothSerialBridge?.close()
            bluetoothSerialBridge?.unregisterNative()

            if (networkClientToDisconnect != null) {
                Thread {
                    networkClientToDisconnect.disconnect()
                }.start()
            }

            pskReporterClient?.stop(flush = true)
            pskReporterEnabled = false

            engine?.stop()
            engine?.close()
            engine = null

            broadcastEngineState(STATE_STOPPED)
            Log.i(TAG, "Engine stopped")

        } catch (e: Exception) {
            Log.e(TAG, "Error stopping engine", e)
        }
    }

    private fun broadcastEngineState(state: String) {
        val intent = Intent(ACTION_ENGINE_STATE).apply {
            putExtra(EXTRA_STATE, state)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

        // The rig indicator on the Monitor strip needs the link state, and the
        // connected flags are set in too many places to broadcast from each one.
        // Poll while the engine runs instead, and report only on a change.
        if (state == STATE_RUNNING || state == STATE_STARTING) {
            startRigStatusPolling()
        } else {
            stopRigStatusPolling()
        }
    }

    private val rigStatusHandler = Handler(Looper.getMainLooper())
    private var rigStatusPolling = false
    private var lastRigConnected: Boolean? = null
    private val rigStatusRunnable = object : Runnable {
        override fun run() {
            if (!rigStatusPolling) return
            broadcastRigStatus(isRigControlConnected())
            rigStatusHandler.postDelayed(this, RIG_STATUS_POLL_INTERVAL_MS)
        }
    }

    private fun startRigStatusPolling() {
        if (rigStatusPolling) return
        rigStatusPolling = true
        rigStatusHandler.post(rigStatusRunnable)
    }

    private fun stopRigStatusPolling() {
        rigStatusPolling = false
        rigStatusHandler.removeCallbacks(rigStatusRunnable)
        broadcastRigStatus(false)
    }

    private fun broadcastRigStatus(connected: Boolean) {
        if (lastRigConnected == connected) return
        lastRigConnected = connected
        val intent = Intent(ACTION_RIG_STATUS).apply {
            putExtra(EXTRA_RIG_CONNECTED, connected)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastDecode(
        utc: Int, snr: Int, dt: Float, freq: Float,
        text: String, type: Int, quality: Float, mode: Int, driftMs: Int
    ) {
        val intent = Intent(ACTION_DECODE).apply {
            putExtra(EXTRA_UTC, utc)
            putExtra(EXTRA_SNR, snr)
            putExtra(EXTRA_DT, dt)
            putExtra(EXTRA_FREQ, freq)
            putExtra(EXTRA_TEXT, text)
            putExtra(EXTRA_TYPE, type)
            putExtra(EXTRA_QUALITY, quality)
            putExtra(EXTRA_MODE, mode)
            putExtra(EXTRA_DRIFT_MS, driftMs)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private var timeSyncOncePending = false
    private var driftMmaMs = 0L
    private var driftMmaN = 0

    private fun maybeApplyTimeSync(suggestedDriftMs: Int) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val autoSync = prefs.getBoolean(PREF_TIME_SYNC_AUTO, false)
        if (!autoSync && !timeSyncOncePending) return

        if (timeSyncOncePending) {
            timeSyncOncePending = false
            driftMmaMs = suggestedDriftMs.toLong()
            driftMmaN = 1
            applyTimeDrift(suggestedDriftMs.toLong())
            return
        }

        if (driftMmaN == 0) {
            driftMmaN = 1
            driftMmaMs = engine?.timeDriftMs() ?: 0L
        }
        driftMmaMs = (((driftMmaN - 1) * driftMmaMs) + suggestedDriftMs) / driftMmaN
        if (driftMmaN < 60) driftMmaN++
        applyTimeDrift(driftMmaMs)
    }

    private fun applyTimeDrift(driftMs: Long) {
        engine?.setTimeDriftMs(driftMs)
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit()
            .putLong(PREF_TIME_DRIFT_MS, driftMs)
            .apply()
        Log.i(TAG, "Time drift applied: $driftMs ms")
        broadcastTimeDrift(driftMs)
        // TX slots are computed against the drifted clock now; reschedule.
        scheduleHeartbeat(false)
    }

    private fun applyTimeDriftSetting() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val driftMs = prefs.getLong(PREF_TIME_DRIFT_MS, 0L)
        if (driftMs != 0L) {
            engine?.setTimeDriftMs(driftMs)
            Log.i(TAG, "Time drift restored: $driftMs ms")
        }
        broadcastTimeDrift(driftMs)
    }

    private fun broadcastTimeDrift(driftMs: Long) {
        val intent = Intent(ACTION_TIME_DRIFT).apply {
            putExtra(EXTRA_TIME_DRIFT_MS, driftMs)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastSpectrum(
        bins: FloatArray, binHz: Float,
        powerDb: Float, peakDb: Float
    ) {
        val intent = Intent(ACTION_SPECTRUM).apply {
            putExtra(EXTRA_BINS, bins)
            putExtra(EXTRA_BIN_HZ, binHz)
            putExtra(EXTRA_POWER_DB, powerDb)
            putExtra(EXTRA_PEAK_DB, peakDb)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastDecodeStarted(submodes: Int) {
        val intent = Intent(ACTION_DECODE_STARTED).apply {
            putExtra(EXTRA_SUBMODES, submodes)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastDecodeFinished(count: Int) {
        val intent = Intent(ACTION_DECODE_FINISHED).apply {
            putExtra(EXTRA_COUNT, count)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastError(message: String) {
        val intent = Intent(ACTION_ERROR).apply {
            putExtra(EXTRA_ERROR_MESSAGE, message)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastAudioDevice(deviceName: String) {
        val intent = Intent(ACTION_AUDIO_DEVICE).apply {
            putExtra(EXTRA_AUDIO_DEVICE, deviceName)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastRadioFrequency(frequencyHz: Long) {
        val intent = Intent(ACTION_RADIO_FREQUENCY).apply {
            putExtra(EXTRA_RADIO_FREQUENCY_HZ, frequencyHz)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun getActiveAudioDevice(): String {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        // For Android M (API 23) and above, use AudioDeviceInfo
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)

            // If a specific device is selected, find it
            if (selectedAudioDeviceId != -1) {
                for (device in devices) {
                    if (device.id == selectedAudioDeviceId) {
                        val deviceType = getDeviceName(device)
                        Log.i(TAG, "Using selected audio device: $deviceType (ID: ${device.id})")
                        return deviceType
                    }
                }
            }

            // Find the first active input device
            for (device in devices) {
                val deviceType = getDeviceName(device)
                // Return first valid device (Oboe typically uses default)
                Log.i(TAG, "Detected audio device: $deviceType")
                return deviceType
            }
        }

        // Fallback for older Android versions or if no device found
        return "Microphone"
    }

    private fun updateOutputDeviceForInput(inputDeviceId: Int) {
        val outputId = findOutputDeviceId(inputDeviceId)
        selectedOutputDeviceId = outputId
        engine?.setOutputDevice(outputId)
        if (outputId > 0) {
            Log.i(TAG, "Using output device: ${getOutputDeviceName(outputId)} (ID: $outputId)")
        } else {
            Log.i(TAG, "Using default output device")
        }
    }

    private fun findOutputDeviceId(inputDeviceId: Int): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return -1
        if (inputDeviceId == -1) return -1

        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val inputDevice = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.id == inputDeviceId } ?: return -1

        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val inputName = inputDevice.productName?.toString()?.takeIf { it.isNotBlank() }
        val inputFamily = deviceFamily(inputDevice.type)

        var output = outputs.firstOrNull { device ->
            device.type == inputDevice.type &&
                (inputName == null || device.productName?.toString() == inputName)
        }

        if (output == null && inputName != null) {
            output = outputs.firstOrNull { device ->
                device.productName?.toString() == inputName &&
                    (inputFamily.isEmpty() || deviceFamily(device.type) == inputFamily)
            }
        }

        if (output == null && inputName != null) {
            output = outputs.firstOrNull { device -> device.productName?.toString() == inputName }
        }

        if (output == null && inputFamily.isNotEmpty()) {
            output = outputs.firstOrNull { device -> deviceFamily(device.type) == inputFamily }
        }

        return output?.id ?: -1
    }

    private fun deviceFamily(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_USB_HEADSET -> "usb"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bt_sco"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bt_a2dp"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "builtin"
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> "line"
            else -> ""
        }
    }

    private fun getOutputDeviceName(deviceId: Int): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return "Default Output"
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val device = outputs.firstOrNull { it.id == deviceId } ?: return "Default Output"
        return getDeviceName(device)
    }

    private fun getDeviceName(device: AudioDeviceInfo): String {
        return when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Internal Microphone"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
            AudioDeviceInfo.TYPE_USB_DEVICE -> {
                // Try to get product name for USB devices
                device.productName?.toString() ?: "USB Audio Device"
            }
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB Audio Accessory"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth Headset"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth Audio"
            AudioDeviceInfo.TYPE_LINE_ANALOG -> "Line Input"
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Digital Line Input"
            else -> "Unknown Device"
        }
    }

    private fun switchAudioDevice(deviceId: Int) {
        try {
            if (engine != null && deviceId == selectedAudioDeviceId) {
                Log.i(TAG, "Audio device already selected (ID: $deviceId); ignoring switch request")
                return
            }
            // Store the selected device ID
            selectedAudioDeviceId = deviceId

            if (rigControlMode == "trusdx_serial") {
                val speakerEnabled = deviceId == TRUSDX_AUDIO_SPEAKER_ID
                val ok = trusdxSerialSession?.setSpeakerEnabled(speakerEnabled) == true
                if (!ok) {
                    broadcastError("Failed to update TruSDX speaker mode")
                }
                val label = if (speakerEnabled) "TruSDX Speaker" else "TruSDX Serial"
                broadcastAudioDevice(label)
                return
            }

            scoRestartAttempts = 0
            scoSourceIndex = 0
            scoSilenceCheckToken++
            scoStartToken++

            if (engine != null) {
                audioHelper?.stopCapture()
                audioHelper?.close()
                audioHelper = null
                if (isScoInputDevice(deviceId)) {
                    startAudioCaptureWithScoWait(engine!!, deviceId)
                } else {
                    startAudioCapture(engine!!, deviceId)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error switching audio device", e)
            broadcastError("Error switching audio device: ${e.message}")
        }
    }

    private fun scheduleScoSilenceCheck() {
        if (!isScoInputDevice(selectedAudioDeviceId)) return
        val token = ++scoSilenceCheckToken
        scheduleScoSilenceCheckInternal(token)
    }

    private fun scheduleScoSilenceCheckInternal(token: Int) {
        mainHandler.postDelayed({
            if (token != scoSilenceCheckToken) return@postDelayed
            val helper = audioHelper ?: return@postDelayed
            val maxAbs = helper.getLastAbsMax()
            if (maxAbs < SCO_SILENCE_THRESHOLD && scoRestartAttempts < SCO_MAX_RESTARTS) {
                scoRestartAttempts++
                scoSourceIndex = (scoSourceIndex + 1) % scoSourceCandidates.size
                Log.w(TAG, "SCO input appears silent (max=$maxAbs); restarting capture (attempt $scoRestartAttempts/$SCO_MAX_RESTARTS)")
                restartAudioCaptureForSco()
                return@postDelayed
            }
            scheduleScoSilenceCheckInternal(token)
        }, SCO_SILENCE_CHECK_DELAY_MS)
    }

    private fun restartAudioCaptureForSco() {
        val deviceId = selectedAudioDeviceId
        val activeEngine = engine ?: return
        scoStartToken++
        audioHelper?.stopCapture()
        audioHelper?.close()
        audioHelper = null
        startAudioCaptureWithScoWait(activeEngine, deviceId)
    }

    private fun startAudioCapture(activeEngine: JS8Engine, deviceId: Int) {
        applyInputRouting(deviceId)
        startAudioCaptureInternal(activeEngine, deviceId)
    }

    private fun startAudioCaptureWithScoWait(activeEngine: JS8Engine, deviceId: Int) {
        val token = scoStartToken
        applyInputRouting(deviceId)
        waitForScoActive(activeEngine, deviceId, token, 0)
    }

    private fun waitForScoActive(
        activeEngine: JS8Engine,
        deviceId: Int,
        token: Int,
        attempt: Int
    ) {
        if (token != scoStartToken) return
        val scoActive = isScoActive(deviceId)
        if (scoActive || attempt >= SCO_START_MAX_ATTEMPTS) {
            if (scoActive) {
                Log.i(TAG, "SCO audio active; starting capture")
            } else {
                Log.w(TAG, "SCO audio not active after $attempt checks; starting capture anyway")
            }
            startAudioCaptureInternal(activeEngine, deviceId)
            return
        }

        mainHandler.postDelayed({
            waitForScoActive(activeEngine, deviceId, token, attempt + 1)
        }, SCO_START_WAIT_INTERVAL_MS)
    }

    private fun startAudioCaptureInternal(activeEngine: JS8Engine, deviceId: Int) {
        audioHelper = buildAudioHelper(activeEngine, deviceId)
        if (audioHelper?.startCapture() == true) {
            Log.i(TAG, "Audio capture started with device ID: $deviceId")
            val deviceName = getActiveAudioDevice()
            broadcastAudioDevice(deviceName)
            updateOutputDeviceForInput(deviceId)
            broadcastEngineState(STATE_RUNNING)
            broadcastProcessTxQueue()
            scheduleScoSilenceCheck()
        } else {
            Log.e(TAG, "Failed to start audio capture")
            broadcastError("Failed to start audio capture")
        }
    }

    private fun handleTruSdxAudioFrame(samplesU8: ByteArray) {
        if (rigControlMode != "trusdx_serial") return
        if (samplesU8.isEmpty()) return

        trusdxRxFrames += 1
        trusdxRxSamples += samplesU8.size.toLong()
        val now = System.nanoTime()
        trusdxLastRxAudioNs = now
        trusdxRxRateWindowSamples += samplesU8.size.toLong()
        enqueueTruSdxRxFrame(samplesU8)

        if (trusdxRxFrames <= 10) {
            val queued = trusdxRxFrameQueue.size
            Log.i(TAG, "TruSDX RX frame #$trusdxRxFrames size=${samplesU8.size} queued=$queued")
        }
        if (trusdxRxRateWindowStartNs == 0L) {
            trusdxRxRateWindowStartNs = now
        } else {
            val windowNs = now - trusdxRxRateWindowStartNs
            if (windowNs >= 1_000_000_000L) {
                val observedRate = ((trusdxRxRateWindowSamples * 1_000_000_000L) / windowNs).toInt()
                if (isTruSdxDiagnosticsEnabled()) {
                    Log.i(TAG, "TruSDX RX stream: frames=$trusdxRxFrames samples=$trusdxRxSamples chunk=${samplesU8.size} rate=${observedRate}B/s")
                }
                trusdxRxRateWindowStartNs = now
                trusdxRxRateWindowSamples = 0L
            }
        }
    }

    private fun scheduleTruSdxRxKeepAlive() {
        val token = ++trusdxRxKeepaliveToken
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                if (token != trusdxRxKeepaliveToken) return
                if (rigControlMode != "trusdx_serial") return
                if (!trusdxConnected) {
                    mainHandler.postDelayed(this, TRUSDX_RX_KEEPALIVE_INTERVAL_MS)
                    return
                }
                if (!trusdxTxIntentActive && !isTransmitActive()) {
                    val ok = trusdxSerialSession?.sendRxKeepAlive() == true
                    trusdxRxKeepaliveCount += 1
                    if (!ok) {
                        Log.w(TAG, "TruSDX frequency poll failed")
                    } else if (isTruSdxDiagnosticsEnabled() && trusdxRxKeepaliveCount % 10L == 0L) {
                        Log.i(TAG, "TruSDX frequency poll count=$trusdxRxKeepaliveCount")
                    }
                }
                mainHandler.postDelayed(this, TRUSDX_RX_KEEPALIVE_INTERVAL_MS)
            }
        }, TRUSDX_RX_KEEPALIVE_INTERVAL_MS)
    }

    private fun enqueueTruSdxRxFrame(frame: ByteArray) {
        if (!trusdxRxFrameQueue.offerLast(frame)) {
            trusdxRxFrameQueue.pollFirst()
            trusdxRxFrameDrops += 1
            trusdxRxFrameQueue.offerLast(frame)
        }
    }

    private fun clearTruSdxRxFrameQueue() {
        trusdxRxFrameQueue.clear()
    }

    private fun startTruSdxRxWorker() {
        if (trusdxRxWorkerRunning) return
        trusdxRxWorkerRunning = true
        trusdxRxWorkerThread = Thread({
            while (trusdxRxWorkerRunning) {
                val frame = try {
                    trusdxRxFrameQueue.pollFirst(100L, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    null
                } ?: continue

                val activeEngine = engine ?: continue
                val pcm = ShortArray(frame.size)
                var i = 0
                while (i < frame.size) {
                    // TruSDX RX audio arrives as unsigned 8-bit PCM on the serial stream.
                    val sampleU8 = frame[i].toInt() and 0xFF
                    pcm[i] = ((sampleU8 - 128) shl 8).toShort()
                    i++
                }

                val ok = activeEngine.submitAudioRaw(
                    samples = pcm,
                    numSamples = pcm.size,
                    inputSampleRateHz = TRUSDX_RX_SAMPLE_RATE_HZ,
                    timestampNs = System.nanoTime()
                )
                if (!ok) {
                    trusdxRxSubmitDrops += 1
                    if (trusdxRxSubmitDrops <= 10L) {
                        Log.w(TAG, "TruSDX submit drop #$trusdxRxSubmitDrops chunk=${pcm.size}")
                    }
                    if (isTruSdxDiagnosticsEnabled()) {
                        Log.w(TAG, "TruSDX audio submit failed for chunk size=${pcm.size}")
                    }
                }
            }
        }, "TruSdxRxWorker")
        trusdxRxWorkerThread?.isDaemon = true
        trusdxRxWorkerThread?.start()
    }

    private fun stopTruSdxRxWorker() {
        trusdxRxWorkerRunning = false
        val thread = trusdxRxWorkerThread
        trusdxRxWorkerThread = null
        thread?.interrupt()
        if (thread != null && thread.isAlive) {
            try {
                thread.join(300)
            } catch (_: InterruptedException) {
            }
        }
    }

    private fun scheduleTruSdxRxWatchdog() {
        val token = ++trusdxWatchdogToken
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                if (token != trusdxWatchdogToken) return
                if (rigControlMode != "trusdx_serial") return
                if (engine == null) return

                val now = System.nanoTime()
                val last = trusdxLastRxAudioNs
                val stalled = last == 0L || (now - last) > TRUSDX_RX_STALL_REARM_NS
                val rearmDue = trusdxLastRxRearmNs == 0L || (now - trusdxLastRxRearmNs) > TRUSDX_RX_REARM_COOLDOWN_NS
                if (stalled && trusdxConnected && rearmDue) {
                    val ok = trusdxSerialSession?.ensureRxStreaming() == true
                    trusdxLastRxRearmNs = now
                    Log.i(TAG, "TruSDX RX watchdog rearm: ok=$ok lastRxNs=$last")
                }

                mainHandler.postDelayed(this, TRUSDX_RX_WATCHDOG_INTERVAL_MS)
            }
        }, TRUSDX_RX_WATCHDOG_INTERVAL_MS)
    }

    private fun handleTruSdxTxAudio(samplesPcm16: ShortArray, sampleRateHz: Int) {
        if (rigControlMode != "trusdx_serial") return
        if (samplesPcm16.isEmpty()) return
        val serialTxActive = trusdxSerialSession?.isTxActive() == true
        var sumSquares = 0.0
        for (s in samplesPcm16) {
            val v = s.toDouble()
            sumSquares += v * v
        }
        val inRms = kotlin.math.sqrt(sumSquares / samplesPcm16.size)
        val txSamples = if (sampleRateHz == TRUSDX_TX_SAMPLE_RATE_HZ) {
            samplesPcm16
        } else {
            resamplePcmLinear(samplesPcm16, sampleRateHz, TRUSDX_TX_SAMPLE_RATE_HZ)
        }
        if (txSamples.isEmpty()) return

        val ok = trusdxSerialSession?.sendTxAudio(txSamples) == true
        if (!ok) {
            trusdxTxDrops += 1
            return
        }

        if (!serialTxActive) {
            return
        }

        trusdxTxFrames += 1
        trusdxTxSamples += txSamples.size.toLong()
        if (inRms < 1.0) {
            trusdxTxSilentFrames += 1
        }
        if (isTruSdxDiagnosticsEnabled() && (trusdxTxFrames % 200L == 0L)) {
            Log.i(TAG, "TruSDX TX frame #$trusdxTxFrames inRate=$sampleRateHz outSamples=${txSamples.size} inRms=${"%.1f".format(Locale.US, inRms)} silentFrames=$trusdxTxSilentFrames drops=$trusdxTxDrops")
        }
    }

    private fun resamplePcmLinear(samples: ShortArray, inputRateHz: Int, outputRateHz: Int): ShortArray {
        if (samples.isEmpty()) return ShortArray(0)
        if (inputRateHz <= 0 || outputRateHz <= 0) return samples
        if (inputRateHz == outputRateHz) return samples

        val ratio = outputRateHz.toDouble() / inputRateHz.toDouble()
        val outSize = kotlin.math.max(1, kotlin.math.round(samples.size * ratio).toInt())
        val out = ShortArray(outSize)
        val step = inputRateHz.toDouble() / outputRateHz.toDouble()
        var pos = 0.0
        var i = 0
        while (i < outSize) {
            val idx = pos.toInt().coerceIn(0, samples.size - 1)
            val next = (idx + 1).coerceAtMost(samples.size - 1)
            val frac = pos - idx.toDouble()
            val a = samples[idx].toInt()
            val b = samples[next].toInt()
            val value = (a + ((b - a) * frac)).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[i] = value.toShort()
            pos += step
            i++
        }
        return out
    }

    private fun broadcastProcessTxQueue() {
        val intent = Intent(MainActivity.ACTION_PROCESS_TX_QUEUE)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun isScoActive(deviceId: Int): Boolean {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val commDevice = audioManager.communicationDevice
            if (commDevice != null && commDevice.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                if (deviceId < 0 || commDevice.id == deviceId) {
                    return true
                }
            }
        }
        return audioManager.isBluetoothScoOn
    }

    private fun isScoInputDevice(deviceId: Int): Boolean {
        return findInputDevice(deviceId)?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    }

    private fun buildAudioHelper(engine: JS8Engine, deviceId: Int): JS8AudioHelper {
        val overrideSource = if (isScoInputDevice(deviceId)) {
            scoSourceCandidates[scoSourceIndex]
        } else {
            -1
        }
        return JS8AudioHelper(engine, 12000, deviceId, applicationContext, overrideSource)
    }

    private fun applyInputRouting(inputDeviceId: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val inputDevice = findInputDevice(inputDeviceId)
        if (inputDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            enableScoRouting(inputDevice)
        } else {
            disableScoRouting()
        }
    }

    private fun findInputDevice(deviceId: Int): AudioDeviceInfo? {
        if (deviceId < 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.id == deviceId }
    }

    private fun enableScoRouting(device: AudioDeviceInfo) {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val priorMode = audioManager.mode

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "BLUETOOTH_CONNECT permission not granted; SCO routing unavailable")
                broadcastError("Bluetooth permission required for SCO routing")
                return
            }
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (!scoRoutingActive) {
                previousAudioMode = priorMode
            }
            val commDevice = findCommunicationDevice(device)
            if (commDevice == null) {
                Log.w(TAG, "No available communication device matches SCO input; falling back to SCO start")
                if (!startBluetoothScoLegacy(audioManager, priorMode)) {
                    broadcastError("Failed to start Bluetooth SCO routing")
                }
                return
            }
            val ok = audioManager.setCommunicationDevice(commDevice)
            if (ok) {
                scoRoutingActive = true
                Log.i(TAG, "SCO routing enabled via communication device: ${commDevice.productName} (ID: ${commDevice.id})")
                if (!audioManager.isBluetoothScoOn) {
                    if (startBluetoothScoLegacy(audioManager, priorMode)) {
                        Log.i(TAG, "SCO audio started after communication device routing")
                    } else {
                        Log.w(TAG, "Failed to start SCO audio after communication device routing")
                    }
                }
            } else {
                audioManager.mode = priorMode
                Log.w(TAG, "Failed to set communication device for SCO; falling back to SCO start")
                if (!startBluetoothScoLegacy(audioManager, priorMode)) {
                    broadcastError("Failed to start Bluetooth SCO routing")
                }
            }
        } else {
            if (!scoRoutingActive) {
                previousAudioMode = priorMode
            }
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            try {
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
                scoRoutingActive = true
                Log.i(TAG, "SCO routing enabled via startBluetoothSco")
            } catch (e: SecurityException) {
                audioManager.mode = priorMode
                Log.w(TAG, "Failed to start Bluetooth SCO", e)
                broadcastError("Bluetooth permission required for SCO routing")
            }
        }
    }

    private fun startBluetoothScoLegacy(audioManager: AudioManager, priorMode: Int): Boolean {
        return try {
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
            scoRoutingActive = true
            Log.i(TAG, "SCO routing enabled via legacy startBluetoothSco")
            true
        } catch (e: SecurityException) {
            audioManager.mode = priorMode
            Log.w(TAG, "Failed to start Bluetooth SCO (legacy)", e)
            false
        }
    }

    private fun findCommunicationDevice(inputDevice: AudioDeviceInfo): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val candidates = audioManager.availableCommunicationDevices
        if (candidates.isEmpty()) return null
        candidates.firstOrNull { it.id == inputDevice.id }?.let { return it }
        val inputName = inputDevice.productName?.toString()
        candidates.firstOrNull {
            it.type == inputDevice.type &&
                (inputName == null || it.productName?.toString() == inputName)
        }?.let { return it }
        return candidates.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
    }

    private fun disableScoRouting() {
        if (!scoRoutingActive) return
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                audioManager.clearCommunicationDevice()
            } catch (e: SecurityException) {
                Log.w(TAG, "Failed to clear communication device", e)
            }
            if (audioManager.isBluetoothScoOn) {
                try {
                    audioManager.stopBluetoothSco()
                    audioManager.isBluetoothScoOn = false
                } catch (e: SecurityException) {
                    Log.w(TAG, "Failed to stop Bluetooth SCO", e)
                }
            }
        } else {
            try {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
            } catch (e: SecurityException) {
                Log.w(TAG, "Failed to stop Bluetooth SCO", e)
            }
        }
        audioManager.mode = previousAudioMode
        scoRoutingActive = false
        Log.i(TAG, "SCO routing disabled")
    }

    private fun checkHeartbeat() {
        // This function is now just the runnable target, actual scheduling is handled by postDelayed in scheduleHeartbeat
        sendHeartbeat()
    }

    private fun scheduleHeartbeat(first: Boolean) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val intervalStr = prefs.getString(PREF_HEARTBEAT_INTERVAL, "0") ?: "0"
        val intervalMinutes = intervalStr.toIntOrNull() ?: 0

        if (intervalMinutes <= 0) {
            heartbeatHandler.removeCallbacks(heartbeatRunnable)
            Log.i(TAG, "Heartbeat disabled")
            return
        }

        // Drifted timeline, so slots match the engine's cycle boundaries.
        val now = System.currentTimeMillis() + (engine?.timeDriftMs() ?: 0L)
        val frameDuration = getFrameDurationMs()

        // Base delay
        var delay = if (first) {
            // If first run, schedule for the next available slot (plus a small random wait to avoid instant blast?)
            // actually, let's just wait one interval to be polite, or at least a few frames.
            // Desktop does "first ? 0 : interval". 
            // If we use 0, we transmit immediately on app start if enabled. 
            // Let's use 10 seconds min for first run to allow system to settle.
            10000L 
        } else {
            intervalMinutes * 60 * 1000L
        }
        
        // Jitter: 25% chance to skip a frame (Desktop style)
        if (Math.random() < 0.25) {
            delay += frameDuration
        }
        
        var targetTime = now + delay
        
        // Align to next frame boundary (UTC epoch)
        // We want the trigger to happen slightly BEFORE the boundary (e.g. 2000ms)
        // so the engine has time to pick it up for the immediate slot.
        val remainder = targetTime % frameDuration
        val timeToBoundary = frameDuration - remainder
        targetTime += timeToBoundary
        
        // Schedule 2 seconds before the slot starts
        targetTime -= 2000L
        
        // Ensure strictly future
        if (targetTime <= now) {
            targetTime += frameDuration
        }
        
        nextHeartbeatTime = targetTime
        
        Log.i(TAG, "Heartbeat scheduled for ${java.util.Date(nextHeartbeatTime)} (interval=$intervalMinutes min, frame=${frameDuration/1000}s)")
        
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        val waitMs = nextHeartbeatTime - now
        heartbeatHandler.postDelayed(heartbeatRunnable, waitMs)
    }

    private fun getFrameDurationMs(): Long {
        return when (getPreferredTxSubmode()) {
            SUBMODE_SLOW -> 30000L
            SUBMODE_NORMAL -> 15000L
            SUBMODE_FAST -> 10000L
            SUBMODE_TURBO -> 6000L
            else -> 15000L
        }
    }

    /**
     * True for messages that belong in the heartbeat sub-band: heartbeats
     * themselves and heartbeat SNR acknowledgements.
     */
    private fun isHeartbeatTraffic(text: String): Boolean {
        if (TxMessageClassifier.isHeartbeatMessage(text)) return true
        return heartbeatAckTxRegex.containsMatchIn(text)
    }

    /**
     * Random audio offset inside the heartbeat sub-band. The upper bound backs
     * off by the submode bandwidth so the whole signal stays under 1000 Hz.
     */
    private fun heartbeatOffsetHz(submode: Int): Float {
        val bandwidthHz = when (submode) {
            SUBMODE_SLOW -> 25
            SUBMODE_FAST -> 80
            SUBMODE_TURBO -> 160
            else -> 50
        }
        val high = HB_SUBBAND_HIGH_HZ - bandwidthHz
        return (HB_SUBBAND_LOW_HZ + Math.random() * (high - HB_SUBBAND_LOW_HZ)).toFloat()
    }

    private fun sendHeartbeat() {
        if (isTransmitActive()) {
            Log.i(TAG, "Skipping heartbeat due to active transmission, rescheduling")
            scheduleHeartbeat(false)
            return
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val callsign = getConfiguredCallsign() ?: return
        val grid = prefs.getString("grid", "")?.trim().orEmpty().uppercase()

        val freq = heartbeatOffsetHz(getPreferredTxSubmode())
        
        // Message format: CALL: @HB HEARTBEAT GRID
        // Note: JS8Call desktop constructs it as: CALL: @HB HEARTBEAT GRID
        // My buildTxMessage handles adding the "CALL: " part if we pass "@HB HEARTBEAT GRID" as text and directedCall as ""?
        // Wait, handleTransmitMessage uses:
        // text = payloadText
        // myCall = callsign
        // selectedCall = directed
        
        // Let's use transmitMessage directly
        
        val message = "@HB HEARTBEAT $grid"
        // If I pass selectedCall as empty, buildTxMessage logic:
        // if directedCall is empty, return text.
        // So I need to construct the full line myself?
        // nativeTransmitMessage expects "text" (payload) and "selectedCall".
        // The engine likely frames it.
        // Let's look at desktop:
        // lines.append(QString("%1: HEARTBEAT %2").arg(mycall).arg(mygrid));
        // So "CALL: HEARTBEAT GRID"
        
        // But wait, the standard heartbeat format is often directed to @HB?
        // Desktop varicode.cpp:
        // QRegularExpression heartbeat_re(R"(^\s*(?<callsign>[@](?:ALLCALL|HB)\s+)?(?<type>CQ CQ CQ|CQ DX|CQ QRP|CQ CONTEST|CQ FIELD|CQ FD|CQ CQ|CQ|HB|HEARTBEAT(?!\s+SNR))(?:\s(?<grid>[A-R]{2}[0-9]{2}))?\b)");
        
        // Let's stick to what the JS8Call guide says or what works.
        // Usually: "CALL: @HB HEARTBEAT GRID4"
        
        val payload = "@HB HEARTBEAT ${grid.take(4)}"
        
        Log.i(TAG, "Sending Heartbeat: $payload at ${freq}Hz")

        val activeEngine = engine
        if (activeEngine != null) {
            val submode = getPreferredTxSubmode()
            prepareEngineForTransmit(activeEngine)
             val ok = activeEngine.transmitMessage(
                text = payload,
                myCall = callsign,
                myGrid = grid,
                selectedCall = "", // Broadcast-ish
                submode = submode,
                audioFrequencyHz = freq.toDouble(),
                txDelaySec = 0.0,
                forceIdentify = true, // Force ID to ensure callsign is sent
                forceData = false
            )
            
            if (ok) {
                updateLastTxMessage(payload, "", submode, freq.toDouble())
                broadcastTxState(TX_STATE_QUEUED)
                startTxMonitor()
            } else {
                Log.e(TAG, "Failed to send heartbeat")
            }
        }

        scheduleHeartbeat(false)
    }

    private fun disableHeartbeat() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val current = prefs.getString(PREF_HEARTBEAT_INTERVAL, "0")
        if (current != "0") {
            Log.i(TAG, "Disabling heartbeat due to manual transmission")
            prefs.edit().putString(PREF_HEARTBEAT_INTERVAL, "0").apply()
            // The listener will trigger and stop the handler
        }
    }

    private fun handleTransmitMessage(intent: Intent) {
        // Disable heartbeat on manual transmission
        disableHeartbeat()

        val activeEngine = engine
        if (activeEngine == null) {
            broadcastError("Engine not running")
            return
        }

        val text = intent.getStringExtra(EXTRA_TX_TEXT)?.trim().orEmpty()
        if (text.isEmpty()) {
            broadcastError("Empty TX message")
            return
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val callsign = getConfiguredCallsign()
        if (callsign == null) {
            warnMissingCallsign()
            return
        }
        val grid = prefs.getString("grid", "")?.trim().orEmpty().uppercase()

        val directed = intent.getStringExtra(EXTRA_TX_DIRECTED)?.trim().orEmpty()
        val submode = if (intent.hasExtra(EXTRA_TX_SUBMODE)) {
            intent.getIntExtra(EXTRA_TX_SUBMODE, SUBMODE_NORMAL)
        } else {
            prefs.getInt(PREF_TX_SUBMODE, SUBMODE_NORMAL)
        }
        // Heartbeat traffic belongs in the 500-1000 Hz sub-band, away from QSOs.
        // This covers manual HB sends and auto HB ACKs; the scheduled heartbeat
        // picks its own offset in sendHeartbeat().
        val requestedFrequencyHz = intent.getDoubleExtra(EXTRA_TX_FREQ_HZ, DEFAULT_AUDIO_FREQUENCY_HZ)
        val audioFrequencyHz = if (isHeartbeatTraffic(text)) {
            heartbeatOffsetHz(submode).toDouble()
        } else {
            requestedFrequencyHz
        }
        val txDelaySec = intent.getDoubleExtra(EXTRA_TX_DELAY_S, 0.0)
        val forceIdentify = intent.getBooleanExtra(EXTRA_TX_FORCE_IDENTIFY, false)
        val forceData = intent.getBooleanExtra(EXTRA_TX_FORCE_DATA, false)
        val effectiveForceIdentify = forceIdentify || callsign.isNotBlank()
        val payloadText = applyGridIfHeartbeat(text, grid)

        // Set transmit mode if configured (before queuing TX)
        val modeSet = setTransmitMode()
        if (!modeSet) {
            Log.w(TAG, "Failed to set transmit mode (rig control might not be connected)")
        }

        Log.i(
            TAG,
            "TX request: text='$payloadText', directed='${directed}', submode=$submode, freq=$audioFrequencyHz, delay=$txDelaySec, identify=$effectiveForceIdentify"
        )

        prepareEngineForTransmit(activeEngine)
        val ok = activeEngine.transmitMessage(
            text = payloadText,
            myCall = callsign,
            myGrid = grid,
            selectedCall = directed,
            submode = submode,
            audioFrequencyHz = audioFrequencyHz,
            txDelaySec = txDelaySec,
            forceIdentify = effectiveForceIdentify,
            forceData = forceData
        )

        if (ok) {
            Log.i(TAG, "TX request accepted")
            updateLastTxMessage(payloadText, directed, submode, audioFrequencyHz)
            broadcastTxSent(buildTxMessage(payloadText, directed), audioFrequencyHz)
            broadcastTxState(TX_STATE_QUEUED)
            startTxMonitor()
        } else {
            Log.e(TAG, "TX request rejected")
            broadcastError("Failed to start transmit")
            broadcastTxState(TX_STATE_FAILED)
        }
    }

    private fun setTransmitMode(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (rigControlMode == "trusdx_serial") {
            Log.d(TAG, "Setting transmit mode for TruSDX: USB")
            return sendRigModeCommand(RIG_MODE_USB, 0)
        }
        val txMode = prefs.getString(PREF_TRANSMIT_MODE, "none") ?: "none"
        
        Log.d(TAG, "Setting transmit mode: $txMode")
        
        return when (txMode) {
            "none" -> true
            "usb" -> sendRigModeCommand(RIG_MODE_USB, 0)  // 0 = rig default passband
            "usb_data" -> sendRigModeCommand(RIG_MODE_PKTUSB, 0)
            else -> true
        }
    }
    
    private fun sendRigModeCommand(mode: String, passband: Int = 0): Boolean {
        return when (rigControlMode) {
            "hamlib_usb" -> hamlibRigControl?.setMode(mode, passband) == true
            "trusdx_serial" -> trusdxSerialSession?.setUsbMode() == true
            "rts_ptt" -> false
            // Network rig control mode setting not requested yet
            else -> false
        }
    }

    private fun broadcastTxState(state: String) {
        val intent = Intent(ACTION_TX_STATE).apply {
            putExtra(EXTRA_TX_STATE, state)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastTxProgress(frameIndex: Int, frameCount: Int) {
        val intent = Intent(ACTION_TX_PROGRESS).apply {
            putExtra(EXTRA_TX_FRAME_INDEX, frameIndex)
            putExtra(EXTRA_TX_FRAME_COUNT, frameCount)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastTxSent(text: String, frequencyHz: Double) {
        if (text.isBlank()) return
        val intent = Intent(ACTION_TX_SENT).apply {
            putExtra(EXTRA_TX_SENT_TEXT, text)
            putExtra(EXTRA_TX_SENT_FREQ, frequencyHz.toFloat())
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastMessageReceived(
        from: String,
        text: String,
        snr: Int,
        freq: Float,
        relayPath: String?,
        conversationId: String = from
    ) {
        val intent = Intent(ACTION_MESSAGE_RECEIVED).apply {
            putExtra(EXTRA_MESSAGE_FROM, from)
            putExtra(EXTRA_MESSAGE_TEXT, text)
            putExtra(EXTRA_MESSAGE_SNR, snr)
            putExtra(EXTRA_MESSAGE_FREQ, freq)
            putExtra(EXTRA_MESSAGE_CONVERSATION_ID, conversationId)
            relayPath?.let { putExtra(EXTRA_MESSAGE_RELAY_PATH, it) }
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        
        // Also show a notification
        showMessageNotification(from, text)
    }
    
    /**
     * Broadcast a request to queue a TX message.
     * The UI layer (TransmitViewModel) will handle adding it to the TX queue.
     */
    private fun broadcastQueueTx(text: String, directed: String?, priority: Int = 0) {
        val intent = Intent(ACTION_QUEUE_TX).apply {
            putExtra(EXTRA_QUEUE_TX_TEXT, text)
            directed?.let { putExtra(EXTRA_QUEUE_TX_DIRECTED, it) }
            putExtra(EXTRA_QUEUE_TX_PRIORITY, priority)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        Log.d(TAG, "Broadcast queue TX: text='$text' directed=$directed priority=$priority")
    }

    private fun showMessageNotification(from: String, text: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        
        // Create message notification channel if it doesn't exist
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID_MESSAGES,
                getString(R.string.notification_message_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notification_message_channel_desc)
            }
            notificationManager.createNotificationChannel(channel)
        }
        
        // Create intent to open the app
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_messages", true)
            putExtra("callsign", from)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, from.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID_MESSAGES)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notification_new_message_title, from))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(NOTIFICATION_ID_MESSAGE_BASE + from.hashCode(), notification)
    }

    private fun startTxMonitor() {
        txMonitorHandler.removeCallbacks(txMonitorRunnable)
        txMonitorActive = true
        txMonitorWasAudioActive = false
        synchronized(pttStateLock) {
            txPttGeneration++
            rigPttDesired = rigPttAsserted
            rigPttDesiredGeneration = txPttGeneration
            rigPttFailureCount = 0
            rigPttCompletion = null
            pttShuttingDown = false
            txPttFailed = false
        }
        if (rigControlMode == "trusdx_serial") {
            trusdxTxIntentActive = true
        }
        txMonitorHandler.post(txMonitorRunnable)
    }

    private fun stopTxMonitor() {
        if (!txMonitorActive) return
        txMonitorActive = false
        trusdxTxIntentActive = false
        txMonitorHandler.removeCallbacks(txMonitorRunnable)
    }

    private fun isRigPttReady(generation: Int): Boolean {
        return synchronized(pttStateLock) {
            generation == txPttGeneration &&
                rigPttAsserted &&
                rigPttDesired &&
                !rigPttCommandPending
        }
    }

    private fun isCurrentTxGeneration(generation: Int): Boolean {
        return synchronized(pttStateLock) { generation == txPttGeneration }
    }

    private fun requestRigPtt(
        enabled: Boolean,
        generation: Int,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        if (!enabled) engine?.setTransmitReady(false)

        var completion: ((Boolean) -> Unit)? = null
        var scheduleCommand = false
        var openTransmitGate = false
        synchronized(pttStateLock) {
            if (generation != txPttGeneration || (txPttFailed && enabled)) return
            if (rigPttDesired != enabled || rigPttDesiredGeneration != generation) {
                rigPttDesired = enabled
                rigPttDesiredGeneration = generation
                rigPttFailureCount = 0
            }
            if (onComplete != null) rigPttCompletion = onComplete

            if (!rigPttCommandPending) {
                if (rigPttAsserted == rigPttDesired) {
                    completion = rigPttCompletion
                    rigPttCompletion = null
                    openTransmitGate = enabled && generation == txPttGeneration
                } else {
                    rigPttCommandPending = true
                    scheduleCommand = true
                }
            }
        }

        if (openTransmitGate) engine?.setTransmitReady(true)
        completion?.invoke(true)
        if (scheduleCommand) txHandler.post { runRigPttCommand() }
    }

    private fun runRigPttCommand() {
        val target: Boolean
        val generation: Int
        synchronized(pttStateLock) {
            target = rigPttDesired
            generation = rigPttDesiredGeneration
        }

        val success = setRigPtt(target)
        var scheduleNext = false
        var completion: ((Boolean) -> Unit)? = null
        var failEnable = false
        var transmitReady: Boolean? = null
        var completionResult = success
        synchronized(pttStateLock) {
            rigPttCommandPending = false
            if (success) {
                rigPttAsserted = target
                rigPttFailureCount = 0
                transmitReady = if (target) {
                    target == rigPttDesired && generation == txPttGeneration
                } else {
                    false
                }
            }

            if (pttShuttingDown) {
                transmitReady = false
                rigPttCompletion = null
                return@synchronized
            }

            val targetStillDesired = target == rigPttDesired &&
                generation == rigPttDesiredGeneration
            if (!success && targetStillDesired) {
                rigPttFailureCount++
                if (rigPttFailureCount <= PTT_COMMAND_RETRIES) {
                    rigPttCommandPending = true
                    scheduleNext = true
                } else {
                    completion = rigPttCompletion
                    rigPttCompletion = null
                    failEnable = target
                    rigPttDesired = rigPttAsserted
                }
            } else if (rigPttAsserted != rigPttDesired) {
                rigPttCommandPending = true
                scheduleNext = true
            } else if (rigPttDesiredGeneration == txPttGeneration) {
                completion = rigPttCompletion
                rigPttCompletion = null
                completionResult = true
            }
        }

        transmitReady?.let { engine?.setTransmitReady(it) }

        mainHandler.post {
            if (success) {
                Log.i(TAG, "PTT ${if (target) "enabled" else "released"} generation=$generation")
            } else {
                Log.w(TAG, "PTT ${if (target) "enable" else "release"} failed generation=$generation")
            }
            completion?.invoke(completionResult)
            if (failEnable) {
                failTransmitForPtt(generation, "Failed to enable PTT")
            }
        }
        if (scheduleNext) txHandler.post { runRigPttCommand() }
    }

    private fun failTransmitForPtt(generation: Int, message: String) {
        synchronized(pttStateLock) {
            if (generation != txPttGeneration || txPttFailed) return
            txPttFailed = true
            rigPttDesired = false
            rigPttDesiredGeneration = generation
            rigPttFailureCount = 0
            rigPttCompletion = null
            if (!rigPttCommandPending && rigPttAsserted) {
                rigPttCommandPending = true
                txHandler.post { runRigPttCommand() }
            }
        }

        Log.e(TAG, message)
        txMonitorActive = false
        txMonitorHandler.removeCallbacks(txMonitorRunnable)
        engine?.stopTransmit()
        txSessionActive = false
        txAudioActive = false
        txMonitorWasAudioActive = false
        trusdxTxIntentActive = false
        engine?.setTransmitReady(false)
        broadcastError(message)
        broadcastTxState(TX_STATE_FAILED)
    }

    private fun releaseRigPttForShutdown(): Boolean {
        if (Looper.myLooper() == txHandler.looper) {
            val released = setRigPtt(false)
            if (released) rigPttAsserted = false
            return released
        }

        val completed = CountDownLatch(1)
        var released = false
        txHandler.post {
            try {
                released = setRigPtt(false)
                if (released) {
                    synchronized(pttStateLock) {
                        rigPttAsserted = false
                    }
                }
            } finally {
                completed.countDown()
            }
        }
        completed.await()
        if (!released) {
            released = setRigPtt(false)
            if (released) rigPttAsserted = false
        }
        return released
    }

    /**
     * Handle incoming MSG commands - always runs regardless of autoreply setting.
     * This ensures messages are saved to inbox even if auto-ACK is disabled.
     * 
     * JS8Call MSG format can be:
     *   FROM: TO MSG payload    (with space, single frame)
     *   FROM: TO MSGpayload     (without space, bandwidth optimization)
     *   FROM: TO MSG            (multi-frame: command frame)
     *   payload...              (multi-frame: data frames follow)
     */
    private fun maybeHandleIncomingMessage(text: String, snr: Int, freq: Float, type: Int) {
        val callsign = getConfiguredCallsign()
        Log.d(TAG, "maybeHandleIncomingMessage: text='$text' type=$type callsign=$callsign")
        if (callsign == null) {
            Log.d(TAG, "maybeHandleIncomingMessage: no callsign configured, skipping")
            return
        }
        
        val now = System.currentTimeMillis()
        cleanupMsgBuffers(now)
        
        // Try parsing as a directed command (MSG header frame)
        val directed = parseDirectedCommand(text)
        
        if (directed != null && (directed.command.uppercase() == "MSG" || directed.command.uppercase().startsWith("MSG"))) {
            // This is a MSG command frame
            val isForMe = isSelfCallsign(callsign, directed.to)
            val isForMyGroup = isSubscribedGroup(directed.to)
            
            if (!isForMe && !isForMyGroup) {
                Log.d(TAG, "maybeHandleIncomingMessage: MSG not for me ($callsign) or my groups, skipping")
                return
            }
            
            // Extract any inline payload from concatenated format (MSGpayload)
            val inlinePayload = if (directed.command.uppercase().startsWith("MSG") && directed.command.length > 3) {
                directed.command.substring(3)
            } else {
                ""
            }
            
            // Combine inline payload with any additional payload tokens
            val initialPayload = if (inlinePayload.isNotBlank() && directed.payload.isNotBlank()) {
                "$inlinePayload ${directed.payload}"
            } else if (inlinePayload.isNotBlank()) {
                inlinePayload
            } else {
                directed.payload
            }
            
            Log.d(TAG, "maybeHandleIncomingMessage: MSG command from=${directed.from} to=${directed.to} inlinePayload='$inlinePayload' initialPayload='$initialPayload' isLastFrame=${isLastFrame(type)}")
            
            // If this is the last frame and we have payload, deliver immediately
            if (isLastFrame(type) && initialPayload.isNotBlank()) {
                val conversationId = if (isForMyGroup) directed.to else directed.from
                // Strip checksum (3 uppercase alphanumeric chars at end, preceded by space)
                val cleanPayload = initialPayload.trim()
                    .replace(Regex("\\s*[▪■]+\\s*$"), "")
                    .replace(Regex("\\s+[A-Z0-9]{3}$"), "")
                    .trim()
                Log.i(TAG, "MSG received (single frame): from=${directed.from} to=${directed.to} text='$cleanPayload' conversationId=$conversationId")
                broadcastMessageReceived(directed.from, cleanPayload, snr, freq, null, conversationId)
                
                // Queue auto-ACK now that full message is received (if autoreply enabled)
                if (isAutoreplyEnabled()) {
                    Log.i(TAG, "Auto ACK for MSG from=${directed.from}")
                    broadcastQueueTx("$callsign: ${directed.from} ACK", null, priority = 2)
                }
                return
            }
            
            // Buffer the MSG command and wait for data frames
            val key = findMatchingMsgBufferKey(freq) ?: Math.round(freq)
            val buffer = MsgBuffer(
                from = directed.from.trim().uppercase(),
                to = directed.to.trim().uppercase(),
                snr = snr,
                frequency = freq,
                lastUpdated = now,
                parts = if (initialPayload.isNotBlank()) mutableListOf(initialPayload) else mutableListOf()
            )
            synchronized(msgLock) {
                msgBuffers[key] = buffer
            }
            Log.d(TAG, "maybeHandleIncomingMessage: buffered MSG command, waiting for data frames")
            
            // If last frame with no payload, it's an empty MSG (shouldn't happen normally)
            if (isLastFrame(type)) {
                synchronized(msgLock) {
                    msgBuffers.remove(key)
                }
                Log.d(TAG, "maybeHandleIncomingMessage: last frame but no payload, discarding")
            }
            return
        }
        
        // Not a directed command - check if it's a data frame for a buffered MSG
        if (!isDataFrame(type)) {
            return
        }
        
        val result = synchronized(msgLock) {
            val key = findMatchingMsgBufferKey(freq) ?: return@synchronized null
            val buffer = msgBuffers[key] ?: return@synchronized null
            buffer.parts.add(text)
            buffer.lastUpdated = now
            Log.d(TAG, "maybeHandleIncomingMessage: added data frame to buffer, parts=${buffer.parts.size} isLastFrame=${isLastFrame(type)}")
            if (isLastFrame(type)) {
                msgBuffers.remove(key)
                buffer
            } else {
                null
            }
        }
        
        if (result != null) {
            processMsgBuffer(result, callsign)
        }
    }
    
    private data class MsgBuffer(
        val from: String,
        val to: String,
        val snr: Int,
        val frequency: Float,
        var lastUpdated: Long,
        val parts: MutableList<String> = mutableListOf()
    )
    
    private val msgBuffers = mutableMapOf<Int, MsgBuffer>()
    private val msgLock = Any()
    private val MSG_BUFFER_TIMEOUT_MS = 60_000L
    
    private fun cleanupMsgBuffers(now: Long) {
        synchronized(msgLock) {
            msgBuffers.entries.removeIf { (_, buffer) ->
                now - buffer.lastUpdated > MSG_BUFFER_TIMEOUT_MS
            }
        }
    }
    
    private fun findMatchingMsgBufferKey(freq: Float): Int? {
        val rounded = Math.round(freq)
        synchronized(msgLock) {
            for (key in msgBuffers.keys) {
                if (Math.abs(key - rounded) <= 5) {
                    return key
                }
            }
        }
        return null
    }
    
    private fun processMsgBuffer(buffer: MsgBuffer, myCallsign: String) {
        // Data frames split at arbitrary byte boundaries, not word boundaries.
        val fullText = assembleMsgPayload(buffer.parts).trim()
        // Remove end-of-message marker if present
        var cleanText = fullText.replace(Regex("\\s*[▪■]+\\s*$"), "").trim()
        
        // Remove MSG checksum (3 uppercase alphanumeric chars at end, preceded by space)
        // JS8Call MSG uses 16-bit checksum which is 3 characters
        cleanText = cleanText.replace(Regex("\\s+[A-Z0-9]{3}$"), "").trim()
        
        if (cleanText.isBlank()) {
            Log.d(TAG, "processMsgBuffer: empty message after joining parts, discarding")
            return
        }
        
        val isForMyGroup = isSubscribedGroup(buffer.to)
        val conversationId = if (isForMyGroup) buffer.to else buffer.from
        
        Log.i(TAG, "MSG received (multi-frame): from=${buffer.from} to=${buffer.to} text='$cleanText' conversationId=$conversationId")
        broadcastMessageReceived(buffer.from, cleanText, buffer.snr, buffer.frequency, null, conversationId)
        
        // Queue auto-ACK now that full message is received (if autoreply enabled)
        if (isAutoreplyEnabled()) {
            Log.i(TAG, "Auto ACK for MSG from=${buffer.from}")
            broadcastQueueTx("$myCallsign: ${buffer.from} ACK", null, priority = 2)
        }
    }
    
    private fun isDataFrame(type: Int): Boolean = (type and 0x4) != 0
    
    private fun isSubscribedGroup(target: String): Boolean {
        if (!target.startsWith("@")) return false
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val groupsStr = prefs.getString("my_groups", "") ?: ""
        val groups = groupsStr.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        return groups.contains(target.uppercase())
    }

    private fun maybeHandleAutoReply(text: String, snr: Int, mode: Int) {
        if (!isAutoreplyEnabled()) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val callsign = getConfiguredCallsign()
        if (callsign == null) {
            warnMissingCallsign()
            return
        }
        if (isTransmitActive()) return

        val heartbeat = parseHeartbeat(text)
        if (heartbeat != null) {
            if (isSelfCallsign(callsign, heartbeat.from)) return
            val snrText = formatSNR(snr)
            if (snrText.isEmpty()) return
            val target = heartbeat.from.trim().uppercase()
            val payload = "$callsign: $target HEARTBEAT SNR $snrText"
            Log.i(TAG, "Auto HB ACK: from=$target snr=$snrText text='$payload'")
            broadcastQueueTx(payload, null, priority = 1)
            return
        }

        val directed = parseDirectedCommand(text) ?: return
        if (!shouldReplyToDirected(callsign, directed)) return
        val cmdUpper = directed.command.uppercase()
        when {
            cmdUpper == "SNR?" || cmdUpper == "?" -> {
                val snrText = formatSNR(snr)
                if (snrText.isEmpty()) return
                Log.i(TAG, "Auto SNR reply: from=${directed.from} snr=$snrText")
                broadcastQueueTx("$callsign: ${directed.from} SNR $snrText", null, priority = 1)
            }
            cmdUpper == "INFO?" -> {
                val info = prefs.getString(PREF_MY_INFO, "")?.trim().orEmpty()
                if (info.isBlank()) return
                Log.i(TAG, "Auto INFO reply: from=${directed.from}")
                broadcastQueueTx("$callsign: ${directed.from} INFO $info", null, priority = 1)
            }
            cmdUpper == "STATUS?" -> {
                val status = prefs.getString(PREF_MY_STATUS, "")?.trim().orEmpty()
                if (status.isBlank()) return
                Log.i(TAG, "Auto STATUS reply: from=${directed.from}")
                broadcastQueueTx("$callsign: ${directed.from} STATUS $status", null, priority = 1)
            }
            cmdUpper == "HEARING?" -> {
                val heard = getRecentHeardCallsigns(
                    exclude = setOf(directed.from.trim().uppercase(), callsign),
                    limit = HEARD_LIMIT
                )
                if (heard.isEmpty()) return
                Log.i(TAG, "Auto HEARING reply: from=${directed.from}, count=${heard.size}")
                broadcastQueueTx("$callsign: ${directed.from} HEARING ${heard.joinToString(" ")}", null, priority = 1)
            }
            cmdUpper == "GRID?" -> {
                val grid = prefs.getString("grid", "")?.trim().orEmpty().uppercase()
                if (grid.isBlank()) return
                Log.i(TAG, "Auto GRID reply: from=${directed.from}")
                broadcastQueueTx("$callsign: ${directed.from} GRID $grid", null, priority = 1)
            }
            cmdUpper == "AGN?" -> {
                val message = lastTxMessage.trimEnd()
                if (message.isBlank()) return
                Log.i(TAG, "Auto AGN reply: from=${directed.from}")
                broadcastQueueTx(message, null, priority = 1)
            }
            // MSG auto-ACK is handled in maybeHandleIncomingMessage after full message is received
            else -> return
        }
    }

    private fun handleRelayFrame(text: String, snr: Int, mode: Int, freq: Float, type: Int) {
        if (!isRelayEnabled()) return
        val callsign = getConfiguredCallsign() ?: return
        val now = System.currentTimeMillis()
        cleanupRelayBuffers(now)

        val directed = parseDirectedCommand(text)
        if (directed != null && directed.command == ">") {
            val target = directed.to.trim().uppercase()
            if (isGroupTarget(target)) return
            if (!isSelfCallsign(callsign, target)) return

            val key = findMatchingRelayBufferKey(freq) ?: Math.round(freq)
            val buffer = RelayBuffer(
                from = directed.from.trim().uppercase(),
                to = target,
                snr = snr,
                submode = getPreferredTxSubmode(),
                frequency = freq,
                lastUpdated = now,
                inlinePayload = directed.payload.isNotBlank()
            )
            synchronized(relayLock) {
                relayBuffers[key] = buffer
            }
            Log.i(TAG, "Relay command buffered from=${directed.from} to=${directed.to} freq=$freq")

            if (directed.payload.isNotBlank()) {
                val (payload, hasEom) = normalizeRelayPayload(directed.payload)
                if (payload.isNotBlank()) {
                    buffer.parts.add(payload)
                    buffer.lastUpdated = now
                }
                if (hasEom || isLastFrame(type)) {
                    synchronized(relayLock) {
                        relayBuffers.remove(key)
                    }
                    processRelayBuffer(buffer)
                }
                return
            }

            return
        }

        if (!isRelayDataFrame(type)) return

        val result = synchronized(relayLock) {
            val key = findMatchingRelayBufferKey(freq) ?: return@synchronized null
            val buffer = relayBuffers[key] ?: return@synchronized null
            buffer.parts.add(text)
            buffer.lastUpdated = now
            if (isLastFrame(type)) {
                relayBuffers.remove(key)
                buffer
            } else {
                null
            }
        }

        if (result != null) {
            processRelayBuffer(result)
        }
    }

    private fun parseHeartbeat(text: String): Heartbeat? {
        val match = heartbeatRegex.find(text) ?: return null
        val from = match.groupValues.getOrNull(1)?.trim().orEmpty()
        if (from.isBlank()) return null
        return Heartbeat(from)
    }

    private data class Heartbeat(val from: String)

    private fun parseDirectedCommand(text: String): DirectedCommand? {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return null

        val tokens = trimmed.split(Regex("\\s+"))
        if (tokens.size < 2) return null

        var index = 0
        var from = ""
        var toToken = tokens[index]

        if (toToken.endsWith(":")) {
            from = toToken.trimEnd(':')
            index++
            if (index >= tokens.size) return null
            toToken = tokens[index]
        }

        var to = toToken
        var command: String
        var payloadStart = index + 1

        if (toToken.endsWith(">")) {
            to = toToken.trimEnd('>')
            command = ">"
        } else {
            if (index + 1 >= tokens.size) return null
            command = tokens[index + 1]
            payloadStart = index + 2
        }

        if (to.isBlank() || command.isBlank()) return null
        if (from.isBlank() && command != ">") return null
        val payload = if (payloadStart < tokens.size) {
            tokens.subList(payloadStart, tokens.size).joinToString(" ")
        } else {
            ""
        }
        return DirectedCommand(from, to, command, payload)
    }

    private data class DirectedCommand(
        val from: String,
        val to: String,
        val command: String,
        val payload: String
    )

    private data class RelayBuffer(
        val from: String,
        val to: String,
        val snr: Int,
        val submode: Int,
        val frequency: Float,
        var lastUpdated: Long,
        val inlinePayload: Boolean,
        val parts: MutableList<String> = mutableListOf()
    )

    private fun shouldReplyToDirected(myCall: String, command: DirectedCommand): Boolean {
        if (command.to.startsWith("@")) return false
        if (!isSelfCallsign(myCall, command.to)) return false
        if (isSelfCallsign(myCall, command.from)) return false
        return true
    }

    private fun isAutoreplyEnabled(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        return prefs.getBoolean(PREF_AUTOREPLY_ENABLED, false)
    }

    private fun isTruSdxDiagnosticsEnabled(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        return prefs.getBoolean(PREF_TRUSDX_DIAGNOSTICS_ENABLED, false)
    }

    private fun isRelayEnabled(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        return prefs.getBoolean(PREF_RELAY_ENABLED, false)
    }

    private fun getPreferredTxSubmode(): Int {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val submode = prefs.getInt(PREF_TX_SUBMODE, SUBMODE_NORMAL)
        return when (submode) {
            SUBMODE_NORMAL,
            SUBMODE_FAST,
            SUBMODE_TURBO,
            SUBMODE_SLOW -> submode
            else -> SUBMODE_NORMAL
        }
    }

    private fun getConfiguredCallsign(): String? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val callsign = prefs.getString("callsign", "")?.trim().orEmpty().uppercase()
        if (callsign.isBlank()) return null
        callsignWarningShown = false
        return callsign
    }

    private fun warnMissingCallsign() {
        if (callsignWarningShown) return
        callsignWarningShown = true
        broadcastError(getString(R.string.error_callsign_required))
    }

    private fun initPskReporter() {
        if (pskReporterClient != null) return
        val programInfo = "JS8Android-${BuildConfig.VERSION_NAME}"
        pskReporterClient = PskReporterClient(programInfo)
        updatePskReporterState()
    }

    private fun updatePskReporterState() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val enabled = prefs.getBoolean(PREF_PSK_REPORTER, false)
        val callsign = prefs.getString("callsign", "")?.trim().orEmpty().uppercase()
        val grid = prefs.getString("grid", "")?.trim().orEmpty().uppercase()
        Log.d(TAG, "PSKReporter state: enabled=$enabled callsign='$callsign' grid='$grid'")
        currentCallsign = callsign
        currentGrid = grid
        if (!enabled || callsign.isBlank() || grid.isBlank()) {
            pskReporterClient?.stop(flush = false, discardPending = true)
            pskReporterEnabled = false
            return
        }
        pskReporterClient?.start()
        pskReporterClient?.setLocalStation(callsign, grid, "")
        pskReporterEnabled = true
    }

    private fun updateDialFromPrefs() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val dial = prefs.getString("last_frequency", "")?.trim().orEmpty()
        currentDialHz = dial.toLongOrNull() ?: currentDialHz
    }

    private fun maybeReportToPskReporter(utc: Int, snr: Int, freq: Float, text: String) {
        if (!pskReporterEnabled) return
        if (currentDialHz <= 0) {
            Log.d(TAG, "PSKReporter skip: dialHz=$currentDialHz")
            return
        }
        val call = extractHeardCallsign(text)
        if (call == null) {
            Log.d(TAG, "PSKReporter skip: no callsign in '$text'")
            return
        }
        if (currentCallsign.isNotBlank() && isSelfCallsign(currentCallsign, call)) {
            Log.d(TAG, "PSKReporter skip: self call=$call")
            return
        }
        val grid = extractGrid(text)
        if (grid == null) {
            Log.d(TAG, "PSKReporter skip: no grid in '$text'")
            return
        }
        val rfHz = currentDialHz + freq.toInt()
        if (rfHz <= 0) {
            Log.d(TAG, "PSKReporter skip: rfHz=$rfHz dialHz=$currentDialHz freq=$freq")
            return
        }
        val timestamp = decodeUtcToEpochSeconds(utc)
        Log.d(TAG, "PSKReporter spot: call=$call grid=$grid snr=$snr rfHz=$rfHz ts=$timestamp")
        pskReporterClient?.addSpot(call, grid, snr, rfHz, "JS8", timestamp)
    }

    private fun extractGrid(text: String): String? {
        val match = gridRegex.find(text) ?: return null
        return match.value.uppercase(Locale.US)
    }

    private fun decodeUtcToEpochSeconds(utc: Int): Long {
        val hours = utc / 10000
        val minutes = (utc / 100) % 100
        val seconds = utc % 100
        val nowMillis = System.currentTimeMillis()
        val nowUtc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = nowMillis
        }
        val candidate = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(Calendar.YEAR, nowUtc.get(Calendar.YEAR))
            set(Calendar.MONTH, nowUtc.get(Calendar.MONTH))
            set(Calendar.DAY_OF_MONTH, nowUtc.get(Calendar.DAY_OF_MONTH))
            set(Calendar.HOUR_OF_DAY, hours)
            set(Calendar.MINUTE, minutes)
            set(Calendar.SECOND, seconds)
            set(Calendar.MILLISECOND, 0)
        }
        var candidateMillis = candidate.timeInMillis
        val diff = candidateMillis - nowMillis
        val twelveHoursMs = 12L * 60L * 60L * 1000L
        if (diff > twelveHoursMs) {
            candidateMillis -= 24L * 60L * 60L * 1000L
        } else if (diff < -twelveHoursMs) {
            candidateMillis += 24L * 60L * 60L * 1000L
        }
        return candidateMillis / 1000L
    }

    private fun isTransmitActive(): Boolean {
        return txSessionActive || txAudioActive
    }

    private fun isSelfCallsign(myCall: String, from: String): Boolean {
        val mine = myCall.trim().uppercase()
        val theirs = from.trim().uppercase()
        return mine == theirs
    }

    private fun isGroupTarget(target: String): Boolean {
        return target.contains("@")
    }

    private fun isRelayDataFrame(type: Int): Boolean = (type and 0x4) != 0

    private fun isLastFrame(type: Int): Boolean = (type and 0x2) != 0

    private fun formatSNR(snr: Int): String {
        if (snr < -60 || snr > 60) return ""
        val sign = if (snr >= 0) "+" else ""
        val width = if (snr < 0) 3 else 2
        return String.format("%s%0${width}d", sign, snr)
    }

    private fun cleanupRelayBuffers(now: Long) {
        synchronized(relayLock) {
            val iterator = relayBuffers.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value.lastUpdated > RELAY_BUFFER_TIMEOUT_MS) {
                    iterator.remove()
                }
            }
        }
    }

    private fun updateMessageLogging() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val enabled = prefs.getBoolean(PREF_LOG_MESSAGES, false)
        messageLogger?.setEnabled(enabled)
    }

    private fun logRxDecode(text: String, snr: Int, freq: Float, mode: Int) {
        val logger = messageLogger ?: return
        logger.logRx(text, snr, freq, mode, extractHeardCallsign(text))
    }

    private fun logTxStarted() {
        val logger = messageLogger ?: return
        val message = lastTxMessage.trim()
        if (message.isBlank()) return
        val directed = lastTxDirected.trim().ifEmpty { null }
        logger.logTx(message, directed, lastTxFrequencyHz, lastTxSubmode)
    }

    private fun findMatchingRelayBufferKey(freq: Float): Int? {
        synchronized(relayLock) {
            for ((key, _) in relayBuffers) {
                if (kotlin.math.abs(freq - key) <= RELAY_FREQUENCY_TOLERANCE_HZ) {
                    return key
                }
            }
        }
        return null
    }

    private fun processRelayBuffer(buffer: RelayBuffer) {
        if (buffer.parts.isEmpty()) return
        val combined = stripRelayEom(buffer.parts.joinToString(separator = "").trimEnd())
        if (combined.isBlank()) return

        if (combined.trimStart().startsWith("@")) {
            Log.i(TAG, "Relay payload starts with group token, ignoring")
            return
        }

        val (valid, message) = validateRelayChecksum(combined)
        val payload = if (valid) {
            message
        } else if (buffer.inlinePayload) {
            Log.w(TAG, "Relay payload checksum invalid, forwarding inline payload without validation")
            stripOptionalRelayChecksum(combined)
        } else {
            Log.w(TAG, "Relay payload failed checksum validation")
            return
        }

        if (payload.isBlank()) return

        val forwardPayload = buildRelayForwardPayload(payload)
        if (forwardPayload != null) {
            val forwardText = if (buffer.from.isNotBlank()) {
                "$forwardPayload *DE* ${buffer.from}"
            } else {
                forwardPayload
            }
            sendRelayMessage(forwardText, buffer.submode)
            return
        }

        val trimmed = payload.trimStart()
        if (trimmed.startsWith("ACK", ignoreCase = true)) {
            return
        }

        val relayPath = parseRelayPathCallsigns(buffer.from, payload).joinToString(">")
        if (relayPath.isBlank()) return

        val handled = maybeHandleRelayedAutoreply(payload, relayPath, buffer.snr, buffer.submode)
        if (!handled) {
            sendRelayMessage("$relayPath ACK", buffer.submode)
        }
    }

    private fun buildRelayForwardPayload(message: String): String? {
        val trimmed = message.trimStart()
        val match = relayTargetRegex.find(trimmed) ?: return null
        val target = match.groupValues[1].trim().uppercase()
        if (!isCallsignLike(target) || isGroupTarget(target)) return null

        val separator = match.groupValues[2]
        if (separator == ">") return trimmed

        val replaceIndex = match.groupValues[1].length
        if (replaceIndex >= trimmed.length) return null
        val builder = StringBuilder(trimmed)
        builder.setCharAt(replaceIndex, '>')
        return builder.toString()
    }

    private fun isCallsignLike(token: String): Boolean {
        return CallsignValidator.isAmateurCallsign(token)
    }

    private fun parseRelayPathCallsigns(from: String, text: String): List<String> {
        val calls = mutableListOf<String>()
        for (match in relayPathRegex.findAll(text.uppercase())) {
            val call = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (call.isNotEmpty()) {
                calls.add(0, call)
            }
        }
        val base = from.trim().uppercase()
        if (base.isNotEmpty()) {
            calls.add(0, base)
        }
        return calls
    }

    private fun maybeHandleRelayedAutoreply(
        payload: String,
        relayPath: String,
        snr: Int,
        submode: Int
    ): Boolean {
        if (!isAutoreplyEnabled()) return false
        if (isTransmitActive()) return false
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        val tokens = payload.trimStart().split(Regex("\\s+"), limit = 3)
        if (tokens.isEmpty()) return false
        val cmd = tokens[0].uppercase()

        return when (cmd) {
            "SNR?", "?" -> {
                val snrText = formatSNR(snr)
                if (snrText.isEmpty()) return false
                sendRelayMessage("$relayPath SNR $snrText", submode)
            }
            "INFO?" -> {
                val info = prefs.getString(PREF_MY_INFO, "")?.trim().orEmpty()
                if (info.isBlank()) return false
                sendRelayMessage("$relayPath INFO $info", submode)
            }
            "STATUS?" -> {
                val status = prefs.getString(PREF_MY_STATUS, "")?.trim().orEmpty()
                if (status.isBlank()) return false
                sendRelayMessage("$relayPath STATUS $status", submode)
            }
            "HEARING?" -> {
                val callsign = getConfiguredCallsign() ?: return false
                val heard = getRecentHeardCallsigns(
                    exclude = setOf(relayPath, callsign),
                    limit = HEARD_LIMIT
                )
                if (heard.isEmpty()) return false
                sendRelayMessage("$relayPath HEARING ${heard.joinToString(" ")}", submode)
            }
            "GRID?" -> {
                val grid = prefs.getString("grid", "")?.trim().orEmpty().uppercase()
                if (grid.isBlank()) return false
                sendRelayMessage("$relayPath GRID $grid", submode)
            }
            "AGN?" -> {
                val message = lastTxMessage.trimEnd()
                if (message.isBlank()) return false
                sendRelayMessage(message, submode)
            }
            else -> false
        }
    }

    private fun validateRelayChecksum(message: String): Pair<Boolean, String> {
        val trimmed = message.trimStart()
        if (trimmed.length < 4) return false to trimmed
        val checksum = trimmed.takeLast(3).uppercase()
        val body = trimmed.dropLast(4)
        return checksum16Valid(checksum, body) to body
    }

    private fun normalizeRelayPayload(payload: String): Pair<String, Boolean> {
        var trimmed = payload.trimEnd()
        val hasEom = trimmed.endsWith(RELAY_EOM_MARKER)
        if (hasEom) {
            trimmed = trimmed.dropLast(1).trimEnd()
        }
        return trimmed to hasEom
    }

    private fun stripRelayEom(payload: String): String {
        var trimmed = payload.trimEnd()
        if (trimmed.endsWith(RELAY_EOM_MARKER)) {
            trimmed = trimmed.dropLast(1).trimEnd()
        }
        return trimmed
    }

    private fun stripOptionalRelayChecksum(message: String): String {
        val trimmed = message.trimEnd()
        val lastSpace = trimmed.lastIndexOf(' ')
        if (lastSpace <= 0 || trimmed.length - lastSpace != 4) {
            return trimmed
        }
        val checksum = trimmed.substring(lastSpace + 1)
        if (checksum.length != 3) return trimmed
        val isChecksumToken = checksum.all { CHECKSUM_ALPHABET.contains(it.uppercaseChar()) }
        return if (isChecksumToken) trimmed.substring(0, lastSpace) else trimmed
    }

    private fun checksum16Valid(checksum: String, input: String): Boolean {
        val crc = crc16Kermit(input.toByteArray(Charsets.US_ASCII))
        return pack16Bits(crc) == checksum
    }

    private fun crc16Kermit(data: ByteArray): Int {
        var crc = 0x0000
        for (byte in data) {
            var cur = byte.toInt() and 0xFF
            for (i in 0 until 8) {
                val mix = (crc xor cur) and 0x01
                crc = crc ushr 1
                if (mix != 0) {
                    crc = crc xor 0x8408
                }
                cur = cur ushr 1
            }
        }
        return crc and 0xFFFF
    }

    private fun pack16Bits(value: Int): String {
        val alphabet = CHECKSUM_ALPHABET
        val base = CHECKSUM_BASE
        val tmp1 = value / (base * base)
        val tmp2 = (value - (tmp1 * base * base)) / base
        val tmp3 = value % base
        return "${alphabet[tmp1]}${alphabet[tmp2]}${alphabet[tmp3]}"
    }

    private fun sendRelayMessage(text: String, submode: Int): Boolean {
        val activeEngine = engine ?: return false
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val callsign = getConfiguredCallsign() ?: return false
        val grid = prefs.getString("grid", "")?.trim().orEmpty().uppercase()
        if (isTransmitActive()) return false

        val payload = text.trim()
        if (payload.isEmpty()) return false

        prepareEngineForTransmit(activeEngine)
        val ok = activeEngine.transmitMessage(
            text = payload,
            myCall = callsign,
            myGrid = grid,
            selectedCall = "",
            submode = submode,
            audioFrequencyHz = currentTxOffsetHz.toDouble(),
            txDelaySec = 0.0,
            forceIdentify = callsign.isNotBlank(),
            forceData = false
        )

        if (ok) {
            updateLastTxMessage(payload, "", submode, currentTxOffsetHz.toDouble())
            broadcastTxState(TX_STATE_QUEUED)
            startTxMonitor()
        }
        return ok
    }

    private fun sendAutoReply(
        text: String,
        directed: String?,
        submode: Int,
        requireDirected: Boolean = true,
        forceData: Boolean = false
    ) {
        val activeEngine = engine ?: return
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val callsign = getConfiguredCallsign()
        if (callsign == null) {
            warnMissingCallsign()
            return
        }
        val grid = prefs.getString("grid", "")?.trim().orEmpty().uppercase()

        val payloadText = text.trim()
        val directedCall = directed?.trim().orEmpty().uppercase()
        if (requireDirected && directedCall.isBlank()) return

        prepareEngineForTransmit(activeEngine)
        val ok = activeEngine.transmitMessage(
            text = payloadText,
            myCall = callsign,
            myGrid = grid,
            selectedCall = directedCall,
            submode = submode,
            audioFrequencyHz = currentTxOffsetHz.toDouble(),
            txDelaySec = 0.0,
            forceIdentify = callsign.isNotBlank(),
            forceData = forceData
        )

        if (ok) {
            Log.i(TAG, "Autoreply queued: to=$directedCall text='$payloadText'")
            updateLastTxMessage(payloadText, directedCall, submode, currentTxOffsetHz.toDouble())
            broadcastTxState(TX_STATE_QUEUED)
            startTxMonitor()
        } else {
            Log.e(TAG, "Autoreply rejected")
            broadcastError("Failed to start transmit")
            broadcastTxState(TX_STATE_FAILED)
        }
    }

    private fun updateHeardCallsign(text: String) {
        val callsign = extractHeardCallsign(text) ?: return
        val now = System.currentTimeMillis()
        synchronized(heardLock) {
            heardCallsigns[callsign] = now
            pruneHeardEntries(now)
        }
    }

    private fun getRecentHeardCallsigns(exclude: Set<String>, limit: Int): List<String> {
        val now = System.currentTimeMillis()
        synchronized(heardLock) {
            pruneHeardEntries(now)
            return heardCallsigns.entries
                .asSequence()
                .filter { !exclude.contains(it.key) }
                .sortedByDescending { it.value }
                .take(limit)
                .map { it.key }
                .toList()
        }
    }

    private fun pruneHeardEntries(now: Long) {
        val iter = heardCallsigns.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (now - entry.value > HEARD_WINDOW_MS) {
                iter.remove()
            }
        }
    }

    private fun extractHeardCallsign(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return null
        val firstToken = trimmed.split(Regex("\\s+"), limit = 2)[0]
        var token = firstToken.trimEnd(':').uppercase()
        if (token.contains(">")) {
            token = token.substringBefore(">").trimEnd(':')
        }
        if (token.startsWith("@")) return null
        if (token in HEARD_EXCLUDE_TOKENS) return null
        return token.takeIf(CallsignValidator::isAmateurCallsign)
    }

    private fun updateLastTxMessage(
        text: String,
        directedCall: String,
        submode: Int,
        frequencyHz: Double
    ) {
        val built = buildTxMessage(text, directedCall)
        if (built.isNotBlank()) {
            lastTxMessage = built
            lastTxDirected = directedCall.trim().uppercase()
            lastTxSubmode = submode
            lastTxFrequencyHz = frequencyHz
        }
    }

    private fun buildTxMessage(text: String, directedCall: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        val selected = directedCall.trim().uppercase()
        if (selected.isEmpty()) return trimmed
        if (trimmed.startsWith("`")) return trimmed

        if (TxMessageClassifier.isBaseMessage(trimmed)) return trimmed
        if (trimmed.startsWith(selected, ignoreCase = true)) return trimmed

        val sep = if (trimmed.startsWith(" ")) "" else " "
        return selected + sep + trimmed
    }

    private fun applyGridIfHeartbeat(text: String, grid: String): String {
        val trimmed = text.trim()
        if (grid.length < 4) return trimmed
        val grid4 = grid.substring(0, 4).uppercase()
        if (!TxMessageClassifier.shouldAppendGrid(trimmed)) return trimmed
        val gridRegex = Regex("\\b[A-R]{2}[0-9]{2}\\b", RegexOption.IGNORE_CASE)
        if (gridRegex.containsMatchIn(trimmed)) return trimmed
        return "$trimmed $grid4".trim()
    }

    private fun isRigControlConnected(): Boolean {
        return when (rigControlMode) {
            "network" -> rigCtlConnected
            "hamlib_usb" -> hamlibRigConnected
            "rts_ptt" -> rtsPttConnected
            "trusdx_serial" -> trusdxConnected
            else -> false
        }
    }

    private fun isRigPttRequired(): Boolean = rigControlMode != "none"

    private fun prepareEngineForTransmit(activeEngine: JS8Engine) {
        activeEngine.setTransmitReady(!isRigPttRequired())
    }

    private fun setRigPtt(enabled: Boolean): Boolean {
        return when (rigControlMode) {
            "network" -> rigCtlClient?.setPtt(enabled) == true
            "hamlib_usb" -> hamlibRigControl?.setPtt(enabled) == true
            "rts_ptt" -> setRtsPtt(enabled)
            "trusdx_serial" -> trusdxSerialSession?.setPtt(enabled) == true
            else -> false
        }
    }

    private fun setRtsPtt(enabled: Boolean): Boolean {
        val transport = rtsPttTransport
        val result = when (transport) {
            SerialTransport.USB -> usbSerialBridge?.setRts(enabled) == true
            SerialTransport.BLUETOOTH -> bluetoothSerialBridge?.setRts(enabled) == true
            else -> false
        }
        if (!result) {
            Log.w(TAG, "RTS PTT toggle failed (transport=$transport)")
        }
        return result
    }

    private fun setFrequency(frequencyHz: Long) {
        if (!isRigControlConnected()) {
            Log.d(TAG, "Cannot set frequency: rig control not connected")
            return
        }

        // Run on background thread
        Thread {
            val success = when (rigControlMode) {
                "network" -> rigCtlClient?.setFrequency(frequencyHz) == true
                "hamlib_usb" -> hamlibRigControl?.setFrequency(frequencyHz) == true
                "rts_ptt" -> false
                "trusdx_serial" -> trusdxSerialSession?.setFrequency(frequencyHz) == true
                else -> false
            }

            mainHandler.post {
                if (success) {
                    Log.i(TAG, "Frequency set to $frequencyHz Hz")
                } else {
                    val detail = when (rigControlMode) {
                        "hamlib_usb" -> hamlibRigControl?.getLastError().orEmpty()
                        "rts_ptt" -> "RTS PTT does not support frequency control"
                        "trusdx_serial" -> "TruSDX serial CAT command failed"
                        else -> ""
                    }
                    if (detail.isNotBlank()) {
                        Log.w(TAG, "Failed to set frequency to $frequencyHz Hz: $detail")
                    } else {
                        Log.w(TAG, "Failed to set frequency to $frequencyHz Hz")
                    }
                    if (!rigCtlErrorShown && rigControlMode != "rts_ptt") {
                        if (detail.isNotBlank()) {
                            broadcastError("Rig control failed: $detail")
                        } else {
                            broadcastError("Rig control communication failed")
                        }
                        rigCtlErrorShown = true
                    }
                }
            }
        }.start()
    }

    companion object {
        private const val TAG = "JS8EngineService"
        private const val PREF_AUTOREPLY_ENABLED = "autoreply_enabled"
        private const val PREF_RELAY_ENABLED = "relay_enabled"
        private const val PREF_TX_SUBMODE = "tx_submode"
        private const val PREF_MY_INFO = "my_info"
        private const val PREF_MY_STATUS = "my_status"
        private const val PREF_PSK_REPORTER = "psk_reporter"
        private const val PREF_TRUSDX_DIAGNOSTICS_ENABLED = "trusdx_diagnostics_enabled"
        private const val HEARD_LIMIT = 4
        private const val HEARD_WINDOW_MS = 15 * 60 * 1000L
        private val HEARD_EXCLUDE_TOKENS = setOf("CQ", "HB", "HEARTBEAT", "ALLCALL", "@ALLCALL")
        private const val RELAY_BUFFER_TIMEOUT_MS = 90_000L
        private const val RELAY_FREQUENCY_TOLERANCE_HZ = 10.0f
        private const val RELAY_EOM_MARKER = "\u2662"
        private const val SUBMODE_NORMAL = 0
        private const val SUBMODE_FAST = 1
        private const val SUBMODE_TURBO = 2
        private const val SUBMODE_SLOW = 4
        private const val RX_SUBMODES_BASE = SUBMODE_NORMAL or SUBMODE_FAST or SUBMODE_SLOW

        // Heartbeat sub-band, matching the desktop app
        private const val HB_SUBBAND_LOW_HZ = 500
        private const val HB_SUBBAND_HIGH_HZ = 1000

        // Outgoing HB ACK: "MYCALL: TARGET HEARTBEAT SNR +NN"
        private val heartbeatAckTxRegex =
            Regex("^\\s*[A-Z0-9/]+:\\s+\\S+\\s+HEARTBEAT\\s+SNR\\b", RegexOption.IGNORE_CASE)
        private const val CHECKSUM_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ+-./?"
        private const val CHECKSUM_BASE = 41

        // Actions
        const val ACTION_START = "com.js8call.example.ACTION_START"
        const val ACTION_STOP = "com.js8call.example.ACTION_STOP"
        const val ACTION_SWITCH_AUDIO_DEVICE = "com.js8call.example.ACTION_SWITCH_AUDIO_DEVICE"
        const val ACTION_SET_FREQUENCY = "com.js8call.example.ACTION_SET_FREQUENCY"
        const val ACTION_SET_TX_OFFSET = "com.js8call.example.ACTION_SET_TX_OFFSET"
        const val ACTION_ENGINE_STATE = "com.js8call.example.ACTION_ENGINE_STATE"
        const val ACTION_DECODE = "com.js8call.example.ACTION_DECODE"
        const val ACTION_SPECTRUM = "com.js8call.example.ACTION_SPECTRUM"
        const val ACTION_DECODE_STARTED = "com.js8call.example.ACTION_DECODE_STARTED"
        const val ACTION_DECODE_FINISHED = "com.js8call.example.ACTION_DECODE_FINISHED"
        const val ACTION_AUDIO_DEVICE = "com.js8call.example.ACTION_AUDIO_DEVICE"
        const val ACTION_ERROR = "com.js8call.example.ACTION_ERROR"
        const val ACTION_TRANSMIT_MESSAGE = "com.js8call.example.ACTION_TRANSMIT_MESSAGE"
        const val ACTION_TX_STATE = "com.js8call.example.ACTION_TX_STATE"
        const val ACTION_TX_SENT = "com.js8call.example.ACTION_TX_SENT"
        const val ACTION_TX_PROGRESS = "com.js8call.example.ACTION_TX_PROGRESS"
        const val ACTION_RADIO_FREQUENCY = "com.js8call.example.ACTION_RADIO_FREQUENCY"
        const val ACTION_MESSAGE_RECEIVED = "com.js8call.example.ACTION_MESSAGE_RECEIVED"
        const val ACTION_QUEUE_TX = "com.js8call.example.ACTION_QUEUE_TX"
        const val ACTION_TIME_SYNC_ONCE = "com.js8call.example.ACTION_TIME_SYNC_ONCE"
        const val ACTION_SET_TIME_DRIFT = "com.js8call.example.ACTION_SET_TIME_DRIFT"
        const val ACTION_TIME_DRIFT = "com.js8call.example.ACTION_TIME_DRIFT"
        const val ACTION_RIG_STATUS = "com.js8call.example.ACTION_RIG_STATUS"

        // Engine states
        const val STATE_STOPPED = "stopped"
        const val STATE_STARTING = "starting"
        const val STATE_RUNNING = "running"
        const val STATE_ERROR = "error"

        // Extras
        const val EXTRA_STATE = "state"
        const val EXTRA_UTC = "utc"
        const val EXTRA_SNR = "snr"
        const val EXTRA_DT = "dt"
        const val EXTRA_FREQ = "freq"
        const val EXTRA_TEXT = "text"
        const val EXTRA_TYPE = "type"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_MODE = "mode"
        const val EXTRA_DRIFT_MS = "drift_ms"
        const val EXTRA_TIME_DRIFT_MS = "time_drift_ms"
        const val EXTRA_RIG_CONNECTED = "rig_connected"
        const val EXTRA_BINS = "bins"
        const val EXTRA_BIN_HZ = "bin_hz"
        const val EXTRA_POWER_DB = "power_db"
        const val EXTRA_PEAK_DB = "peak_db"
        const val EXTRA_SUBMODES = "submodes"
        const val EXTRA_COUNT = "count"
        const val EXTRA_AUDIO_DEVICE = "audio_device"
        const val EXTRA_AUDIO_DEVICE_ID = "audio_device_id"
        const val EXTRA_ERROR_MESSAGE = "error_message"
        const val EXTRA_FREQUENCY_HZ = "frequency_hz"
        const val EXTRA_TX_TEXT = "tx_text"
        const val EXTRA_TX_DIRECTED = "tx_directed"
        const val EXTRA_TX_SUBMODE = "tx_submode"
        const val EXTRA_TX_FREQ_HZ = "tx_freq_hz"
        const val EXTRA_TX_OFFSET_HZ = "tx_offset_hz"
        const val EXTRA_TX_DELAY_S = "tx_delay_s"
        const val EXTRA_TX_FORCE_IDENTIFY = "tx_force_identify"
        const val EXTRA_TX_FORCE_DATA = "tx_force_data"
        const val EXTRA_TX_STATE = "tx_state"
        const val EXTRA_TX_FRAME_INDEX = "tx_frame_index"
        const val EXTRA_TX_FRAME_COUNT = "tx_frame_count"
        const val EXTRA_TX_SENT_TEXT = "tx_sent_text"
        const val EXTRA_TX_SENT_FREQ = "tx_sent_freq"
        const val EXTRA_RADIO_FREQUENCY_HZ = "radio_frequency_hz"
        const val EXTRA_MESSAGE_FROM = "message_from"
        const val EXTRA_MESSAGE_TEXT = "message_text"
        const val EXTRA_MESSAGE_SNR = "message_snr"
        const val EXTRA_MESSAGE_FREQ = "message_freq"
        const val EXTRA_MESSAGE_RELAY_PATH = "message_relay_path"
        const val EXTRA_MESSAGE_CONVERSATION_ID = "message_conversation_id"
        const val EXTRA_QUEUE_TX_TEXT = "queue_tx_text"
        const val EXTRA_QUEUE_TX_DIRECTED = "queue_tx_directed"
        const val EXTRA_QUEUE_TX_PRIORITY = "queue_tx_priority"
        const val PREF_TRANSMIT_MODE = "transmit_mode"
        const val PREF_HEARTBEAT_INTERVAL = "heartbeat_interval"
        const val PREF_TIME_SYNC_AUTO = "time_sync_auto"
        const val PREF_TIME_DRIFT_MS = "time_drift_ms"
        const val PREF_LOG_MESSAGES = "log_messages_to_file"
        const val RIG_MODE_USB = "USB"
        const val RIG_MODE_PKTUSB = "PKTUSB"

        const val TX_STATE_QUEUED = "queued"
        const val TX_STATE_STARTED = "started"
        const val TX_STATE_FINISHED = "finished"
        const val TX_STATE_FAILED = "failed"

        const val DEFAULT_AUDIO_FREQUENCY_HZ = 1500.0
        const val TRUSDX_RX_SAMPLE_RATE_HZ = 7812
        const val TRUSDX_TX_SAMPLE_RATE_HZ = 11520
        const val TRUSDX_AUDIO_SERIAL_ID = -2001
        const val TRUSDX_AUDIO_SPEAKER_ID = -2002
        private const val TRUSDX_RX_FRAME_QUEUE_MAX = 512
        private const val TRUSDX_RX_WATCHDOG_INTERVAL_MS = 1200L
        private const val TRUSDX_RX_STALL_REARM_NS = 2_000_000_000L
        private const val TRUSDX_RX_REARM_COOLDOWN_NS = 2_500_000_000L
        private const val TRUSDX_RX_KEEPALIVE_INTERVAL_MS = 2000L
        private const val PTT_LEAD_TIME_MS = 500
        private const val PTT_COMMAND_RETRIES = 1
        private const val TX_PREKEY_MONITOR_INTERVAL_MS = 25L
        private const val TX_MONITOR_INTERVAL_MS = 250L
        private const val RIG_STATUS_POLL_INTERVAL_MS = 2000L
        private const val SCO_START_WAIT_INTERVAL_MS = 200L
        private const val SCO_START_MAX_ATTEMPTS = 10
        private const val SCO_SILENCE_CHECK_DELAY_MS = 2000L
        private const val SCO_SILENCE_THRESHOLD = 5
        private const val SCO_MAX_RESTARTS = 3

        private const val CHANNEL_ID = "js8call_service"
        private const val CHANNEL_ID_MESSAGES = "js8call_messages"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_ID_MESSAGE_BASE = 1000
    }
}
