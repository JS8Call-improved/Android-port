package com.js8call.core

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Feeds the desktop project's reference recordings straight into the engine,
 * so a failure is the decoder rather than the microphone or the radio. The
 * files are named MODE_DEPTH_EXPECTEDDECODES.wav.
 *
 * This is a smoke test, not desktop parity: the engine decodes a 13.6 second
 * window at a fixed depth, where the desktop CLI reads the whole file, so the
 * counts run a little under the names. The counts are logged for comparison.
 */
@RunWith(AndroidJUnit4::class)
class JS8EngineReferenceDecodeTest {

    @Test
    fun decodesReferenceRecordings() {
        val results = listOf("A_2_1.wav" to 1, "A_2_9.wav" to 9, "A_3_3.wav" to 3)
            .map { (name, expected) -> Triple(name, expected, decodeAsset(name)) }

        results.forEach { (name, expected, texts) ->
            Log.i(TAG, "$name: desktop gets $expected, got ${texts.size} $texts")
        }

        val total = results.sumOf { (_, _, texts) -> texts.size }
        assertTrue("decoded $total messages from the reference set", total >= MIN_DECODES)
    }

    /** Aligns the ring to the start of a period, then submits the whole file. */
    private fun decodeAsset(name: String): List<String> {
        val samples = readWavMono16(name)
        val decoded = CopyOnWriteArrayList<String>()
        var finished = false

        val engine = JS8Engine.create(
            sampleRateHz = SAMPLE_RATE,
            submodes = SUBMODE_A,
            callbackHandler = object : JS8Engine.CallbackHandler {
                override fun onDecoded(
                    utc: Int, snr: Int, dt: Float, freq: Float, text: String,
                    type: Int, quality: Float, mode: Int, driftMs: Int
                ) {
                    decoded.add(String.format("%s [dt=%+.2f f=%.0f]", text, dt, freq))
                }

                override fun onSpectrum(
                    bins: FloatArray, binHz: Float, powerDb: Float, peakDb: Float
                ) = Unit

                override fun onDecodeStarted(submodes: Int) = Unit
                override fun onDecodeFinished(count: Int) { finished = true }
                override fun onError(message: String) { Log.w(TAG, "engine error: $message") }
                override fun onLog(level: Int, message: String) = Unit
            }
        )

        try {
            assertTrue(engine.start())

            // The ring phase comes from the wall clock, so shift the engine's
            // clock to the top of a minute and let the next submit re-align.
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

    private fun readWavMono16(name: String): ShortArray {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open(name).use { it.readBytes() }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // Walk the RIFF chunks rather than assuming a 44 byte header.
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = buf.getInt(pos + 4)
            if (id == "data") {
                val out = ShortArray(size / 2)
                buf.position(pos + 8)
                buf.asShortBuffer().get(out)
                return out
            }
            pos += 8 + size + (size and 1)
        }
        throw IllegalStateException("no data chunk in $name")
    }

    private companion object {
        const val TAG = "RefDecodeTest"
        const val SAMPLE_RATE = 12_000
        const val SUBMODE_A = 0x1
        const val CHUNK = 4_096
        const val MIN_DECODES = 5
        const val MINUTE_MS = 60_000L
        const val DECODE_TIMEOUT_MS = 30_000L
    }
}
