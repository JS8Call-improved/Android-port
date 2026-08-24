package com.js8call.example.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.js8call.example.R
import com.js8call.example.data.ContactEntity

/**
 * Stations we have heard, offered as the next hop. Only the first hop is
 * really a station we hear ourselves, so this is a shortcut rather than the
 * only way in: further hops are typed.
 */
class RelayCandidateAdapter(
    private val onAdd: (String) -> Unit
) : ListAdapter<ContactEntity, RelayCandidateAdapter.CandidateViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CandidateViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_relay_candidate, parent, false)
        return CandidateViewHolder(view, onAdd)
    }

    override fun onBindViewHolder(holder: CandidateViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class CandidateViewHolder(
        view: View,
        private val onAdd: (String) -> Unit
    ) : RecyclerView.ViewHolder(view) {

        private val callsignText: TextView = view.findViewById(R.id.callsign_text)
        private val detailText: TextView = view.findViewById(R.id.detail_text)
        private val addButton: ImageButton = view.findViewById(R.id.add_button)

        fun bind(contact: ContactEntity) {
            callsignText.text = contact.callsign
            val snr = contact.snr?.let { "$it dB · " }.orEmpty()
            detailText.text = snr + formatAge(contact.lastHeard)
            itemView.setOnClickListener { onAdd(contact.callsign) }
            addButton.setOnClickListener { onAdd(contact.callsign) }
        }

        private fun formatAge(timestamp: Long): String {
            val seconds = (System.currentTimeMillis() - timestamp) / 1000
            return when {
                seconds < 60 -> itemView.resources.getString(R.string.contact_age_now)
                seconds < 3600 -> "${seconds / 60}m"
                seconds < 86400 -> "${seconds / 3600}h"
                else -> "${seconds / 86400}d"
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ContactEntity>() {
            override fun areItemsTheSame(oldItem: ContactEntity, newItem: ContactEntity) =
                oldItem.callsign == newItem.callsign

            override fun areContentsTheSame(oldItem: ContactEntity, newItem: ContactEntity) =
                oldItem == newItem
        }
    }
}
