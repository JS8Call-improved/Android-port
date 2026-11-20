package com.js8call.example.util

import com.js8call.core.rxMaskOf
import com.js8call.example.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Js8SpeedTest {
    @Test
    fun persistedModeValuesRemainCompatible() {
        assertEquals(listOf(4, 0, 1, 2, 8), Js8Speed.entries.map { it.submode })
        Js8Speed.entries.forEach {
            assertEquals(it, Js8Speed.fromSubmode(it.submode))
        }
        assertEquals(Js8Speed.NORMAL, Js8Speed.fromSubmode(-1))
        assertEquals(Js8Speed.NORMAL, Js8Speed.fromSubmode(16))
    }

    @Test
    fun highSpeedsUseTheirOwnTimingBandwidthAndLabels() {
        assertEquals(6_000L, Js8Speed.SPEED_40.periodMs)
        assertEquals(4_000L, Js8Speed.SPEED_60.periodMs)
        assertEquals(160, Js8Speed.SPEED_40.bandwidthHz)
        assertEquals(250, Js8Speed.SPEED_60.bandwidthHz)
        assertEquals(R.string.tx_speed_40, Js8Speed.SPEED_40.label)
        assertEquals(R.string.tx_speed_60, Js8Speed.SPEED_60.label)
        assertEquals(16_000L, 4 * Js8Speed.SPEED_60.periodMs)
        assertEquals(listOf(30_000L, 15_000L, 10_000L),
            listOf(Js8Speed.SLOW, Js8Speed.NORMAL, Js8Speed.FAST).map { it.periodMs })
    }

    @Test
    fun receiveMaskIncludesAllFiveSpeedsUsingMaskBitsNotModeIds() {
        assertEquals(16, rxMaskOf(Js8Speed.SPEED_60.submode))
        assertEquals(4, rxMaskOf(Js8Speed.SPEED_40.submode))
        assertEquals(0x1F, Js8Speed.receiveMask())
    }

    @Test
    fun wholeHeartbeatSignalStaysInsideSubBandAtEverySpeed() {
        Js8Speed.entries.forEach { speed ->
            assertEquals(500f, speed.heartbeatOffsetHz(0.0), 0f)
            assertEquals(1000f - speed.bandwidthHz, speed.heartbeatOffsetHz(1.0), 0f)
            for (step in 0..100) {
                val offset = speed.heartbeatOffsetHz(step / 100.0)
                assertTrue(offset >= 500f)
                assertTrue(offset + speed.bandwidthHz <= 1000f)
            }
        }
    }
}
