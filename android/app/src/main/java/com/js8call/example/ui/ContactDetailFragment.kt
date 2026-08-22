package com.js8call.example.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.js8call.example.R
import com.js8call.example.data.ContactEntity
import com.js8call.example.util.AvatarColor
import com.js8call.example.util.DisplayName
import com.js8call.example.util.Maidenhead
import com.js8call.example.util.RelayPath
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything the app knows about one station, and everywhere the operator
 * can change it. Reached from the contact list, from a thread's toolbar,
 * and from a sender's name on a group message.
 *
 * The name and notes save when a field loses focus and again when the
 * screen stops, so there is no Save button to forget.
 */
class ContactDetailFragment : Fragment() {

    private lateinit var viewModel: ContactsViewModel
    private lateinit var messagesViewModel: MessagesViewModel

    private lateinit var toolbar: MaterialToolbar
    private lateinit var avatarFrame: View
    private lateinit var avatarText: TextView
    private lateinit var nameText: TextView
    private lateinit var callsignText: TextView
    private lateinit var hearsUsChip: Chip
    private lateinit var messageButton: MaterialButton
    private lateinit var mailText: TextView
    private lateinit var relayPathValue: TextView
    private lateinit var nameInput: TextInputEditText
    private lateinit var notesInput: TextInputEditText
    private lateinit var heardContainer: LinearLayout
    private lateinit var neverHeardText: TextView

    private var callsign: String = ""
    private var contact: ContactEntity? = null

    /** Seed the editable fields once, so live updates cannot fight the typist. */
    private var fieldsSeeded = false

    private val timeFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        callsign = arguments?.getString("callsign")?.trim()?.uppercase() ?: ""
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_contact_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(requireActivity())[ContactsViewModel::class.java]
        messagesViewModel = ViewModelProvider(requireActivity())[MessagesViewModel::class.java]

        bindViews(view)

        toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        toolbar.inflateMenu(R.menu.contact_detail_menu)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_favorite -> {
                    val starred = contact?.starred ?: false
                    viewModel.setStarred(callsign, !starred)
                    true
                }
                R.id.action_delete_contact -> {
                    confirmDelete()
                    true
                }
                else -> false
            }
        }

        avatarText.text = DisplayName.initial(callsign, null)
        avatarFrame.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(requireContext(), AvatarColor.forCallsign(callsign))
        )
        nameText.text = callsign

        messageButton.setOnClickListener { openConversation() }
        view.findViewById<View>(R.id.relay_path_row).setOnClickListener { openRelayPath() }

        // Leaving a field commits it, which is what makes the Save button
        // unnecessary. onStop covers backing out without moving focus first.
        nameInput.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveName() }
        notesInput.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveNotes() }

        viewModel.getContact(callsign).observe(viewLifecycleOwner) { entity ->
            contact = entity
            render(entity)
        }

        messagesViewModel.getRelayPath(callsign).observe(viewLifecycleOwner) { stored ->
            relayPathValue.text = describePath(RelayPath.parse(stored))
        }

        viewModel.getHeldMailCount(callsign).observe(viewLifecycleOwner) { count ->
            if (count > 0) {
                mailText.visibility = View.VISIBLE
                mailText.text = resources.getQuantityString(
                    R.plurals.contact_detail_mail, count, count
                )
            } else {
                mailText.visibility = View.GONE
            }
        }
    }

    override fun onStop() {
        saveName()
        saveNotes()
        super.onStop()
    }

    private fun bindViews(view: View) {
        toolbar = view.findViewById(R.id.contact_toolbar)
        avatarFrame = view.findViewById(R.id.avatar_frame)
        avatarText = view.findViewById(R.id.avatar_text)
        nameText = view.findViewById(R.id.name_text)
        callsignText = view.findViewById(R.id.callsign_text)
        hearsUsChip = view.findViewById(R.id.hears_us_chip)
        messageButton = view.findViewById(R.id.message_button)
        mailText = view.findViewById(R.id.mail_text)
        relayPathValue = view.findViewById(R.id.relay_path_value)
        nameInput = view.findViewById(R.id.name_input)
        notesInput = view.findViewById(R.id.notes_input)
        heardContainer = view.findViewById(R.id.heard_container)
        neverHeardText = view.findViewById(R.id.never_heard_text)
    }

    /** A station with no row yet still gets a card, just an empty one. */
    private fun render(entity: ContactEntity?) {
        val name = entity?.name

        nameText.text = DisplayName.of(callsign, name)
        avatarText.text = DisplayName.initial(callsign, name)
        val secondary = DisplayName.secondary(callsign, name)
        callsignText.text = secondary.orEmpty()
        callsignText.visibility = if (secondary == null) View.GONE else View.VISIBLE

        hearsUsChip.visibility = if (entity?.heardUs == true) View.VISIBLE else View.GONE

        val starred = entity?.starred == true
        toolbar.menu.findItem(R.id.action_favorite)?.apply {
            setIcon(if (starred) R.drawable.ic_star else R.drawable.ic_star_outline)
            setTitle(if (starred) R.string.contact_detail_unfavorite else R.string.contact_star)
            iconTintList = ColorStateList.valueOf(
                ContextCompat.getColor(
                    requireContext(),
                    if (starred) R.color.star_active else R.color.star_inactive
                )
            )
        }

        if (!fieldsSeeded) {
            fieldsSeeded = true
            nameInput.setText(name.orEmpty())
            notesInput.setText(entity?.comment.orEmpty())
        }

        renderHeard(entity)
    }

    private fun renderHeard(entity: ContactEntity?) {
        heardContainer.removeAllViews()

        // lastHeard of 0 marks a row created by naming a station rather than
        // by hearing one, so it has nothing to report.
        val heard = entity != null && entity.lastHeard > 0L
        neverHeardText.visibility = if (heard) View.GONE else View.VISIBLE
        if (!heard || entity == null) return

        addRow(R.string.contact_detail_last_heard, timeFormat.format(Date(entity.lastHeard)))
        entity.snr?.let { addRow(R.string.contact_detail_snr_label, getString(R.string.contact_snr, it)) }
        entity.offset?.let {
            addRow(R.string.contact_detail_offset_label, getString(R.string.contact_offset, it.toInt()))
        }
        entity.grid?.takeIf { it.isNotBlank() }?.let { grid ->
            addRow(R.string.contact_detail_grid_label, grid)
            // From the operator's own grid to theirs, when both are known.
            // Grid centers, so this is an estimate by nature.
            Maidenhead.describePath(myGrid(), grid, miles = useMiles())?.let {
                addRow(R.string.contact_detail_distance_label, it)
            }
        }
        entity.info?.takeIf { it.isNotBlank() }?.let { addRow(R.string.contact_detail_info_label, it) }
    }

    private fun addRow(labelRes: Int, value: String) {
        val row = layoutInflater.inflate(R.layout.item_contact_detail_row, heardContainer, false)
        row.findViewById<TextView>(R.id.row_label).setText(labelRes)
        row.findViewById<TextView>(R.id.row_value).text = value
        heardContainer.addView(row)
    }

    private fun myGrid(): String? =
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
            .getString("grid", null)?.trim()?.takeIf { it.isNotEmpty() }

    private fun useMiles(): Boolean =
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(requireContext())
            .getString("distance_units", "mi") != "km"

    private fun describePath(hops: List<String>): String {
        if (hops.isEmpty()) return getString(R.string.relay_direct)
        val count = resources.getQuantityString(R.plurals.relay_hop_count, hops.size, hops.size)
        return "$count · " + hops.joinToString(" › ")
    }

    private fun saveName() {
        if (!fieldsSeeded) return
        val typed = nameInput.text?.toString()?.trim().orEmpty()
        val current = contact?.name?.trim().orEmpty()
        if (typed == current) return
        viewModel.setName(callsign, typed.takeIf { it.isNotEmpty() })
    }

    private fun saveNotes() {
        if (!fieldsSeeded) return
        val typed = notesInput.text?.toString()?.trim().orEmpty()
        val current = contact?.comment?.trim().orEmpty()
        if (typed == current) return
        viewModel.setComment(callsign, typed.takeIf { it.isNotEmpty() })
    }

    private fun openConversation() {
        findNavController().navigate(
            R.id.navigation_conversation,
            Bundle().apply { putString("callsign", callsign) }
        )
    }

    private fun openRelayPath() {
        findNavController().navigate(
            R.id.navigation_relay_path,
            Bundle().apply { putString("callsign", callsign) }
        )
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.contact_delete_title, callsign))
            .setMessage(R.string.contact_delete_message)
            .setPositiveButton(R.string.contact_action_delete) { _, _ ->
                // Do not let onStop write the fields back into a row we
                // just removed.
                fieldsSeeded = false
                contact = null
                viewModel.deleteContact(callsign)
                findNavController().navigateUp()
                requireActivity().findViewById<View>(android.R.id.content)?.let {
                    Snackbar.make(
                        it, getString(R.string.contact_deleted, callsign), Snackbar.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
