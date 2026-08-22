package com.js8call.example.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.js8call.example.R

/**
 * The Messages tab: the pinned Everything thread plus DM conversations.
 */
class MessagesFragment : Fragment() {

    private lateinit var viewModel: MessagesViewModel
    private lateinit var decodeViewModel: DecodeViewModel
    private lateinit var adapter: ConversationListAdapter

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var newMessageFab: FloatingActionButton

    private var conversationCallsigns: List<String> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_messages, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(requireActivity())[MessagesViewModel::class.java]
        decodeViewModel = ViewModelProvider(requireActivity())[DecodeViewModel::class.java]

        recyclerView = view.findViewById(R.id.conversations_recycler_view)
        emptyState = view.findViewById(R.id.empty_state)
        newMessageFab = view.findViewById(R.id.new_message_fab)

        view.findViewById<View>(R.id.everything_card).setOnClickListener {
            findNavController().navigate(R.id.action_messages_to_everything)
        }

        // Held messages entry: visible once the mailbox holds anything,
        // badged with the count still waiting for its recipients.
        val mailboxViewModel = ViewModelProvider(requireActivity())[MailboxViewModel::class.java]
        val mailboxFrame = view.findViewById<View>(R.id.mailbox_button_frame)
        val mailboxBadge = view.findViewById<android.widget.TextView>(R.id.mailbox_badge)
        view.findViewById<View>(R.id.mailbox_button).setOnClickListener {
            findNavController().navigate(R.id.action_messages_to_mailbox)
        }
        mailboxViewModel.messages.observe(viewLifecycleOwner) { rows ->
            mailboxFrame.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        }
        mailboxViewModel.heldCount.observe(viewLifecycleOwner) { held ->
            mailboxBadge.visibility = if (held > 0) View.VISIBLE else View.GONE
            mailboxBadge.text = held.toString()
        }

        // Newest band activity as the thread preview, like a DM row
        val everythingPreview = view.findViewById<android.widget.TextView>(R.id.everything_preview)
        decodeViewModel.decodes.observe(viewLifecycleOwner) { decodes ->
            everythingPreview.text = decodes.lastOrNull()?.text
                ?: getString(R.string.everything_subtitle)
        }

        adapter = ConversationListAdapter().apply {
            onItemClick = { conversation ->
                navigateToConversation(conversation.callsign)
            }
            onItemLongClick = { conversation ->
                showConversationOptions(conversation.callsign)
                true
            }
        }
        recyclerView.adapter = adapter

        newMessageFab.setOnClickListener {
            showNewMessageDialog()
        }

        // Observe conversations
        viewModel.conversations.observe(viewLifecycleOwner) { conversations ->
            adapter.submitList(conversations)
            conversationCallsigns = conversations.map { it.callsign }

            // Show/hide empty state
            if (conversations.isEmpty()) {
                emptyState.visibility = View.VISIBLE
                recyclerView.visibility = View.GONE
            } else {
                emptyState.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
            }
        }
    }

    private fun showNewMessageDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_new_message, null)
        val input = dialogView.findViewById<MaterialAutoCompleteTextView>(R.id.callsign_input)

        // Suggest stations from conversation history and this session's decodes
        val suggestions = (conversationCallsigns + decodeViewModel.heardCallsigns()).distinct()
        input.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, suggestions)
        )
        input.threshold = 1

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.messages_new)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val callsign = input.text?.toString()?.trim()?.uppercase().orEmpty()
                if (callsign.isNotEmpty()) {
                    navigateToConversation(callsign)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun navigateToConversation(callsign: String) {
        val bundle = Bundle().apply {
            putString("callsign", callsign)
        }
        findNavController().navigate(R.id.action_messages_to_conversation, bundle)
    }

    private fun showConversationOptions(callsign: String) {
        val options = arrayOf(
            getString(R.string.messages_delete_conversation)
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(callsign)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> confirmDeleteConversation(callsign)
                }
            }
            .show()
    }

    private fun confirmDeleteConversation(callsign: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.messages_delete_conversation)
            .setMessage(getString(R.string.messages_delete_confirm, callsign))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewModel.deleteConversation(callsign)
                Snackbar.make(requireView(), R.string.messages_deleted, Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
