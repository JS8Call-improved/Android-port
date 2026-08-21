package com.js8call.example.service

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageAssemblyTest {
    @Test
    fun preservesTurboFrameBoundariesWithoutInsertingSpaces() {
        assertEquals("CHECKSUM", assembleMsgPayload(listOf("CHECK", "SUM")))
        assertEquals("HELLO WORLD", assembleMsgPayload(listOf("HELLO ", "WORLD")))
    }
}
