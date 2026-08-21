package com.js8call.example.ui

import android.widget.PopupMenu
import androidx.preference.PreferenceManager
import com.google.android.material.button.MaterialButton
import com.js8call.example.R

/**
 * Toolbar chip showing the current TX speed; tapping opens the picker.
 * The choice is saved to the same preference the engine reads.
 */
object SpeedChip {

    fun bind(chip: MaterialButton) {
        chip.text = currentLabel(chip)
        chip.setOnClickListener { showPicker(chip) }
    }

    private fun showPicker(chip: MaterialButton) {
        val popup = PopupMenu(chip.context, chip)
        popup.menuInflater.inflate(R.menu.compose_speed_menu, popup.menu)

        val checkedItem = when (currentSubmode(chip)) {
            TransmitViewModel.SUBMODE_SLOW -> R.id.speed_slow
            TransmitViewModel.SUBMODE_FAST -> R.id.speed_fast
            TransmitViewModel.SUBMODE_TURBO -> R.id.speed_turbo
            else -> R.id.speed_normal
        }
        popup.menu.findItem(checkedItem)?.isChecked = true

        popup.setOnMenuItemClickListener { item ->
            val submode = when (item.itemId) {
                R.id.speed_slow -> TransmitViewModel.SUBMODE_SLOW
                R.id.speed_normal -> TransmitViewModel.SUBMODE_NORMAL
                R.id.speed_fast -> TransmitViewModel.SUBMODE_FAST
                R.id.speed_turbo -> TransmitViewModel.SUBMODE_TURBO
                else -> return@setOnMenuItemClickListener false
            }
            PreferenceManager.getDefaultSharedPreferences(chip.context)
                .edit()
                .putInt(TransmitViewModel.PREF_TX_SUBMODE, submode)
                .apply()
            chip.text = currentLabel(chip)
            true
        }
        popup.show()
    }

    private fun currentSubmode(chip: MaterialButton): Int {
        return PreferenceManager.getDefaultSharedPreferences(chip.context)
            .getInt(TransmitViewModel.PREF_TX_SUBMODE, TransmitViewModel.SUBMODE_NORMAL)
    }

    private fun currentLabel(chip: MaterialButton): String {
        val res = when (currentSubmode(chip)) {
            TransmitViewModel.SUBMODE_SLOW -> R.string.tx_speed_slow
            TransmitViewModel.SUBMODE_FAST -> R.string.tx_speed_fast
            TransmitViewModel.SUBMODE_TURBO -> R.string.tx_speed_turbo
            else -> R.string.tx_speed_normal
        }
        return chip.context.getString(res)
    }
}
