package com.js8call.example.ui

import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import com.google.android.material.snackbar.Snackbar
import com.js8call.example.R
import com.js8call.example.util.CallsignValidator

/**
 * False, with a snackbar, when no callsign is set.
 */
internal fun Fragment.readyToSend(): Boolean {
    val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
    if (prefs.getString("callsign", "")?.isNotBlank() != true) {
        Snackbar.make(requireView(), R.string.error_callsign_required, Snackbar.LENGTH_LONG).show()
        return false
    }
    return true
}

/** Queues an undirected send; false when refused. */
internal fun Fragment.queueBroadcast(text: String): Boolean {
    if (!readyToSend()) return false
    ViewModelProvider(requireActivity())[TransmitViewModel::class.java].queueManualMessage(text)
    val running = ViewModelProvider(requireActivity())[MonitorViewModel::class.java].isRunning.value == true
    val queued = if (running) R.string.decodes_queued else R.string.decodes_queued_offline
    Snackbar.make(requireView(), getString(queued, text), Snackbar.LENGTH_SHORT).show()
    return true
}

/** Why [address] can't have a thread, or null when it can. A [heard] station skips the shape check. */
internal fun Fragment.threadAddressError(address: String, heard: Boolean = false): Int? {
    val ownCallsign = PreferenceManager.getDefaultSharedPreferences(requireContext())
        .getString("callsign", "").orEmpty()
    return when {
        CallsignValidator.matches(address, ownCallsign) -> R.string.messages_new_own
        heard || CallsignValidator.isAmateurCallsign(address) || CallsignValidator.isGroup(address) -> null
        else -> R.string.messages_new_invalid
    }
}
