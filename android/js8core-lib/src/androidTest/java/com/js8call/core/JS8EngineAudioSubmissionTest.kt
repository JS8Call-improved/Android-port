package com.js8call.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JS8EngineAudioSubmissionTest {
    @Test
    fun validatesAudioSampleCounts() {
        val engine = JS8Engine.create(callbackHandler = NoOpCallbackHandler)
        try {
            assertTrue(engine.start())

            assertFalse(engine.submitAudio(shortArrayOf()))
            assertTrue(engine.submitAudio(shortArrayOf(1)))
            assertTrue(engine.submitAudio(shortArrayOf(1, 2)))

            val raw = shortArrayOf(1, 2, 3)
            assertFalse(engine.submitAudioRaw(raw, -1, 12_000))
            assertFalse(engine.submitAudioRaw(raw, raw.size + 1, 12_000))
            assertFalse(engine.submitAudioRaw(shortArrayOf(), 0, 12_000))
            assertTrue(engine.submitAudioRaw(raw, 2, 12_000))
        } finally {
            engine.close()
        }
    }

    private object NoOpCallbackHandler : JS8Engine.CallbackHandler {
        override fun onDecoded(
            utc: Int,
            snr: Int,
            dt: Float,
            freq: Float,
            text: String,
            type: Int,
            quality: Float,
            mode: Int
        ) = Unit

        override fun onSpectrum(
            bins: FloatArray,
            binHz: Float,
            powerDb: Float,
            peakDb: Float
        ) = Unit

        override fun onDecodeStarted(submodes: Int) = Unit

        override fun onDecodeFinished(count: Int) = Unit

        override fun onError(message: String) = Unit

        override fun onLog(level: Int, message: String) = Unit
    }
}
