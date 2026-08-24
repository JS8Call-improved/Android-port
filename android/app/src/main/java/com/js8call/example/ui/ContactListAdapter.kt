package com.js8call.example.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.js8call.example.R
import com.js8call.example.data.ContactEntity
import com.js8call.example.util.AvatarColor
import com.js8call.example.util.DisplayName

/**
 * Adapter for the heard-station contact list.
 */
class ContactListAdapter : ListAdapter<ContactEntity, ContactListAdapter.ContactViewHolder>(DIFF) {

    var onItemClick: ((ContactEntity) -> Unit)? = null
    var onStarClick: ((ContactEntity) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_contact, parent, false)
        return ContactViewHolder(view)
    }

    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ContactViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val avatarFrame: View = itemView.findViewById(R.id.avatar_frame)
        private val avatarText: TextView = itemView.findViewById(R.id.avatar_text)
        private val callsignText: TextView = itemView.findViewById(R.id.callsign_text)
        private val hearsUsIcon: ImageView = itemView.findViewById(R.id.hears_us_icon)
        private val detailText: TextView = itemView.findViewById(R.id.detail_text)
        private val infoText: TextView = itemView.findViewById(R.id.info_text)
        private val commentText: TextView = itemView.findViewById(R.id.comment_text)
        private val ageText: TextView = itemView.findViewById(R.id.age_text)
        private val starButton: MaterialButton = itemView.findViewById(R.id.star_button)

        fun bind(contact: ContactEntity) {
            avatarText.text = DisplayName.initial(contact.callsign, contact.name)
            avatarFrame.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(itemView.context, AvatarColor.forCallsign(contact.callsign))
            )
            callsignText.text = DisplayName.of(contact.callsign, contact.name)
            hearsUsIcon.visibility = if (contact.heardUs) View.VISIBLE else View.GONE

            val res = itemView.resources
            val parts = mutableListOf<String>()
            // A named station shows its callsign here, since the headline
            // gave the row to the name.
            DisplayName.secondary(contact.callsign, contact.name)?.let { parts.add(it) }
            contact.snr?.let { parts.add(res.getString(R.string.contact_snr, it)) }
            contact.offset?.let { parts.add(res.getString(R.string.contact_offset, it.toInt())) }
            contact.grid?.let { parts.add(it) }
            detailText.text = parts.joinToString("  ·  ")
            detailText.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE

            if (contact.info.isNullOrBlank()) {
                infoText.visibility = View.GONE
            } else {
                infoText.visibility = View.VISIBLE
                infoText.text = contact.info
            }

            if (contact.comment.isNullOrBlank()) {
                commentText.visibility = View.GONE
            } else {
                commentText.visibility = View.VISIBLE
                commentText.text = contact.comment
            }

            ageText.text = formatAge(contact.lastHeard)

            starButton.setIconResource(
                if (contact.starred) R.drawable.ic_star else R.drawable.ic_star_outline
            )
            starButton.setIconTintResource(
                if (contact.starred) R.color.star_active else R.color.star_inactive
            )

            itemView.setOnClickListener { onItemClick?.invoke(contact) }
            starButton.setOnClickListener { onStarClick?.invoke(contact) }
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
