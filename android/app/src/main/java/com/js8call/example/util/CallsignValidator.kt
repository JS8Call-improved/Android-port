package com.js8call.example.util

import java.util.Locale

object CallsignValidator {
    private val baseCallsign = Regex("(?:[A-Z]{1,3}\\d[A-Z]{1,4}|\\d[A-Z]{1,3}\\d[A-Z]{1,4})")

    fun isAmateurCallsign(value: String): Boolean {
        val token = value.trim().uppercase(Locale.US)
        if (token.length !in 3..12 || !token.matches(Regex("[A-Z0-9/]+"))) return false

        return token.split('/').any { baseCallsign.matches(it) }
    }
}
