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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.js8call.example.R
import com.js8call.example.data.MailboxEntity

/**
 * Held messages: store-and-forward mail this station holds for other
 * operators, grouped by destination. Not conversations — that is the
 * point of the separate screen.
 */
class MailboxFragment : Fragment() {

    private lateinit var viewModel: MailboxViewModel
    private lateinit var adapter: MailboxListAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_mailbox, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(requireActivity())[MailboxViewModel::class.java]

        val toolbar = view.findViewById<MaterialToolbar>(R.id.mailbox_toolbar)
        toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        toolbar.inflateMenu(R.menu.mailbox_menu)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_delete_delivered -> {
                    confirmDeleteDelivered()
                    true
                }
                else -> false
            }
        }

        recyclerView = view.findViewById(R.id.mailbox_recycler_view)
        emptyState = view.findViewById(R.id.empty_state)

        adapter = MailboxListAdapter().apply {
            onMessageClick = { row -> showMessage(row) }
        }
        recyclerView.adapter = adapter

        viewModel.messages.observe(viewLifecycleOwner) { rows ->
            adapter.submitList(MailboxListAdapter.buildRows(rows))
            emptyState.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            recyclerView.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun showMessage(row: MailboxViewModel.MailboxRow) {
        val msg = row.message
        val details = buildString {
            append(getString(R.string.mailbox_detail_from, msg.originator))
            msg.relayPath?.let { append("\n").append(getString(R.string.mailbox_detail_via, it)) }
            append("\n\n").append(msg.text)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(msg.destination)
            .setMessage(details)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(R.string.mailbox_delete) { _, _ -> confirmDelete(msg) }
            .show()
    }

    private fun confirmDelete(msg: MailboxEntity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.mailbox_delete)
            .setMessage(getString(R.string.mailbox_delete_confirm, msg.destination))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewModel.delete(msg.id)
                Snackbar.make(requireView(), R.string.mailbox_deleted, Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteDelivered() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.mailbox_delete_delivered)
            .setMessage(R.string.mailbox_delete_delivered_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewModel.deleteDelivered()
                Snackbar.make(requireView(), R.string.mailbox_deleted, Snackbar.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
