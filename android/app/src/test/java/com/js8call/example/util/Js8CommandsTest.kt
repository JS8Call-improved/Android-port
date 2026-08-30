package com.js8call.example.util

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Js8CommandsTest {

    @Test
    fun matchesMultiWordCommandsWhole() {
        assertEquals(Js8Commands.Match("QUERY MSGS", ""), Js8Commands.matchAt("QUERY MSGS"))
        assertEquals(Js8Commands.Match("QUERY MSGS?", ""), Js8Commands.matchAt("QUERY MSGS?"))
        assertEquals(Js8Commands.Match("QUERY CALL", "KN4CRD"), Js8Commands.matchAt("QUERY CALL KN4CRD"))
        assertEquals(Js8Commands.Match("HEARTBEAT SNR", "-10"), Js8Commands.matchAt("HEARTBEAT SNR -10"))
        assertEquals(Js8Commands.Match("HW CPY?", ""), Js8Commands.matchAt("HW CPY?"))
        assertEquals(Js8Commands.Match("DIT DIT", ""), Js8Commands.matchAt("DIT DIT"))
    }

    /**
     * QUERY MSG {id} is command QUERY with a MSG argument, not a command of its
     * own. The desktop dispatches it the same way.
     */
    @Test
    fun treatsQueryMsgIdAsQueryWithArgument() {
        assertEquals(Js8Commands.Match("QUERY", "MSG 3"), Js8Commands.matchAt("QUERY MSG 3"))
    }

    @Test
    fun acceptsMsgToWithAndWithoutSpaceAfterColon() {
        assertEquals(Js8Commands.Match("MSG TO:", "KN4CRD HELLO"), Js8Commands.matchAt("MSG TO:KN4CRD HELLO"))
        assertEquals(Js8Commands.Match("MSG TO:", "KN4CRD HELLO"), Js8Commands.matchAt("MSG TO: KN4CRD HELLO"))
    }

    @Test
    fun requiresTokenBoundaryForNamesWithoutColon() {
        // MSG must not match the front of MSGS
        assertEquals(Js8Commands.Match("MSG", "HELLO"), Js8Commands.matchAt("MSG HELLO"))
        val msgs = Js8Commands.matchAt("MSGS HELLO")
        assertTrue("MSGS should not match MSG", msgs == null || msgs.command != "MSG")
    }

    @Test
    fun matchesSingleWordCommandsAndTheirPayloads() {
        assertEquals(Js8Commands.Match("SNR?", ""), Js8Commands.matchAt("SNR?"))
        assertEquals(Js8Commands.Match("GRID?", ""), Js8Commands.matchAt("GRID?"))
        assertEquals(Js8Commands.Match("INFO?", ""), Js8Commands.matchAt("INFO?"))
        assertEquals(Js8Commands.Match("STATUS?", ""), Js8Commands.matchAt("STATUS?"))
        assertEquals(Js8Commands.Match("HEARING?", ""), Js8Commands.matchAt("HEARING?"))
        assertEquals(Js8Commands.Match("AGN?", ""), Js8Commands.matchAt("AGN?"))
        assertEquals(Js8Commands.Match("ACK", ""), Js8Commands.matchAt("ACK"))
        assertEquals(Js8Commands.Match("MSG", "HELLO THERE"), Js8Commands.matchAt("MSG HELLO THERE"))
    }

    @Test
    fun reportsBufferedAndChecksummedCommands() {
        assertTrue(Js8Commands.isBuffered("MSG"))
        assertTrue(Js8Commands.isBuffered("MSG TO:"))
        assertTrue(Js8Commands.isBuffered("QUERY MSGS"))
        assertTrue(Js8Commands.isChecksummed("MSG TO:"))
    }

    @Test
    fun returnsNullForUnknownText() {
        assertNull(Js8Commands.matchAt("HELLO WORLD"))
        assertNull(Js8Commands.matchAt(""))
    }

    /**
     * Drift guard. The native table is the one the protocol actually packs
     * against, so a divergence here is a wire-format bug, not a style problem.
     */
    @Test
    fun kotlinTableMatchesNativeVaricodeTable() {
        val source = findVaricodeSource()
        assertNotNull(
            "Could not locate core/src/protocol/varicode.cpp from ${File("").absolutePath}",
            source
        )

        val body = source!!.readText()
        val start = body.indexOf("kDirectedCmds")
        assertTrue("kDirectedCmds not found in ${source.path}", start >= 0)
        val open = body.indexOf('{', start)
        val close = body.indexOf("};", open)
        assertTrue("kDirectedCmds block is malformed", open in 0 until close)

        val entry = Regex("""\{\s*"([^"]*)"\s*,\s*(-?\d+)\s*\}""")
        val native = mutableMapOf<String, Int>()
        for (m in entry.findAll(body.substring(open, close))) {
            val name = m.groupValues[1].trim()
            // The blank names are the free-text sentinel, which is not a command
            if (name.isEmpty()) continue
            native[name] = m.groupValues[2].toInt()
        }

        assertTrue("Parsed no entries from the native table", native.size > 20)
        assertEquals(
            "Kotlin and native directed-command tables disagree",
            native.toSortedMap(),
            Js8Commands.COMMANDS.toSortedMap()
        )
    }

    private fun findVaricodeSource(): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "core/src/protocol/varicode.cpp")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        return null
    }
}
