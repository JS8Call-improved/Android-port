package com.js8call.core

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Transmits through the modulator, captures the waveform off the TX tap and
 * decodes it. No speaker or microphone in the path, so a failure here is the
 * transmitted audio itself rather than the device.
 */
@RunWith(AndroidJUnit4::class)
class JS8EngineLoopbackTest {

    @Test
    fun ownTransmissionDecodes() {
        val raw = transmit("CQ CQ CQ")
        val lead = raw.indexOfFirst { it.toInt() != 0 }.coerceAtLeast(0)
        val tx = raw.copyOfRange(lead, raw.size)
        val peak = tx.maxOf { kotlin.math.abs(it.toInt()) }
        val rms = kotlin.math.sqrt(tx.sumOf { it.toDouble() * it.toDouble() } / tx.size)
        Log.i(
            TAG,
            "captured ${raw.size} samples, ${lead} leading silent, " +
                "signal ${tx.size} (${tx.size / SAMPLE_RATE.toFloat()}s), peak=$peak rms=$rms"
        )
        assertTrue("no TX audio captured", tx.size > SAMPLE_RATE * 10)

        // The modulator starts start_delay_ms into the period, so lead with
        // that much silence and pad the rest of the period out.
        val period = ShortArray(SAMPLE_RATE * PERIOD_S)
        tx.copyInto(period, START_DELAY_SAMPLES, 0, minOf(tx.size, period.size - START_DELAY_SAMPLES))

        // The decoder measures each candidate against a noise floor, so give it
        // a realistic one rather than digital silence.
        val rng = java.util.Random(1)
        for (i in period.indices) {
            period[i] = (period[i] + rng.nextGaussian() * NOISE_RMS).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        dumpWav(period)

        val decoded = decode(period)
        Log.i(TAG, "decoded ${decoded.size}: $decoded")
        assertTrue("own transmission did not decode", decoded.any { it.contains("CQ") })
    }

    private fun transmit(text: String): ShortArray {
        val captured = ArrayList<ShortArray>()
        var tapRate = SAMPLE_RATE
        val engine = JS8Engine.create(
            sampleRateHz = SAMPLE_RATE,
            submodes = SUBMODE_A,
            enableTxAudioTap = true,
            callbackHandler = object : Handler() {
                override fun onTxAudio(samples: ShortArray, sampleRateHz: Int) {
                    synchronized(captured) { tapRate = sampleRateHz; captured.add(samples) }
                }
            }
        )
        try {
            assertTrue(engine.start())
            engine.setTransmitReady(true)

            // Ask shortly before a period boundary with a delay big enough to
            // land in the modulator's wait-for-next-period branch, the way the
            // service does. Otherwise it joins the frame in progress.
            val periodMs = PERIOD_S * 1000L
            val offset = System.currentTimeMillis() % periodMs
            Thread.sleep(((periodMs - LEAD_MS - offset) + periodMs) % periodMs)

            assertTrue(
                engine.transmitMessage(
                    text = text,
                    myCall = MY_CALL,
                    myGrid = MY_GRID,
                    submode = 0,
                    audioFrequencyHz = 1500.0,
                    txDelaySec = 2.0
                )
            )

            // Wait out the period start, then let one frame finish.
            val deadline = System.currentTimeMillis() + TX_TIMEOUT_MS
            while (!engine.isTransmittingAudio() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            assertTrue("modulator never started", engine.isTransmittingAudio())
            while (engine.isTransmittingAudio() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            Thread.sleep(DRAIN_MS) // let the last tap buffers arrive
            engine.stopTransmit()
        } finally {
            engine.close()
        }

        val frames = synchronized(captured) { captured.toList() }
        val total = frames.sumOf { it.size }
        val out = ShortArray(total)
        var at = 0
        frames.forEach { it.copyInto(out, at); at += it.size }

        // The tap sits after the output resampler, so it runs at whatever rate
        // the audio device negotiated, not the engine rate the decoder wants.
        Log.i(TAG, "TX tap rate $tapRate Hz")
        return if (tapRate == SAMPLE_RATE) out else resample(out, tapRate, SAMPLE_RATE)
    }

    private fun resample(input: ShortArray, from: Int, to: Int): ShortArray {
        val out = ShortArray((input.size.toLong() * to / from).toInt())
        for (i in out.indices) {
            val pos = i.toDouble() * from / to
            val j = pos.toInt()
            val frac = pos - j
            val a = input[j].toDouble()
            val b = if (j + 1 < input.size) input[j + 1].toDouble() else a
            out[i] = (a + (b - a) * frac).toInt().toShort()
        }
        return out
    }

    /** Writes the period out so it can be pulled off the device and inspected. */
    private fun dumpWav(samples: ShortArray) {
        val dir = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().targetContext.getExternalFilesDir(null)
        val file = java.io.File(dir, "tx_capture.wav")
        val bytes = samples.size * 2
        java.io.DataOutputStream(file.outputStream().buffered()).use { out ->
            fun le32(v: Int) = out.write(
                byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
            )
            fun le16(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
            out.writeBytes("RIFF"); le32(36 + bytes); out.writeBytes("WAVEfmt ")
            le32(16); le16(1); le16(1); le32(SAMPLE_RATE)
            le32(SAMPLE_RATE * 2); le16(2); le16(16)
            out.writeBytes("data"); le32(bytes)
            samples.forEach { le16(it.toInt()) }
        }
        Log.i(TAG, "wrote ${file.absolutePath}")
    }

    private fun decode(samples: ShortArray): List<String> {
        val decoded = CopyOnWriteArrayList<String>()
        var finished = false
        val engine = JS8Engine.create(
            sampleRateHz = SAMPLE_RATE,
            submodes = SUBMODE_A,
            callbackHandler = object : Handler() {
                override fun onDecoded(
                    utc: Int, snr: Int, dt: Float, freq: Float, text: String,
                    type: Int, quality: Float, mode: Int, driftMs: Int
                ) {
                    Log.i(TAG, "decode: '$text' dt=$dt freq=$freq snr=$snr")
                    decoded.add(text)
                }

                override fun onDecodeFinished(count: Int) { finished = true }
            }
        )
        try {
            assertTrue(engine.start())
            val now = System.currentTimeMillis()
            engine.setTimeDriftMs((MINUTE_MS - now % MINUTE_MS) % MINUTE_MS)
            engine.submitAudio(ShortArray(1))

            var offset = 0
            while (offset < samples.size) {
                val n = minOf(CHUNK, samples.size - offset)
                engine.submitAudio(samples.copyOfRange(offset, offset + n))
                offset += n
            }

            val deadline = System.currentTimeMillis() + DECODE_TIMEOUT_MS
            while (!finished && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            Thread.sleep(500)
        } finally {
            engine.close()
        }
        return decoded.toList()
    }

    private open class Handler : JS8Engine.CallbackHandler {
        override fun onDecoded(
            utc: Int, snr: Int, dt: Float, freq: Float, text: String,
            type: Int, quality: Float, mode: Int, driftMs: Int
        ) = Unit
        override fun onSpectrum(
            bins: FloatArray, binHz: Float, powerDb: Float, peakDb: Float
        ) = Unit
        override fun onDecodeStarted(submodes: Int) = Unit
        override fun onDecodeFinished(count: Int) = Unit
        override fun onError(message: String) { Log.w(TAG, "engine error: $message") }
        override fun onLog(level: Int, message: String) = Unit
    }

    private companion object {
        const val TAG = "LoopbackTest"
        const val SAMPLE_RATE = 12_000
        const val SUBMODE_A = 0x1
        const val PERIOD_S = 15
        const val START_DELAY_SAMPLES = 6_000 // submode A starts 500 ms in
        const val CHUNK = 4_096
        const val LEAD_MS = 1_500L
        const val DRAIN_MS = 1_500L
        const val NOISE_RMS = 300.0
        const val MINUTE_MS = 60_000L
        const val TX_TIMEOUT_MS = 45_000L
        const val DECODE_TIMEOUT_MS = 30_000L
        const val MY_CALL = "N5EKS"
        const val MY_GRID = "EM12"
    }
}
