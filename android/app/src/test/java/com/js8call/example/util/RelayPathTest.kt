package com.js8call.example.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelayPathTest {

    @Test
    fun parseReadsStoredForm() {
        assertEquals(listOf("KA0XYZ", "N0DEF"), RelayPath.parse("KA0XYZ>N0DEF"))
    }

    @Test
    fun parseIsForgivingAboutSpacingAndCase() {
        assertEquals(listOf("KA0XYZ", "N0DEF"), RelayPath.parse(" ka0xyz > n0def "))
    }

    @Test
    fun parseTreatsBlankAsDirect() {
        assertEquals(emptyList<String>(), RelayPath.parse(null))
        assertEquals(emptyList<String>(), RelayPath.parse("   "))
        assertEquals(emptyList<String>(), RelayPath.parse(">>"))
    }

    @Test
    fun formatRoundTrips() {
        assertEquals("KA0XYZ>N0DEF", RelayPath.format(listOf("ka0xyz", " n0def ")))
        assertNull(RelayPath.format(emptyList()))
        assertNull(RelayPath.format(listOf("", "  ")))
    }

    @Test
    fun composeWithNoHopsLeavesTheBodyAlone() {
        // The caller sends this with KN4CRD as the directed callsign, so the
        // destination must not appear in the text as well.
        assertEquals("HELLO OM", RelayPath.compose(emptyList(), "KN4CRD", "HELLO OM"))
    }

    @Test
    fun composeOneHopMatchesDesktopForm() {
        assertEquals(
            "KA0XYZ>KN4CRD HELLO OM",
            RelayPath.compose(listOf("KA0XYZ"), "KN4CRD", "HELLO OM")
        )
    }

    @Test
    fun composeTwoHopsMatchesDesktopForm() {
        assertEquals(
            "KA0XYZ>N0DEF>KN4CRD HELLO OM",
            RelayPath.compose(listOf("KA0XYZ", "N0DEF"), "KN4CRD", "HELLO OM")
        )
    }

    @Test
    fun composeThreeHopsMatchesDesktopForm() {
        assertEquals(
            "KA0XYZ>N0DEF>W1AW>KN4CRD QUERY MSGS",
            RelayPath.compose(listOf("KA0XYZ", "N0DEF", "W1AW"), "KN4CRD", "QUERY MSGS")
        )
    }

    @Test
    fun composeNormalizesTheDestination() {
        assertEquals(
            "KA0XYZ>KN4CRD 73",
            RelayPath.compose(listOf("KA0XYZ"), " kn4crd ", "73")
        )
    }

    /**
     * The first hop is what the native packer reads as the directed callsign,
     * so it has to be the leading token followed by ">".
     */
    @Test
    fun composedTextStartsWithTheNearestHop() {
        val composed = RelayPath.compose(listOf("KA0XYZ", "N0DEF"), "KN4CRD", "HELLO")
        assertEquals("KA0XYZ", composed.substringBefore(">"))
    }

    @Test
    fun originatorOfReturnPathIsTheFarEnd() {
        // What a destination recovers from "HELLO *DE* W1AW *DE* KA0XYZ"
        // handed over by N0DEF: nearest hop first, originator last.
        assertEquals("W1AW", RelayPath.originatorOfReturnPath("N0DEF>KA0XYZ>W1AW"))
        assertEquals("W1AW", RelayPath.originatorOfReturnPath("KA0XYZ>W1AW"))
        assertNull(RelayPath.originatorOfReturnPath(null))
    }
}
