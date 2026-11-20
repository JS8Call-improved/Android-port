package com.js8call.example.ui

import android.widget.PopupMenu
import androidx.preference.PreferenceManager
import com.google.android.material.button.MaterialButton
import com.js8call.example.service.JS8EngineService
import com.js8call.example.util.Js8Speed

/**
 * Toolbar chip showing the current TX speed; tapping opens the picker.
 * The choice is saved to the same preference the engine reads.
 */
object SpeedChip {

    private val speeds = Js8Speed.entries

    fun bind(chip: MaterialButton) {
        chip.setText(current(chip).label)
        chip.setOnClickListener { showPicker(chip) }
    }

    private fun showPicker(chip: MaterialButton) {
        val popup = PopupMenu(chip.context, chip)
        speeds.forEachIndexed { index, speed -> popup.menu.add(0, index, index, speed.label) }
        popup.menu.setGroupCheckable(0, true, true)
        popup.menu.getItem(speeds.indexOf(current(chip))).isChecked = true

        popup.setOnMenuItemClickListener { item ->
            val speed = speeds[item.itemId]
            PreferenceManager.getDefaultSharedPreferences(chip.context)
                .edit()
                .putInt(JS8EngineService.PREF_TX_SUBMODE, speed.submode)
                .apply()
            chip.setText(speed.label)
            true
        }
        popup.show()
    }

    private fun current(chip: MaterialButton): Js8Speed {
        val submode = PreferenceManager.getDefaultSharedPreferences(chip.context)
            .getInt(JS8EngineService.PREF_TX_SUBMODE, JS8EngineService.SUBMODE_NORMAL)
        return Js8Speed.fromSubmode(submode)
    }
}
