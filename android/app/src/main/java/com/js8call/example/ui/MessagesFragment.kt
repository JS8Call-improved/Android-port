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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.js8call.example.R

/**
 * Fragment showing the list of conversations (Messages tab).
 */
class MessagesFragment : Fragment() {

    private lateinit var viewModel: MessagesViewModel
    private lateinit var adapter: ConversationListAdapter

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var newMessageFab: FloatingActionButton

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_messages, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize ViewModel (shared with ConversationFragment)
        viewModel = ViewModelProvider(requireActivity())[MessagesViewModel::class.java]

        // Find views
        recyclerView = view.findViewById(R.id.conversations_recycler_view)
        emptyState = view.findViewById(R.id.empty_state)
        newMessageFab = view.findViewById(R.id.new_message_fab)

        // Set up RecyclerView
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

        // Set up FAB (navigate to Transmit tab to compose new message)
        newMessageFab.setOnClickListener {
            // Navigate to Transmit tab for new message
            val bottomNav = activity?.findViewById<com.google.android.material.navigation.NavigationBarView>(R.id.bottom_navigation)
            bottomNav?.selectedItemId = R.id.navigation_transmit
        }

        // Observe conversations
        viewModel.conversations.observe(viewLifecycleOwner) { conversations ->
            adapter.submitList(conversations)

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
