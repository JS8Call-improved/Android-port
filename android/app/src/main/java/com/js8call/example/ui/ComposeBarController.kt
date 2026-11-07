package com.js8call.example.ui

import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.PopupMenu
import androidx.annotation.MenuRes
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.js8call.example.R
import com.js8call.example.util.TxMessageClassifier

/**
 * Wires up the shared compose bar: queue chip, command menu, text field, and send arrow.
 *
 * The send callback returns false to refuse, which keeps the typed text.
 */
class ComposeBarController(
    private val fragment: Fragment,
    root: View,
    @MenuRes private val commandMenuRes: Int,
    private val onSend: (String) -> Boolean
) {

    private val queueChip: Chip = root.findViewById(R.id.queue_chip)
    private val commandButton: MaterialButton = root.findViewById(R.id.command_button)
    private val input: EditText = root.findViewById(R.id.compose_input)
    private val sendButton: View = root.findViewById(R.id.send_button)

    init {
        // A single-line input type stops wrapping; it stays so Enter sends instead of adding a line
        input.setHorizontallyScrolling(false)
        input.maxLines = 4
        commandButton.setOnClickListener { showCommandMenu() }
        sendButton.setOnClickListener { send() }
        input.setOnEditorActionListener { _, actionId, event ->
            // Hardware Enter arrives as IME_NULL, once for down and once for up.
            val enterKey = actionId == EditorInfo.IME_NULL &&
                (event?.keyCode == KeyEvent.KEYCODE_ENTER || event?.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
            if (actionId == EditorInfo.IME_ACTION_SEND || enterKey && event?.action == KeyEvent.ACTION_DOWN) {
                send()
            }
            actionId == EditorInfo.IME_ACTION_SEND || enterKey
        }

        ViewModelProvider(fragment.requireActivity())[TransmitViewModel::class.java]
            .queue.observe(fragment.viewLifecycleOwner) { queue ->
                queueChip.isVisible = queue.isNotEmpty()
                queueChip.text = fragment.getString(R.string.queue_count, queue.size)
            }
        queueChip.setOnClickListener {
            val fragments = fragment.childFragmentManager
            if (fragments.findFragmentByTag(QueueSheet.TAG) == null) {
                QueueSheet().showNow(fragments, QueueSheet.TAG)
            }
        }
    }

    private fun send() {
        val text = input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        if (!TxMessageClassifier.isEncodable(text)) {
            Snackbar.make(input, R.string.compose_not_encodable, Snackbar.LENGTH_LONG).show()
            return
        }
        if (onSend(text)) input.text?.clear()
    }

    private fun showCommandMenu() {
        val popup = PopupMenu(commandButton.context, commandButton)
        popup.menuInflater.inflate(commandMenuRes, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            val command = when (item.itemId) {
                R.id.cmd_cq -> "CQ CQ CQ"
                R.id.cmd_hb -> "HB"
                R.id.cmd_snr -> "SNR?"
                R.id.cmd_grid -> "GRID?"
                R.id.cmd_info -> "INFO?"
                R.id.cmd_status -> "STATUS?"
                R.id.cmd_hearing -> "HEARING?"
                R.id.cmd_agn -> "AGN?"
                else -> return@setOnMenuItemClickListener false
            }
            onSend(command)
            true
        }
        popup.show()
    }
}
