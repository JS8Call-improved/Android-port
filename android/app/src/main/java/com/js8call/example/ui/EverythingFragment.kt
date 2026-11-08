package com.js8call.example.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.appbar.MaterialToolbar
import com.js8call.example.R

/**
 * The All activity thread: every decode and transmission on the band.
 */
class EverythingFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_everything, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<MaterialToolbar>(R.id.thread_toolbar).setNavigationOnClickListener {
            findNavController().navigateUp()
        }
        view.findViewById<View>(R.id.clear_button).setOnClickListener {
            (childFragmentManager.findFragmentById(R.id.decode_pane) as? DecodeFragment)
                ?.confirmClearDecodes()
        }
        SpeedChip.bind(view.findViewById(R.id.speed_chip))

        ComposeBarController(
            root = view,
            commandMenuRes = R.menu.broadcast_command_menu,
            onSend = { text -> queueBroadcast(text) }
        )
    }
}
