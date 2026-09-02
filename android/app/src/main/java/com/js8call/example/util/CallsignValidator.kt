package com.js8call.example.util

import java.util.Locale

object CallsignValidator {
    private val baseCallsign = Regex("(?:[A-Z]{1,3}\\d[A-Z]{1,4}|\\d[A-Z]{1,3}\\d[A-Z]{1,4})")

    fun isAmateurCallsign(value: String): Boolean {
        val token = value.trim().uppercase(Locale.US)
        if (token.length !in 3..12 || !token.matches(Regex("[A-Z0-9/]+"))) return false

        return token.split('/').any { baseCallsign.matches(it) }
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
