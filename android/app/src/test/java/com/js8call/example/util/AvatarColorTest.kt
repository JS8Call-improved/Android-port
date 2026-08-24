package com.js8call.example.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarColorTest {

    @Test
    fun sameCallsignAlwaysGetsTheSameColor() {
        val first = AvatarColor.forCallsign("KN4CRD")
        repeat(5) { assertEquals(first, AvatarColor.forCallsign("KN4CRD")) }
    }

    @Test
    fun caseAndSpacingDoNotChangeTheColor() {
        assertEquals(
            AvatarColor.forCallsign("KN4CRD"),
            AvatarColor.forCallsign(" kn4crd ")
        )
    }

    @Test
    fun emptyCallsignStillReturnsAColor() {
        assertTrue(AvatarColor.forCallsign("") != 0)
    }

    /**
     * A palette that lands every station on two or three colours would be
     * worse than no colour at all, so check the spread over a realistic set.
     */
    @Test
    fun realisticCallsignsSpreadAcrossThePalette() {
        val calls = listOf(
            "KN4CRD", "W1AW", "KA0XYZ", "N0DEF", "VE3ABC", "G0ABC", "JA1XYZ",
            "VK2DEF", "DL1ABC", "F5XYZ", "EA3ABC", "PY2DEF", "ZL1ABC", "OH8STN",
            "K5ABC", "N5EKS", "KB1XX", "W6DEF", "VE7XYZ", "LU1ABC"
        )
        val used = calls.map { AvatarColor.forCallsign(it) }.toSet()
        assertTrue("only ${used.size} colors over ${calls.size} calls", used.size >= 5)
    }
}
