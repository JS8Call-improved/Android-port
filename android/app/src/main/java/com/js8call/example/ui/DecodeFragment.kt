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
import com.js8call.example.MainActivity
import com.js8call.example.R

/**
 * Fragment showing list of decoded messages.
 */
open class DecodeFragment : Fragment() {

    protected open val layoutRes: Int = R.layout.fragment_decodes

    private lateinit var viewModel: DecodeViewModel
    private lateinit var adapter: DecodeListAdapter

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView

    private var myGroups: Set<String> = emptySet()
    private var followNewest = true
    private var userScrolling = false

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

        // Find views
        recyclerView = view.findViewById(R.id.decodes_recycler_view)
        emptyText = view.findViewById(R.id.empty_text)

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
        (recyclerView.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)?.stackFromEnd = true
        followNewest = true
        userScrolling = false
        // Only a drag changes this; a burst lands while the last smooth scroll still runs
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) userScrolling = true
                if (newState == RecyclerView.SCROLL_STATE_IDLE && userScrolling) {
                    userScrolling = false
                    followNewest = !rv.canScrollVertically(1)
                }
            }
        })

        // Header buttons are absent in the bare layout
        view.findViewById<View>(R.id.clear_button)?.setOnClickListener {
            confirmClearDecodes()
        }
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
            val firstLoad = adapter.itemCount == 0
            if (firstLoad) followNewest = true

            // The new row only exists once the diff commits
            adapter.submitList(decodes) {
                // Never pull the list out from under a drag or fling
                if (decodes.isNotEmpty() && followNewest && !userScrolling) {
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
    }

    private fun showDecodeOptions(decode: com.js8call.example.model.DecodedMessage) {
        val options = listOfNotNull(
            getString(R.string.decodes_copy),
            if (decode.outgoing) null else getString(R.string.decodes_reply),
            if (decode.outgoing) null else getString(R.string.decodes_sync_time, decode.driftMs)
        ).toTypedArray()

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
        val callsign = viewModel.senderCallsign(decode.text)
        val error = callsign?.let { threadAddressError(it, heard = true) }
        if (callsign == null || error != null) {
            val message = if (error != null) getString(error) else "No callsign found"
            Snackbar.make(requireView(), message, Snackbar.LENGTH_SHORT).show()
            return
        }
        (requireActivity() as MainActivity).openThread(callsign)
    }

    fun confirmClearDecodes() {
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
