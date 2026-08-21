package com.js8call.example.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallsignValidatorTest {
    @Test
    fun acceptsCommonAndPortableCallsigns() {
        assertTrue(CallsignValidator.isAmateurCallsign("K1ABC"))
        assertTrue(CallsignValidator.isAmateurCallsign("2W0OXE"))
        assertTrue(CallsignValidator.isAmateurCallsign("EA8/K1ABC"))
        assertTrue(CallsignValidator.isAmateurCallsign("K1ABC/P"))
    }

    @Test
    fun rejectsCallsignLikeFreeText() {
        assertFalse(CallsignValidator.isAmateurCallsign("TEST123"))
        assertFalse(CallsignValidator.isAmateurCallsign("HELLO"))
        assertFalse(CallsignValidator.isAmateurCallsign("@ALLCALL"))
        assertFalse(CallsignValidator.isAmateurCallsign("K1ABC!"))
    }
}
