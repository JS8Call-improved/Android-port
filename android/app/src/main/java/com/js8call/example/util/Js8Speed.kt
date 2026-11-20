package com.js8call.example.util

import androidx.annotation.StringRes
import com.js8call.core.rxMaskOf
import com.js8call.example.R

/** App-supported speeds. Mode IDs are persisted TX values, not RX mask bits. */
enum class Js8Speed(
    val submode: Int,
    val periodMs: Long,
    val bandwidthHz: Int,
    @StringRes val label: Int
) {
    SLOW(4, 30_000L, 25, R.string.tx_speed_slow),
    NORMAL(0, 15_000L, 50, R.string.tx_speed_normal),
    FAST(1, 10_000L, 80, R.string.tx_speed_fast),
    SPEED_40(2, 6_000L, 160, R.string.tx_speed_40),
    SPEED_60(8, 4_000L, 250, R.string.tx_speed_60);

    /** Keep the complete signal inside the desktop-compatible 500–1000 Hz HB sub-band. */
    fun heartbeatOffsetHz(fraction: Double): Float {
        require(fraction in 0.0..1.0)
        return (500 + fraction * (500 - bandwidthHz)).toFloat()
    }

    companion object {
        fun fromSubmode(submode: Int): Js8Speed =
            entries.firstOrNull { it.submode == submode } ?: NORMAL

        fun receiveMask(): Int = entries.fold(0) { mask, speed ->
            mask or rxMaskOf(speed.submode)
        }
    }
}
