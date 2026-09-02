package com.js8call.example.service

import org.junit.Assert.assertEquals
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
}
