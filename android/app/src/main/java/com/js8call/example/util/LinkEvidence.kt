package com.js8call.example.util

import java.util.Locale

/**
 * Mines decoded traffic for who-hears-whom evidence, the raw material of the
 * network map. Everything here is inference from frames that were mostly not
 * addressed to us:
 *
 *  - any decode proves we hear its sender, at the SNR we measured
 *  - "N0DEF: KA0XYZ HEARTBEAT SNR +05" proves N0DEF hears KA0XYZ at +05
 *  - "N0DEF: KA0XYZ SNR -12" proves the same, from an SNR? exchange
 *  - "N0DEF: KA0XYZ HEARING W1AW K5ABC" proves N0DEF hears both, no number
 *  - "N0DEF: KA0XYZ ACK" proves N0DEF received something from KA0XYZ
 *  - a forwarded relay frame's *DE* trail proves each hop heard the one before
 *
 * Links are directed. A hearing B says nothing about B hearing A, and on HF
 * the reverse direction routinely differs.
 */
object LinkEvidence {

    /**
     * What kind of frame produced the observation. Kept on the row so the map
     * and the path recommender can weight a checksummed relay transfer above a
     * bare HEARING mention.
     */
    enum class Source { DECODE, HB_ACK, SNR_REPORT, HEARING, ACK, RELAY }

    /** [reporter] heard [heard], optionally at [snr] dB. */
    data class Observation(
        val reporter: String,
        val heard: String,
        val snr: Int?,
        val source: Source
    )

    // "N0DEF: rest" — the transmitting station and everything after it.
    private val senderRegex = Regex("^\\s*([A-Z0-9/]+):\\s+(.*)$")

    // The "*DE* CALL" trail a forwarded relay frame accumulates, one entry
    // per hop, each naming the station that hop received from.
    private val relayTrailRegex = Regex("\\s(?:\\*DE\\*|VIA)\\s([A-Z0-9/]+)", RegexOption.IGNORE_CASE)

    // formatSNR output: "+05", "-12". Anything else is not a report.
    private val snrRegex = Regex("^([+-]\\d{1,2})\\b")

    // HEARING lists are short (the desktop and this app both cap near 4), so
    // anything past this is a garbled frame, not a longer list.
    private const val MAX_HEARING_CALLS = 8

    /**
     * Evidence in one decoded frame. [decodeSnr] is what our own receiver
     * measured for the frame's sender.
     *
     * Relay (`>`) frames only yield the we-hear-the-sender row here; their
     * *DE* trail spans data frames and is mined by [fromRelayChain] after
     * reassembly.
     */
    fun fromDecode(myCallsign: String, text: String, decodeSnr: Int): List<Observation> {
        val my = myCallsign.trim().uppercase(Locale.US)
        if (my.isEmpty()) return emptyList()

        val match = senderRegex.find(text.trim().uppercase(Locale.US)) ?: return emptyList()
        val from = match.groupValues[1]
        val rest = match.groupValues[2].trim()
        if (!CallsignValidator.isAmateurCallsign(from) || from == my) return emptyList()

        val observations = mutableListOf<Observation>()
        observations.add(Observation(my, from, decodeSnr, Source.DECODE))

        val tokens = rest.split(Regex("\\s+"))
        val to = tokens.firstOrNull().orEmpty()
        if (to.contains(">")) return observations

        val commandMatch = Js8Commands.matchAt(tokens.drop(1).joinToString(" "))
            ?: return observations
        val toIsStation = CallsignValidator.isAmateurCallsign(to) && to != from

        when (commandMatch.command.uppercase(Locale.US)) {
            "HEARTBEAT SNR" -> if (toIsStation) {
                observations.add(Observation(from, to, parseSnr(commandMatch.payload), Source.HB_ACK))
            }
            "SNR" -> if (toIsStation) {
                observations.add(Observation(from, to, parseSnr(commandMatch.payload), Source.SNR_REPORT))
            }
            "ACK" -> if (toIsStation) {
                observations.add(Observation(from, to, null, Source.ACK))
            }
            "HEARING" -> {
                commandMatch.payload.split(Regex("\\s+"))
                    .take(MAX_HEARING_CALLS)
                    .filter { CallsignValidator.isAmateurCallsign(it) && it != from }
                    .forEach { observations.add(Observation(from, it, null, Source.HEARING)) }
            }
        }
        return observations
    }

    /**
     * Evidence in a reassembled relay payload. Each hop appended the station
     * it received from, so the trail plus the transmitting station form a
     * hearing sequence: in "... *DE* A *DE* B" transmitted by C, B heard A
     * and C heard B.
     */
    fun fromRelayChain(transmitter: String, payload: String): List<Observation> {
        val trail = relayTrailRegex.findAll(payload.uppercase(Locale.US))
            .map { it.groupValues[1] }
            .filter { CallsignValidator.isAmateurCallsign(it) }
            .toMutableList()
        val tx = transmitter.trim().uppercase(Locale.US)
        if (CallsignValidator.isAmateurCallsign(tx)) trail.add(tx)

        return trail.zipWithNext()
            .filter { (heard, reporter) -> heard != reporter }
            .map { (heard, reporter) -> Observation(reporter, heard, null, Source.RELAY) }
    }

    private fun parseSnr(payload: String): Int? {
        val value = snrRegex.find(payload.trim())?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return if (value in -40..40) value else null
    }
}
