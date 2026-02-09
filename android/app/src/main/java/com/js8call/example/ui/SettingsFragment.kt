package com.js8call.example.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.snackbar.Snackbar
import com.js8call.core.BluetoothSerialPortCatalog
import com.js8call.core.HamlibRigCatalog
import com.js8call.core.UsbSerialPortCatalog
import com.js8call.example.R
import java.util.Locale

/**
 * Fragment for app settings.
 */
class SettingsFragment : PreferenceFragmentCompat() {

    private val locationHandler = Handler(Looper.getMainLooper())
    private var pendingLocationListener: LocationListener? = null
    private var pendingLocationTimeout: Runnable? = null
    private var gridPreference: GridSquarePreference? = null
    private var pendingStoragePermissionEnable = false
    private var logPreference: SwitchPreferenceCompat? = null

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                requestGridLocation()
            } else {
                showGridError(R.string.permission_location_denied)
            }
        }

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                if (pendingStoragePermissionEnable) {
                    pendingStoragePermissionEnable = false
                    val pref = logPreference
                    val key = pref?.key ?: "log_messages_to_file"
                    pref?.isChecked = true
                    pref?.sharedPreferences
                        ?.edit()
                        ?.putBoolean(key, true)
                        ?.apply()
                }
            } else {
                pendingStoragePermissionEnable = false
                view?.let { Snackbar.make(it, R.string.permission_storage_denied, Snackbar.LENGTH_LONG).show() }
            }
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        val prefs = preferenceManager.sharedPreferences
        if (prefs != null && !prefs.contains("my_status")) {
            val statusPref = findPreference<EditTextPreference>("my_status")
            val versionName = try {
                requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
            } catch (e: PackageManager.NameNotFoundException) {
                "unknown"
            }
            statusPref?.text = "JS8Android-$versionName"
        }

        val customFrequencyPref = findPreference<EditTextPreference>("custom_frequency_mhz")
        customFrequencyPref?.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        customFrequencyPref?.setOnPreferenceChangeListener { preference, newValue ->
            val rawValue = newValue?.toString().orEmpty().trim()
            val normalizedValue = normalizeFrequencyMhz(rawValue)
            preference.summary = if (normalizedValue.isBlank()) {
                getString(R.string.settings_custom_frequency_summary)
            } else {
                getString(R.string.settings_custom_frequency_summary_format, normalizedValue)
            }
            preference.sharedPreferences
                ?.edit()
                ?.putString(preference.key, normalizedValue)
                ?.apply()
            false
        }
        val existingCustom = customFrequencyPref?.text.orEmpty().trim()
        if (customFrequencyPref != null) {
            val normalizedExisting = normalizeFrequencyMhz(existingCustom)
            customFrequencyPref.summary = if (normalizedExisting.isBlank()) {
                getString(R.string.settings_custom_frequency_summary)
            } else {
                getString(R.string.settings_custom_frequency_summary_format, normalizedExisting)
            }
        }

        val rigModelPref = findPreference<ListPreference>("rig_hamlib_model")
        if (rigModelPref != null) {
            val models = try {
                HamlibRigCatalog.listRigModels()
            } catch (_: Throwable) {
                emptyList()
            }

            val entries = if (models.isNotEmpty()) {
                models.map { it.label }.toTypedArray()
            } else {
                arrayOf(getString(R.string.settings_hamlib_model_none))
            }
            val values = if (models.isNotEmpty()) {
                models.map { it.id }.toTypedArray()
            } else {
                arrayOf("0")
            }
            rigModelPref.entries = entries
            rigModelPref.entryValues = values
        }

        val rigUsbPortPref = findPreference<ListPreference>("rig_hamlib_usb_port")
        if (rigUsbPortPref != null) {
            val usbPorts = try {
                UsbSerialPortCatalog.listPorts(requireContext())
            } catch (_: Throwable) {
                emptyList()
            }
            val btPorts = try {
                BluetoothSerialPortCatalog.listPorts(requireContext())
            } catch (_: Throwable) {
                emptyList()
            }

            val entries = ArrayList<String>()
            val values = ArrayList<String>()
            if (usbPorts.isEmpty() && btPorts.isEmpty()) {
                entries.add(getString(R.string.settings_hamlib_usb_port_none))
                values.add("auto")
            } else {
                entries.add(getString(R.string.settings_hamlib_usb_port_auto))
                values.add("auto")
                usbPorts.forEach { port ->
                    entries.add("USB ${port.label}")
                    values.add("android-usb:${port.deviceId}:${port.portIndex}")
                }
                btPorts.forEach { port ->
                    entries.add(port.label)
                    values.add("android-bt:${port.address}:${port.portIndex}")
                }
            }

            val currentValueRaw = prefs?.getString("rig_hamlib_usb_port", "auto") ?: "auto"
            val normalizedValue = normalizeSerialSelection(currentValueRaw, prefs, useLegacyFallback = true)
            if (normalizedValue != currentValueRaw) {
                prefs?.edit()?.putString("rig_hamlib_usb_port", normalizedValue)?.apply()
            }

            if (!values.contains(normalizedValue)) {
                entries.add(getString(R.string.settings_hamlib_usb_port_previous, normalizedValue))
                values.add(normalizedValue)
            }

            rigUsbPortPref.entries = entries.toTypedArray()
            rigUsbPortPref.entryValues = values.toTypedArray()
            rigUsbPortPref.value = normalizedValue

            rigUsbPortPref.setOnPreferenceChangeListener { _, newValue ->
                val selection = normalizeSerialSelection(
                    newValue?.toString().orEmpty(),
                    prefs,
                    useLegacyFallback = false
                )
                updateLegacyUsbPrefs(prefs, selection)
                true
            }
        }

        gridPreference = findPreference("grid")
        gridPreference?.onUpdateClickListener = { onGridUpdateRequested() }

        logPreference = findPreference("log_messages_to_file")
        logPreference?.setOnPreferenceChangeListener { _, newValue ->
            val enable = newValue as? Boolean ?: false
            if (enable && android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
                val granted = ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    pendingStoragePermissionEnable = true
                    storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    return@setOnPreferenceChangeListener false
                }
            }
            true
        }
    }

    override fun onStop() {
        cancelLocationRequest()
        super.onStop()
    }

    private fun onGridUpdateRequested() {
        val context = context ?: return
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            requestGridLocation()
        } else {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun requestGridLocation() {
        val context = context ?: return
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        cancelLocationRequest()

        val now = System.currentTimeMillis()
        val lastLocation = findRecentLocation(locationManager, now, LOCATION_MAX_AGE_MS)
        if (lastLocation != null) {
            applyGridFromLocation(lastLocation)
            return
        }

        val provider = when {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                LocationManager.GPS_PROVIDER
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (provider == null) {
            showGridError(R.string.error_location_update_failed)
            return
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                cancelLocationRequest()
                applyGridFromLocation(location)
            }

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) = Unit

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        pendingLocationListener = listener
        try {
            locationManager.requestLocationUpdates(
                provider,
                0L,
                0f,
                listener,
                Looper.getMainLooper()
            )
        } catch (_: SecurityException) {
            cancelLocationRequest()
            showGridError(R.string.error_location_update_failed)
            return
        }

        val timeout = Runnable {
            cancelLocationRequest()
            showGridError(R.string.error_location_update_failed)
        }
        pendingLocationTimeout = timeout
        locationHandler.postDelayed(timeout, LOCATION_TIMEOUT_MS)
    }

    private fun findRecentLocation(
        locationManager: LocationManager,
        nowMs: Long,
        maxAgeMs: Long
    ): Location? {
        val gps = getLastKnownIfFresh(
            locationManager,
            LocationManager.GPS_PROVIDER,
            nowMs,
            maxAgeMs
        )
        if (gps != null) return gps

        return getLastKnownIfFresh(
            locationManager,
            LocationManager.NETWORK_PROVIDER,
            nowMs,
            maxAgeMs
        )
    }

    private fun getLastKnownIfFresh(
        locationManager: LocationManager,
        provider: String,
        nowMs: Long,
        maxAgeMs: Long
    ): Location? {
        if (!locationManager.isProviderEnabled(provider)) return null
        val location = try {
            locationManager.getLastKnownLocation(provider)
        } catch (_: SecurityException) {
            null
        } ?: return null
        return if (nowMs - location.time <= maxAgeMs) location else null
    }

    private fun cancelLocationRequest() {
        pendingLocationTimeout?.let { locationHandler.removeCallbacks(it) }
        pendingLocationTimeout = null
        val listener = pendingLocationListener
        pendingLocationListener = null
        val context = context ?: return
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        listener?.let { locationManager.removeUpdates(it) }
    }

    private fun applyGridFromLocation(location: Location) {
        val grid = maidenheadFromLocation(location.latitude, location.longitude)
        gridPreference?.setGridValue(grid)
    }

    private fun showGridError(messageResId: Int) {
        val view = view ?: return
        Snackbar.make(view, messageResId, Snackbar.LENGTH_LONG).show()
    }

    private fun maidenheadFromLocation(latitude: Double, longitude: Double): String {
        var lon = -longitude
        var lat = latitude.coerceIn(-90.0, 90.0)
        if (lon < -180.0) lon += 360.0
        if (lon > 180.0) lon -= 360.0
        if (lon == 180.0) lon = 179.999999
        if (lat == 90.0) lat = 89.999999

        val lonMinutes = (180.0 - lon) * 60.0
        val latMinutes = (lat + 90.0) * 60.0

        val lonField = (lonMinutes / 1200.0).toInt().coerceIn(0, 17)
        val latField = (latMinutes / 600.0).toInt().coerceIn(0, 17)

        val lonFieldRemainder = lonMinutes - lonField * 1200.0
        val latFieldRemainder = latMinutes - latField * 600.0

        val lonSquare = (lonFieldRemainder / 120.0).toInt().coerceIn(0, 9)
        val latSquare = (latFieldRemainder / 60.0).toInt().coerceIn(0, 9)

        val lonSquareRemainder = lonFieldRemainder - lonSquare * 120.0
        val latSquareRemainder = latFieldRemainder - latSquare * 60.0

        val lonSub = (lonSquareRemainder / 5.0).toInt().coerceIn(0, 23)
        val latSub = (latSquareRemainder / 2.5).toInt().coerceIn(0, 23)

        val lonSubRemainder = lonSquareRemainder - lonSub * 5.0
        val latSubRemainder = latSquareRemainder - latSub * 2.5

        val lonExt = (lonSubRemainder / 0.5).toInt().coerceIn(0, 9)
        val latExt = (latSubRemainder / 0.25).toInt().coerceIn(0, 9)

        return buildString(8) {
            append(('A'.code + lonField).toChar())
            append(('A'.code + latField).toChar())
            append(('0'.code + lonSquare).toChar())
            append(('0'.code + latSquare).toChar())
            append(('A'.code + lonSub).toChar())
            append(('A'.code + latSub).toChar())
            append(('0'.code + lonExt).toChar())
            append(('0'.code + latExt).toChar())
        }.uppercase(Locale.US)
    }

    private fun normalizeSerialSelection(
        selection: String,
        prefs: android.content.SharedPreferences?,
        useLegacyFallback: Boolean
    ): String {
        val trimmed = selection.trim()
        if (trimmed.startsWith("android-usb:") || trimmed.startsWith("android-bt:")) {
            return trimmed
        }
        if (trimmed.isEmpty() || trimmed == "auto") {
            if (!useLegacyFallback) {
                return "auto"
            }
            val deviceId = prefs?.getString("rig_usb_device_id", "")?.toIntOrNull()
            val portIndex = prefs?.getString("rig_usb_port_index", "0")?.toIntOrNull() ?: 0
            return if (deviceId != null) {
                "android-usb:$deviceId:$portIndex"
            } else {
                "auto"
            }
        }
        val parts = trimmed.split(':', limit = 2)
        val deviceId = parts.firstOrNull()?.toIntOrNull()
        return if (deviceId != null) {
            val portIndex = parts.getOrNull(1)?.toIntOrNull() ?: 0
            "android-usb:$deviceId:$portIndex"
        } else {
            trimmed
        }
    }

    private fun updateLegacyUsbPrefs(
        prefs: android.content.SharedPreferences?,
        selection: String
    ) {
        val editor = prefs?.edit() ?: return
        if (selection.startsWith("android-usb:")) {
            val remainder = selection.removePrefix("android-usb:")
            val parts = remainder.split(':', limit = 2)
            val deviceId = parts.firstOrNull()?.toIntOrNull()
            val portIndex = parts.getOrNull(1)?.toIntOrNull() ?: 0
            if (deviceId != null) {
                editor.putString("rig_usb_device_id", deviceId.toString())
                editor.putString("rig_usb_port_index", portIndex.toString())
            }
        } else {
            editor.putString("rig_usb_device_id", "")
            editor.putString("rig_usb_port_index", "0")
        }
        editor.apply()
    }

    private fun normalizeFrequencyMhz(value: String): String {
        val trimmed = value.trim()
        val parsed = trimmed.toDoubleOrNull() ?: return ""
        if (parsed <= 0) return ""
        return if (parsed % 1.0 == 0.0) {
            parsed.toLong().toString()
        } else {
            String.format(Locale.US, "%.3f", parsed).trimEnd('0').trimEnd('.')
        }
    }

    private companion object {
        const val LOCATION_MAX_AGE_MS = 15 * 60 * 1000L
        const val LOCATION_TIMEOUT_MS = 20 * 1000L
    }
}
