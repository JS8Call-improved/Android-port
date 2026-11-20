package com.js8call.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

/** JNI policy/diagnostic checks; use Release builds for device performance measurements. */
@RunWith(AndroidJUnit4::class)
class JS8EngineReceiveTrackingTest {
    @Test
    fun reportsLinkedCoreVersion() {
        assertEquals("0.1.0", JS8Engine.coreVersion())
    }

    @Test
    fun rejectsInvalidOptionalBudgets() {
        for (budget in listOf(-1, 10001)) {
            var rejected = false
            try {
                JS8Engine.create(
                    callbackHandler = Capture(),
                    optionalDecodeBudgetMs = budget
                ).close()
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue("accepted invalid budget $budget", rejected)
        }
    }

    @Test
    fun historicalDefaultsAndZeroBudgetPreserveMandatoryDecodes() {
        val audio = readWav("A_1_4.wav")
        val baseline = receive(audio, 1)
        assertTrue("reference produced no mandatory frames", baseline.frames.isNotEmpty())
        assertTrue(baseline.flags.all { !it.first && !it.second })
        assertTrue(baseline.finishes.all { it.cancellations == 0 && !it.canceled })

        val denied = receive(audio, 1, JS8Engine.SymbolTracking.BOTH, true, 0)
        assertEquals(baseline.frames.toList(), denied.frames.toList())
        assertTrue(denied.flags.all { !it.first && !it.second })
        assertTrue("zero budget was not reported", denied.finishes.any { it.skipped })
        assertTrue(denied.finishes.all { it.cancellations == 0 && !it.canceled })
    }

    @Test
    fun trackingAndCancellationPreserveReferenceDecodes() {
        val audio = readWav("A_1_4.wav")
        val baseline = receive(audio, 1)
        assertTrue(baseline.frames.isNotEmpty())
        val configurations = listOf(
            JS8Engine.SymbolTracking.FREQUENCY to false,
            JS8Engine.SymbolTracking.TIMING to false,
            JS8Engine.SymbolTracking.BOTH to false,
            JS8Engine.SymbolTracking.BOTH to true
        )
        for ((tracking, sic) in configurations) {
            val enabled = receive(audio, 1, tracking, sic, 10000)
            assertEquals(
                "tracking changed mandatory frame metadata",
                baseline.frames.toList(),
                enabled.frames.take(baseline.frames.size)
            )
            assertTrue(enabled.finishes.none { it.canceled })
        }
    }

    @Test
    fun recoversTrackedFramesAndReportsCancellationFollowup() {
        // The same impairment geometry as upstream's independent Ultra streaming
        // regression. Java's Gaussian generator differs from C++; use a seed pool
        // and require actual results, not merely enabled configuration flags.
        var tracked = false
        var followup = false
        for (seed in 0..7) {
            val audio = overlapAudio(seed)
            val trackingOnly = receive(audio, 1 shl 4, JS8Engine.SymbolTracking.BOTH, false, 10000)
            assertTrue(trackingOnly.flags.none { it.second })
            assertTrue(trackingOnly.finishes.all { it.cancellations == 0 })
            val enabled = receive(audio, 1 shl 4, JS8Engine.SymbolTracking.BOTH, true, 10000)
            assertEquals(
                trackingOnly.frames.toList(),
                enabled.frames.take(trackingOnly.frames.size)
            )
            tracked = tracked || enabled.flags.any { it.first }
            followup = followup || (
                enabled.flags.any { it.first && it.second } &&
                    enabled.finishes.any { it.cancellations > 0 }
                )
            if (tracked && followup) break
        }
        assertTrue("no CRC-valid tracked frame recovered", tracked)
        assertTrue("no CRC-valid tracked cancellation follow-up recovered", followup)
    }

    private data class Frame(
        val snr: Int, val dt: Float, val freq: Float,
        val text: String, val type: Int, val quality: Float, val mode: Int, val driftMs: Int
    )

    private data class Finish(val count: Int, val cancellations: Int, val skipped: Boolean, val canceled: Boolean)

    private class Capture : JS8Engine.ReceiveDiagnosticsHandler {
        val frames = CopyOnWriteArrayList<Frame>()
        val flags = CopyOnWriteArrayList<Pair<Boolean, Boolean>>()
        val finishes = CopyOnWriteArrayList<Finish>()
        val errors = CopyOnWriteArrayList<String>()
        val done = CountDownLatch(1)
        var completionCount = -1

        override fun onDecodedDiagnostics(trackingUsed: Boolean, afterTrackedSic: Boolean) {
            flags.add(trackingUsed to afterTrackedSic)
        }

        override fun onDecoded(
            utc: Int, snr: Int, dt: Float, freq: Float, text: String,
            type: Int, quality: Float, mode: Int, driftMs: Int
        ) {
            // UTC is the worker's wall time, not a property of the recording;
            // separate engine runs can cross a second/minute boundary.
            frames.add(Frame(snr, dt, freq, text, type, quality, mode, driftMs))
        }

        override fun onDecodeFinishedDiagnostics(
            count: Int, trackedCancellations: Int, optionalWorkSkipped: Boolean, canceled: Boolean
        ) {
            finishes.add(Finish(count, trackedCancellations, optionalWorkSkipped, canceled))
        }

        override fun onDecodeFinished(count: Int) {
            completionCount = count
            done.countDown()
        }

        override fun onError(message: String) { errors.add(message) }
        override fun onSpectrum(bins: FloatArray, binHz: Float, powerDb: Float, peakDb: Float) = Unit
        override fun onDecodeStarted(submodes: Int) = Unit
        override fun onLog(level: Int, message: String) = Unit
    }

    private fun receive(
        audio: ShortArray,
        mask: Int,
        tracking: JS8Engine.SymbolTracking? = null,
        sic: Boolean = false,
        budget: Int = 250
    ): Capture {
        val capture = Capture()
        val engine = if (tracking == null) {
            // Exercise the public defaults rather than repeating their values.
            JS8Engine.create(callbackHandler = capture, submodes = mask, enableTxAudioTap = true)
        } else JS8Engine.create(
            callbackHandler = capture,
            submodes = mask,
            enableTxAudioTap = true,
            symbolTracking = tracking,
            trackedSic = sic,
            optionalDecodeBudgetMs = budget
        )
        engine.use {
            assertTrue(engine.start())
            // First-sample timestamps, contiguous in the monotonic clock domain,
            // aligned to 10 ms after a UTC minute just like the upstream test.
            val now = System.currentTimeMillis()
            val origin = System.nanoTime() + (10L - now % 60000L) * 1_000_000L
            val first = if (mask == 1 shl 4) 120 else 0
            val last = minOf(audio.size, if (mask == 1 shl 4) 39000 else 15 * SAMPLE_RATE)
            for (i in first until last step 512) {
                assertTrue(engine.submitAudio(
                    audio.copyOfRange(i, minOf(i + 512, last)),
                    origin + (i - first).toLong() * 1_000_000_000L / SAMPLE_RATE
                ))
            }
            assertTrue("decode never completed", capture.done.await(30, TimeUnit.SECONDS))
            engine.stop()
        }
        assertTrue("engine errors: ${capture.errors}", capture.errors.isEmpty())
        assertEquals("diagnostics missing for decoded frames", capture.frames.size, capture.flags.size)
        assertFalse(capture.finishes.isEmpty())
        assertEquals("completion diagnostic differs from legacy callback",
            capture.completionCount, capture.finishes.last().count)
        return capture
    }

    private fun overlapAudio(seed: Int): ShortArray {
        // Frozen wire tones from legacy_encode, Modified Costas, frame types 2/1.
        // Only encoding comes from the modem; phase integration below is independent.
        val strongTones = "0623541412347514070722237124722117721502364211625253076403033251576235622506413"
        val weakTones = "0623541171161437250523363370003166751502364351634350102030405060710172602506413"
        val symbol = 384.0 / SAMPLE_RATE
        fun value(time: Double, weak: Boolean): Double {
            val start = 0.113037 + if (weak) 0.03 else 0.0
            val elapsed = time - start
            val tx = elapsed * if (weak) 0.9998 else 1.0
            val position = tx / symbol
            if (position < 0.0 || position >= 79.0) return 0.0
            val tones = if (weak) weakTones else strongTones
            val index = position.toInt()
            var cycles = (1508.125 + if (weak) 0.7 / symbol else 0.0) * tx
            for (k in 0 until index) cycles += tones[k] - '0'
            cycles += (tones[index] - '0') * (position - index)
            if (!weak) cycles += 0.5 * 1.4 / (79 * symbol * symbol) * elapsed * elapsed
            return 4500.0 * (if (weak) 0.35 else 1.0) * sin(2 * PI * cycles + seed * 0.71)
        }
        val random = Random(0x51a700L + seed)
        return ShortArray(48000) { i ->
            val time = i.toDouble() / SAMPLE_RATE
            (value(time, false) + value(time, true) + 5000 * random.nextGaussian())
                .toInt().coerceIn(-32768, 32767).toShort()
        }
    }
}
