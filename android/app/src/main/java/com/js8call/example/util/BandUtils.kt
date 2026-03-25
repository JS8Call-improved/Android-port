package com.js8call.example.util

import java.util.Locale
import kotlin.math.floor

object BandUtils {
    enum class FallbackStyle {
        FLOOR_MHZ,
        ONE_DECIMAL_MHZ
    }

    fun bandLabelForFrequencyHz(
        frequencyHz: Long,
        fallbackStyle: FallbackStyle = FallbackStyle.FLOOR_MHZ
    ): String {
        val mhz = frequencyHz.toDouble() / 1_000_000.0
        return when {
            mhz in 1.8..2.0 -> "160m"
            mhz in 3.5..4.0 -> "80m"
            mhz in 5.0..5.5 -> "60m"
            mhz in 7.0..7.3 -> "40m"
            mhz in 10.0..10.2 -> "30m"
            mhz in 14.0..14.35 -> "20m"
            mhz in 18.0..18.2 -> "17m"
            mhz in 21.0..21.45 -> "15m"
            mhz in 24.8..25.0 -> "12m"
            mhz in 28.0..29.7 -> "10m"
            mhz in 50.0..54.0 -> "6m"
            mhz in 144.0..148.0 -> "2m"
            mhz in 420.0..450.0 -> "70cm"
            else -> when (fallbackStyle) {
                FallbackStyle.FLOOR_MHZ -> String.format(Locale.US, "%dmhz", floor(mhz).toInt())
                FallbackStyle.ONE_DECIMAL_MHZ -> String.format(Locale.US, "%.1fMHz", mhz)
            }
        }
    }
}
