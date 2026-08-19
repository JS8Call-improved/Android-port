package com.js8call.example.model

import com.js8call.example.R

/**
 * Represents a decoded JS8 message.
 */
data class DecodedMessage(
    val utc: Int,
    val snr: Int,
    val dt: Float,
    val frequency: Float,
    val text: String,
    val type: Int,
    val quality: Float,
    val mode: Int,
    // Engine-suggested total clock drift (ms) that would center this signal's
    // cycle; used by "sync clock to this signal".
    val driftMs: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * Check if this frame is marked as the first frame of a multipart message.
     * JS8CallFirst flag is bit 0 (type & 1).
     */
    fun isFirstFrame(): Boolean = (type and 1) != 0

    /**
     * Check if this frame is marked as the last frame of a multipart message.
     * JS8CallLast flag is bit 1 (type & 2).
     */
    fun isLastFrame(): Boolean = (type and 2) != 0

    /**
     * Check if this is a single-frame message (not part of a multipart sequence).
     * Single-frame messages have both first and last flags set (data or non-data).
     */
    fun isSingleFrame(): Boolean = isFirstFrame() && isLastFrame()

    /**
     * Get color resource ID based on SNR level.
     */
    val snrColorRes: Int
        get() = when {
            snr >= -5 -> R.color.snr_excellent   // >= -5 dB
            snr >= -10 -> R.color.snr_good       // -10 to -6 dB
            snr >= -15 -> R.color.snr_fair       // -15 to -11 dB
            snr >= -25 -> R.color.snr_poor       // -25 to -16 dB
            else -> R.color.snr_weak             // < -25 dB
        }

    /**
     * Format as display string.
     */
    fun toDisplayString(): String {
        return String.format(
            "%04d  %+3d dB  %+5.1f s  %7.1f Hz  %s",
            utc, snr, dt, frequency, text
        )
    }

    /**
     * Format time as local HH:MM:SS derived from UTC HHMMSS.
     */
    fun formattedTime(): String {
        val hours = utc / 10000
        val minutes = (utc / 100) % 100
        val seconds = utc % 100

        val utcCal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            set(java.util.Calendar.HOUR_OF_DAY, hours)
            set(java.util.Calendar.MINUTE, minutes)
            set(java.util.Calendar.SECOND, seconds)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val localCal = java.util.Calendar.getInstance().apply {
            timeInMillis = utcCal.timeInMillis
        }
        return String.format(
            "%02d:%02d:%02d",
            localCal.get(java.util.Calendar.HOUR_OF_DAY),
            localCal.get(java.util.Calendar.MINUTE),
            localCal.get(java.util.Calendar.SECOND)
        )
    }
}

/**
 * Buffer for accumulating multipart message frames.
 * Groups frames by frequency to assemble complete messages.
 */
data class MessageBuffer(
    val frames: MutableList<DecodedMessage>,
    val firstTimestamp: Long,
    val frequencyKey: Int
)
