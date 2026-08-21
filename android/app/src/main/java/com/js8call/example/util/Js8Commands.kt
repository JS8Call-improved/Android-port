package com.js8call.example.util

/**
 * The JS8 directed command vocabulary.
 *
 * This mirrors kDirectedCmds in core/src/protocol/varicode.cpp, which is the
 * table the native layer packs and unpacks against. Js8CommandsTest reads that
 * file and fails when the two drift apart, so the copy stays honest without a
 * JNI crossing on the decode path.
 *
 * Names are stored without the leading space the native table carries.
 */
object Js8Commands {

    const val CMD_MSG = "MSG"
    const val CMD_MSG_TO = "MSG TO:"
    const val CMD_QUERY = "QUERY"
    const val CMD_QUERY_MSGS = "QUERY MSGS"
    const val CMD_QUERY_CALL = "QUERY CALL"
    const val CMD_ACK = "ACK"
    const val CMD_NACK = "NACK"
    const val CMD_YES = "YES"
    const val CMD_NO = "NO"

    /** Command name to the number the protocol packs it as. */
    val COMMANDS: Map<String, Int> = mapOf(
        "HEARTBEAT" to -1,
        "HB" to -1,
        "CQ" to -1,
        "SNR?" to 0,
        "?" to 0,
        "DIT DIT" to 1,
        "NACK" to 2,
        "HEARING?" to 3,
        "GRID?" to 4,
        ">" to 5,
        "STATUS?" to 6,
        "STATUS" to 7,
        "HEARING" to 8,
        "MSG" to 9,
        "MSG TO:" to 10,
        "QUERY" to 11,
        "QUERY MSGS" to 12,
        "QUERY MSGS?" to 12,
        "QUERY CALL" to 13,
        "ACK" to 14,
        "GRID" to 15,
        "INFO?" to 16,
        "INFO" to 17,
        "FB" to 18,
        "HW CPY?" to 19,
        "SK" to 20,
        "RR" to 21,
        "QSL?" to 22,
        "QSL" to 23,
        "CMD" to 24,
        "SNR" to 25,
        "NO" to 26,
        "YES" to 27,
        "73" to 28,
        "HEARTBEAT SNR" to 29,
        "AGN?" to 30
    )

    /**
     * Commands whose payload spans data frames, so the receiver has to buffer
     * until the last-frame bit before the text is complete.
     */
    val BUFFERED: Set<Int> = setOf(5, 9, 10, 11, 12, 13, 15, 24)

    /** Commands that carry a trailing checksum, and its width in bits. */
    val CHECKSUMMED: Map<Int, Int> = mapOf(
        5 to 16, 9 to 16, 10 to 16, 11 to 16, 12 to 16, 13 to 16, 15 to 0, 24 to 16
    )

    /** Longest name first, so QUERY MSGS wins over QUERY and MSG TO: over MSG. */
    private val byLongestName: List<String> =
        COMMANDS.keys.sortedByDescending { it.length }

    data class Match(val command: String, val payload: String)

    /**
     * Match a command at the head of [remainder], which is everything after the
     * addressed callsign.
     *
     * Matching runs against the raw string rather than tokens because two of
     * these names contain a space, and because MSG TO: is written both as
     * "MSG TO:KN4CRD" and as "MSG TO: KN4CRD" depending on who composed it.
     * Returns null when nothing matches, leaving the caller to decide.
     */
    fun matchAt(remainder: String): Match? {
        val text = remainder.trimStart()
        if (text.isEmpty()) return null

        for (name in byLongestName) {
            if (!text.regionMatches(0, name, 0, name.length, ignoreCase = true)) continue

            // A name ending in ':' may be followed immediately by its argument.
            // Every other name has to end on a token boundary, so that MSG does
            // not match the front of MSGS.
            if (!name.endsWith(":")) {
                val next = text.getOrNull(name.length)
                if (next != null && !next.isWhitespace()) continue
            }

            return Match(name, text.substring(name.length).trim())
        }
        return null
    }

    fun isBuffered(command: String): Boolean =
        COMMANDS[command.uppercase()]?.let { BUFFERED.contains(it) } == true

    fun isChecksummed(command: String): Boolean =
        COMMANDS[command.uppercase()]?.let { CHECKSUMMED.containsKey(it) } == true
}
