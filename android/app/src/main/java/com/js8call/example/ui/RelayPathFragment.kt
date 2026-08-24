package com.js8call.example.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.js8call.example.R
import com.js8call.example.data.ContactEntity
import com.js8call.example.util.CallsignValidator
import com.js8call.example.util.RelayPath

/**
 * Sets the relay path for one thread: the stations that carry a message to
 * its destination, in the order they carry it.
 *
 * This is live forwarding, so every station on the path has to be on the air
 * when the message goes out. Leaving a message at a station for later pickup
 * is the mailbox instead, on the thread menu.
 */
class RelayPathFragment : Fragment() {

    private lateinit var viewModel: MessagesViewModel
    private lateinit var contactsViewModel: ContactsViewModel
    private lateinit var hopAdapter: RelayHopAdapter
    private lateinit var candidateAdapter: RelayCandidateAdapter
    private lateinit var touchHelper: ItemTouchHelper

    private lateinit var hintText: TextView
    private lateinit var addInput: TextInputEditText
    private lateinit var addInputLayout: TextInputLayout
    private lateinit var heardEmpty: TextView
    private lateinit var heardRecycler: RecyclerView

    private var callsign: String = ""
    private var loaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        callsign = arguments?.getString("callsign")?.trim()?.uppercase() ?: ""
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_relay_path, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(requireActivity())[MessagesViewModel::class.java]
        contactsViewModel = ViewModelProvider(requireActivity())[ContactsViewModel::class.java]

        val toolbar = view.findViewById<MaterialToolbar>(R.id.relay_path_toolbar)
        toolbar.subtitle = getString(R.string.relay_path_subtitle, callsign)
        toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        toolbar.inflateMenu(R.menu.relay_path_menu)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_save_relay_path -> {
                    save()
                    true
                }
                else -> false
            }
        }

        hintText = view.findViewById(R.id.hint_text)
        addInput = view.findViewById(R.id.add_input)
        addInputLayout = view.findViewById(R.id.add_input_layout)
        heardEmpty = view.findViewById(R.id.heard_empty)
        heardRecycler = view.findViewById(R.id.heard_recycler_view)

        hopAdapter = RelayHopAdapter(
            destination = callsign,
            onRemove = { index -> removeHop(index) },
            onStartDrag = { holder -> touchHelper.startDrag(holder) }
        )
        val hopsRecycler = view.findViewById<RecyclerView>(R.id.hops_recycler_view)
        hopsRecycler.adapter = hopAdapter
        touchHelper = ItemTouchHelper(RelayHopTouchCallback(hopAdapter))
        touchHelper.attachToRecyclerView(hopsRecycler)

        candidateAdapter = RelayCandidateAdapter { call -> addHop(call) }
        heardRecycler.adapter = candidateAdapter

        addInputLayout.setEndIconOnClickListener { addTypedHop() }
        addInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addTypedHop()
                true
            } else {
                false
            }
        }

        // The stored path seeds the list once. Later emissions are our own
        // writes coming back, and re-seeding on those would undo edits the
        // operator made after saving.
        viewModel.getRelayPath(callsign).observe(viewLifecycleOwner) { stored ->
            if (!loaded) {
                loaded = true
                hopAdapter.setHops(RelayPath.parse(stored))
                refreshHint()
            }
        }

        contactsViewModel.contacts.observe(viewLifecycleOwner) { contacts ->
            showCandidates(contacts)
        }

        refreshHint()
    }

    private fun showCandidates(contacts: List<ContactEntity>) {
        val hops = hopAdapter.currentHops.map { it.uppercase() }.toSet()
        val available = contacts.filter {
            val call = it.callsign.uppercase()
            !call.startsWith("@") && call != callsign && call !in hops
        }
        candidateAdapter.submitList(available)
        heardEmpty.visibility = if (available.isEmpty()) View.VISIBLE else View.GONE
        heardRecycler.visibility = if (available.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun addTypedHop() {
        val typed = addInput.text?.toString()?.trim()?.uppercase().orEmpty()
        if (typed.isEmpty()) return
        if (addHop(typed)) {
            addInput.text?.clear()
        }
    }

    /**
     * Only the first hop is a station we hear ourselves. Anything past it is
     * somebody the previous hop hears and we do not, so a typed callsign is
     * accepted as long as it is shaped like one.
     */
    private fun addHop(call: String): Boolean {
        val hop = call.trim().uppercase()
        val hops = hopAdapter.currentHops
        when {
            hops.size >= RelayPath.MAX_HOPS -> {
                toast(getString(R.string.relay_path_full, RelayPath.MAX_HOPS))
                return false
            }
            hop == callsign -> {
                toast(getString(R.string.relay_path_not_destination, hop))
                return false
            }
            hops.any { it.equals(hop, ignoreCase = true) } -> {
                toast(getString(R.string.relay_path_duplicate, hop))
                return false
            }
            !CallsignValidator.isAmateurCallsign(hop) -> {
                toast(getString(R.string.relay_path_invalid_callsign, hop))
                return false
            }
        }
        hopAdapter.setHops(hops + hop)
        refreshHint()
        contactsViewModel.contacts.value?.let { showCandidates(it) }
        return true
    }

    private fun removeHop(index: Int) {
        val hops = hopAdapter.currentHops.toMutableList()
        if (index !in hops.indices) return
        hops.removeAt(index)
        hopAdapter.setHops(hops)
        refreshHint()
        contactsViewModel.contacts.value?.let { showCandidates(it) }
    }

    private fun refreshHint() {
        hintText.text = if (hopAdapter.currentHops.isEmpty()) {
            getString(R.string.relay_path_hint_direct, callsign)
        } else {
            getString(R.string.relay_path_hint)
        }
    }

    private fun save() {
        val path = RelayPath.format(hopAdapter.currentHops)
        viewModel.setRelayPath(callsign, path)
        toastOnParent(
            if (path == null) {
                getString(R.string.relay_path_cleared)
            } else {
                getString(R.string.relay_path_saved)
            }
        )
        findNavController().navigateUp()
    }

    private fun toast(message: String) {
        Snackbar.make(requireView(), message, Snackbar.LENGTH_SHORT).show()
    }

    /** Shown after navigating up, so it has to hang off the activity's view. */
    private fun toastOnParent(message: String) {
        val root = requireActivity().findViewById<View>(android.R.id.content) ?: return
        Snackbar.make(root, message, Snackbar.LENGTH_SHORT).show()
    }
}
