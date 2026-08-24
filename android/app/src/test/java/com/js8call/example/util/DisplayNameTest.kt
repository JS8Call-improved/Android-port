package com.js8call.example.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayNameTest {

    @Test
    fun unnamedStationLeadsWithItsCallsign() {
        assertEquals("KN4CRD", DisplayName.of("KN4CRD", null))
        assertNull(DisplayName.secondary("KN4CRD", null))
    }

    @Test
    fun namedStationLeadsWithTheNameAndKeepsTheCallsign() {
        assertEquals("Jordan", DisplayName.of("KN4CRD", "Jordan"))
        assertEquals("KN4CRD", DisplayName.secondary("KN4CRD", "Jordan"))
    }

    @Test
    fun blankNameCountsAsNoName() {
        // A cleared field arrives as "" or "   ", not null.
        assertEquals("KN4CRD", DisplayName.of("KN4CRD", ""))
        assertEquals("KN4CRD", DisplayName.of("KN4CRD", "   "))
        assertNull(DisplayName.secondary("KN4CRD", "   "))
    }

    @Test
    fun nameIsTrimmed() {
        assertEquals("Jordan", DisplayName.of("KN4CRD", "  Jordan  "))
    }

    @Test
    fun initialFollowsWhicheverLeads() {
        assertEquals("J", DisplayName.initial("KN4CRD", "Jordan"))
        assertEquals("K", DisplayName.initial("KN4CRD", null))
    }

    @Test
    fun initialSkipsPunctuationAndUppercases() {
        assertEquals("D", DisplayName.initial("KN4CRD", "\"dave\""))
        assertEquals("N", DisplayName.initial("@NET", null))
    }

    @Test
    fun initialFallsBackWhenThereIsNoLetterOrDigit() {
        assertEquals("?", DisplayName.initial("---", null))
    }
}
