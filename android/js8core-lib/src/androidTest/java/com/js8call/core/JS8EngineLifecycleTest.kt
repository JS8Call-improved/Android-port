package com.js8call.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JS8EngineLifecycleTest {
    @Test
    fun closeWaitsForActiveSpectrumCallback() {
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val closeFinished = CountDownLatch(1)
        val closeFailure = AtomicReference<Throwable?>()
        val engine = JS8Engine.create(
            callbackHandler = object : JS8Engine.CallbackHandler {
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
                ) {
                    callbackEntered.countDown()
                    releaseCallback.await(5, TimeUnit.SECONDS)
                }

                override fun onDecodeStarted(submodes: Int) = Unit

                override fun onDecodeFinished(count: Int) = Unit

                override fun onError(message: String) = Unit

                override fun onLog(level: Int, message: String) = Unit
            }
        )

        var closeThread: Thread? = null
        try {
            assertTrue(engine.start())
            assertTrue(engine.submitAudio(ShortArray(4096) { (it % 256).toShort() }))
            assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))

            closeThread = Thread {
                try {
                    engine.close()
                } catch (error: Throwable) {
                    closeFailure.set(error)
                } finally {
                    closeFinished.countDown()
                }
            }.apply { start() }

            assertFalse(closeFinished.await(100, TimeUnit.MILLISECONDS))
        } finally {
            releaseCallback.countDown()
            closeThread?.join(5_000) ?: engine.close()
        }

        assertFalse(closeThread!!.isAlive)
        assertNull(closeFailure.get())
    }
}
