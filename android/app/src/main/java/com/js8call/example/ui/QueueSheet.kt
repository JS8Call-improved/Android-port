package com.js8call.example.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.js8call.example.R
import com.js8call.example.ui.TransmitViewModel.QueueItem

/**
 * Bottom sheet listing queued sends, each one cancelable until it goes to the engine.
 */
class QueueSheet : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.sheet_queue, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val transmitViewModel = ViewModelProvider(requireActivity())[TransmitViewModel::class.java]
        val monitorViewModel = ViewModelProvider(requireActivity())[MonitorViewModel::class.java]

        val adapter = QueueAdapter { transmitViewModel.cancel(it.message) }
        view.findViewById<RecyclerView>(R.id.queue_list).adapter = adapter
        val cancelAll = view.findViewById<View>(R.id.cancel_all_button)
        cancelAll.setOnClickListener { transmitViewModel.cancelAll() }
        val empty = view.findViewById<View>(R.id.queue_empty)
        val waiting = view.findViewById<View>(R.id.queue_waiting)

        transmitViewModel.queue.observe(viewLifecycleOwner) { queue ->
            adapter.submitList(queue)
            empty.isVisible = queue.isEmpty()
            cancelAll.isVisible = queue.any { !it.sending }
        }
        monitorViewModel.isRunning.observe(viewLifecycleOwner) { running ->
            waiting.isVisible = !running
        }
    }

    override fun onStart() {
        super.onStart()
        // Landscape peeks too short to show any rows
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private class QueueAdapter(
        private val onCancel: (QueueItem) -> Unit
    ) : ListAdapter<QueueItem, QueueAdapter.Holder>(Diff()) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_queue, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(getItem(position), onCancel)
        }

        class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val text: TextView = itemView.findViewById(R.id.queue_text)
            private val detail: TextView = itemView.findViewById(R.id.queue_detail)
            private val cancel: View = itemView.findViewById(R.id.queue_cancel)

            fun bind(item: QueueItem, onCancel: (QueueItem) -> Unit) {
                val context = itemView.context
                val message = item.message
                text.text = message.text
                detail.text = when {
                    item.sending -> context.getString(R.string.queue_sending)
                    message.priority > 0 -> context.getString(R.string.queue_auto_reply)
                    message.directed != null -> context.getString(R.string.queue_to, message.directed)
                    else -> context.getString(R.string.queue_broadcast)
                }
                cancel.isVisible = !item.sending
                cancel.setOnClickListener { onCancel(item) }
            }
        }

        private class Diff : DiffUtil.ItemCallback<QueueItem>() {
            override fun areItemsTheSame(oldItem: QueueItem, newItem: QueueItem) =
                oldItem.message === newItem.message

            override fun areContentsTheSame(oldItem: QueueItem, newItem: QueueItem) =
                oldItem == newItem
        }
    }

    companion object {
        const val TAG = "QueueSheet"
    }
}
