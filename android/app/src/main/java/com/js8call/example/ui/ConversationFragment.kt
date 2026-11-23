package com.js8call.example.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.snackbar.Snackbar
import com.js8call.example.R
import com.js8call.example.data.MessageEntity

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
            onUnsentClick = { message, bubble -> showUnsentOptions(message, bubble) }
        }
        recyclerView.adapter = adapter

        // Register adapter data observer to scroll to bottom on new messages
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                recyclerView.scrollToPosition(adapter.itemCount - 1)
            }
        })

        ComposeBarController(
            fragment = this,
            root = view,
            commandMenuRes = R.menu.directed_command_menu,
            onSend = { text -> sendMessage(text) }
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

            // Messages can arrive while the thread is open
            viewModel.markConversationAsRead(callsign)
        }
    }

    private fun sendMessage(text: String): Boolean {
        if (!readyToSend()) return false
        transmitViewModel.queueThreadMessage(callsign, text)
        return true
    }

    private fun showUnsentOptions(message: MessageEntity, bubble: View) {
        val popup = PopupMenu(requireContext(), bubble)
        popup.menuInflater.inflate(R.menu.outgoing_message_menu, popup.menu)
        popup.menu.findItem(R.id.action_retry).isVisible =
            message.isFailed() && transmitViewModel.canRetry(message.id)
        popup.menu.findItem(R.id.action_cancel_send).isVisible = message.isPending()
        popup.menu.findItem(R.id.action_delete).isVisible = message.isFailed()
        popup.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_retry) {
                transmitViewModel.retry(message.id)
            } else if (!transmitViewModel.discard(message.id)) {
                Snackbar.make(requireView(), R.string.messages_already_sending, Snackbar.LENGTH_SHORT).show()
            }
            true
        }
        popup.show()
    }
}
