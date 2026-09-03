package com.js8call.example.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.js8call.example.R
import com.js8call.example.model.EngineState
import com.js8call.example.model.MonitorStatus
import com.js8call.example.model.TransmitState
import com.js8call.example.service.JS8EngineService

/**
 * Fragment for monitoring/receiving screen.
 * Shows the waterfall and the status strip below it.
 */
class MonitorFragment : Fragment() {

    private lateinit var viewModel: MonitorViewModel
    private lateinit var transmitViewModel: TransmitViewModel

    private lateinit var waterfallView: WaterfallView
    private lateinit var stateDot: ImageView
    private lateinit var statusText: TextView
    private lateinit var frequencyButton: MaterialButton
    private lateinit var powerSwitch: MaterialSwitch
    private lateinit var telemetryText: TextView

    // Frequency management
    private var frequencyEntries = listOf<String>()
    private var frequencyValues = listOf<String>()
    private var appliedFrequencyIndex = -1

    private var lastLabelRes = 0
    private var lastColorRes = 0

    // Set true while the switch is moved in code, so the listener can tell a
    // state update apart from a tap.
    private var applyingSwitchState = false

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
        stateDot = view.findViewById(R.id.state_dot)
        statusText = view.findViewById(R.id.status_text)
        frequencyButton = view.findViewById(R.id.frequency_button)
        powerSwitch = view.findViewById(R.id.power_switch)
        telemetryText = view.findViewById(R.id.telemetry_text)

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

        loadFrequencies()
        observeViewModel()

        powerSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (applyingSwitchState) return@setOnCheckedChangeListener
            if (isChecked) startMonitoring() else stopMonitoring()
        }

        frequencyButton.setOnClickListener { showFrequencyDialog() }
        view.findViewById<MaterialButton>(R.id.monitor_overflow)
            .setOnClickListener { showOverflowMenu(it) }
    }

    private fun observeViewModel() {
        // Observe status
        viewModel.status.observe(viewLifecycleOwner) { status ->
            renderState()
            renderTelemetry(status)
            waterfallView.txOffsetHz = status.txOffsetHz

            // Show error if present
            status.errorMessage?.let { error ->
                Snackbar.make(requireView(), error, Snackbar.LENGTH_LONG).show()
                viewModel.clearError()
            }
        }

        transmitViewModel.txState.observe(viewLifecycleOwner) { renderState() }

        viewModel.radioFrequency.observe(viewLifecycleOwner) { frequencyHz ->
            if (frequencyHz != null && frequencyHz > 0) {
                updateFrequencyFromRadio(frequencyHz)
            }
        }
    }

    private fun renderState() {
        val engineState = viewModel.status.value?.state ?: EngineState.STOPPED
        val transmitting = engineState == EngineState.RUNNING &&
            transmitViewModel.txState.value == TransmitState.TRANSMITTING

        val (labelRes, colorRes) = when {
            transmitting -> R.string.monitor_state_transmitting to R.color.tx_button_transmitting
            engineState == EngineState.RUNNING -> R.string.monitor_state_receiving to R.color.snr_excellent
            engineState == EngineState.STARTING -> R.string.monitor_state_starting to R.color.tx_button_queued
            engineState == EngineState.ERROR -> R.string.monitor_state_error to R.color.message_failed
            else -> R.string.monitor_state_off to R.color.message_pending
        }
        // The status LiveData ticks at the spectrum rate; skip unchanged paints
        if (labelRes == lastLabelRes && colorRes == lastColorRes) return
        lastLabelRes = labelRes
        lastColorRes = colorRes

        statusText.setText(labelRes)
        stateDot.imageTintList =
            ColorStateList.valueOf(ContextCompat.getColor(requireContext(), colorRes))

        val shouldBeOn = engineState == EngineState.RUNNING || engineState == EngineState.STARTING
        applyingSwitchState = true
        powerSwitch.isChecked = shouldBeOn
        applyingSwitchState = false
    }

    private fun renderTelemetry(status: MonitorStatus) {
        val drift = if (status.timeDriftMs != 0L) {
            String.format("%+d ms", status.timeDriftMs)
        } else {
            "0 ms"
        }
        val offsetAndDrift = getString(
            R.string.monitor_telemetry,
            status.txOffsetHz.toInt(),
            drift
        )
        // A stopped engine reads no power, and a leading placeholder just adds noise
        telemetryText.text = if (status.powerDb != 0f) {
            getString(R.string.monitor_telemetry_power, status.powerDb, offsetAndDrift)
        } else {
            offsetAndDrift
        }
    }

    private fun showOverflowMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.monitor_overflow, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_audio_device -> {
                    showAudioDeviceDialog()
                    true
                }
                R.id.action_time_sync -> {
                    armTimeSync()
                    true
                }
                R.id.action_time_drift_reset -> {
                    resetTimeDrift()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun armTimeSync() {
        val intent = Intent(requireContext(), JS8EngineService::class.java).apply {
            action = JS8EngineService.ACTION_TIME_SYNC_ONCE
        }
        requireContext().startService(intent)
        Snackbar.make(requireView(), getString(R.string.monitor_time_sync_armed), Snackbar.LENGTH_SHORT).show()
    }

    private fun resetTimeDrift() {
        val intent = Intent(requireContext(), JS8EngineService::class.java).apply {
            action = JS8EngineService.ACTION_SET_TIME_DRIFT
            putExtra(JS8EngineService.EXTRA_TIME_DRIFT_MS, 0L)
        }
        requireContext().startService(intent)
    }

    private fun startMonitoring() {
        // Starting an engine that is already up tears down its audio capture
        val state = viewModel.status.value?.state
        if (state == EngineState.RUNNING || state == EngineState.STARTING) return

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
                // The switch moved on the tap that asked for the permission
                renderState()
                Snackbar.make(
                    requireView(),
                    R.string.permission_audio_denied,
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showAudioDeviceDialog() {
        val running = viewModel.isRunning.value == true
        AudioDevices.showPicker(requireContext(), running) { device ->
            // A stopped engine has nothing to move, so say what will be used
            val message = if (running) {
                getString(R.string.monitor_audio_device_switching, device.name)
            } else {
                getString(R.string.monitor_audio_device_selected, device.name)
            }
            Snackbar.make(requireView(), message, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun loadFrequencies() {
        val baseEntries = resources.getStringArray(R.array.js8_frequency_entries)
        val baseValues = resources.getStringArray(R.array.js8_frequency_values)

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
        val entries = presetPairs.map { it.first }.toMutableList()
        val values = presetPairs.map { it.second }.toMutableList()

        val customFrequencyHz = customFrequencyMhz.toDoubleOrNull()?.let { mhz ->
            if (mhz > 0) (mhz * 1_000_000.0).toLong() else null
        }

        if (customFrequencyHz != null && (rigType != "qmx_serial" || isQmxFrequencySupported(customFrequencyHz, qmxBands))) {
            entries.add("Custom - ${customFrequencyMhz}MHz")
            values.add(customFrequencyHz.toString())
        }

        frequencyEntries = entries
        frequencyValues = values

        // Preserve the established 20 m default for normal radios. QMX may not
        // support 20 m in every hardware profile, so use its first valid preset.
        val defaultFrequency = if (rigType == "qmx_serial") {
            values.firstOrNull() ?: "14078000"
        } else {
            baseValues.getOrNull(3) ?: "14078000"
        }
        val savedFrequency = prefs.getString("last_frequency", defaultFrequency) ?: defaultFrequency
        val savedIndex = values.indexOf(savedFrequency).takeIf { it >= 0 }
            ?: values.indexOf(defaultFrequency).takeIf { it >= 0 }
            ?: 0

        applyFrequency(savedIndex, persist = false)
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

    private fun applyFrequency(index: Int, persist: Boolean = true) {
        appliedFrequencyIndex = index
        frequencyButton.text = shortFrequencyLabel(frequencyEntries[index])
        if (persist) {
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
                .edit().putString("last_frequency", frequencyValues[index]).apply()
        }
    }

    /** "20m - 14.078 MHz" is too wide for the strip, so show it as "20m · 14.078". */
    private fun shortFrequencyLabel(entry: String): String {
        return entry.removeSuffix(" MHz").replace(" - ", " · ")
    }

    private fun showFrequencyDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.monitor_radio_frequency)
            .setSingleChoiceItems(
                frequencyEntries.toTypedArray(),
                appliedFrequencyIndex
            ) { dialog, which ->
                dialog.dismiss()
                selectFrequency(which)
            }
            .show()
    }

    private fun selectFrequency(position: Int) {
        if (position == appliedFrequencyIndex) return
        if (position < 0 || position >= frequencyValues.size) return
        applyFrequency(position)

        val frequencyHz = frequencyValues[position].toLongOrNull() ?: return
        android.util.Log.d("MonitorFragment", "Frequency selected: ${frequencyEntries[position]} ($frequencyHz Hz)")

        // Check if rig control is enabled
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
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

    private fun updateFrequencyFromRadio(frequencyHz: Long) {
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

        // Update the label if we found a reasonable match (within 100 kHz)
        if (closestDiff < 100000) {
            if (appliedFrequencyIndex == closestIndex) {
                return
            }
            applyFrequency(closestIndex)

            android.util.Log.i("MonitorFragment", "Set frequency to ${frequencyEntries[closestIndex]} based on radio frequency $frequencyHz Hz")
            Snackbar.make(requireView(), "Radio tuned to ${frequencyEntries[closestIndex]}", Snackbar.LENGTH_SHORT).show()
        } else {
            android.util.Log.d("MonitorFragment", "Radio frequency $frequencyHz Hz doesn't match any preset (closest: ${frequencyValues[closestIndex]} Hz, diff: $closestDiff Hz)")
        }
    }

    companion object {
        private const val REQUEST_AUDIO_PERMISSION = 1
    }
}
