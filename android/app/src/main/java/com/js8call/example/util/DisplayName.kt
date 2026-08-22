package com.js8call.example.util

/**
 * What a station is called on screen. One place decides, so a list row, a
 * thread toolbar and a notification never disagree.
 *
 * A named station leads with its name and keeps the callsign underneath,
 * because the callsign is the identity on the air and has to stay visible.
 * An unnamed one is just its callsign, with nothing underneath.
 */
object DisplayName {

    /** The headline: the name when there is one, otherwise the callsign. */
    fun of(callsign: String, name: String?): String {
        val trimmed = name?.trim()
        return if (trimmed.isNullOrEmpty()) callsign else trimmed
    }

    /** The second line: the callsign, or null when it is already the headline. */
    fun secondary(callsign: String, name: String?): String? {
        val trimmed = name?.trim()
        return if (trimmed.isNullOrEmpty()) null else callsign
    }

    /** The letter for the avatar circle, taken from whatever leads. */
    fun initial(callsign: String, name: String?): String {
        val headline = of(callsign, name)
        val letter = headline.firstOrNull { it.isLetterOrDigit() } ?: return "?"
        return letter.uppercase()
    }
}
