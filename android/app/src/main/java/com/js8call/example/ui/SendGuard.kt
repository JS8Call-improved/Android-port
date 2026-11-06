package com.js8call.example.ui

import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import com.google.android.material.snackbar.Snackbar
import com.js8call.example.R

/**
 * False, with a snackbar, when a send would fail: monitoring off or no callsign set.
 */
internal fun Fragment.readyToSend(): Boolean {
    val monitorViewModel = ViewModelProvider(requireActivity())[MonitorViewModel::class.java]
    if (monitorViewModel.isRunning.value != true) {
        Snackbar.make(requireView(), R.string.decodes_start_first, Snackbar.LENGTH_SHORT).show()
        return false
    }
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
    Snackbar.make(requireView(), getString(R.string.decodes_queued, text), Snackbar.LENGTH_SHORT).show()
    return true
}
