package com.js8call.example.util

import com.js8call.example.data.LinkObservationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkGraphTest {

    private fun obs(
        reporter: String,
        heard: String,
        snr: Int?,
        at: Long,
        source: String = "HB_ACK"
    ) = LinkObservationEntity(
        reporter = reporter, heard = heard, snr = snr,
        source = source, dialFreqHz = null, observedAt = at
    )

    @Test
    fun collapsesAPairToOneEdgeWithTheLatestNumberedSnr() {
        val graph = NetworkGraph.build(
            listOf(
                obs("N0DEF", "KA0XYZ", -15, at = 1000),
                obs("N0DEF", "KA0XYZ", null, at = 3000, source = "ACK"),
                obs("N0DEF", "KA0XYZ", -6, at = 2000)
            ),
            myCallsign = "N5EKS"
        )
        assertEquals(1, graph.edges.size)
        val edge = graph.edges[0]
        // The ACK at 3000 is newest overall but carries no number; the SNR
        // comes from the newest observation that has one.
        assertEquals(-6, edge.snr)
        assertEquals(3000, edge.lastObservedAt)
        assertEquals(3, edge.observationCount)
    }

    @Test
    fun oppositeDirectionsStaySeparateEdges() {
        val graph = NetworkGraph.build(
            listOf(
                obs("N0DEF", "KA0XYZ", -15, at = 1000),
                obs("KA0XYZ", "N0DEF", -3, at = 1000)
            ),
            myCallsign = "N5EKS"
        )
        assertEquals(2, graph.edges.size)
        assertTrue(graph.isBidirectional("N0DEF", "KA0XYZ"))
    }

    @Test
    fun oneDirectionIsNotBidirectional() {
        val graph = NetworkGraph.build(
            listOf(obs("N0DEF", "KA0XYZ", -15, at = 1000)),
            myCallsign = "N5EKS"
        )
        assertFalse(graph.isBidirectional("N0DEF", "KA0XYZ"))
    }

    @Test
    fun myStationIsANodeEvenWithNoEdges() {
        val graph = NetworkGraph.build(emptyList(), myCallsign = "N5EKS")
        assertEquals(listOf("N5EKS"), graph.nodes)
        assertTrue(graph.edges.isEmpty())
    }

    @Test
    fun nodesComeFromBothEndsOfEveryEdge() {
        val graph = NetworkGraph.build(
            listOf(
                obs("N5EKS", "N0DEF", -10, at = 1000, source = "DECODE"),
                obs("N0DEF", "W1AW", null, at = 1000, source = "HEARING")
            ),
            myCallsign = "N5EKS"
        )
        assertEquals(setOf("N5EKS", "N0DEF", "W1AW"), graph.nodes.toSet())
    }

    @Test
    fun edgeWithoutAnyNumberHasNullSnr() {
        val graph = NetworkGraph.build(
            listOf(obs("N0DEF", "W1AW", null, at = 1000, source = "HEARING")),
            myCallsign = "N5EKS"
        )
        assertNull(graph.edges[0].snr)
    }

    @Test
    fun strengthScalesWithSnrAndFloorsForUnnumbered() {
        assertTrue(NetworkGraph.strength(0) == 1f)
        assertTrue(NetworkGraph.strength(10) == 1f)
        assertTrue(NetworkGraph.strength(-28) < NetworkGraph.strength(-10))
        assertEquals(0.25f, NetworkGraph.strength(null), 1e-6f)
    }
}
