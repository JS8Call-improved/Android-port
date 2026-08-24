package com.js8call.example.ui

import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.PopupMenu
import androidx.annotation.MenuRes
import android.widget.EditText
import com.google.android.material.button.MaterialButton
import com.js8call.example.R

/**
 * Wires up the shared compose bar: command menu, text field, and send arrow.
 *
 * The command menu holds one-shot sends: CQ/Heartbeat in the Everything
 * thread, the directed queries (SNR?, GRID?, ...) in a DM thread.
 */
class ComposeBarController(
    root: View,
    @MenuRes private val commandMenuRes: Int,
    private val onSend: (String) -> Unit,
    private val onCommand: (String) -> Unit
) {

    private val commandButton: MaterialButton = root.findViewById(R.id.command_button)
    private val input: EditText = root.findViewById(R.id.compose_input)
    private val sendButton: View = root.findViewById(R.id.send_button)

    init {
        commandButton.setOnClickListener { showCommandMenu() }
        sendButton.setOnClickListener { send() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                send()
                true
            } else {
                false
            }
        }
    }

    private fun send() {
        val text = input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        input.text?.clear()
        onSend(text)
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
                R.id.cmd_query_msgs -> "QUERY MSGS?"
                else -> return@setOnMenuItemClickListener false
            }
            onCommand(command)
            true
        }
        popup.show()
    }
}
