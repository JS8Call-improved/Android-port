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

        // Mailbox actions make sense toward a station, not a group. A group
        // thread the operator has not joined gets a Join action instead.
        if (!callsign.startsWith("@")) {
            toolbar.inflateMenu(R.menu.conversation_menu)
            toolbar.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_check_messages -> {
                        // Any mail waiting for us at this station?
                        sendMessage("QUERY MSGS")
                        true
                    }
                    R.id.action_send_via_relay -> {
                        showSendViaRelayDialog()
                        true
                    }
                    else -> false
                }
            }
        } else if (!isSubscribedGroup()) {
            toolbar.inflateMenu(R.menu.group_menu)
            toolbar.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_join_group -> {
                        joinGroup()
                        toolbar.menu.removeItem(R.id.action_join_group)
                        true
                    }
                    else -> false
                }
            }
        }

        recyclerView = view.findViewById(R.id.messages_recycler_view)
        emptyState = view.findViewById(R.id.empty_state)

        adapter = MessageBubbleAdapter()
        recyclerView.adapter = adapter
        // No cross-fade on rebinds; the sending label ticks every second
        (recyclerView.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)
            ?.supportsChangeAnimations = false

        ComposeBarController(
            root = view,
            // Per-station queries are meaningless aimed at a group
            commandMenuRes = if (callsign.startsWith("@")) {
                R.menu.group_command_menu
            } else {
                R.menu.directed_command_menu
            },
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

    private fun isSubscribedGroup(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val groups = (prefs.getString("my_groups", "") ?: "")
            .split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        return callsign.uppercase() in groups
    }

    private fun joinGroup() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val groups = (prefs.getString("my_groups", "") ?: "")
            .split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        if (callsign.uppercase() !in groups) {
            prefs.edit()
                .putString("my_groups", (groups + callsign.uppercase()).joinToString(","))
                .apply()
        }
        Snackbar.make(requireView(), getString(R.string.group_joined, callsign), Snackbar.LENGTH_SHORT)
            .show()
    }

    /**
     * Deposit a message for this station at a third station's mailbox:
     * MY: RELAY MSG TO:DEST text. The thread row stays under this station,
     * because the conversation is with the person, not the hop.
     */
    private fun showSendViaRelayDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_send_via_relay, null)
        val relayInput = dialogView.findViewById<
            com.google.android.material.textfield.MaterialAutoCompleteTextView>(R.id.relay_input)
        val messageInput = dialogView.findViewById<
            com.google.android.material.textfield.TextInputEditText>(R.id.relay_message_input)

        val decodeViewModel = ViewModelProvider(requireActivity())[DecodeViewModel::class.java]
        val heard = decodeViewModel.heardCallsigns().filterNot { it.equals(callsign, true) }
        relayInput.setAdapter(
            android.widget.ArrayAdapter(
                requireContext(), android.R.layout.simple_list_item_1, heard
            )
        )
        relayInput.threshold = 1

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.conversation_send_via_relay)
            .setView(dialogView)
            .setPositiveButton(R.string.relay_send) { _, _ ->
                val relay = relayInput.text?.toString()?.trim()?.uppercase().orEmpty()
                val text = messageInput.text?.toString()?.trim()?.uppercase().orEmpty()
                if (relay.isEmpty() || text.isEmpty()) return@setPositiveButton
                sendViaRelay(relay, text)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun sendViaRelay(relay: String, text: String) {
        if (!hasCallsignConfigured()) {
            Snackbar.make(requireView(), R.string.error_callsign_required, Snackbar.LENGTH_LONG).show()
            return
        }
        viewModel.insertOutgoingMessage(callsign, text, relayPath = relay)
            .observe(viewLifecycleOwner) { messageId ->
                // The engine prefixes "MY: RELAY" and appends the checksum
                // the buffered MSG TO: command requires.
                transmitViewModel.queueMessage(
                    "MSG TO:$callsign $text", directed = relay, dbId = messageId
                )
                LocalBroadcastManager.getInstance(requireContext())
                    .sendBroadcast(Intent(MainActivity.ACTION_PROCESS_TX_QUEUE))
            }
    }
}
