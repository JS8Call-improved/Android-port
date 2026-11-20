package com.js8call.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Mode 60 uses four-second slots and must work with all-mode RX enabled. */
@RunWith(AndroidJUnit4::class)
class JS8Engine60LoopbackTest {
    @Test
    fun own60TransmissionDecodes() {
        val samples = renderPeriod("CQ CQ CQ")
        val decoded = TestEngine(MODE_60).use {
            it.decode(samples, realTime = true)
        }
        assertTrue("own 60 transmission did not decode with mode 8: $decoded",
            decoded.any { "CQ" in it.text && it.mode == MODE_60 })
    }

    @Test
    fun consecutive60FramesDecodeWithAllModesEnabled() {
        val samples = renderPeriod("CQ CQ CQ") + renderPeriod("HELLO WORLD")
        val decoded = TestEngine(MODE_60, rxMaskOf(0, 1, 2, 4, MODE_60)).use {
            it.decode(samples, realTime = true)
        }
        assertTrue("first 60 frame missing: $decoded",
            decoded.any { "CQ" in it.text && it.mode == MODE_60 })
        assertTrue("second 60 frame missing: $decoded",
            decoded.any { "HELLO WORLD" in it.text && it.mode == MODE_60 })
    }

    private fun renderPeriod(text: String): ShortArray {
        val tx = TestEngine(MODE_60).use { it.transmitFromMidPeriod(text) }
        // 79 symbols x 32 ms = 2.528 seconds of payload.
        assertTrue("captured ${tx.seconds}s, expected a 60 frame", tx.seconds in 2.5f..3.0f)
        return tx.placedInPeriod(periodMs = 4_000, startDelayMs = 100)
            .withNoise(rms = 300.0, seed = 60)
    }

    private companion object {
        const val MODE_60 = 8
    }
}
