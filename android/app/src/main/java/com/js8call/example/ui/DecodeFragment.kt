package com.js8call.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.snackbar.Snackbar
import com.js8call.example.R

/**
 * Fragment showing list of decoded messages.
 */
class DecodeFragment : Fragment() {

    private lateinit var viewModel: DecodeViewModel
    private lateinit var transmitViewModel: TransmitViewModel
    private lateinit var adapter: DecodeListAdapter

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var clearFab: FloatingActionButton

    private var myGroups: Set<String> = emptySet()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_decodes, container, false)
    }

    override fun onStart() {
        super.onStart()
        loadGroups()
    }

    private fun loadGroups() {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
        val groupsStr = prefs.getString("my_groups", "") ?: ""
        myGroups = groupsStr.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()
        if (::adapter.isInitialized) {
            adapter.myGroups = myGroups
            adapter.notifyDataSetChanged()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize ViewModel (shared with MonitorFragment)
        viewModel = ViewModelProvider(requireActivity())[DecodeViewModel::class.java]
        transmitViewModel = ViewModelProvider(requireActivity())[TransmitViewModel::class.java]

        // Find views
        recyclerView = view.findViewById(R.id.decodes_recycler_view)
        emptyText = view.findViewById(R.id.empty_text)
        clearFab = view.findViewById(R.id.clear_fab)

        // Set up RecyclerView
        adapter = DecodeListAdapter().apply {
            onItemClick = { decode ->
                showDecodeOptions(decode)
            }
            onItemLongClick = { decode ->
                copyToClipboard(decode.text)
                true
            }
        }
        recyclerView.adapter = adapter

        // Set up FAB
        clearFab.setOnClickListener {
            confirmClearDecodes()
        }

        // Observe decodes
        viewModel.decodes.observe(viewLifecycleOwner) { decodes ->
            adapter.submitList(decodes)

            // Show/hide empty state
            if (decodes.isEmpty()) {
                emptyText.visibility = View.VISIBLE
                recyclerView.visibility = View.GONE
            } else {
                emptyText.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE

                // Auto-scroll to top for new messages
                recyclerView.scrollToPosition(0)
            }
        }
    }

    private fun showDecodeOptions(decode: com.js8call.example.model.DecodedMessage) {
        val options = arrayOf(
            getString(R.string.decodes_copy),
            getString(R.string.decodes_reply),
            getString(R.string.decodes_sync_time, decode.driftMs)
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(decode.formattedTime())
            .setItems(options) { _, which ->
                when (which) {
                    0 -> copyToClipboard(decode.text)
                    1 -> replyToMessage(decode)
                    2 -> syncTimeToSignal(decode)
                }
            }
            .show()
    }

    private fun syncTimeToSignal(decode: com.js8call.example.model.DecodedMessage) {
        val intent = android.content.Intent(requireContext(), com.js8call.example.service.JS8EngineService::class.java).apply {
            action = com.js8call.example.service.JS8EngineService.ACTION_SET_TIME_DRIFT
            putExtra(com.js8call.example.service.JS8EngineService.EXTRA_TIME_DRIFT_MS, decode.driftMs.toLong())
        }
        requireContext().startService(intent)
        android.widget.Toast.makeText(
            requireContext(),
            getString(R.string.decodes_sync_time_applied, decode.driftMs),
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("JS8 Message", text)
        clipboard.setPrimaryClip(clip)

        Snackbar.make(requireView(), "Copied to clipboard", Snackbar.LENGTH_SHORT).show()
    }

    private fun replyToMessage(decode: com.js8call.example.model.DecodedMessage) {
        val callsign = extractCallsign(decode.text)
        if (callsign.isNullOrBlank()) {
            Snackbar.make(requireView(), "No callsign found", Snackbar.LENGTH_SHORT).show()
            return
        }
        transmitViewModel.setDirectedTo(callsign)
        val bottomNav = activity?.findViewById<NavigationBarView>(R.id.bottom_navigation)
        if (bottomNav != null) {
            bottomNav.selectedItemId = R.id.navigation_transmit
        } else {
            findNavController().navigate(R.id.navigation_transmit)
        }
    }

    private fun extractCallsign(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return null

        val firstToken = trimmed.split(Regex("\\s+"), limit = 2)[0]
        val callsign = firstToken.trimEnd(':').uppercase()
        return if (isCallsignPrefix(callsign)) callsign else null
    }

    private fun isCallsignPrefix(token: String): Boolean {
        if (token.isBlank()) return false
        if (myGroups.contains(token)) return true
        if (token.startsWith("@")) return false
        if (token in setOf("CQ", "HB", "HEARTBEAT", "ALLCALL", "@ALLCALL")) return false
        val callsignRegex = Regex("^[A-Z0-9/]{3,12}$")
        if (!callsignRegex.matches(token)) return false
        if (!token.any { it.isLetter() }) return false
        if (!token.any { it.isDigit() }) return false
        return true
    }

    private fun confirmClearDecodes() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Clear All Decodes?")
            .setMessage("This will remove all decoded messages from the list.")
            .setPositiveButton("Clear") { _, _ ->
                viewModel.clearDecodes()
                Snackbar.make(requireView(), "Decodes cleared", Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
