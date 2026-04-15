package com.js8call.example.util

object TxMessageClassifier {
    fun isBaseMessage(text: String): Boolean {
        return isAllCall(text) || isCqMessage(text) || isHeartbeatMessage(text)
    }

    fun shouldAppendGrid(text: String): Boolean {
        return isCqMessage(text) || isHeartbeatMessage(text)
    }

    fun isHeartbeatMessage(text: String): Boolean {
        val trimmed = text.trim()
        return hasTokenPrefix(trimmed, "HB") ||
            hasTokenPrefix(trimmed, "HEARTBEAT") ||
            hasTokenPrefix(trimmed, "@HB HEARTBEAT")
    }

    private fun isAllCall(text: String): Boolean = hasTokenPrefix(text.trim(), "@ALLCALL")

    private fun isCqMessage(text: String): Boolean = hasTokenPrefix(text.trim(), "CQ")

    private fun hasTokenPrefix(text: String, prefix: String): Boolean {
        if (text.equals(prefix, ignoreCase = true)) return true
        if (text.length <= prefix.length) return false
        return text.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true) &&
            text[prefix.length].isWhitespace()
    }
}
