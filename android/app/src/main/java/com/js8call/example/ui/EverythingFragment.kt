package com.js8call.example.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.snackbar.Snackbar
import com.js8call.example.MainActivity
import com.js8call.example.R

/**
 * The Everything thread: all band activity with a compose bar.
 * Sends here are undirected (free text, CQ, Heartbeat).
 */
class EverythingFragment : Fragment() {

    private lateinit var transmitViewModel: TransmitViewModel

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_everything, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        transmitViewModel = ViewModelProvider(requireActivity())[TransmitViewModel::class.java]

        view.findViewById<MaterialToolbar>(R.id.thread_toolbar).setNavigationOnClickListener {
            findNavController().navigateUp()
        }
        SpeedChip.bind(view.findViewById(R.id.speed_chip))
        view.findViewById<View>(R.id.clear_button).setOnClickListener { confirmClearDecodes() }

        ComposeBarController(
            root = view,
            commandMenuRes = R.menu.broadcast_command_menu,
            onSend = { text -> queueBroadcast(text) },
            onCommand = { text -> queueBroadcast(text, priority = 1) }
        )
    }

    private fun queueBroadcast(text: String, priority: Int = 0) {
        if (!hasCallsignConfigured()) {
            Snackbar.make(requireView(), R.string.error_callsign_required, Snackbar.LENGTH_LONG).show()
            return
        }
        transmitViewModel.queueMessage(text, directed = null, priority = priority)
        LocalBroadcastManager.getInstance(requireContext())
            .sendBroadcast(Intent(MainActivity.ACTION_PROCESS_TX_QUEUE))
    }

    private fun hasCallsignConfigured(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        return prefs.getString("callsign", "")?.isNotBlank() == true
    }

    private fun confirmClearDecodes() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Clear All Decodes?")
            .setMessage("This will remove all decoded messages from the list.")
            .setPositiveButton("Clear") { _, _ ->
                ViewModelProvider(requireActivity())[DecodeViewModel::class.java].clearDecodes()
                Snackbar.make(requireView(), "Decodes cleared", Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
