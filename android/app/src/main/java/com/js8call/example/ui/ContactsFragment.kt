package com.js8call.example.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import com.js8call.example.R

/**
 * Contacts tab: every station heard on the air, starred first,
 * then most recently heard.
 */
class ContactsFragment : Fragment() {

    private lateinit var viewModel: ContactsViewModel
    private lateinit var adapter: ContactListAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: View
    private lateinit var emptyTitle: TextView
    private lateinit var emptyHint: TextView
    private lateinit var searchInput: TextInputEditText

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

        emptyTitle = view.findViewById(R.id.empty_title)
        emptyHint = view.findViewById(R.id.empty_hint)
        searchInput = view.findViewById(R.id.search_input)

        adapter = ContactListAdapter().apply {
            onItemClick = { contact -> openContact(contact.callsign) }
            onStarClick = { contact -> viewModel.setStarred(contact.callsign, !contact.starred) }
        }
        recyclerView.adapter = adapter

        view.findViewById<View>(R.id.network_map_button).setOnClickListener {
            findNavController().navigate(R.id.navigation_network_map)
        }

        searchInput.setText(viewModel.query.value.orEmpty())
        searchInput.addTextChangedListener(
            afterTextChanged = { viewModel.setQuery(it?.toString().orEmpty()) }
        )

        viewModel.visibleContacts.observe(viewLifecycleOwner) { contacts ->
            adapter.submitList(contacts)
            val empty = contacts.isEmpty()
            emptyState.visibility = if (empty) View.VISIBLE else View.GONE
            recyclerView.visibility = if (empty) View.GONE else View.VISIBLE
            if (empty) showEmptyState()
        }
    }

    /**
     * An empty list means two different things. Nothing heard yet is the
     * normal starting state; nothing matching a search is the operator's
     * own doing and needs to say so.
     */
    private fun showEmptyState() {
        val query = viewModel.query.value.orEmpty().trim()
        if (query.isEmpty()) {
            emptyTitle.setText(R.string.contacts_empty)
            emptyHint.setText(R.string.contacts_empty_hint)
        } else {
            emptyTitle.setText(R.string.contact_search_hint)
            emptyHint.text = getString(R.string.contacts_no_matches, query)
        }
    }

    private fun openContact(callsign: String) {
        findNavController().navigate(
            R.id.navigation_contact_detail,
            Bundle().apply { putString("callsign", callsign) }
        )
    }

    override fun onStart() {
        super.onStart()
        ageHandler.postDelayed(ageTick, AGE_REFRESH_MS)
    }

    override fun onStop() {
        ageHandler.removeCallbacks(ageTick)
        super.onStop()
    }

    companion object {
        private const val AGE_REFRESH_MS = 30_000L
    }
}
