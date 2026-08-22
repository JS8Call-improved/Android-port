package com.js8call.example.util

import com.js8call.example.data.LinkObservationEntity
import java.util.Locale

/**
 * Aggregates raw link observations into the graph the network map draws:
 * one node per station, one directed edge per (reporter, heard) pair.
 *
 * The edge keeps the most recent numbered SNR rather than an average,
 * because on HF the current state of a path matters and an average blends
 * in propagation that no longer exists.
 */
object NetworkGraph {

    /** [from] hears [to]. */
    data class Edge(
        val from: String,
        val to: String,
        /** Most recent numbered SNR, or null when no observation carried one. */
        val snr: Int?,
        val lastObservedAt: Long,
        val observationCount: Int
    )

    data class Graph(val nodes: List<String>, val edges: List<Edge>) {

        /** Both directions of a pair confirmed, the strongest relay signal. */
        fun isBidirectional(a: String, b: String): Boolean =
            edges.any { it.from == a && it.to == b } &&
                edges.any { it.from == b && it.to == a }
    }

    fun build(observations: List<LinkObservationEntity>, myCallsign: String): Graph {
        val my = myCallsign.trim().uppercase(Locale.US)

        val byPair = observations
            .filter { it.reporter != it.heard }
            .groupBy { it.reporter to it.heard }

        val edges = byPair.map { (pair, group) ->
            val latestNumbered = group.filter { it.snr != null }.maxByOrNull { it.observedAt }
            Edge(
                from = pair.first,
                to = pair.second,
                snr = latestNumbered?.snr,
                lastObservedAt = group.maxOf { it.observedAt },
                observationCount = group.size
            )
        }.sortedByDescending { it.lastObservedAt }

        val nodes = buildList {
            if (my.isNotEmpty()) add(my)
            edges.forEach {
                if (it.from !in this) add(it.from)
                if (it.to !in this) add(it.to)
            }
        }

        return Graph(nodes, edges)
    }

    /**
     * Edge strength on a 0..1 scale for drawing, from the JS8 SNR range.
     * -28 is the decode floor; anything at or above 0 is a strong path.
     * An edge with no number sits at the weak end rather than zero, since
     * it is still a confirmed link.
     */
    fun strength(snr: Int?): Float {
        if (snr == null) return 0.25f
        return ((snr + 28f) / 28f).coerceIn(0.15f, 1f)
    }
}
