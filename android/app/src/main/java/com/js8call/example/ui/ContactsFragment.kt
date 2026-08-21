package com.js8call.example.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.js8call.example.R
import com.js8call.example.data.ContactEntity

/**
 * Contacts tab: every station heard on the air, starred first,
 * then most recently heard.
 */
class ContactsFragment : Fragment() {

    private lateinit var viewModel: ContactsViewModel
    private lateinit var adapter: ContactListAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: View

    // Refresh the age column while the list is on screen
    private val ageHandler = Handler(Looper.getMainLooper())
    private val ageTick = object : Runnable {
        override fun run() {
            adapter.notifyItemRangeChanged(0, adapter.itemCount)
            ageHandler.postDelayed(this, AGE_REFRESH_MS)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_contacts, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(requireActivity())[ContactsViewModel::class.java]

        recyclerView = view.findViewById(R.id.contacts_recycler_view)
        emptyState = view.findViewById(R.id.empty_state)

        adapter = ContactListAdapter().apply {
            onItemClick = { contact -> showContactOptions(contact) }
            onStarClick = { contact -> viewModel.setStarred(contact.callsign, !contact.starred) }
        }
        recyclerView.adapter = adapter

        viewModel.contacts.observe(viewLifecycleOwner) { contacts ->
            adapter.submitList(contacts)
            emptyState.visibility = if (contacts.isEmpty()) View.VISIBLE else View.GONE
            recyclerView.visibility = if (contacts.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    override fun onStart() {
        super.onStart()
        ageHandler.postDelayed(ageTick, AGE_REFRESH_MS)
    }

    override fun onStop() {
        ageHandler.removeCallbacks(ageTick)
        super.onStop()
    }

    private fun showContactOptions(contact: ContactEntity) {
        val options = arrayOf(
            getString(R.string.contact_action_message),
            getString(R.string.contact_action_comment),
            getString(R.string.contact_action_delete)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(contact.callsign)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openConversation(contact.callsign)
                    1 -> editComment(contact)
                    2 -> confirmDelete(contact)
                }
            }
            .show()
    }

    private fun openConversation(callsign: String) {
        val bundle = Bundle().apply { putString("callsign", callsign) }
        findNavController().navigate(R.id.navigation_conversation, bundle)
    }

    private fun editComment(contact: ContactEntity) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_comment, null)
        val input = dialogView.findViewById<TextInputEditText>(R.id.comment_input)
        input.setText(contact.comment.orEmpty())

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(contact.callsign)
            .setView(dialogView)
            .setPositiveButton(R.string.contact_comment_save) { _, _ ->
                val text = input.text?.toString()?.trim()
                viewModel.setComment(contact.callsign, text?.takeIf { it.isNotEmpty() })
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(contact: ContactEntity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.contact_delete_title, contact.callsign))
            .setMessage(R.string.contact_delete_message)
            .setPositiveButton(R.string.contact_action_delete) { _, _ ->
                viewModel.deleteContact(contact.callsign)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        private const val AGE_REFRESH_MS = 30_000L
    }
}
