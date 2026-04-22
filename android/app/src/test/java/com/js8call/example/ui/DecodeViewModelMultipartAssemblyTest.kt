package com.js8call.example.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodeViewModelMultipartAssemblyTest {
    @Test
    fun insertsSpaceAfterGroupCallsignAcrossFrames() {
        val assembled = assembleMultipartDecodeText(
            listOf("2W0OXE: @RAYNET", "TEST")
        )

        assertEquals("2W0OXE: @RAYNET TEST", assembled)
    }

    @Test
    fun insertsSpaceAfterAllcallAcrossFrames() {
        val assembled = assembleMultipartDecodeText(
            listOf("@ALLCALL", "QUERY")
        )

        assertEquals("@ALLCALL QUERY", assembled)
    }

    @Test
    fun stillInsertsSpaceAfterDirectedCallsign() {
        val assembled = assembleMultipartDecodeText(
            listOf("2W0OXE", "SNR?")
        )

        assertEquals("2W0OXE SNR?", assembled)
    }

    @Test
    fun doesNotInsertSpaceAfterNonAddressWord() {
        assertFalse(shouldInsertMultipartSpace(StringBuilder("HELLO"), "WORLD"))
    }

    @Test
    fun detectsGroupTokensAsMultipartBoundaries() {
        assertTrue(shouldInsertMultipartSpace(StringBuilder("@RAYNET"), "TEST"))
    }
}
