package com.js8call.example

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import androidx.preference.PreferenceManager
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.snackbar.Snackbar
import com.js8call.example.model.EngineState
import com.js8call.example.service.JS8EngineService
import com.js8call.example.ui.DecodeViewModel
import com.js8call.example.ui.MessagesViewModel
import com.js8call.example.ui.MonitorViewModel
import com.js8call.example.ui.TransmitViewModel

class MainActivity : AppCompatActivity() {

    private lateinit var bottomNav: NavigationBarView
    private lateinit var decodeViewModel: DecodeViewModel
    private lateinit var monitorViewModel: MonitorViewModel
    private var spectrumBroadcastCount: Long = 0
    private lateinit var messagesViewModel: MessagesViewModel
    private lateinit var transmitViewModel: TransmitViewModel

    private val decodeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                JS8EngineService.ACTION_DECODE -> {
                    val utc = intent.getIntExtra(JS8EngineService.EXTRA_UTC, 0)
                    val snr = intent.getIntExtra(JS8EngineService.EXTRA_SNR, 0)
                    val dt = intent.getFloatExtra(JS8EngineService.EXTRA_DT, 0f)
                    val freq = intent.getFloatExtra(JS8EngineService.EXTRA_FREQ, 0f)
                    val text = intent.getStringExtra(JS8EngineService.EXTRA_TEXT) ?: ""
                    val type = intent.getIntExtra(JS8EngineService.EXTRA_TYPE, 0)
                    val quality = intent.getFloatExtra(JS8EngineService.EXTRA_QUALITY, 0f)
                    val mode = intent.getIntExtra(JS8EngineService.EXTRA_MODE, 0)
                    val driftMs = intent.getIntExtra(JS8EngineService.EXTRA_DRIFT_MS, 0)
                    decodeViewModel.addDecode(utc, snr, dt, freq, text, type, quality, mode, driftMs)
                    monitorViewModel.updateSnr(snr)
                }
                JS8EngineService.ACTION_QUEUE_TX -> {
                    val text = intent.getStringExtra(JS8EngineService.EXTRA_QUEUE_TX_TEXT) ?: return
                    val directed = intent.getStringExtra(JS8EngineService.EXTRA_QUEUE_TX_DIRECTED)
                    val priority = intent.getIntExtra(JS8EngineService.EXTRA_QUEUE_TX_PRIORITY, 0)
                    transmitViewModel.queueMessage(text, directed, priority, clearComposed = false)
                    // Trigger queue processing
                    processNextTxIfIdle()
                }
                JS8EngineService.ACTION_TX_STATE -> {
                    val state = intent.getStringExtra(JS8EngineService.EXTRA_TX_STATE)
                    when (state) {
                        JS8EngineService.TX_STATE_FINISHED, JS8EngineService.TX_STATE_FAILED -> {
                            // Update ViewModel state first
                            if (state == JS8EngineService.TX_STATE_FINISHED) {
                                transmitViewModel.transmissionComplete()
                            } else {
                                transmitViewModel.transmissionFailed()
                            }
                            // Process next item in queue after TX completes
                            processNextTxIfIdle()
                        }
                        JS8EngineService.TX_STATE_QUEUED -> {
                            transmitViewModel.setQueued()
                        }
                        JS8EngineService.TX_STATE_STARTED -> {
                            transmitViewModel.startTransmitting()
                        }
                    }
                }
                ACTION_PROCESS_TX_QUEUE -> {
                    android.util.Log.d("MainActivity", "Received ACTION_PROCESS_TX_QUEUE")
                    processNextTxIfIdle()
                }
            }
        }
    }

    private val monitorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                JS8EngineService.ACTION_ENGINE_STATE -> {
                    val state = when (intent.getStringExtra(JS8EngineService.EXTRA_STATE)) {
                        JS8EngineService.STATE_RUNNING -> EngineState.RUNNING
                        JS8EngineService.STATE_STOPPED -> EngineState.STOPPED
                        JS8EngineService.STATE_STARTING -> EngineState.STARTING
                        JS8EngineService.STATE_ERROR -> EngineState.ERROR
                        else -> EngineState.ERROR
                    }
                    monitorViewModel.updateState(state)
                    if (state == EngineState.RUNNING) {
                        processNextTxIfIdle()
                    }
                }
                JS8EngineService.ACTION_SPECTRUM -> {
                    val bins = intent.getFloatArrayExtra(JS8EngineService.EXTRA_BINS)
                    val binHz = intent.getFloatExtra(JS8EngineService.EXTRA_BIN_HZ, 0f)
                    val powerDb = intent.getFloatExtra(JS8EngineService.EXTRA_POWER_DB, 0f)
                    val peakDb = intent.getFloatExtra(JS8EngineService.EXTRA_PEAK_DB, 0f)
                    if (bins != null) {
                        spectrumBroadcastCount += 1
                        if (spectrumBroadcastCount % 20L == 0L) {
                            android.util.Log.i(
                                "JS8MainActivity",
                                "Spectrum broadcasts: count=$spectrumBroadcastCount bins=${bins.size} binHz=$binHz power=$powerDb peak=$peakDb"
                            )
                        }
                        monitorViewModel.updateSpectrum(bins, binHz, powerDb, peakDb)
                    }
                }
                JS8EngineService.ACTION_AUDIO_DEVICE -> {
                    val deviceName = intent.getStringExtra(JS8EngineService.EXTRA_AUDIO_DEVICE) ?: "Unknown"
                    monitorViewModel.updateAudioDevice(deviceName)
                }
                JS8EngineService.ACTION_ERROR -> {
                    val message = intent.getStringExtra(JS8EngineService.EXTRA_ERROR_MESSAGE) ?: "Unknown error"
                    monitorViewModel.onError(message)
                }
                JS8EngineService.ACTION_RADIO_FREQUENCY -> {
                    val frequencyHz = intent.getLongExtra(JS8EngineService.EXTRA_RADIO_FREQUENCY_HZ, 0L)
                    if (frequencyHz > 0) {
                        monitorViewModel.updateFrequency(frequencyHz)
                    }
                }
                JS8EngineService.ACTION_TIME_DRIFT -> {
                    val driftMs = intent.getLongExtra(JS8EngineService.EXTRA_TIME_DRIFT_MS, 0L)
                    monitorViewModel.updateTimeDrift(driftMs)
                }
                JS8EngineService.ACTION_RIG_STATUS -> {
                    val connected = intent.getBooleanExtra(JS8EngineService.EXTRA_RIG_CONNECTED, false)
                    monitorViewModel.updateRigConnected(connected)
                }
            }
        }
    }

    companion object {
        private const val REQUEST_RECORD_AUDIO = 1
        private const val REQUEST_BLUETOOTH_CONNECT = 3
        private const val REQUEST_LOCATION = 2
        private const val PREF_KEEP_SCREEN_ON = "keep_screen_on"
        const val ACTION_PROCESS_TX_QUEUE = "com.js8call.example.ACTION_PROCESS_TX_QUEUE"
    }

    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == PREF_KEEP_SCREEN_ON) {
            applyKeepScreenOn(prefs.getBoolean(PREF_KEEP_SCREEN_ON, false))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Set up bottom navigation
        bottomNav = findViewById(R.id.bottom_navigation)
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        bottomNav.setupWithNavController(navController)
        navController.addOnDestinationChangedListener { _, destination, _ ->
            if (destination.id == R.id.navigation_conversation) {
                bottomNav.menu.findItem(R.id.navigation_messages).isChecked = true
            }
        }

        decodeViewModel = ViewModelProvider(this)[DecodeViewModel::class.java]
        monitorViewModel = ViewModelProvider(this)[MonitorViewModel::class.java]
        messagesViewModel = ViewModelProvider(this)[MessagesViewModel::class.java]
        transmitViewModel = ViewModelProvider(this)[TransmitViewModel::class.java]
        decodeViewModel.loadPersistedDecodesIfEnabled()

        // Observe unread count for badge on Messages tab
        messagesViewModel.totalUnreadCount.observe(this) { count ->
            val badge = bottomNav.getOrCreateBadge(R.id.navigation_messages)
            if (count > 0) {
                badge.isVisible = true
                badge.number = count
            } else {
                badge.isVisible = false
            }
        }

        // Check permissions
        checkPermissions()

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        applyKeepScreenOn(prefs.getBoolean(PREF_KEEP_SCREEN_ON, false))
    }

    override fun onStart() {
        super.onStart()
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        applyKeepScreenOn(prefs.getBoolean(PREF_KEEP_SCREEN_ON, false))
        val filter = IntentFilter().apply {
            addAction(JS8EngineService.ACTION_DECODE)
            addAction(JS8EngineService.ACTION_QUEUE_TX)
            addAction(JS8EngineService.ACTION_TX_STATE)
            addAction(ACTION_PROCESS_TX_QUEUE)
        }
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(decodeReceiver, filter)

        val monitorFilter = IntentFilter().apply {
            addAction(JS8EngineService.ACTION_ENGINE_STATE)
            addAction(JS8EngineService.ACTION_SPECTRUM)
            addAction(JS8EngineService.ACTION_AUDIO_DEVICE)
            addAction(JS8EngineService.ACTION_ERROR)
            addAction(JS8EngineService.ACTION_RADIO_FREQUENCY)
            addAction(JS8EngineService.ACTION_TIME_DRIFT)
            addAction(JS8EngineService.ACTION_RIG_STATUS)
        }
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(monitorReceiver, monitorFilter)
    }

    override fun onStop() {
        PreferenceManager.getDefaultSharedPreferences(this)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
        LocalBroadcastManager.getInstance(this)
            .unregisterReceiver(decodeReceiver)
        LocalBroadcastManager.getInstance(this)
            .unregisterReceiver(monitorReceiver)
        decodeViewModel.persistDecodesOnStop()
        if (isFinishing && !isChangingConfigurations) {
            stopEngineService()
        }
        super.onStop()
    }

    /**
     * Process the next message in the TX queue if not currently transmitting.
     */
    private fun processNextTxIfIdle() {
        // Don't send if already transmitting
        val state = transmitViewModel.txState.value
        android.util.Log.d("MainActivity", "processNextTxIfIdle: state=$state")
        if (state == com.js8call.example.model.TransmitState.TRANSMITTING) {
            android.util.Log.d("MainActivity", "processNextTxIfIdle: already transmitting, skipping")
            return
        }
        
        val nextMessage = transmitViewModel.getNextMessage()
        android.util.Log.d("MainActivity", "processNextTxIfIdle: nextMessage=$nextMessage")
        if (nextMessage == null) {
            android.util.Log.d("MainActivity", "processNextTxIfIdle: no message in queue")
            return
        }
        
        // Send to service for transmission
        android.util.Log.d("MainActivity", "processNextTxIfIdle: sending to service: text='${nextMessage.text}' directed=${nextMessage.directed}")
        val txIntent = Intent(this, JS8EngineService::class.java).apply {
            action = JS8EngineService.ACTION_TRANSMIT_MESSAGE
            putExtra(JS8EngineService.EXTRA_TX_TEXT, nextMessage.text)
            nextMessage.directed?.let { putExtra(JS8EngineService.EXTRA_TX_DIRECTED, it) }
            putExtra(JS8EngineService.EXTRA_TX_FREQ_HZ, transmitViewModel.getTxOffset().toDouble())
        }
        startService(txIntent)
    }

    private fun checkPermissions() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED -> {
                // Permission already granted
            }
            shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {
                // Show explanation
                Snackbar.make(
                    findViewById(android.R.id.content),
                    R.string.permission_audio_message,
                    Snackbar.LENGTH_LONG
                ).setAction("Grant") {
                    requestAudioPermission()
                }.show()
            }
            else -> {
                // Request permission
                requestAudioPermission()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasConnect = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            val hasScan = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
            when {
                hasConnect && hasScan -> {
                    // Permission already granted
                }
                shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT) ||
                    shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_SCAN) -> {
                    Snackbar.make(
                        findViewById(android.R.id.content),
                        "Bluetooth permission needed for device access",
                        Snackbar.LENGTH_LONG
                    ).setAction("Grant") {
                        requestBluetoothPermission()
                    }.show()
                }
                else -> {
                    requestBluetoothPermission()
                }
            }
        }
    }

    private fun requestAudioPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQUEST_RECORD_AUDIO
        )
    }

    private fun requestBluetoothPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            ),
            REQUEST_BLUETOOTH_CONNECT
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            REQUEST_RECORD_AUDIO -> {
                if (grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
                ) {
                    // Permission granted
                    Snackbar.make(
                        findViewById(android.R.id.content),
                        "Microphone permission granted",
                        Snackbar.LENGTH_SHORT
                    ).show()
                } else {
                    // Permission denied
                    Snackbar.make(
                        findViewById(android.R.id.content),
                        R.string.permission_audio_denied,
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            }
            REQUEST_BLUETOOTH_CONNECT -> {
                val granted = grantResults.isNotEmpty() &&
                    grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                if (granted) {
                    Snackbar.make(
                        findViewById(android.R.id.content),
                        "Bluetooth permission granted",
                        Snackbar.LENGTH_SHORT
                    ).show()
                } else {
                    Snackbar.make(
                        findViewById(android.R.id.content),
                        "Bluetooth permission denied; Bluetooth devices may not work",
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun applyKeepScreenOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun stopEngineService() {
        val intent = Intent(this, JS8EngineService::class.java).apply {
            action = JS8EngineService.ACTION_STOP
        }
        startService(intent)
    }
}
