package com.js8call.example.service

/**
 * Isolates USB serial backpressure from the native TX audio callback.
 *
 * The caller owns conversion to the TruSDX U8 wire format. [writeFrame] must
 * serialize with CAT/PTT operations so raw audio cannot be interleaved with
 * control bytes.
 */
internal class TruSdxTxWriter(
    private val writeFrame: (ByteArray) -> Boolean,
    private val maxQueuedFrames: Int = DEFAULT_MAX_QUEUED_FRAMES
) : AutoCloseable {
    private val lock = Object()
    private val frames = ArrayDeque<ByteArray>()
    private var accepting = false
    private var running = false
    private var worker: Thread? = null

    @Volatile var droppedFrames = 0L
        private set
    @Volatile var writeFailures = 0L
        private set
    @Volatile var highWaterMark = 0
        private set

    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
            worker = Thread(::runWriter, "TruSdxTxWriter").apply { start() }
        }
    }

    fun enable() {
        synchronized(lock) {
            if (!running) return
            accepting = true
        }
    }

    fun enqueue(frame: ByteArray): Boolean {
        if (frame.isEmpty()) return true
        synchronized(lock) {
            if (!accepting || !running) return true
            if (frames.size >= maxQueuedFrames) {
                droppedFrames += 1
                return false
            }
            frames.addLast(frame)
            highWaterMark = maxOf(highWaterMark, frames.size)
            lock.notifyAll()
            return true
        }
    }

    /** Stops accepting new audio and discards queued audio before RX resumes. */
    fun disableAndClear() {
        synchronized(lock) {
            accepting = false
            frames.clear()
            lock.notifyAll()
        }
    }

    fun queuedFrames(): Int = synchronized(lock) { frames.size }

    override fun close() {
        val thread = synchronized(lock) {
            accepting = false
            frames.clear()
            running = false
            lock.notifyAll()
            worker.also { worker = null }
        }
        if (thread != null && thread !== Thread.currentThread()) thread.join()
    }

    private fun runWriter() {
        while (true) {
            val frame = synchronized(lock) {
                while (running && frames.isEmpty()) lock.wait()
                if (!running) return
                frames.removeFirst()
            }
            if (!writeFrame(frame)) writeFailures += 1
        }
    }

    private companion object {
        const val DEFAULT_MAX_QUEUED_FRAMES = 16
    }
}
