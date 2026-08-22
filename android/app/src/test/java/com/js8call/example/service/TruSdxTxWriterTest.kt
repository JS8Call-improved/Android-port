package com.js8call.example.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TruSdxTxWriterTest {
    @Test
    fun writesFramesInFifoOrder() {
        val written = Collections.synchronizedList(mutableListOf<Int>())
        val complete = CountDownLatch(3)
        TruSdxTxWriter(
            writeFrame = {
                written += it[0].toInt()
                complete.countDown()
                true
            }
        ).use { writer ->
            writer.start()
            writer.enable()
            assertTrue(writer.enqueue(byteArrayOf(1)))
            assertTrue(writer.enqueue(byteArrayOf(2)))
            assertTrue(writer.enqueue(byteArrayOf(3)))

            assertTrue(complete.await(1, TimeUnit.SECONDS))
            assertEquals(listOf(1, 2, 3), written)
        }
    }

    @Test
    fun discardsPreKeyAndQueuedFramesAfterDisable() {
        val firstWriteStarted = CountDownLatch(1)
        val allowFirstWrite = CountDownLatch(1)
        val written = Collections.synchronizedList(mutableListOf<Int>())
        TruSdxTxWriter(
            writeFrame = {
                written += it[0].toInt()
                firstWriteStarted.countDown()
                allowFirstWrite.await(1, TimeUnit.SECONDS)
                true
            }
        ).use { writer ->
            writer.start()
            assertTrue(writer.enqueue(byteArrayOf(1)))
            assertTrue(written.isEmpty())

            writer.enable()
            assertTrue(writer.enqueue(byteArrayOf(2)))
            assertTrue(firstWriteStarted.await(1, TimeUnit.SECONDS))
            assertTrue(writer.enqueue(byteArrayOf(3)))
            writer.disableAndClear()
            allowFirstWrite.countDown()

            Thread.sleep(50)
            assertEquals(listOf(2), written)
        }
    }

    @Test
    fun boundsQueueWithoutBlockingTheProducer() {
        val firstWriteStarted = CountDownLatch(1)
        val allowFirstWrite = CountDownLatch(1)
        TruSdxTxWriter(
            writeFrame = {
                firstWriteStarted.countDown()
                allowFirstWrite.await(1, TimeUnit.SECONDS)
                true
            },
            maxQueuedFrames = 1
        ).use { writer ->
            writer.start()
            writer.enable()
            assertTrue(writer.enqueue(byteArrayOf(1)))
            assertTrue(firstWriteStarted.await(1, TimeUnit.SECONDS))
            assertTrue(writer.enqueue(byteArrayOf(2)))
            assertFalse(writer.enqueue(byteArrayOf(3)))
            assertEquals(1L, writer.droppedFrames)
            allowFirstWrite.countDown()
        }
    }
}
