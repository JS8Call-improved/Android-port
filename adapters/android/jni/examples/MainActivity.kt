package com.js8call.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.js8call.core.JS8AudioHelper
import com.js8call.core.JS8Engine
import com.js8call.core.UIThreadCallbackAdapter

/**
 * Example Android Activity showing JS8Call integration.
 *
 * This demonstrates:
 * - Requesting microphone permission
 * - Creating and configuring the JS8 engine
 * - Capturing audio and feeding to the engine
 * - Handling decode callbacks on the UI thread
 */
class MainActivity : AppCompatActivity() {

    private var engine: JS8Engine? = null
    private var audioHelper: JS8AudioHelper? = null
    private var isRunning = false

    private lateinit var statusText: TextView
    private lateinit var decodedText: TextView
    private lateinit var startStopButton: Button

    private val uiHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "JS8CallExample"
        private const val REQUEST_RECORD_AUDIO = 1
        private const val SAMPLE_RATE = 12000
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        decodedText = findViewById(R.id.decoded_text)
        startStopButton = findViewById(R.id.start_stop_button)

        startStopButton.setOnClickListener {
            if (isRunning) {
                stopEngine()
            } else {
                checkPermissionsAndStart()
            }
        }

        updateUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopEngine()
        engine?.close()
        engine = null
    }

    private fun checkPermissionsAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO
            )
        } else {
            startEngine()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startEngine()
            } else {
                statusText.text = "Microphone permission denied"
            }
        }
    }

    private fun startEngine() {
        try {
            // Create engine if needed
            if (engine == null) {
                val callbackHandler = UIThreadCallbackAdapter(
                    uiHandler,
                    createCallbackHandler()
                )

                engine = JS8Engine.create(
                    sampleRateHz = SAMPLE_RATE,
                    submodes = 0xFF,  // All submodes
                    callbackHandler = callbackHandler
                )
            }

            // Start engine
            if (engine?.start() == true) {
                // Create audio helper and start capture
                audioHelper = JS8AudioHelper(engine!!, SAMPLE_RATE)
                if (audioHelper?.startCapture() == true) {
                    isRunning = true
                    statusText.text = "Running - listening for signals..."
                    Log.i(TAG, "Engine started successfully")
                } else {
                    engine?.stop()
                    statusText.text = "Failed to start audio capture"
                    Log.e(TAG, "Failed to start audio capture")
                }
            } else {
                statusText.text = "Failed to start engine"
                Log.e(TAG, "Failed to start engine")
            }
        } catch (e: Exception) {
            statusText.text = "Error: ${e.message}"
            Log.e(TAG, "Error starting engine", e)
        }

        updateUI()
    }

    private fun stopEngine() {
        audioHelper?.stopCapture()
        audioHelper?.close()
        audioHelper = null

        engine?.stop()

        isRunning = false
        statusText.text = "Stopped"
        Log.i(TAG, "Engine stopped")

        updateUI()
    }

    private fun updateUI() {
        startStopButton.text = if (isRunning) "Stop" else "Start"
        startStopButton.isEnabled = true
    }

    private fun createCallbackHandler() = object : JS8Engine.CallbackHandler {
        override fun onDecoded(
            utc: Int,
            snr: Int,
            dt: Float,
            freq: Float,
            text: String,
            type: Int,
            quality: Float,
            mode: Int,
            driftMs: Int
        ) {
            // Update UI with decoded message
            val decoded = String.format(
                "%04d  %+3d dB  %+5.2f s  %7.1f Hz  %s",
                utc, snr, dt, freq, text
            )
            Log.i(TAG, "Decoded: $decoded")

            decodedText.text = "${decodedText.text}\n$decoded"

            // Limit text view to last 20 lines
            val lines = decodedText.text.split("\n")
            if (lines.size > 20) {
                decodedText.text = lines.takeLast(20).joinToString("\n")
            }
        }

        override fun onSpectrum(
            bins: FloatArray,
            binHz: Float,
            powerDb: Float,
            peakDb: Float
        ) {
            // TODO: Update waterfall display
            // For now, just update status with power level
            statusText.text = String.format(
                "Running - Power: %.1f dB (peak: %.1f dB)",
                powerDb, peakDb
            )
        }

        override fun onDecodeStarted(submodes: Int) {
            Log.d(TAG, "Decode started with submodes: $submodes")
        }

        override fun onDecodeFinished(count: Int) {
            Log.d(TAG, "Decode finished, $count messages decoded")
        }

        override fun onError(message: String) {
            Log.e(TAG, "Engine error: $message")
            statusText.text = "Error: $message"
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
    }
}
