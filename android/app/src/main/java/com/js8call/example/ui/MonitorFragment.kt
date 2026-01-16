package com.js8call.example.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
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
    private lateinit var audioDeviceSpinner: Spinner
    private lateinit var frequencySpinner: Spinner
    private lateinit var startStopButton: Button
    private lateinit var monitorVersionText: TextView

    // Audio device management
    private var audioDeviceAdapter: ArrayAdapter<AudioDeviceItem>? = null
    private var availableDevices = mutableListOf<AudioDeviceItem>()
    private var isUpdatingSpinner = false
    private var userInitiatedAudioSelection = false
    private var lastSelectedAudioDeviceId = -1

    // Frequency management
    private var isUpdatingFrequencySpinner = false

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

    }

    override fun onPause() {
        super.onPause()
        userInitiatedAudioSelection = false
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
        // Check permission
        if (!hasAudioPermission()) {
            requestAudioPermission()
            return
        }

        // Update view model
        viewModel.startMonitoring()

        // Start service with selected audio device
        val intent = Intent(requireContext(), JS8EngineService::class.java).apply {
            action = JS8EngineService.ACTION_START
            // Pass selected device ID if any
            if (availableDevices.isNotEmpty()) {
                val selectedPos = audioDeviceSpinner.selectedItemPosition
                if (selectedPos >= 0 && selectedPos < availableDevices.size) {
                    val selectedDevice = availableDevices[selectedPos]
                    putExtra(JS8EngineService.EXTRA_AUDIO_DEVICE_ID, selectedDevice.id)
                    android.util.Log.d("MonitorFragment",
                        "Starting with device: ${selectedDevice.name} (ID: ${selectedDevice.id})")
                }
            }
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
                if (isUpdatingSpinner) return
                if (position < 0 || position >= availableDevices.size) return

                val selectedDevice = availableDevices[position]
                android.util.Log.d("MonitorFragment", "Audio device selected: ${selectedDevice.name} (ID: ${selectedDevice.id})")

                // Only switch if engine is running
                if (viewModel.isRunning.value == true) {
                    if (selectedDevice.id == lastSelectedAudioDeviceId) return
                    lastSelectedAudioDeviceId = selectedDevice.id
                    val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
                    prefs.edit().putInt(PREF_LAST_AUDIO_DEVICE_ID, selectedDevice.id).apply()
                    switchAudioDevice(selectedDevice.id)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                // Do nothing
            }
        }

        // Populate with available devices
        refreshAudioDevices()
    }

    private fun refreshAudioDevices() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // Fallback for older versions
            availableDevices.clear()
            availableDevices.add(AudioDeviceItem(-1, "Default Microphone"))
            audioDeviceAdapter?.notifyDataSetChanged()
            return
        }

        val audioManager = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)

        availableDevices.clear()

        for (device in devices) {
            if (!device.isSource) continue
            val deviceName = when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Internal Microphone"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
                AudioDeviceInfo.TYPE_USB_DEVICE -> {
                    device.productName?.toString() ?: "USB Audio Device"
                }
                AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB Audio Accessory"
                AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth Headset"
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth Audio"
                AudioDeviceInfo.TYPE_LINE_ANALOG -> "Line Input"
                AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Digital Line Input"
                else -> continue  // Skip unknown types
            }

            availableDevices.add(AudioDeviceItem(device.id, deviceName))
            android.util.Log.d("MonitorFragment", "Found audio device: $deviceName (ID: ${device.id})")
        }

        // Add default option if no devices found
        if (availableDevices.isEmpty()) {
            availableDevices.add(AudioDeviceItem(-1, "Default Microphone"))
        }

        audioDeviceAdapter?.notifyDataSetChanged()

        if (availableDevices.isNotEmpty()) {
            val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
            val savedDeviceId = prefs.getInt(PREF_LAST_AUDIO_DEVICE_ID, -1)
            val selectedIndex = availableDevices.indexOfFirst { it.id == savedDeviceId }
                .takeIf { it >= 0 } ?: 0
            isUpdatingSpinner = true
            audioDeviceSpinner.setSelection(selectedIndex)
            isUpdatingSpinner = false
            lastSelectedAudioDeviceId = availableDevices[selectedIndex].id
        }
    }

    private fun switchAudioDevice(deviceId: Int) {
        // Send intent to service to switch audio device
        val intent = Intent(requireContext(), JS8EngineService::class.java).apply {
            action = JS8EngineService.ACTION_SWITCH_AUDIO_DEVICE
            putExtra(JS8EngineService.EXTRA_AUDIO_DEVICE_ID, deviceId)
        }
        requireContext().startService(intent)

        Snackbar.make(requireView(), "Switching audio device...", Snackbar.LENGTH_SHORT).show()
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
            isUpdatingFrequencySpinner = true
            frequencySpinner.setSelection(closestIndex)
            isUpdatingFrequencySpinner = false

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

        val frequencyEntries = baseEntries.toMutableList()
        val frequencyValues = baseValues.toMutableList()

        val customFrequencyHz = customFrequencyMhz.toDoubleOrNull()?.let { mhz ->
            if (mhz > 0) (mhz * 1_000_000.0).toLong() else null
        }

        if (customFrequencyHz != null) {
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

        val defaultFrequency = baseValues.getOrNull(3) ?: "14078000"
        val savedFrequency = prefs.getString("last_frequency", defaultFrequency) ?: defaultFrequency
        val savedIndex = frequencyValues.indexOf(savedFrequency).takeIf { it >= 0 }
            ?: frequencyValues.indexOf(defaultFrequency).takeIf { it >= 0 }
            ?: 0

        // Set initial selection
        isUpdatingFrequencySpinner = true
        frequencySpinner.setSelection(savedIndex)
        isUpdatingFrequencySpinner = false

        // Set up selection listener
        frequencySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isUpdatingFrequencySpinner) return
                if (position < 0 || position >= frequencyValues.size) return

                val frequencyHz = frequencyValues[position].toLongOrNull() ?: return
                android.util.Log.d("MonitorFragment", "Frequency selected: ${frequencyEntries[position]} ($frequencyHz Hz)")

                // Save frequency preference
                prefs.edit().putString("last_frequency", frequencyValues[position]).apply()

                // Check if rig control is enabled
                val rigControlEnabled = prefs.getBoolean("rig_control_enabled", false)
                val rigType = prefs.getString("rig_type", "none")

                if (rigControlEnabled && (rigType == "network" || rigType == "hamlib_usb")) {
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

    /**
     * Data class for audio device items in spinner.
     */
    private data class AudioDeviceItem(
        val id: Int,
        val name: String
    ) {
        override fun toString(): String = name
    }

    companion object {
        private const val REQUEST_AUDIO_PERMISSION = 1
        private const val PREF_LAST_AUDIO_DEVICE_ID = "last_audio_device_id"
    }
}
