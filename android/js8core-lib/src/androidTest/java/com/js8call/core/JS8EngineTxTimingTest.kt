package com.js8call.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JS8EngineTxTimingTest {
    @Test
    fun transmitReadyGateHoldsActiveModulation() {
        val engine = JS8Engine.create(
            callbackHandler = NoOpCallbackHandler,
            enableTxAudioTap = true
        )
        try {
            assertTrue(engine.start())
            engine.setTransmitReady(false)
            assertTrue(engine.startTune(audioFrequencyHz = 1500.0, submode = 0))
            assertFalse(engine.isTransmittingAudio())

            engine.setTransmitReady(true)
            assertTrue(engine.isTransmittingAudio())
            engine.stopTransmit()
        } finally {
            engine.close()
        }
    }

    @Test
    fun reportsTimeUntilScheduledAudio() {
        val engine = JS8Engine.create(
            callbackHandler = NoOpCallbackHandler,
            enableTxAudioTap = true
        )
        try {
            assertTrue(engine.start())
            assertEquals(-1, engine.txMillisecondsUntilAudio())
            assertTrue(
                engine.transmitFrame(
                    frame = "0123456789AB",
                    bits = 0,
                    submode = 0,
                    audioFrequencyHz = 1500.0
                )
            )

            val remainingMs = engine.txMillisecondsUntilAudio()
            assertTrue(remainingMs >= 0)
            if (remainingMs > 0) {
                assertFalse(engine.isTransmittingAudio())
            }

            engine.stopTransmit()
            assertEquals(-1, engine.txMillisecondsUntilAudio())
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
            mode: Int,
            driftMs: Int
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
