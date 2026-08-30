package com.js8call.core

import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random

/** The engine's own rate. Everything here is 16-bit mono PCM at this rate. */
internal const val SAMPLE_RATE = 12_000

internal val ShortArray.seconds: Float
    get() = size / SAMPLE_RATE.toFloat()

/** Drops the silence the TX tap delivers while the modulator waits for its slot. */
internal fun ShortArray.trimLeadingSilence(): ShortArray {
    val first = indexOfFirst { it.toInt() != 0 }.coerceAtLeast(0)
    return copyOfRange(first, size)
}

/** Lays the signal [startDelayMs] into an otherwise silent period of [periodMs]. */
internal fun ShortArray.placedInPeriod(periodMs: Int, startDelayMs: Int): ShortArray {
    val period = ShortArray(SAMPLE_RATE * periodMs / 1000)
    val at = SAMPLE_RATE * startDelayMs / 1000
    copyInto(period, at, 0, minOf(size, period.size - at))
    return period
}

/** Adds Gaussian noise for the decoder to measure against. Seeded, so runs repeat. */
internal fun ShortArray.withNoise(rms: Double, seed: Long): ShortArray {
    val rng = Random(seed)
    return ShortArray(size) { i ->
        (this[i] + rng.nextGaussian() * rms).toInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
    }
}

/** Linear interpolation: enough to bring the tap's rate back to the engine's. */
internal fun ShortArray.resampled(fromHz: Int, toHz: Int): ShortArray {
    val out = ShortArray((size.toLong() * toHz / fromHz).toInt())
    for (i in out.indices) {
        val pos = i.toDouble() * fromHz / toHz
        val j = pos.toInt()
        val a = this[j].toDouble()
        val b = if (j + 1 < size) this[j + 1].toDouble() else a
        out[i] = (a + (b - a) * (pos - j)).toInt().toShort()
    }
    return out
}

internal fun List<ShortArray>.concatenated(): ShortArray {
    val out = ShortArray(sumOf { it.size })
    var at = 0
    for (part in this) {
        part.copyInto(out, at)
        at += part.size
    }
    return out
}

/** A recording from the test assets. */
internal fun readWav(name: String): ShortArray {
    val bytes = InstrumentationRegistry.getInstrumentation().context.assets
        .open(name).use { it.readBytes() }
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    // Walk the RIFF chunks; the recordings carry more than the 44 byte header.
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
