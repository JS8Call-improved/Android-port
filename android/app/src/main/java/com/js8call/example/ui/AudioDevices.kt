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
 * The capture inputs the engine can listen on, and the picker both screens show.
 *
 * The Monitor strip and Settings offer the same choice, so the list, the stored
 * selection and the dialog live here rather than in either fragment.
 */
object AudioDevices {

    const val PREF_SELECTED_ID = "last_audio_device_id"

    data class Device(val id: Int, val name: String) {
        override fun toString(): String = name
    }

    /**
     * Inputs available right now, in the order they are offered.
     *
     * A TruSDX rig replaces the list: its audio arrives over the serial link,
     * so the phone's own inputs cannot carry it.
     */
    fun list(context: Context): List<Device> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (prefs.getString("rig_type", "none") == "trusdx_serial") {
            return listOf(
                Device(JS8EngineService.TRUSDX_AUDIO_SERIAL_ID, "TruSDX Serial"),
                Device(JS8EngineService.TRUSDX_AUDIO_SPEAKER_ID, "TruSDX Speaker")
            )
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return listOf(Device(DEFAULT_DEVICE_ID, "Default Microphone"))
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = mutableListOf<Device>()
        for (device in audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (!device.isSource) continue
            val name = when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Internal Microphone"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
                AudioDeviceInfo.TYPE_USB_DEVICE -> device.productName?.toString() ?: "USB Audio Device"
                AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB Audio Accessory"
                AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth Headset"
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth Audio"
                AudioDeviceInfo.TYPE_LINE_ANALOG -> "Line Input"
                AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Digital Line Input"
                else -> continue  // Skip unknown types
            }
            devices.add(Device(device.id, name))
        }

        if (devices.isEmpty()) {
            devices.add(Device(DEFAULT_DEVICE_ID, "Default Microphone"))
        }
        return devices
    }

    /**
     * The device the engine will capture from, resolved against what is
     * plugged in now. A saved device that has since been unplugged falls back
     * to the first one available.
     */
    fun selected(context: Context): Device? {
        val devices = list(context)
        if (devices.isEmpty()) return null
        val savedId = PreferenceManager.getDefaultSharedPreferences(context)
            .getInt(PREF_SELECTED_ID, DEFAULT_DEVICE_ID)
        return devices.firstOrNull { it.id == savedId } ?: devices.first()
    }

    fun selectedName(context: Context): String? = selected(context)?.name

    /**
     * Remember the choice, and move a live capture onto it.
     * A stopped engine reads the saved choice when it next starts.
     */
    fun select(context: Context, device: Device, engineRunning: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putInt(PREF_SELECTED_ID, device.id)
            .apply()

        if (!engineRunning) return
        val intent = Intent(context, JS8EngineService::class.java).apply {
            action = JS8EngineService.ACTION_SWITCH_AUDIO_DEVICE
            putExtra(JS8EngineService.EXTRA_AUDIO_DEVICE_ID, device.id)
        }
        context.startService(intent)
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
        if (devices.isEmpty()) return
        val current = selected(context)
        val checked = devices.indexOfFirst { it.id == current?.id }

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

    private const val DEFAULT_DEVICE_ID = -1
}
