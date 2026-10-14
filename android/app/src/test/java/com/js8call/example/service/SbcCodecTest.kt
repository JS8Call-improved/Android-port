package com.js8call.example.service

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SbcCodecTest {

    @Test
    fun writesThe32kMonoHeaderTheUvProExpects() {
        val snr = SbcEncoder(bitpool = 40, snrAllocation = true).encode(ShortArray(128))
        assertEquals(0x9C, snr[0].toInt() and 0xFF)
        assertEquals(0x73, snr[1].toInt() and 0xFF)
        assertEquals(40, snr[2].toInt() and 0xFF)
        assertEquals(88, snr.size)

        val loudness = SbcEncoder(bitpool = 18, snrAllocation = false).encode(ShortArray(128))
        assertEquals(0x71, loudness[1].toInt() and 0xFF)
        assertEquals(44, loudness.size)
    }

    @Test
    fun frameLengthAcceptsOwnFramesAndRejectsCorruptOnes() {
        val frame = SbcEncoder().encode(tone(listOf(1000.0), 128))
        assertEquals(frame.size, Sbc.frameLength(frame, 0, frame.size))
        assertEquals(32000, Sbc.sampleRateHz(frame, 0))
        assertEquals(-1, Sbc.frameLength(frame, 0, frame.size - 1))

        val badCrc = frame.copyOf().also { it[3] = (it[3].toInt() xor 0x01).toByte() }
        assertEquals(-1, Sbc.frameLength(badCrc, 0, badCrc.size))
        assertNull(SbcDecoder().decode(badCrc, 0, badCrc.size))
    }

    @Test
    fun roundTripKeepsASingleTone() {
        assertRoundTrip(listOf(1500.0), bitpool = 40, snr = true, minSnrDb = 30.0)
        assertRoundTrip(listOf(1500.0), bitpool = 18, snr = false, minSnrDb = 20.0)
    }

    @Test
    fun roundTripKeepsJs8LikeTonesApart() {
        // JS8 Normal uses 8 tones 6.25 Hz apart; neighbours must stay distinct.
        assertRoundTrip(listOf(1000.0, 1006.25, 1043.75), bitpool = 40, snr = true, minSnrDb = 25.0)
    }

    private fun assertRoundTrip(freqs: List<Double>, bitpool: Int, snr: Boolean, minSnrDb: Double) {
        val encoder = SbcEncoder(bitpool = bitpool, snrAllocation = snr)
        val decoder = SbcDecoder()
        val input = tone(freqs, 128 * 270)
        val output = ShortArray(input.size)
        for (frame in 0 until input.size / 128) {
            val bytes = encoder.encode(input, frame * 128)
            val pcm = decoder.decode(bytes, 0, bytes.size)!!
            pcm.copyInto(output, frame * 128)
        }

        // Skip the start-up transient and compare energy at the input tones
        // with everything else, over 0.96 s so every tone fits whole cycles.
        val settled = output.copyOfRange(3840, output.size)
        val total = settled.sumOf { it.toDouble() * it }
        val wanted = freqs.sumOf { power(settled, it) }
        val snrDb = 10 * log10(wanted / (total - wanted))
        assertTrue("SNR $snrDb dB below $minSnrDb dB", snrDb >= minSnrDb)

        val inRms = rms(input.copyOfRange(3840, input.size))
        val outRms = rms(settled)
        assertEquals("level changed", 1.0, outRms / inRms, 0.1)
    }

    private fun tone(freqs: List<Double>, n: Int): ShortArray = ShortArray(n) { i ->
        (freqs.sumOf { sin(2 * PI * it * i / 32000) } * 8000 / freqs.size).toInt().toShort()
    }

    // Energy at one frequency (Goertzel), scaled to match sum of squares.
    private fun power(x: ShortArray, freq: Double): Double {
        val coeff = 2 * cos(2 * PI * freq / 32000)
        var s1 = 0.0
        var s2 = 0.0
        for (v in x) {
            val s = v + coeff * s1 - s2
            s2 = s1
            s1 = s
        }
        return 2 * (s1 * s1 + s2 * s2 - coeff * s1 * s2) / x.size
    }

    private fun rms(x: ShortArray) = sqrt(x.sumOf { it.toDouble() * it } / x.size)
}
