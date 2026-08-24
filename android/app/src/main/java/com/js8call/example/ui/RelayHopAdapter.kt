package com.js8call.example.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.js8call.example.R

/**
 * The hops of a relay path, in transmit order, followed by a fixed row for
 * the destination. The destination is shown because a path reads wrong
 * without its end, but it is not a hop and cannot be dragged or removed.
 */
class RelayHopAdapter(
    private val destination: String,
    private val onRemove: (Int) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit
) : RecyclerView.Adapter<RelayHopAdapter.HopViewHolder>() {

    private val hops = mutableListOf<String>()

    val currentHops: List<String>
        get() = hops.toList()

    @SuppressLint("NotifyDataSetChanged")
    fun setHops(newHops: List<String>) {
        hops.clear()
        hops.addAll(newHops)
        notifyDataSetChanged()
    }

    fun move(from: Int, to: Int): Boolean {
        if (from !in hops.indices || to !in hops.indices) return false
        hops.add(to, hops.removeAt(from))
        notifyItemMoved(from, to)
        // The position numbers of everything between the two rows changed
        notifyItemRangeChanged(minOf(from, to), kotlin.math.abs(from - to) + 1)
        return true
    }

    override fun getItemCount(): Int = hops.size + 1

    private fun isDestinationRow(position: Int): Boolean = position == hops.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HopViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_relay_hop, parent, false)
        return HopViewHolder(view)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: HopViewHolder, position: Int) {
        holder.position.text = holder.itemView.context.getString(
            R.string.relay_position, position + 1
        )
        if (isDestinationRow(position)) {
            holder.callsign.text = destination
            holder.role.setText(R.string.relay_path_destination)
            holder.role.visibility = View.VISIBLE
            holder.remove.visibility = View.INVISIBLE
            holder.handle.visibility = View.INVISIBLE
            holder.handle.setOnTouchListener(null)
            holder.remove.setOnClickListener(null)
        } else {
            holder.callsign.text = hops[position]
            holder.role.visibility = View.GONE
            holder.remove.visibility = View.VISIBLE
            holder.handle.visibility = View.VISIBLE
            holder.remove.setOnClickListener {
                val index = holder.bindingAdapterPosition
                if (index != RecyclerView.NO_POSITION) onRemove(index)
            }
            holder.handle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    onStartDrag(holder)
                }
                false
            }
        }
    }

    fun isDraggable(holder: RecyclerView.ViewHolder): Boolean {
        val position = holder.bindingAdapterPosition
        return position != RecyclerView.NO_POSITION && !isDestinationRow(position)
    }

    class HopViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val handle: ImageView = view.findViewById(R.id.drag_handle)
        val position: TextView = view.findViewById(R.id.position_text)
        val callsign: TextView = view.findViewById(R.id.callsign_text)
        val role: TextView = view.findViewById(R.id.role_text)
        val remove: ImageButton = view.findViewById(R.id.remove_button)
    }
}

/**
 * Drag-to-reorder for the hop list. The destination row sits at the end and
 * refuses both to move and to be moved through.
 */
class RelayHopTouchCallback(
    private val adapter: RelayHopAdapter
) : ItemTouchHelper.Callback() {

    override fun isLongPressDragEnabled(): Boolean = false

    override fun isItemViewSwipeEnabled(): Boolean = false

    override fun getMovementFlags(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder
    ): Int {
        if (!adapter.isDraggable(viewHolder)) return makeMovementFlags(0, 0)
        return makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
    }

    override fun canDropOver(
        recyclerView: RecyclerView,
        current: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean = adapter.isDraggable(target)

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean {
        return adapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
}
