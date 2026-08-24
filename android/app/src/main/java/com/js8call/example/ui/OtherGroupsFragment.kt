package com.js8call.example.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.js8call.example.R
import com.js8call.example.data.ConversationSummary

/**
 * Group threads heard on the band that the operator is not in. Stored
 * silently so joining a group hands over the history the app already
 * decoded. Joining promotes the thread to the main list and turns on
 * notifications for it.
 */
class OtherGroupsFragment : Fragment() {

    private lateinit var viewModel: MessagesViewModel
    private lateinit var adapter: ConversationListAdapter
    private lateinit var recyclerView: RecyclerView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_other_groups, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(requireActivity())[MessagesViewModel::class.java]

        view.findViewById<MaterialToolbar>(R.id.other_groups_toolbar)
            .setNavigationOnClickListener { findNavController().navigateUp() }

        recyclerView = view.findViewById(R.id.other_groups_recycler_view)
        adapter = ConversationListAdapter().apply {
            onItemClick = { conversation -> openThread(conversation.callsign) }
            onItemLongClick = { conversation ->
                confirmJoin(conversation.callsign)
                true
            }
        }
        recyclerView.adapter = adapter

        viewModel.conversations.observe(viewLifecycleOwner) { conversations ->
            refresh(conversations)
        }
    }

    private fun refresh(conversations: List<ConversationSummary>) {
        val subscribed = subscribedGroups()
        val other = conversations.filter {
            it.callsign.startsWith("@") && it.callsign.uppercase() !in subscribed
        }
        adapter.submitList(other)
        // Joining the last group leaves nothing to browse
        if (other.isEmpty()) {
            findNavController().navigateUp()
        }
    }

    private fun openThread(group: String) {
        findNavController().navigate(
            R.id.navigation_conversation,
            Bundle().apply { putString("callsign", group) }
        )
    }

    private fun confirmJoin(group: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(group)
            .setMessage(getString(R.string.join_group_confirm, group))
            .setPositiveButton(R.string.join_group) { _, _ -> joinGroup(group) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun joinGroup(group: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val groups = (prefs.getString("my_groups", "") ?: "")
            .split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        if (group.uppercase() !in groups) {
            prefs.edit()
                .putString("my_groups", (groups + group.uppercase()).joinToString(","))
                .apply()
        }
        Snackbar.make(requireView(), getString(R.string.group_joined, group), Snackbar.LENGTH_SHORT)
            .show()
        viewModel.conversations.value?.let { refresh(it) }
    }

    private fun subscribedGroups(): List<String> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        return (prefs.getString("my_groups", "") ?: "")
            .split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }
    }
}
