package com.js8call.example.util

import com.js8call.example.util.LinkEvidence.Observation
import com.js8call.example.util.LinkEvidence.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkEvidenceTest {

    private val me = "N5EKS"

    @Test
    fun plainHeartbeatYieldsOnlyTheDecodeEdge() {
        val obs = LinkEvidence.fromDecode(me, "KA0XYZ: @HB HEARTBEAT EM73", -7)
        assertEquals(listOf(Observation(me, "KA0XYZ", -7, Source.DECODE)), obs)
    }

    @Test
    fun heartbeatAckToUsReportsTheyHearUs() {
        val obs = LinkEvidence.fromDecode(me, "KA0XYZ: N5EKS HEARTBEAT SNR -08", -3)
        assertEquals(
            listOf(
                Observation(me, "KA0XYZ", -3, Source.DECODE),
                Observation("KA0XYZ", me, -8, Source.HB_ACK)
            ),
            obs
        )
    }

    @Test
    fun thirdPartyHeartbeatAckYieldsBothEdges() {
        val obs = LinkEvidence.fromDecode(me, "N0DEF: KA0XYZ HEARTBEAT SNR +05", -12)
        assertEquals(
            listOf(
                Observation(me, "N0DEF", -12, Source.DECODE),
                Observation("N0DEF", "KA0XYZ", 5, Source.HB_ACK)
            ),
            obs
        )
    }

    @Test
    fun snrReplyIsAReport() {
        val obs = LinkEvidence.fromDecode(me, "N0DEF: KA0XYZ SNR -12", -12)
        assertEquals(Observation("N0DEF", "KA0XYZ", -12, Source.SNR_REPORT), obs[1])
    }

    @Test
    fun snrQueryIsNotAReport() {
        // "SNR?" asks, it does not answer. Only the decode edge remains.
        val obs = LinkEvidence.fromDecode(me, "KA0XYZ: N0DEF SNR?", -5)
        assertEquals(1, obs.size)
        assertEquals(Source.DECODE, obs[0].source)
    }

    @Test
    fun hearingListYieldsAnEdgePerCallsignWithoutNumbers() {
        val obs = LinkEvidence.fromDecode(me, "N0DEF: KA0XYZ HEARING W1AW KN4CRD", -10)
        assertEquals(
            listOf(
                Observation(me, "N0DEF", -10, Source.DECODE),
                Observation("N0DEF", "W1AW", null, Source.HEARING),
                Observation("N0DEF", "KN4CRD", null, Source.HEARING)
            ),
            obs
        )
    }

    @Test
    fun hearingListIgnoresTokensThatAreNotCallsigns() {
        val obs = LinkEvidence.fromDecode(me, "N0DEF: KA0XYZ HEARING W1AW ♢", -10)
        assertEquals(2, obs.size)
        assertEquals("W1AW", obs[1].heard)
    }

    @Test
    fun ackProvesReceiptWithoutANumber() {
        val obs = LinkEvidence.fromDecode(me, "W1AW: KN4CRD ACK", -6)
        assertEquals(Observation("W1AW", "KN4CRD", null, Source.ACK), obs[1])
    }

    @Test
    fun groupAddressedCommandYieldsNoThirdPartyEdge() {
        val obs = LinkEvidence.fromDecode(me, "KA0XYZ: @ALLCALL ACK", -5)
        assertEquals(1, obs.size)
    }

    @Test
    fun ourOwnTransmissionYieldsNothing() {
        assertTrue(LinkEvidence.fromDecode(me, "N5EKS: KA0XYZ SNR -12", 20).isEmpty())
    }

    @Test
    fun freeTextYieldsOnlyTheDecodeEdge() {
        val obs = LinkEvidence.fromDecode(me, "KA0XYZ: N0DEF HELLO FROM THE PARK", -9)
        assertEquals(1, obs.size)
        assertEquals(Source.DECODE, obs[0].source)
    }

    @Test
    fun garbageWithoutASenderYieldsNothing() {
        assertTrue(LinkEvidence.fromDecode(me, "  ~~~ NOISE ~~~", -20).isEmpty())
        assertTrue(LinkEvidence.fromDecode(me, "", -20).isEmpty())
    }

    @Test
    fun relayFrameYieldsOnlyTheDecodeEdgeFromDecode() {
        // The *DE* trail spans data frames, so the raw first frame is not
        // mined here. fromRelayChain handles it after reassembly.
        val obs = LinkEvidence.fromDecode(me, "KA0XYZ: N0DEF> KN4CRD HELLO", -4)
        assertEquals(1, obs.size)
        assertEquals(Source.DECODE, obs[0].source)

        // The composed form carries the whole path in the first token.
        val composed = LinkEvidence.fromDecode(me, "KA0XYZ: N0DEF>KN4CRD HELLO", -4)
        assertEquals(1, composed.size)
    }

    @Test
    fun relayChainPairsEachHopWithItsPredecessor() {
        // KA0XYZ originated, N0DEF forwarded (appending KA0XYZ), and W1AW
        // transmitted the frame we decoded (appending N0DEF).
        val obs = LinkEvidence.fromRelayChain("W1AW", "KN4CRD HELLO *DE* KA0XYZ *DE* N0DEF")
        assertEquals(
            listOf(
                Observation("N0DEF", "KA0XYZ", null, Source.RELAY),
                Observation("W1AW", "N0DEF", null, Source.RELAY)
            ),
            obs
        )
    }

    @Test
    fun singleHopRelayChain() {
        val obs = LinkEvidence.fromRelayChain("N0DEF", "KN4CRD HELLO *DE* KA0XYZ")
        assertEquals(listOf(Observation("N0DEF", "KA0XYZ", null, Source.RELAY)), obs)
    }

    @Test
    fun relayChainWithoutATrailYieldsNothing() {
        assertTrue(LinkEvidence.fromRelayChain("KA0XYZ", "KN4CRD HELLO").isEmpty())
    }

    @Test
    fun unparseableSnrKeepsTheEdgeWithoutANumber() {
        val obs = LinkEvidence.fromDecode(me, "N0DEF: KA0XYZ HEARTBEAT SNR XX", -12)
        assertEquals(Observation("N0DEF", "KA0XYZ", null, Source.HB_ACK), obs[1])
    }

    @Test
    fun eotMarkerAfterSnrDoesNotBreakParsing() {
        val obs = LinkEvidence.fromDecode(me, "N0DEF: KA0XYZ HEARTBEAT SNR +05 ♢", -12)
        assertEquals(5, obs[1].snr)
    }
}
