package com.js8call.example.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.snackbar.Snackbar
import com.js8call.example.MainActivity
import com.js8call.example.R
import com.js8call.example.model.TransmitState

/**
 * A direct-message thread with one station.
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

        viewModel = ViewModelProvider(requireActivity())[MessagesViewModel::class.java]
        transmitViewModel = ViewModelProvider(requireActivity())[TransmitViewModel::class.java]

        val toolbar = view.findViewById<MaterialToolbar>(R.id.thread_toolbar)
        toolbar.title = callsign
        toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        SpeedChip.bind(view.findViewById(R.id.speed_chip))

        recyclerView = view.findViewById(R.id.messages_recycler_view)
        emptyState = view.findViewById(R.id.empty_state)

        adapter = MessageBubbleAdapter()
        recyclerView.adapter = adapter
        // No cross-fade on rebinds; the sending label ticks every second
        (recyclerView.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)
            ?.supportsChangeAnimations = false

        ComposeBarController(
            root = view,
            commandMenuRes = R.menu.directed_command_menu,
            onSend = { text -> sendMessage(text) },
            // Directed queries ride the normal send path, so they get
            // bubble states and TX tracking like any other message.
            onCommand = { text -> sendMessage(text) }
        )

        // Observe messages for this conversation
        viewModel.getMessagesForConversation(callsign).observe(viewLifecycleOwner) { messages ->
            val wasAtBottom = !recyclerView.canScrollVertically(1)
            val firstLoad = adapter.itemCount == 0
            adapter.submitList(messages) {
                if (messages.isNotEmpty() && (wasAtBottom || firstLoad)) {
                    if (firstLoad) {
                        recyclerView.scrollToPosition(messages.size - 1)
                    } else {
                        recyclerView.smoothScrollToPosition(messages.size - 1)
                    }
                }
            }

            if (messages.isEmpty()) {
                emptyState.visibility = View.VISIBLE
                recyclerView.visibility = View.GONE
            } else {
                emptyState.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
            }
        }

        // The sending bubble: the queue head while transmitting, with the
        // frame countdown and progress from the TransmitViewModel.
        transmitViewModel.txState.observe(viewLifecycleOwner) { updateSendingBubble() }
        transmitViewModel.queue.observe(viewLifecycleOwner) { updateSendingBubble() }
        transmitViewModel.txCountdownSeconds.observe(viewLifecycleOwner) { updateSendingBubble() }
        transmitViewModel.txFrameProgress.observe(viewLifecycleOwner) { updateSendingBubble() }

        // Mark conversation as read when viewing
        viewModel.markConversationAsRead(callsign)
    }

    override fun onResume() {
        super.onResume()
        // Mark as read again in case new messages arrived
        viewModel.markConversationAsRead(callsign)
    }

    private fun updateSendingBubble() {
        val countdown = transmitViewModel.txCountdownSeconds.value
        val progress = transmitViewModel.txFrameProgress.value
        val sending = transmitViewModel.txState.value == TransmitState.TRANSMITTING ||
            progress != null
        val activeDbId = if (sending) transmitViewModel.getNextMessage()?.dbId else null
        val label = when {
            !sending || countdown == null -> null
            progress != null && progress.second > 1 ->
                getString(R.string.msg_status_sending_frames, progress.first, progress.second, countdown)
            else -> getString(R.string.msg_status_sending, countdown)
        }

        val previousId = adapter.sendingMessageId
        if (previousId == activeDbId && label == adapter.sendingLabel) return
        adapter.sendingMessageId = activeDbId
        adapter.sendingLabel = label

        val list = adapter.currentList
        for (id in listOfNotNull(previousId, activeDbId).distinct()) {
            val index = list.indexOfFirst { it.id == id }
            if (index >= 0) adapter.notifyItemChanged(index, MessageBubbleAdapter.PAYLOAD_STATUS)
        }
    }

    private fun sendMessage(text: String) {
        if (!hasCallsignConfigured()) {
            Snackbar.make(requireView(), R.string.error_callsign_required, Snackbar.LENGTH_LONG).show()
            return
        }

        viewModel.insertOutgoingMessage(callsign, text).observe(viewLifecycleOwner) { messageId ->
            transmitViewModel.queueMessage(text, directed = callsign, dbId = messageId)
            LocalBroadcastManager.getInstance(requireContext())
                .sendBroadcast(Intent(MainActivity.ACTION_PROCESS_TX_QUEUE))
        }
    }

    private fun hasCallsignConfigured(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        return prefs.getString("callsign", "")?.isNotBlank() == true
    }
}
