package com.js8call.example.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.js8call.example.R
import com.js8call.example.service.JS8EngineService

/**
 * Fragment showing a single conversation with chat bubbles.
 */
class ConversationFragment : Fragment() {

    private lateinit var viewModel: MessagesViewModel
    private lateinit var transmitViewModel: TransmitViewModel
    private lateinit var adapter: MessageBubbleAdapter

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var messageInputLayout: TextInputLayout
    private lateinit var messageInput: TextInputEditText
    private lateinit var sendButton: FloatingActionButton

    private var callsign: String = ""

    // Track the last sent message ID for status updates
    private var lastSentMessageId: Long? = null

    private val txStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                JS8EngineService.ACTION_TX_STATE -> {
                    val state = intent.getStringExtra(JS8EngineService.EXTRA_TX_STATE)
                    if (state == JS8EngineService.TX_STATE_FINISHED) {
                        lastSentMessageId?.let { id ->
                            viewModel.updateMessageStatus(id, com.js8call.example.data.MessageEntity.STATUS_SENT)
                        }
                    }
                }
            }
        }
    }

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

        // Set title to callsign
        activity?.title = callsign

        // Initialize ViewModels
        viewModel = ViewModelProvider(requireActivity())[MessagesViewModel::class.java]
        transmitViewModel = ViewModelProvider(requireActivity())[TransmitViewModel::class.java]

        // Find views
        recyclerView = view.findViewById(R.id.messages_recycler_view)
        emptyState = view.findViewById(R.id.empty_state)
        messageInputLayout = view.findViewById(R.id.message_input_layout)
        messageInput = view.findViewById(R.id.message_input)
        sendButton = view.findViewById(R.id.send_button)

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

        // Set up send button
        sendButton.setOnClickListener {
            sendMessage()
        }

        // Set up keyboard send action
        messageInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else {
                false
            }
        }

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
        // Register for TX state updates
        val filter = IntentFilter().apply {
            addAction(JS8EngineService.ACTION_TX_STATE)
        }
        LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(txStateReceiver, filter)

        // Mark as read again in case new messages arrived
        viewModel.markConversationAsRead(callsign)
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(requireContext())
            .unregisterReceiver(txStateReceiver)
    }

    private fun sendMessage() {
        val text = messageInput.text?.toString()?.trim() ?: return
        if (text.isEmpty()) return

        // Clear input
        messageInput.text?.clear()

        // Insert outgoing message to database
        viewModel.insertOutgoingMessage(callsign, text).observe(viewLifecycleOwner) { messageId ->
            lastSentMessageId = messageId

            // Queue the message for transmission via the TX queue
            // Format: MSG text, directed to callsign
            // The engine will prepend the callsign when selectedCall is set
            val fullMessage = "MSG $text"
            transmitViewModel.queueMessage(fullMessage, callsign, priority = 0, clearComposed = false)
            
            // Trigger queue processing
            LocalBroadcastManager.getInstance(requireContext()).sendBroadcast(
                Intent(com.js8call.example.MainActivity.ACTION_PROCESS_TX_QUEUE)
            )
        }
    }
}
