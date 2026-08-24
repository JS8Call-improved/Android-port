package com.js8call.example.util

import com.js8call.example.data.ContactEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactSearchTest {

    private fun contact(
        callsign: String,
        name: String? = null,
        grid: String? = null,
        comment: String? = null,
        info: String? = null
    ) = ContactEntity(
        callsign = callsign, name = name, lastHeard = 0L,
        grid = grid, comment = comment, info = info
    )

    private val contacts = listOf(
        contact("KN4CRD", name = "Jordan", grid = "EM73", comment = "sked partner"),
        contact("W1AW", info = "ARRL HQ STATION"),
        contact("KA0XYZ", grid = "EN34")
    )

    @Test
    fun blankQueryKeepsEverything() {
        assertEquals(contacts, ContactSearch.filter(contacts, ""))
        assertEquals(contacts, ContactSearch.filter(contacts, "   "))
    }

    @Test
    fun matchesOnCallsign() {
        assertEquals(listOf("W1AW"), ContactSearch.filter(contacts, "w1aw").map { it.callsign })
    }

    @Test
    fun matchesOnName() {
        assertEquals(listOf("KN4CRD"), ContactSearch.filter(contacts, "jordan").map { it.callsign })
    }

    @Test
    fun matchesOnGrid() {
        assertEquals(listOf("KA0XYZ"), ContactSearch.filter(contacts, "EN34").map { it.callsign })
    }

    @Test
    fun matchesOnComment() {
        assertEquals(listOf("KN4CRD"), ContactSearch.filter(contacts, "sked").map { it.callsign })
    }

    @Test
    fun matchesOnStationInfo() {
        assertEquals(listOf("W1AW"), ContactSearch.filter(contacts, "arrl").map { it.callsign })
    }

    @Test
    fun matchesPartOfAToken() {
        // Typing the first few characters has to narrow the list, or the
        // field is useless while it is being filled in.
        assertEquals(listOf("KN4CRD"), ContactSearch.filter(contacts, "KN4").map { it.callsign })
    }

    @Test
    fun noMatchReturnsNothing() {
        assertTrue(ContactSearch.filter(contacts, "VK2ZZZ").isEmpty())
    }

    @Test
    fun nullFieldsAreNotAMatch() {
        assertFalse(ContactSearch.matches(contact("KA0XYZ"), "jordan"))
    }

    @Test
    fun queryIsTrimmed() {
        assertEquals(listOf("KN4CRD"), ContactSearch.filter(contacts, "  jordan  ").map { it.callsign })
    }
}
