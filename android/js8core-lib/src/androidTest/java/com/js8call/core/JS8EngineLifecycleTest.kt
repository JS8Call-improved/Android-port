package com.js8call.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JS8EngineLifecycleTest {
    @Test
    fun closeWhileSubmittingAudioIsSafe() {
        val engine = JS8Engine.create(callbackHandler = NoOpCallbackHandler)
        val keepSubmitting = AtomicBoolean(true)
        val submissionStarted = CountDownLatch(1)
        val workerFailure = AtomicReference<Throwable?>()
        val samples = ShortArray(4096) { (it % 256).toShort() }
        var worker: Thread? = null

        try {
            assertTrue(engine.start())
            worker = Thread {
                try {
                    while (keepSubmitting.get()) {
                        try {
                            engine.submitAudio(samples)
                            submissionStarted.countDown()
                        } catch (_: IllegalStateException) {
                            break
                        }
                    }
                } catch (error: Throwable) {
                    workerFailure.set(error)
                }
            }.apply { start() }

            assertTrue(submissionStarted.await(5, TimeUnit.SECONDS))
            engine.close()
        } finally {
            keepSubmitting.set(false)
            engine.close()
            worker?.join(5_000)
        }

        assertFalse(worker!!.isAlive)
        assertNull(workerFailure.get())
        assertFalse(engine.isRunning())
    }

    @Test
    fun concurrentCloseIsIdempotent() {
        val engine = JS8Engine.create(callbackHandler = NoOpCallbackHandler)
        val closeGate = CountDownLatch(1)
        val closeFailure = AtomicReference<Throwable?>()
        val closeThreads = List(2) {
            Thread {
                try {
                    closeGate.await()
                    engine.close()
                } catch (error: Throwable) {
                    closeFailure.compareAndSet(null, error)
                }
            }
        }

        try {
            assertTrue(engine.start())
            closeThreads.forEach { it.start() }
            closeGate.countDown()
            closeThreads.forEach { it.join(5_000) }
        } finally {
            closeGate.countDown()
            engine.close()
        }

        assertTrue(closeThreads.none { it.isAlive })
        assertNull(closeFailure.get())
        assertFalse(engine.isRunning())
    }

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
