package com.js8call.example.ui

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.js8call.example.R
import com.js8call.example.service.JS8EngineService

/**
 * The capture inputs the engine can listen on. The list, the stored selection
 * and the live switch live together here; screens only render the choice.
 */
object AudioDevices {

    data class Device(val id: Int, val name: String) {
        override fun toString(): String = name
    }

    /** True when rig audio arrives over the serial link instead of a microphone. */
    fun usesSerialAudio(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getString("rig_type", "none") == "trusdx_serial"

    /** The display name for a capture device, or null for types not offered. */
    fun nameFor(device: AudioDeviceInfo): String? = when (device.type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Internal Microphone"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
        AudioDeviceInfo.TYPE_USB_DEVICE -> device.productName?.toString() ?: "USB Audio Device"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB Audio Accessory"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth Headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth Audio"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "Line Input"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Digital Line Input"
        else -> null
    }

    /**
     * Inputs available right now, in the order they are offered.
     *
     * A TruSDX rig replaces the list: its audio arrives over the serial link,
     * so the phone's own inputs cannot carry it.
     */
    fun list(context: Context): List<Device> {
        if (usesSerialAudio(context)) {
            return listOf(
                Device(JS8EngineService.TRUSDX_AUDIO_SERIAL_ID, JS8EngineService.TRUSDX_AUDIO_SERIAL_NAME),
                Device(JS8EngineService.TRUSDX_AUDIO_SPEAKER_ID, JS8EngineService.TRUSDX_AUDIO_SPEAKER_NAME)
            )
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return listOf(Device(DEFAULT_DEVICE_ID, "Default Microphone"))
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = mutableListOf<Device>()
        for (device in audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (!device.isSource) continue
            val name = nameFor(device) ?: continue
            devices.add(Device(device.id, name))
        }

        if (devices.isEmpty()) {
            devices.add(Device(DEFAULT_DEVICE_ID, "Default Microphone"))
        }
        return devices
    }

    /**
     * The device the engine will capture from. A saved device that has since
     * been unplugged falls back to the first one available.
     */
    fun selected(context: Context): Device = selected(context, list(context))

    fun selectedName(context: Context): String = selected(context).name

    /** The same resolution against a list the caller already holds. */
    fun selected(context: Context, devices: List<Device>): Device {
        val savedId = PreferenceManager.getDefaultSharedPreferences(context)
            .getInt(PREF_SELECTED_ID, DEFAULT_DEVICE_ID)
        return devices.firstOrNull { it.id == savedId } ?: devices.first()
    }

    /**
     * Remember the choice, and move a live capture onto it. Returns true when
     * a switch request was dispatched; the service ignores a request for the
     * device it is already capturing on, since only it knows the active one.
     */
    fun select(context: Context, device: Device, engineRunning: Boolean): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (prefs.getInt(PREF_SELECTED_ID, DEFAULT_DEVICE_ID) != device.id) {
            prefs.edit().putInt(PREF_SELECTED_ID, device.id).apply()
        }

        if (!engineRunning) return false
        val intent = Intent(context, JS8EngineService::class.java).apply {
            action = JS8EngineService.ACTION_SWITCH_AUDIO_DEVICE
            putExtra(JS8EngineService.EXTRA_AUDIO_DEVICE_ID, device.id)
        }
        context.startService(intent)
        return true
    }

    /**
     * Show the picker. [onSelected] runs after the choice is stored, so the
     * caller only has to refresh whatever it shows the device on.
     */
    fun showPicker(
        context: Context,
        engineRunning: Boolean,
        onSelected: (Device) -> Unit
    ) {
        val devices = list(context)
        val current = selected(context, devices)
        val checked = devices.indexOf(current)

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.monitor_menu_audio_device)
            .setSingleChoiceItems(devices.map { it.name }.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                val device = devices[which]
                select(context, device, engineRunning)
                onSelected(device)
            }
            .show()
    }

    private const val PREF_SELECTED_ID = "last_audio_device_id"
    private const val DEFAULT_DEVICE_ID = -1
}
