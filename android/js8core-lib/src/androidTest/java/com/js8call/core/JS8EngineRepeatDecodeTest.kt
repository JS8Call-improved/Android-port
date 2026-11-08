package com.js8call.core

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JS8EngineRepeatDecodeTest {

    @Test
    fun reportsTheSameFrameFromTwoStationsTwice() {
        val weak = TestEngine().use { it.transmitFromMidPeriod(TEXT, audioFrequencyHz = 1500.0) }
        val strong = TestEngine().use { it.transmitFromMidPeriod(TEXT, audioFrequencyHz = 2000.0) }
        val period = weak.mixedWith(strong, gain = WEAK_GAIN)
            .placedInPeriod(periodMs = 15_000, startDelayMs = 500)
            .withNoise(rms = 300.0, seed = 1)

        val decoded = TestEngine().use { it.decode(period) }
        Log.i(TAG, "decoded ${decoded.size}: $decoded")
        val freqs = decoded.filter { "CQ" in it.text }.map { it.freq }.sorted()
        assertEquals("reports of the frame: $decoded", 2, freqs.size)
        assertEquals(1500f, freqs[0], 10f)
        assertEquals(2000f, freqs[1], 10f)
    }

    @Test
    fun reportsATurboFrameOnceAcrossRetries() {
        val tx = TestEngine(SUBMODE_TURBO).use { it.transmitFromMidPeriod(TEXT) }
        val period = tx.placedInPeriod(periodMs = 6_000, startDelayMs = 100)
            .withNoise(rms = 300.0, seed = 2)

        val engine = TestEngine(SUBMODE_TURBO)
        val decoded = engine.use { it.decode(period, realTime = true) }
        Log.i(TAG, "turbo decoded ${decoded.size}, frames per job ${engine.framesPerJob}: $decoded")
        assertTrue("the window was not decoded again", engine.framesPerJob.count { it > 0 } >= 2)
        assertEquals("reports of the frame: $decoded", 1, decoded.count { "CQ" in it.text })
    }

    @Test
    fun reportsATurboFrameAgainInTheNextPeriod() {
        val tx = TestEngine(SUBMODE_TURBO).use { it.transmitFromMidPeriod(TEXT) }
        val periods = listOf(3L, 4L).map { seed ->
            tx.placedInPeriod(periodMs = 6_000, startDelayMs = 100).withNoise(rms = 300.0, seed = seed)
        }

        val decoded = TestEngine(SUBMODE_TURBO).use { it.decode(periods.concatenated(), realTime = true) }
        Log.i(TAG, "turbo over two periods decoded ${decoded.size}: $decoded")
        assertEquals("reports of the frame: $decoded", 2, decoded.count { "CQ" in it.text })
    }

    private companion object {
        const val TAG = "RepeatDecodeTest"
        const val TEXT = "CQ CQ CQ"
        const val WEAK_GAIN = 0.2
        const val SUBMODE_TURBO = 2
    }
}
