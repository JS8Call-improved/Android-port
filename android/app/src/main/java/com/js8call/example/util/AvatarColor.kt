package com.js8call.example.util

import com.js8call.example.R

/**
 * A colour per station, so a list can be read by shape rather than by
 * reading every callsign. Derived from the callsign rather than stored, so
 * a station keeps its colour across devices and database wipes, and a
 * renamed station does not change colour underneath the operator.
 */
object AvatarColor {

    private val PALETTE = intArrayOf(
        R.color.avatar_1, R.color.avatar_2, R.color.avatar_3, R.color.avatar_4,
        R.color.avatar_5, R.color.avatar_6, R.color.avatar_7, R.color.avatar_8
    )

    /**
     * A colour resource for [callsign]. Uses its own hash rather than
     * String.hashCode, which is stable across JVMs but not something to
     * depend on for a value that has to look the same every run.
     */
    fun forCallsign(callsign: String): Int {
        val key = callsign.trim().uppercase()
        if (key.isEmpty()) return PALETTE[0]
        var hash = 0
        for (c in key) {
            hash = (hash * 31 + c.code) and 0x7FFFFFFF
        }
        return PALETTE[hash % PALETTE.size]
    }
}
