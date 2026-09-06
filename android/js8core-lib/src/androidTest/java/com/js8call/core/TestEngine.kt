package com.js8call.core

import android.util.Log
import org.junit.Assert.assertTrue
import java.util.concurrent.CopyOnWriteArrayList

/** One decode as the engine reported it. */
internal data class Decode(val text: String, val snr: Int, val dt: Float, val freq: Float, val type: Int) {
    override fun toString() = String.format("'%s' dt=%+.2f f=%.0f snr=%d type=0x%X", text, dt, freq, snr, type)
}

/**
 * A running engine for one transmit or one decode, closed by [use]. Submode A
 * at the engine's own rate, TX tap on. Each job shifts the clock first,
 * because the ring phase and the modulator both follow the wall clock.
 */
internal class TestEngine : AutoCloseable {
    private val decodes = CopyOnWriteArrayList<Decode>()
    private val tapped = ArrayList<ShortArray>()
    private var tapRate = SAMPLE_RATE
    @Volatile private var finished = false

    private val engine = JS8Engine.create(
        sampleRateHz = SAMPLE_RATE,
        submodes = SUBMODE_A,
        enableTxAudioTap = true,
        callbackHandler = object : JS8Engine.CallbackHandler {
            override fun onDecoded(
                utc: Int, snr: Int, dt: Float, freq: Float, text: String,
                type: Int, quality: Float, mode: Int, driftMs: Int
            ) {
                decodes.add(Decode(text, snr, dt, freq, type))
            }

            override fun onDecodeFinished(count: Int) {
                finished = true
            }

            override fun onTxAudio(samples: ShortArray, sampleRateHz: Int) {
                synchronized(tapped) {
                    tapRate = sampleRateHz
                    tapped.add(samples)
                }
            }

            override fun onError(message: String) {
                Log.w(TAG, "engine error: $message")
            }

            override fun onSpectrum(
                bins: FloatArray, binHz: Float, powerDb: Float, peakDb: Float
            ) = Unit
            override fun onDecodeStarted(submodes: Int) = Unit
            override fun onLog(level: Int, message: String) = Unit
        }
    )

    init {
        assertTrue("engine did not start", engine.start())
    }

    override fun close() = engine.close()

    /** Feeds the whole clip and waits for the decode pass over it to finish. */
    fun decode(samples: ShortArray): List<Decode> {
        // Shift the clock to the top of a minute; the first submit re-aligns
        // the ring to it before writing.
        val now = System.currentTimeMillis()
        engine.setTimeDriftMs((MINUTE_MS - now % MINUTE_MS) % MINUTE_MS)

        // A buffer at a time, as a device would deliver it. The scheduler
        // places the decode window from where a write ends, so one write
        // spanning the whole period would put the window in the next one.
        for (start in samples.indices step CHUNK) {
            engine.submitAudio(samples.copyOfRange(start, minOf(start + CHUNK, samples.size)))
        }

        assertTrue("decode pass never finished", waitUntil(DECODE_TIMEOUT_MS) { finished })
        Thread.sleep(SETTLE_MS) // decodes from that pass still arriving
        return decodes.toList()
    }

    /**
     * Asks for [text] with the clock 13 s into a period, so the modulator has
     * to defer to the next boundary on its own, and returns what came off the
     * TX tap: at the engine rate, leading silence trimmed.
     */
    fun transmitFromMidPeriod(text: String, forceData: Boolean = false): ShortArray {
        engine.setTransmitReady(true)
        val offset = System.currentTimeMillis() % PERIOD_MS
        engine.setTimeDriftMs((ASK_AT_MS - offset + PERIOD_MS) % PERIOD_MS)
        val accepted = engine.transmitMessage(
            text = text,
            myCall = "N5EKS",
            myGrid = "EM12",
            submode = 0,
            audioFrequencyHz = 1500.0,
            txDelaySec = 0.0,
            forceData = forceData
        )
        assertTrue("transmit refused", accepted)

        val transmitting = { engine.isTransmittingAudio() }
        assertTrue("modulator never started", waitUntil(TX_TIMEOUT_MS, transmitting))
        assertTrue("modulator never stopped", waitUntil(TX_TIMEOUT_MS) { !transmitting() })
        Thread.sleep(DRAIN_MS) // the last tap buffers are still in flight
        engine.stopTransmit()

        // The tap sits after the output resampler, so it runs at whatever rate
        // the audio device negotiated, not the engine rate the decoder wants.
        val (raw, rate) = synchronized(tapped) { tapped.concatenated() to tapRate }
        val tx = (if (rate == SAMPLE_RATE) raw else raw.resampled(rate, SAMPLE_RATE))
            .trimLeadingSilence()
        Log.i(TAG, "tap at $rate Hz, ${raw.size} samples; ${tx.seconds}s of signal")
        return tx
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() >= deadline) return false
            Thread.sleep(POLL_MS)
        }
        return true
    }

    private companion object {
        const val TAG = "TestEngine"
        const val SUBMODE_A = 0x1
        const val PERIOD_MS = 15_000L
        const val ASK_AT_MS = 13_000L // inside the frame, late enough to keep the wait short
        const val MINUTE_MS = 60_000L
        const val CHUNK = 4_096
        const val POLL_MS = 50L
        const val SETTLE_MS = 500L
        const val DRAIN_MS = 1_500L
        const val TX_TIMEOUT_MS = 45_000L
        const val DECODE_TIMEOUT_MS = 30_000L
    }
}
