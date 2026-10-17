package com.js8call.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Turbo must retain its six-second slot timing through modulation and decode. */
@RunWith(AndroidJUnit4::class)
class JS8EngineTurboLoopbackTest {
    @Test
    fun ownTurboTransmissionDecodes() {
        val tx = TestEngine(SUBMODE_TURBO).use { it.transmitFromMidPeriod("CQ CQ CQ") }

        // Turbo has 79 x 50 ms symbols. The tap has leading slot silence removed.
        assertTrue("captured ${tx.seconds}s, expected a Turbo frame", tx.seconds > 3.9f)

        val period = tx.placedInPeriod(periodMs = 6_000, startDelayMs = 100)
            .withNoise(rms = 300.0, seed = 2)
        val decoded = TestEngine(SUBMODE_TURBO).use { it.decode(period) }

        assertTrue("own Turbo transmission did not decode: $decoded", decoded.any { "CQ" in it.text })
    }

    private companion object {
        const val SUBMODE_TURBO = 2
    }
}
