package com.js8call.example.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.snackbar.Snackbar
import com.js8call.example.R
import com.js8call.example.model.EngineState
import com.js8call.example.service.JS8EngineService

/**
 * Fragment for monitoring/receiving screen.
 * Shows waterfall display and engine status.
 */
class MonitorFragment : Fragment() {

    private lateinit var viewModel: MonitorViewModel
    private lateinit var transmitViewModel: TransmitViewModel

    private lateinit var waterfallView: WaterfallView
    private lateinit var statusText: TextView
    private lateinit var snrValue: TextView
    private lateinit var powerValue: TextView
    private lateinit var txOffsetValue: TextView
    private lateinit var timeDriftValue: TextView
    private lateinit var timeSyncButton: Button
    private lateinit var timeDriftResetButton: Button
    private lateinit var audioDeviceSpinner: Spinner
    private lateinit var frequencySpinner: Spinner
    private lateinit var startStopButton: Button
    private lateinit var monitorVersionText: TextView

    // Audio device management
    private var audioDeviceAdapter: ArrayAdapter<AudioDevices.Device>? = null
    private var availableDevices = mutableListOf<AudioDevices.Device>()
    private var userInitiatedAudioSelection = false

    // Frequency management
    // Spinner position last applied programmatically; onItemSelected skips it
    // because setSelection() fires the listener asynchronously.
    private var appliedFrequencyIndex = -1

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_monitor, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize ViewModels
        viewModel = ViewModelProvider(requireActivity())[MonitorViewModel::class.java]
        transmitViewModel = ViewModelProvider(requireActivity())[TransmitViewModel::class.java]

        // Find views
        waterfallView = view.findViewById(R.id.waterfall_view)
        statusText = view.findViewById(R.id.status_text)
        snrValue = view.findViewById(R.id.snr_value)
        powerValue = view.findViewById(R.id.power_value)
        txOffsetValue = view.findViewById(R.id.tx_offset_value)
        timeDriftValue = view.findViewById(R.id.time_drift_value)
        timeSyncButton = view.findViewById(R.id.time_sync_button)
        timeDriftResetButton = view.findViewById(R.id.time_drift_reset_button)
        monitorVersionText = view.findViewById(R.id.monitor_version)
        audioDeviceSpinner = view.findViewById(R.id.audio_device_spinner)
        frequencySpinner = view.findViewById(R.id.frequency_spinner)
        startStopButton = view.findViewById(R.id.start_stop_button)

        // Set version text dynamically from package info
        val versionName = try {
            requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
        } catch (e: PackageManager.NameNotFoundException) {
            "unknown"
        }
        monitorVersionText.text = "Version: $versionName"

        // Set up waterfall offset callback
        waterfallView.bindRenderer(viewModel.getWaterfallRenderer())
        waterfallView.onOffsetChanged = { offsetHz ->
            viewModel.setTxOffset(offsetHz)
            transmitViewModel.setTxOffset(offsetHz)
            waterfallView.txOffsetHz = offsetHz

            // Broadcast offset to service for autoreplies
            val intent = Intent(requireContext(), JS8EngineService::class.java).apply {
                action = JS8EngineService.ACTION_SET_TX_OFFSET
                putExtra(JS8EngineService.EXTRA_TX_OFFSET_HZ, offsetHz)
            }
            requireContext().startService(intent)
        }

        // Set up audio device spinner
        setupAudioDeviceSpinner()

        // Set up frequency spinner
        setupFrequencySpinner()

        // Set up observers
        observeViewModel()

        // Set up click listeners
        startStopButton.setOnClickListener {
            toggleMonitoring()
        }

        timeSyncButton.setOnClickListener {
            val intent = Intent(requireContext(), JS8EngineService::class.java).apply {
                action = JS8EngineService.ACTION_TIME_SYNC_ONCE
            }
            requireContext().startService(intent)
            Snackbar.make(requireView(), getString(R.string.monitor_time_sync_armed), Snackbar.LENGTH_SHORT).show()
        }

        timeDriftResetButton.setOnClickListener {
            val intent = Intent(requireContext(), JS8EngineService::class.java).apply {
                action = JS8EngineService.ACTION_SET_TIME_DRIFT
                putExtra(JS8EngineService.EXTRA_TIME_DRIFT_MS, 0L)
            }
            requireContext().startService(intent)
        }

    }

    override fun onPause() {
        super.onPause()
        userInitiatedAudioSelection = false
    }

    override fun onResume() {
        super.onResume()
        refreshAudioDevices()
    }

    override fun onDestroyView() {
        super.onDestroyView()
    }

    private fun observeViewModel() {
        // Observe status
        viewModel.status.observe(viewLifecycleOwner) { status ->
            updateStatus(status.state)

            // Update SNR
            snrValue.text = if (status.snr != 0) {
                getString(R.string.format_snr, status.snr)
            } else {
                "--"
            }

            // Update power
            powerValue.text = if (status.powerDb != 0f) {
                String.format("%.1f dB", status.powerDb)
            } else {
                "--"
            }

            // Update TX offset
            txOffsetValue.text = "${status.txOffsetHz.toInt()} Hz"
            waterfallView.txOffsetHz = status.txOffsetHz

            // Update time drift
            timeDriftValue.text = if (status.timeDriftMs != 0L) {
                String.format("%+d ms", status.timeDriftMs)
            } else {
                "0 ms"
            }

            // Show error if present
            status.errorMessage?.let { error ->
                Snackbar.make(requireView(), error, Snackbar.LENGTH_LONG).show()
                viewModel.clearError()
            }
        }

        // Observe running state
        viewModel.isRunning.observe(viewLifecycleOwner) { isRunning ->
            updateButtonState(isRunning)
        }

        viewModel.radioFrequency.observe(viewLifecycleOwner) { frequencyHz ->
            if (frequencyHz != null && frequencyHz > 0) {
                updateFrequencyFromRadio(frequencyHz)
            }
        }
    }

    private fun updateStatus(state: EngineState) {
        statusText.text = when (state) {
            EngineState.STOPPED -> getString(R.string.monitor_status_stopped)
            EngineState.STARTING -> getString(R.string.monitor_status_starting)
            EngineState.RUNNING -> getString(R.string.monitor_status_running)
            EngineState.ERROR -> "ERROR"
        }
    }

    private fun updateButtonState(isRunning: Boolean) {
        if (isRunning) {
            startStopButton.text = getString(R.string.monitor_stop)
            startStopButton.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_media_pause, 0, 0, 0)
        } else {
            startStopButton.text = getString(R.string.monitor_start)
            startStopButton.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_media_play, 0, 0, 0)
        }
    }

    private fun toggleMonitoring() {
        if (viewModel.isRunning.value == true) {
            stopMonitoring()
        } else {
            startMonitoring()
        }
    }

    private fun startMonitoring() {
        val skipMicPermission = AudioDevices.usesSerialAudio(requireContext())

        // Check permission
        if (!skipMicPermission && !hasAudioPermission()) {
            requestAudioPermission()
            return
        }

        // Update view model
        viewModel.startMonitoring()

        // Start service with selected audio device
        val intent = Intent(requireContext(), JS8EngineService::class.java).apply {
            action = JS8EngineService.ACTION_START
            val device = AudioDevices.selected(requireContext())
            putExtra(JS8EngineService.EXTRA_AUDIO_DEVICE_ID, device.id)
            android.util.Log.d("MonitorFragment",
                "Starting with device: ${device.name} (ID: ${device.id})")
        }
        ContextCompat.startForegroundService(requireContext(), intent)
    }

    private fun stopMonitoring() {
        // Update view model
        viewModel.stopMonitoring()

        // Stop service
        val intent = Intent(requireContext(), JS8EngineService::class.java).apply {
            action = JS8EngineService.ACTION_STOP
        }
        requireContext().startService(intent)
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestAudioPermission() {
        requestPermissions(
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQUEST_AUDIO_PERMISSION
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_AUDIO_PERMISSION) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                // Permission granted, try starting again
                startMonitoring()
            } else {
                Snackbar.make(
                    requireView(),
                    R.string.permission_audio_denied,
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun setupAudioDeviceSpinner() {
        // Create adapter
        audioDeviceAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            availableDevices
        )
        audioDeviceAdapter?.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        audioDeviceSpinner.adapter = audioDeviceAdapter

        audioDeviceSpinner.setOnTouchListener { _, _ ->
            userInitiatedAudioSelection = true
            false
        }
        audioDeviceSpinner.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                userInitiatedAudioSelection = false
            }
        }

        // Set up selection listener
        audioDeviceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val userInitiated = userInitiatedAudioSelection
                userInitiatedAudioSelection = false
                if (!userInitiated) return
                if (position < 0 || position >= availableDevices.size) return

                val selectedDevice = availableDevices[position]
                android.util.Log.d("MonitorFragment", "Audio device selected: ${selectedDevice.name} (ID: ${selectedDevice.id})")

                // Stored even while stopped, so the next start uses the pick.
                if (AudioDevices.select(requireContext(), selectedDevice, viewModel.isRunning.value == true)) {
                    Snackbar.make(requireView(), "Switching audio device...", Snackbar.LENGTH_SHORT).show()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                // Do nothing
            }
        }
    }

    private fun refreshAudioDevices() {
        availableDevices.clear()
        availableDevices.addAll(AudioDevices.list(requireContext()))
        audioDeviceAdapter?.notifyDataSetChanged()
        val selected = AudioDevices.selected(requireContext(), availableDevices)
        audioDeviceSpinner.setSelection(availableDevices.indexOf(selected))
    }

    private fun updateFrequencyFromRadio(frequencyHz: Long) {
        val frequencyValues = resources.getStringArray(R.array.js8_frequency_values)
        val frequencyEntries = resources.getStringArray(R.array.js8_frequency_entries)

        // Find the closest matching frequency in our list
        var closestIndex = 0
        var closestDiff = Long.MAX_VALUE

        for (i in frequencyValues.indices) {
            val freq = frequencyValues[i].toLongOrNull() ?: continue
            val diff = kotlin.math.abs(freq - frequencyHz)
            if (diff < closestDiff) {
                closestDiff = diff
                closestIndex = i
            }
        }

        // Update spinner if we found a reasonable match (within 100 kHz)
        if (closestDiff < 100000) {
            val currentIndex = frequencySpinner.selectedItemPosition
            if (currentIndex == closestIndex) {
                return
            }
            appliedFrequencyIndex = closestIndex
            frequencySpinner.setSelection(closestIndex)

            val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
            prefs.edit().putString("last_frequency", frequencyValues[closestIndex]).apply()

            android.util.Log.i("MonitorFragment", "Set frequency to ${frequencyEntries[closestIndex]} based on radio frequency $frequencyHz Hz")
            Snackbar.make(requireView(), "Radio tuned to ${frequencyEntries[closestIndex]}", Snackbar.LENGTH_SHORT).show()
        } else {
            android.util.Log.d("MonitorFragment", "Radio frequency $frequencyHz Hz doesn't match any preset (closest: ${frequencyValues[closestIndex]} Hz, diff: $closestDiff Hz)")
        }
    }

    private fun setupFrequencySpinner() {
        // Get frequency arrays from resources
        val baseEntries = resources.getStringArray(R.array.js8_frequency_entries)
        val baseValues = resources.getStringArray(R.array.js8_frequency_values)

        // Load saved frequency preference
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
        val customFrequencyMhz = prefs.getString("custom_frequency_mhz", "")?.trim().orEmpty()

        val rigType = prefs.getString("rig_type", "none")
        val qmxBands = when (prefs.getString("qmx_band_profile", "low")) {
            "high" -> setOf("20m", "17m", "15m", "12m", "11m", "10m")
            "mixed" -> setOf("60m", "40m", "30m", "20m", "17m", "15m")
            else -> setOf("80m", "60m", "40m", "30m", "20m")
        }
        val presetPairs = baseEntries.indices.map { baseEntries[it] to baseValues[it] }
            .filter { rigType != "qmx_serial" || qmxBands.any { band -> it.first.startsWith(band) } }
        val frequencyEntries = presetPairs.map { it.first }.toMutableList()
        val frequencyValues = presetPairs.map { it.second }.toMutableList()

        val customFrequencyHz = customFrequencyMhz.toDoubleOrNull()?.let { mhz ->
            if (mhz > 0) (mhz * 1_000_000.0).toLong() else null
        }

        if (customFrequencyHz != null && (rigType != "qmx_serial" || isQmxFrequencySupported(customFrequencyHz, qmxBands))) {
            frequencyEntries.add("Custom - ${customFrequencyMhz}MHz")
            frequencyValues.add(customFrequencyHz.toString())
        }

        // Create adapter
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            frequencyEntries
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        frequencySpinner.adapter = adapter

        // Preserve the established 20 m default for normal radios. QMX may not
        // support 20 m in every hardware profile, so use its first valid preset.
        val defaultFrequency = if (rigType == "qmx_serial") {
            frequencyValues.firstOrNull() ?: "14078000"
        } else {
            baseValues.getOrNull(3) ?: "14078000"
        }
        val savedFrequency = prefs.getString("last_frequency", defaultFrequency) ?: defaultFrequency
        val savedIndex = frequencyValues.indexOf(savedFrequency).takeIf { it >= 0 }
            ?: frequencyValues.indexOf(defaultFrequency).takeIf { it >= 0 }
            ?: 0

        // Set initial selection
        appliedFrequencyIndex = savedIndex
        frequencySpinner.setSelection(savedIndex, false)

        // Set up selection listener
        frequencySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == appliedFrequencyIndex) return
                if (position < 0 || position >= frequencyValues.size) return
                appliedFrequencyIndex = position

                val frequencyHz = frequencyValues[position].toLongOrNull() ?: return
                android.util.Log.d("MonitorFragment", "Frequency selected: ${frequencyEntries[position]} ($frequencyHz Hz)")

                // Save frequency preference
                prefs.edit().putString("last_frequency", frequencyValues[position]).apply()

                // Check if rig control is enabled
                val rigControlEnabled = prefs.getBoolean("rig_control_enabled", false)
                val rigType = prefs.getString("rig_type", "none")

                if (rigControlEnabled && (rigType == "network" || rigType == "hamlib_usb" || rigType == "trusdx_serial" || rigType == "qmx_serial")) {
                    // Send frequency change to service
                    val intent = Intent(requireContext(), JS8EngineService::class.java).apply {
                        action = JS8EngineService.ACTION_SET_FREQUENCY
                        putExtra(JS8EngineService.EXTRA_FREQUENCY_HZ, frequencyHz)
                    }
                    requireContext().startService(intent)

                    Snackbar.make(requireView(), "Setting frequency to ${frequencyEntries[position]}", Snackbar.LENGTH_SHORT).show()
                } else if (rigControlEnabled && rigType == "rts_ptt") {
                    android.util.Log.d("MonitorFragment", "RTS PTT mode does not support frequency control")
                } else {
                    android.util.Log.d("MonitorFragment", "Rig control not enabled or not supported type, skipping frequency change")
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                // Do nothing
            }
        }
    }

    private fun isQmxFrequencySupported(frequencyHz: Long, bands: Set<String>): Boolean {
        val band = when (frequencyHz) {
            in 3_500_000L..4_000_000L -> "80m"
            in 5_000_000L..5_500_000L -> "60m"
            in 7_000_000L..7_300_000L -> "40m"
            in 10_000_000L..10_150_000L -> "30m"
            in 14_000_000L..14_350_000L -> "20m"
            in 18_068_000L..18_168_000L -> "17m"
            in 21_000_000L..21_450_000L -> "15m"
            in 24_890_000L..24_990_000L -> "12m"
            in 26_965_000L..27_405_000L -> "11m"
            in 28_000_000L..29_700_000L -> "10m"
            else -> return false
        }
        return band in bands
    }

    companion object {
        private const val REQUEST_AUDIO_PERMISSION = 1
    }
}
