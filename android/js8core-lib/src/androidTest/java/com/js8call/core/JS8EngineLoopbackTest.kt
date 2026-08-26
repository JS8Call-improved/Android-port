package com.js8call.core

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Transmits through the modulator, captures the waveform off the TX tap and
 * decodes it. No speaker or microphone in the path, so a failure here is the
 * transmitted audio itself rather than the device.
 *
 * The transmission is asked for from the middle of a period, which is the case
 * the modulator has to defer to the next slot boundary on its own. Starting
 * partway through a frame sends a partial waveform, and a partial waveform does
 * not decode, so this fails if that alignment is lost.
 */
@RunWith(AndroidJUnit4::class)
class JS8EngineLoopbackTest {

    @Test
    fun ownTransmissionDecodes() {
        val tx = TestEngine().use { it.transmitFromMidPeriod("CQ CQ CQ") }

        // A whole frame is 12.6 s. Short means the modulator joined one in
        // progress instead of waiting for the boundary.
        assertTrue("captured ${tx.seconds}s, expected a whole frame", tx.seconds > 10)

        // Submode A starts 500 ms into its 15 s period. The noise gives the
        // decoder a floor to measure against instead of digital silence.
        val period = tx.placedInPeriod(periodMs = 15_000, startDelayMs = 500)
            .withNoise(rms = 300.0, seed = 1)

        val decoded = TestEngine().use { it.decode(period) }
        Log.i(TAG, "decoded ${decoded.size}: $decoded")
        assertTrue("own transmission did not decode", decoded.any { "CQ" in it.text })
    }

    private companion object {
        const val TAG = "LoopbackTest"
    }
}
