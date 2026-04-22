package com.js8call.example.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TxMessageClassifierTest {
    @Test
    fun detectsHeartbeatFormsExactly() {
        assertTrue(TxMessageClassifier.isHeartbeatMessage("HB"))
        assertTrue(TxMessageClassifier.isHeartbeatMessage("HB FN31"))
        assertTrue(TxMessageClassifier.isHeartbeatMessage("HEARTBEAT"))
        assertTrue(TxMessageClassifier.isHeartbeatMessage("HEARTBEAT FN31"))
        assertTrue(TxMessageClassifier.isHeartbeatMessage("@HB HEARTBEAT FN31"))
    }

    @Test
    fun doesNotTreatSwissCallsignsAsHeartbeat() {
        assertFalse(TxMessageClassifier.isHeartbeatMessage("HB9ABC"))
        assertFalse(TxMessageClassifier.isHeartbeatMessage("HB3XYZ SNR?"))
        assertFalse(TxMessageClassifier.isHeartbeatMessage("HB9ABC MSG TEST"))
    }

    @Test
    fun detectsBaseMessagesWithoutMatchingCallsignPrefixes() {
        assertTrue(TxMessageClassifier.isBaseMessage("CQ"))
        assertTrue(TxMessageClassifier.isBaseMessage("CQ DX"))
        assertTrue(TxMessageClassifier.isBaseMessage("@ALLCALL QUERY"))
        assertFalse(TxMessageClassifier.isBaseMessage("HB9ABC SNR?"))
        assertFalse(TxMessageClassifier.isBaseMessage("CQDX"))
    }
}
