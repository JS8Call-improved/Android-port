package com.js8call.example.util

/**
 * A relay path is an ordered list of stations that carry a message to its
 * destination, nearest hop first. It is stored and transmitted in the `A>B`
 * notation the JS8 relay command uses.
 *
 * On the wire the originator prepends `CALL>` once per hop, so a message to
 * KN4CRD through KA0XYZ then N0DEF reads `KA0XYZ>N0DEF>KN4CRD HELLO` and is
 * sent with no separate directed callsign. Each hop rewrites the head of the
 * payload and appends its predecessor, and the destination recovers the whole
 * return path from the trailing `*DE*` chain.
 */
object RelayPath {

    /**
     * Every hop retransmits the whole message, so a three-frame message over
     * two hops is three full transmissions. In Slow mode that runs to minutes
     * of airtime. This is a guard rail, not a protocol limit.
     */
    const val MAX_HOPS = 3

    /** Read the stored `A>B` form into hops. Unknown or blank input is direct. */
    fun parse(stored: String?): List<String> {
        if (stored.isNullOrBlank()) return emptyList()
        return stored.split(">")
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
    }

    /** Write hops back to the stored form. An empty path stores nothing. */
    fun format(hops: List<String>): String? {
        val clean = hops.map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        return if (clean.isEmpty()) null else clean.joinToString(">")
    }

    /**
     * Build the text to transmit. With no hops this is the plain body, which
     * the caller sends with [destination] as the directed callsign. With hops
     * the destination moves into the text, because the relay command carries
     * it as payload rather than as the directed target.
     */
    fun compose(hops: List<String>, destination: String, text: String): String {
        val clean = parse(format(hops))
        val dest = destination.trim().uppercase()
        if (clean.isEmpty()) return text
        return clean.joinToString(">", postfix = ">") + dest + " " + text
    }

    /**
     * The station a relayed message came from, given the return path a
     * destination recovers from the `*DE*` chain. That path runs nearest hop
     * first, so the originator is last. The nearest hop only handed it over,
     * and threading or answering against it would credit the wrong station.
     */
    fun originatorOfReturnPath(returnPath: String?): String? = parse(returnPath).lastOrNull()
}
