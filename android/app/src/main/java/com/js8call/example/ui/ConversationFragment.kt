package com.js8call.example.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.js8call.example.R

/**
 * Fragment showing a single conversation with chat bubbles.
 */
class ConversationFragment : Fragment() {

    private lateinit var viewModel: MessagesViewModel
    private lateinit var transmitViewModel: TransmitViewModel
    private lateinit var adapter: MessageBubbleAdapter

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: LinearLayout

    private var callsign: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        callsign = arguments?.getString("callsign") ?: ""
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_conversation, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize ViewModels
        viewModel = ViewModelProvider(requireActivity())[MessagesViewModel::class.java]
        transmitViewModel = ViewModelProvider(requireActivity())[TransmitViewModel::class.java]

        val toolbar = view.findViewById<MaterialToolbar>(R.id.thread_toolbar)
        toolbar.title = callsign
        toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        SpeedChip.bind(view.findViewById(R.id.speed_chip))

        // Find views
        recyclerView = view.findViewById(R.id.messages_recycler_view)
        emptyState = view.findViewById(R.id.empty_state)

        // Set up RecyclerView
        adapter = MessageBubbleAdapter().apply {
            onMessageLongClick = { message ->
                // Could show options to copy/delete
                true
            }
        }
        recyclerView.adapter = adapter

        // Register adapter data observer to scroll to bottom on new messages
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                recyclerView.scrollToPosition(adapter.itemCount - 1)
            }
        })

        ComposeBarController(
            root = view,
            commandMenuRes = R.menu.directed_command_menu,
            onSend = { text -> sendMessage(text) },
            onCommand = { text -> sendMessage(text) }
        )

        // Observe messages for this conversation
        viewModel.getMessagesForConversation(callsign).observe(viewLifecycleOwner) { messages ->
            adapter.submitList(messages) {
                // Scroll to bottom after list update
                if (messages.isNotEmpty()) {
                    recyclerView.scrollToPosition(messages.size - 1)
                }
            }

            // Show/hide empty state
            if (messages.isEmpty()) {
                emptyState.visibility = View.VISIBLE
                recyclerView.visibility = View.GONE
            } else {
                emptyState.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
            }
        }

        // Mark conversation as read when viewing
        viewModel.markConversationAsRead(callsign)
    }

    override fun onResume() {
        super.onResume()
        // Mark as read again in case new messages arrived
        viewModel.markConversationAsRead(callsign)
    }

    private fun sendMessage(text: String): Boolean {
        if (!readyToSend()) return false
        transmitViewModel.queueThreadMessage(callsign, text)
        return true
    }
}
