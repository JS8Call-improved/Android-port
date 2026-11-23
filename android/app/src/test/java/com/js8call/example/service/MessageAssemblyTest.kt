package com.js8call.example.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageAssemblyTest {
    @Test
    fun preservesTurboFrameBoundariesWithoutInsertingSpaces() {
        assertEquals("CHECKSUM", assembleMsgPayload(listOf("CHECK", "SUM")))
        assertEquals("HELLO WORLD", assembleMsgPayload(listOf("HELLO ", "WORLD")))
    }

    @Test
    fun matchesTheNearestMultipartHeaderWithinDecodeTolerance() {
        assertEquals(1500, findClosestMsgBufferKey(listOf(1494, 1500), 1498f))
        assertEquals(1494, findClosestMsgBufferKey(listOf(1494, 1500), 1493f))
        assertEquals(null, findClosestMsgBufferKey(listOf(1494), 1505f))
    }

    @Test
    fun threadsFreeTextAndCommandRepliesAfterTheAddressee() {
        assertEquals("", replyHeaderText("", ""))
        assertEquals("SNR -12", replyHeaderText("SNR", "-12"))
        assertEquals("SNR?", replyHeaderText("snr?", ""))
        assertEquals("GRID", replyHeaderText("GRID", ""))
        assertEquals("ACK", replyHeaderText("ACK", ""))
        assertEquals("HW CPY?", replyHeaderText("HW CPY?", ""))
    }

    @Test
    fun leavesOutTrafficWithItsOwnHandlerOrThatNobodyTyped() {
        listOf("MSG", "MSG TO:", ">", "QUERY", "QUERY MSGS", "QUERY MSGS?", "QUERY CALL", "CMD")
            .forEach { assertNull(it, replyHeaderText(it, "KN4CRD")) }
        listOf("HEARTBEAT SNR", "HEARTBEAT", "HB", "CQ")
            .forEach { assertNull(it, replyHeaderText(it, "-05")) }
    }

    @Test
    fun joinsTheHeaderToItsDataFramesWithOneSpace() {
        assertEquals("HOW COPY", assembleReplyText("", listOf("HOW CO", "PY")))
        assertEquals("GRID EM13TE", assembleReplyText("GRID", listOf("EM13TE")))
        // Commands that are not buffered keep the space in front of their data
        assertEquals("STATUS IDLE", assembleReplyText("STATUS", listOf(" IDLE")))
        assertEquals("ACK", assembleReplyText("ACK", emptyList()))
        assertEquals("", assembleReplyText("", emptyList()))
    }
}
