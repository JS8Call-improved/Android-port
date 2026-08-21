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
import com.google.android.material.snackbar.Snackbar
import com.js8call.example.R

/**
 * Fragment showing list of decoded messages.
 */
open class DecodeFragment : Fragment() {

    protected open val layoutRes: Int = R.layout.fragment_decodes

    private lateinit var viewModel: DecodeViewModel
    private lateinit var transmitViewModel: TransmitViewModel
    private lateinit var adapter: DecodeListAdapter

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView
    private var clearFab: View? = null

    private var myGroups: Set<String> = emptySet()

    // Row that currently carries the live TX label, so it can be cleared
    // when a newer outgoing bubble arrives.
    private var labeledPosition = -1

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(layoutRes, container, false)
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
        // Pin content to the bottom, texting style
        (recyclerView.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)?.stackFromEnd = true
        // No cross-fade on rebinds; the TX label ticks every second
        (recyclerView.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)
            ?.supportsChangeAnimations = false

        // Set up the clear button (absent in the bare layout)
        clearFab?.setOnClickListener {
            confirmClearDecodes()
        }

        // Header quick actions (also absent in the bare layout; the All
        // activity thread has its own compose bar for these)
        view.findViewById<View>(R.id.cq_button)?.setOnClickListener {
            queueBroadcast("CQ CQ CQ")
        }
        view.findViewById<View>(R.id.heartbeat_button)?.setOnClickListener {
            queueBroadcast("HB")
        }
        view.findViewById<View>(R.id.open_all_activity_button)?.setOnClickListener {
            findNavController().navigate(R.id.navigation_everything)
        }

        // Observe decodes
        viewModel.decodes.observe(viewLifecycleOwner) { decodes ->
            // Stick to the newest message unless the user scrolled up to read history.
            val wasAtBottom = !recyclerView.canScrollVertically(1)
            val firstLoad = adapter.itemCount == 0

            // submitList diffs on a background thread; scroll in its commit
            // callback so the new row exists when the scroll runs.
            adapter.submitList(decodes) {
                // The newest-outgoing position may have shifted; move the TX label with it
                updateTxLabel()
                if (decodes.isNotEmpty() && (wasAtBottom || firstLoad)) {
                    if (firstLoad) {
                        recyclerView.scrollToPosition(decodes.size - 1)
                    } else {
                        recyclerView.smoothScrollToPosition(decodes.size - 1)
                    }
                }
            }

            // Show/hide empty state
            if (decodes.isEmpty()) {
                emptyText.visibility = View.VISIBLE
                recyclerView.visibility = View.GONE
            } else {
                emptyText.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
            }
        }

        // TX frame countdown and progress on the newest outgoing bubble
        transmitViewModel.txCountdownSeconds.observe(viewLifecycleOwner) { updateTxLabel() }
        transmitViewModel.txFrameProgress.observe(viewLifecycleOwner) { updateTxLabel() }
    }

    private fun updateTxLabel() {
        val countdown = transmitViewModel.txCountdownSeconds.value
        val progress = transmitViewModel.txFrameProgress.value
        val label = when {
            countdown == null -> null
            progress != null && progress.second > 1 ->
                getString(R.string.decodes_tx_countdown_frames, progress.first, progress.second, countdown)
            else -> getString(R.string.decodes_tx_countdown, countdown)
        }
        val position = adapter.lastOutgoingPosition()
        if (label == adapter.txLabel && position == labeledPosition) return
        adapter.txLabel = label
        // Clear the label off the bubble that used to be newest
        if (labeledPosition >= 0 && labeledPosition != position &&
            labeledPosition < adapter.itemCount
        ) {
            adapter.notifyItemChanged(labeledPosition, DecodeListAdapter.PAYLOAD_TX_LABEL)
        }
        if (position >= 0) {
            adapter.notifyItemChanged(position, DecodeListAdapter.PAYLOAD_TX_LABEL)
        }
        labeledPosition = if (label != null) position else -1
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
        // Open the DM thread with this station
        val bundle = Bundle().apply { putString("callsign", callsign) }
        findNavController().navigate(R.id.navigation_conversation, bundle)
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

    private fun queueBroadcast(text: String) {
        val monitorViewModel = ViewModelProvider(requireActivity())[MonitorViewModel::class.java]
        if (monitorViewModel.status.value?.state != com.js8call.example.model.EngineState.RUNNING) {
            Snackbar.make(requireView(), R.string.decodes_start_first, Snackbar.LENGTH_SHORT).show()
            return
        }
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
        if (prefs.getString("callsign", "")?.isNotBlank() != true) {
            Snackbar.make(requireView(), R.string.error_callsign_required, Snackbar.LENGTH_LONG).show()
            return
        }
        transmitViewModel.queueMessage(text, directed = null, priority = 1)
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(requireContext())
            .sendBroadcast(android.content.Intent(com.js8call.example.MainActivity.ACTION_PROCESS_TX_QUEUE))
        Snackbar.make(requireView(), getString(R.string.decodes_queued, text), Snackbar.LENGTH_SHORT).show()
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
