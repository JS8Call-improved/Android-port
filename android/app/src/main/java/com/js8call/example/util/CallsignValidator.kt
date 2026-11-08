package com.js8call.example.util

import java.util.Locale

object CallsignValidator {
    private val baseCallsign = Regex("(?:[A-Z]{1,3}\\d[A-Z]{1,4}|\\d[A-Z]{1,3}\\d[A-Z]{1,4}|[A-Z]\\d\\d[A-Z]{1,4})")
    private val groupShape = Regex("@[A-Z0-9](?:[A-Z0-9/]*[A-Z0-9])?")
    private val callsignChars = Regex("[A-Z0-9/]+")

    fun isAmateurCallsign(value: String): Boolean {
        val token = value.trim().uppercase(Locale.US)
        if (token.length !in 3..12 || !token.matches(callsignChars)) return false

        return token.split('/').any { baseCallsign.matches(it) }
    }

    /** An @group the engine can encode, like @ALLCALL or @DX/NA. */
    fun isGroup(value: String): Boolean {
        val token = value.trim().uppercase(Locale.US)
        return groupShape.matches(token) && token.count { it != '/' } <= 9
    }

    fun matches(value: String, other: String): Boolean {
        val first = value.trim().uppercase(Locale.US)
        val second = other.trim().uppercase(Locale.US)
        return first.isNotEmpty() && second.isNotEmpty() &&
            (first == second || baseCallsign(first) == baseCallsign(second))
    }

    private fun baseCallsign(value: String): String {
        val slash = value.indexOf('/')
        if (slash < 0) return value
        val prefix = value.substring(0, slash)
        val suffix = value.substring(slash + 1)
        return if (prefix.length >= suffix.length) prefix else suffix
    }
}
