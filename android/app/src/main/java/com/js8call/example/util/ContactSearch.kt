package com.js8call.example.util

import com.js8call.example.data.ContactEntity

/**
 * Filtering for the contacts list. The list grows on its own every time a
 * station is decoded, so it needs a way to find one station without
 * scrolling past every station the radio has ever heard.
 */
object ContactSearch {

    /**
     * Match [query] against everything that identifies a station: callsign,
     * name, grid, the operator's own notes, and the INFO the station sent.
     * A blank query matches everything, so the unfiltered list is the same
     * code path as the filtered one.
     */
    fun filter(contacts: List<ContactEntity>, query: String): List<ContactEntity> {
        val needle = query.trim()
        if (needle.isEmpty()) return contacts
        return contacts.filter { matches(it, needle) }
    }

    fun matches(contact: ContactEntity, query: String): Boolean {
        val needle = query.trim()
        if (needle.isEmpty()) return true
        return sequenceOf(
            contact.callsign, contact.name, contact.grid, contact.comment, contact.info
        ).any { it != null && it.contains(needle, ignoreCase = true) }
    }
}
