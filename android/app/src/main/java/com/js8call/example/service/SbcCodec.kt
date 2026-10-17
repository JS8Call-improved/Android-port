/*
 * SBC (Bluetooth low-complexity subband codec), mono 8-subband frames only.
 * The BTECH UV-Pro carries its audio as SBC over a Bluetooth RFCOMM channel.
 *
 * Ported from HTCommander (https://github.com/Ylianst/HTCommander, src/lib/sbc),
 * which follows Google's libsbc.
 *   Copyright 2026 Ylian Saint-Hilaire
 *   Licensed under the Apache License, Version 2.0
 *   http://www.apache.org/licenses/LICENSE-2.0
 * Modified for JS8Call: translated from Dart to Kotlin and reduced to mono,
 * 8 subbands, 32 kHz encoding.
 */
package com.js8call.example.service

/** Encodes 32 kHz mono PCM into SBC frames of [blocks] x 8 samples. */
class SbcEncoder(
    private val blocks: Int = 16,
    private val bitpool: Int = 40,
    private val snrAllocation: Boolean = true
) {
    private val x = Array(2) { Array(Sbc.SUBBANDS) { IntArray(5) } }
    private val carry = LongArray(4)
    private var index = 0

    init {
        require(blocks == 4 || blocks == 8 || blocks == 12 || blocks == 16)
        require(bitpool in 2..Sbc.maxBitpool(blocks))
    }

    val samplesPerFrame: Int = blocks * Sbc.SUBBANDS
    val frameSize: Int = Sbc.frameSize(blocks, bitpool)

    /** Encodes [samplesPerFrame] samples starting at [offset] into one frame. */
    fun encode(pcm: ShortArray, offset: Int = 0): ByteArray {
        require(offset >= 0 && offset + samplesPerFrame <= pcm.size)
        val sb = IntArray(samplesPerFrame)
        for (blk in 0 until blocks) {
            analyze(pcm, offset + blk * Sbc.SUBBANDS, sb, blk * Sbc.SUBBANDS)
        }

        val scf = IntArray(Sbc.SUBBANDS)
        for (s in 0 until Sbc.SUBBANDS) {
            var m = 0
            for (blk in 0 until blocks) m = m or kotlin.math.abs(sb[blk * Sbc.SUBBANDS + s])
            scf[s] = if (m != 0) 31 - Integer.numberOfLeadingZeros(m) else 0
        }
        val nbits = Sbc.allocateBits(scf, bitpool, snrAllocation, Sbc.FREQ_32K)

        val out = ByteArray(frameSize)
        val w = BitWriter(out)
        w.put(Sbc.SYNCWORD, 8)
        w.put(Sbc.FREQ_32K, 2)
        w.put(blocks / 4 - 1, 2)
        w.put(0, 2) // mono
        w.put(if (snrAllocation) 1 else 0, 1)
        w.put(1, 1) // 8 subbands
        w.put(bitpool, 8)
        w.put(0, 8) // CRC, filled in below
        for (s in 0 until Sbc.SUBBANDS) w.put(scf[s], 4)
        for (blk in 0 until blocks) {
            for (s in 0 until Sbc.SUBBANDS) {
                val nbit = nbits[s]
                if (nbit == 0) continue
                val range = (1L shl nbit) - 1
                val sample = sb[blk * Sbc.SUBBANDS + s].toLong()
                w.put(((((sample * range) shr (scf[s] + 1)) + range) shr 1).toInt(), nbit)
            }
        }
        out[3] = Sbc.crc(out, 0).toByte()
        return out
    }

    private fun analyze(pcm: ShortArray, inOffset: Int, out: IntArray, outOffset: Int) {
        val idx = index shr 1
        val odd = index and 1
        val inIdx = if (idx != 0) 5 - idx else 0
        val xs = x[odd]
        for (i in 0 until Sbc.SUBBANDS) xs[i][inIdx] = pcm[inOffset + PCM_ORDER[i]].toInt()

        val t = LongArray(Sbc.SUBBANDS)
        for (i in 0 until Sbc.SUBBANDS) {
            var s = 0L
            for (j in 0 until 5) s += xs[i][j].toLong() * ANALYSIS_W0[i][idx + j]
            t[i] = s
        }
        val y = longArrayOf(
            t[0] + carry[0], t[2] + t[3], t[4] + t[5], t[6] + t[7],
            carry[1], carry[2], carry[3], t[1]
        )

        carry.fill(0)
        for (j in 0 until 5) {
            val w = idx + j
            carry[0] += xs[0][j].toLong() * ANALYSIS_W1[0][w]
            carry[1] += xs[2][j].toLong() * ANALYSIS_W1[2][w] - xs[3][j].toLong() * ANALYSIS_W1[3][w]
            carry[2] += xs[4][j].toLong() * ANALYSIS_W1[4][w] - xs[5][j].toLong() * ANALYSIS_W1[5][w]
            carry[3] += xs[6][j].toLong() * ANALYSIS_W1[6][w] - xs[7][j].toLong() * ANALYSIS_W1[7][w]
        }

        val ys = IntArray(Sbc.SUBBANDS) { Sbc.saturate16((y[it] + (1 shl 14)) shr 15) }
        index = if (index < 9) index + 1 else 0

        for (i in 0 until Sbc.SUBBANDS) {
            var s = 0L
            for (j in 0 until Sbc.SUBBANDS) s += ys[j].toLong() * COS_MATRIX[i][j]
            out[outOffset + i] = Sbc.saturate16((s + (1 shl 12)) shr 13)
        }
    }

    private class BitWriter(private val buf: ByteArray) {
        private var pos = 0

        fun put(value: Int, bits: Int) {
            for (i in bits - 1 downTo 0) {
                if ((value shr i) and 1 != 0) {
                    buf[pos shr 3] = (buf[pos shr 3].toInt() or (0x80 ushr (pos and 7))).toByte()
                }
                pos++
            }
        }
    }

    private companion object {
        val PCM_ORDER = intArrayOf(7, 3, 6, 0, 5, 1, 4, 2)

        // libsbc analyze_8 window[0] and window[1] (fixed 2.13), period 5, doubled.
        val ANALYSIS_W0 = Sbc.doubled(
            intArrayOf(0, 185, 2228, -2228, -185),
            intArrayOf(27, 480, 4039, -480, 30),
            intArrayOf(5, 263, 2719, -1743, -115),
            intArrayOf(58, 502, 4764, 290, 69),
            intArrayOf(11, 343, 3197, -1280, -54),
            intArrayOf(48, 532, 4612, 96, 65),
            intArrayOf(18, 418, 3644, -856, -6),
            intArrayOf(37, 521, 4367, -161, 53)
        )
        val ANALYSIS_W1 = Sbc.doubled(
            intArrayOf(66, 424, 4815, 424, 66),
            intArrayOf(30, -480, 4039, 480, 27),
            intArrayOf(69, 290, 4764, 502, 58),
            intArrayOf(-115, -1743, 2719, 263, 5),
            intArrayOf(65, 96, 4612, 532, 48),
            intArrayOf(-54, -1280, 3197, 343, 11),
            intArrayOf(53, -161, 4367, 521, 37),
            intArrayOf(-6, -856, 3644, 418, 18)
        )
        val COS_MATRIX = arrayOf(
            intArrayOf(5793, 6811, 7568, 8035, 4551, 3135, 1598, 8192),
            intArrayOf(-5793, -1598, 3135, 6811, -8035, -7568, -4551, 8192),
            intArrayOf(-5793, -8035, -3135, 4551, 1598, 7568, 6811, 8192),
            intArrayOf(5793, -4551, -7568, 1598, 6811, -3135, -8035, 8192),
            intArrayOf(5793, 4551, -7568, -1598, -6811, -3135, 8035, 8192),
            intArrayOf(-5793, 8035, -3135, -4551, -1598, 7568, -6811, 8192),
            intArrayOf(-5793, 1598, 3135, -6811, 8035, -7568, 4551, 8192),
            intArrayOf(5793, -6811, 7568, -8035, -4551, 3135, -1598, 8192)
        )
    }
}

/** Decodes mono 8-subband SBC frames to PCM at the frame's own sample rate. */
class SbcDecoder {
    private val v = Array(2) { Array(Sbc.SUBBANDS) { IntArray(10) } }
    private var index = 0

    /**
     * Decodes the frame at [offset]. Returns its PCM, or null when the bytes
     * there are not a valid mono 8-subband frame. Use [Sbc.frameLength] to step
     * to the next frame.
     */
    fun decode(data: ByteArray, offset: Int, length: Int): ShortArray? {
        val size = Sbc.frameLength(data, offset, length)
        if (size < 0) return null
        val h1 = data[offset + 1].toInt() and 0xFF
        val freq = (h1 shr 6) and 3
        val blocks = (((h1 shr 4) and 3) + 1) * 4
        val snr = (h1 shr 1) and 1 == 1
        val bitpool = data[offset + 2].toInt() and 0xFF

        val r = BitReader(data, offset + 4)
        val scf = IntArray(Sbc.SUBBANDS) { r.get(4) }
        val nbits = Sbc.allocateBits(scf, bitpool, snr, freq)
        val maxScf = scf.max()
        val scale = (15 - maxScf) - 1

        val sb = IntArray(blocks * Sbc.SUBBANDS)
        for (blk in 0 until blocks) {
            for (s in 0 until Sbc.SUBBANDS) {
                val nbit = nbits[s]
                if (nbit == 0) continue
                val sample = ((r.get(nbit) shl 1) or 1) * RANGE_SCALE[nbit - 1]
                sb[blk * Sbc.SUBBANDS + s] =
                    ((sample - (1 shl 28)) shr (28 - ((scf[s] + 1) + scale))).toShort().toInt()
            }
        }

        val out = ShortArray(blocks * Sbc.SUBBANDS)
        for (blk in 0 until blocks) synthesize(sb, blk * Sbc.SUBBANDS, scale, out, blk * Sbc.SUBBANDS)
        return out
    }

    private fun synthesize(input: IntArray, inOffset: Int, scale: Int, out: ShortArray, outOffset: Int) {
        val dctIdx = if (index != 0) 10 - index else 0
        val odd = dctIdx and 1
        dct8(input, inOffset, scale, v[odd], v[1 - odd], dctIdx)
        for (i in 0 until Sbc.SUBBANDS) {
            var s = 0L
            for (j in 0 until 10) s += v[odd][i][j].toLong() * SYNTHESIS_WINDOW[i][index + j]
            out[outOffset + i] = Sbc.saturate16((s + (1 shl 12)) shr 13).toShort()
        }
        index = if (index < 9) index + 1 else 0
    }

    private fun dct8(
        input: IntArray, o: Int, scale: Int,
        out0: Array<IntArray>, out1: Array<IntArray>, idx: Int
    ) {
        val c = COS16
        val s07 = ((input[o] + input[o + 7]) shr 1).toLong()
        val d07 = ((input[o] - input[o + 7]) shr 1).toLong()
        val s16 = ((input[o + 1] + input[o + 6]) shr 1).toLong()
        val d16 = ((input[o + 1] - input[o + 6]) shr 1).toLong()
        val s25 = ((input[o + 2] + input[o + 5]) shr 1).toLong()
        val d25 = ((input[o + 2] - input[o + 5]) shr 1).toLong()
        val s34 = ((input[o + 3] + input[o + 4]) shr 1).toLong()
        val d34 = ((input[o + 3] - input[o + 4]) shr 1).toLong()

        val shr = 12 + scale
        fun round(value: Long) = (value + (1L shl (shr - 1))) shr shr
        val a0 = round(((s07 + s34) - (s25 + s16)) * c[4])
        val b3 = round((-(s07 + s34) - (s25 + s16)) shl 13)
        val a2 = round((s07 - s34) * c[6] + (s25 - s16) * c[2])
        val b1 = round((s34 - s07) * c[2] + (s25 - s16) * c[6])
        val a1 = round(d07 * c[5] - d16 * c[1] + d25 * c[7] + d34 * c[3])
        val b2 = round(-d07 * c[1] - d16 * c[3] - d25 * c[5] - d34 * c[7])
        val a3 = round(d07 * c[7] - d16 * c[5] + d25 * c[3] - d34 * c[1])
        val b0 = round(-d07 * c[3] + d16 * c[7] + d25 * c[1] + d34 * c[5])

        fun sat(value: Long) = Sbc.saturate16(value)
        out0[0][idx] = sat(a0); out0[7][idx] = sat(-a1); out0[1][idx] = sat(a1); out0[6][idx] = sat(-a2)
        out0[2][idx] = sat(a2); out0[5][idx] = sat(-a3); out0[3][idx] = sat(a3); out0[4][idx] = 0
        out1[0][idx] = sat(-a0); out1[7][idx] = sat(b0); out1[1][idx] = sat(b0); out1[6][idx] = sat(b1)
        out1[2][idx] = sat(b1); out1[5][idx] = sat(b2); out1[3][idx] = sat(b2); out1[4][idx] = sat(b3)
    }

    private class BitReader(private val buf: ByteArray, start: Int) {
        private var pos = start * 8

        fun get(bits: Int): Int {
            var value = 0
            repeat(bits) {
                value = (value shl 1) or ((buf[pos shr 3].toInt() shr (7 - (pos and 7))) and 1)
                pos++
            }
            return value
        }
    }

    private companion object {
        // (1 << 28) / ((1 << (n + 1)) - 1), fixed 1.28, as rounded in libsbc.
        val RANGE_SCALE = intArrayOf(
            0x0FFFFFFF, 0x05555556, 0x02492492, 0x01111111,
            0x00842108, 0x00410410, 0x00204081, 0x00101010,
            0x00080402, 0x00040100, 0x00020040, 0x00010010,
            0x00008004, 0x00004001, 0x00002000, 0x00001000
        )

        // cos(i * pi / 16), fixed 0.13.
        val COS16 = longArrayOf(8192, 8035, 7568, 6811, 5793, 4551, 3135, 1598)

        // libsbc synthesis window for 8 subbands (fixed 2.13), period 10, doubled.
        val SYNTHESIS_WINDOW = Sbc.doubled(
            intArrayOf(0, -132, -371, -848, -4456, -9631, 4456, -848, 371, -132),
            intArrayOf(-10, -138, -526, -580, -5438, -9528, 3486, -1004, 229, -117),
            intArrayOf(-22, -131, -685, -192, -6395, -9224, 2561, -1063, 108, -97),
            intArrayOf(-36, -106, -835, 322, -7287, -8734, 1711, -1042, 12, -75),
            intArrayOf(-54, -59, -960, 959, -8078, -8078, 959, -960, -59, -54),
            intArrayOf(-75, 12, -1042, 1711, -8734, -7287, 322, -835, -106, -36),
            intArrayOf(-97, 108, -1063, 2561, -9224, -6395, -192, -685, -131, -22),
            intArrayOf(-117, 229, -1004, 3486, -9528, -5438, -580, -526, -138, -10)
        )
    }
}

/** Frame layout and bit allocation shared by [SbcEncoder] and [SbcDecoder]. */
object Sbc {
    const val SUBBANDS = 8
    const val SYNCWORD = 0x9C
    const val FREQ_32K = 1
    const val HEADER_SIZE = 4

    private val SAMPLE_RATES = intArrayOf(16000, 32000, 44100, 48000)

    // Loudness offsets for 8 subbands, by sampling-frequency index.
    private val LOUDNESS_OFFSET = arrayOf(
        intArrayOf(-2, 0, 0, 0, 0, 0, 0, 1),
        intArrayOf(-3, 0, 0, 0, 0, 0, 1, 2),
        intArrayOf(-4, 0, 0, 0, 0, 0, 1, 2),
        intArrayOf(-4, 0, 0, 0, 0, 0, 1, 2)
    )

    // CRC-8, polynomial x^8 + x^4 + x^3 + x^2 + 1.
    private val CRC_TABLE = IntArray(256) { i ->
        var c = i
        repeat(8) { c = if (c and 0x80 != 0) ((c shl 1) xor 0x1D) and 0xFF else (c shl 1) and 0xFF }
        c
    }

    fun frameSize(blocks: Int, bitpool: Int): Int =
        HEADER_SIZE + (4 * SUBBANDS + blocks * bitpool + 7) / 8

    fun maxBitpool(blocks: Int): Int =
        minOf((16 * SUBBANDS * blocks - HEADER_SIZE * 8 - 4 * SUBBANDS) / blocks, 16 * SUBBANDS)

    /** Sample rate of the frame at [offset], in Hz. */
    fun sampleRateHz(data: ByteArray, offset: Int): Int =
        SAMPLE_RATES[((data[offset + 1].toInt() and 0xFF) shr 6) and 3]

    /**
     * Length in bytes of the mono 8-subband frame at [offset], or -1 if the
     * bytes there are not one (bad sync, other layout, truncated or bad CRC).
     */
    fun frameLength(data: ByteArray, offset: Int, length: Int): Int {
        if (length < HEADER_SIZE || (data[offset].toInt() and 0xFF) != SYNCWORD) return -1
        val h1 = data[offset + 1].toInt() and 0xFF
        val blocks = (((h1 shr 4) and 3) + 1) * 4
        val mono = (h1 shr 2) and 3 == 0
        val eightSubbands = h1 and 1 == 1
        val bitpool = data[offset + 2].toInt() and 0xFF
        if (!mono || !eightSubbands || bitpool > maxBitpool(blocks)) return -1
        val size = frameSize(blocks, bitpool)
        if (length < size) return -1
        if (crc(data, offset) != (data[offset + 3].toInt() and 0xFF)) return -1
        return size
    }

    /** CRC over the header and scale factors of the mono 8-subband frame at [offset]. */
    fun crc(data: ByteArray, offset: Int): Int {
        var crc = 0x0F
        crc = CRC_TABLE[crc xor (data[offset + 1].toInt() and 0xFF)]
        crc = CRC_TABLE[crc xor (data[offset + 2].toInt() and 0xFF)]
        for (i in 4 until 4 + SUBBANDS / 2) crc = CRC_TABLE[crc xor (data[offset + i].toInt() and 0xFF)]
        return crc
    }

    /** Bits per subband for one mono channel, per the SBC allocation rules. */
    fun allocateBits(scf: IntArray, bitpool: Int, snr: Boolean, freq: Int): IntArray {
        val offsets = LOUDNESS_OFFSET[freq]
        val bitneed = IntArray(SUBBANDS)
        var maxBitneed = 0
        for (sb in 0 until SUBBANDS) {
            var need = if (snr) {
                scf[sb]
            } else {
                if (scf[sb] != 0) scf[sb] - offsets[sb] else -5
            }
            if (!snr && need > 0) need = need shr 1
            if (need > maxBitneed) maxBitneed = need
            bitneed[sb] = need
        }

        var bitcount = 0
        var bitslice = maxBitneed + 1
        var bc = 0
        while (bc < bitpool) {
            val bs = bitslice--
            bitcount = bc
            if (bitcount == bitpool) break
            for (sb in 0 until SUBBANDS) {
                val bn = bitneed[sb]
                bc += (if (bn >= bs && bn < bs + 15) 1 else 0) + (if (bn == bs) 1 else 0)
            }
        }

        val nbits = IntArray(SUBBANDS) { sb ->
            val n = bitneed[sb] - bitslice
            if (n < 2) 0 else minOf(n, 16)
        }
        var sb = 0
        while (sb < SUBBANDS && bitcount < bitpool) {
            val n = when {
                nbits[sb] in 1..15 -> 1
                bitneed[sb] == bitslice + 1 && bitpool > bitcount + 1 -> 2
                else -> 0
            }
            nbits[sb] += n
            bitcount += n
            sb++
        }
        sb = 0
        while (sb < SUBBANDS && bitcount < bitpool) {
            if (nbits[sb] < 16) {
                nbits[sb]++
                bitcount++
            }
            sb++
        }
        return nbits
    }

    internal fun saturate16(value: Long): Int = value.coerceIn(-32768L, 32767L).toInt()

    internal fun doubled(vararg rows: IntArray): Array<IntArray> = Array(rows.size) { rows[it] + rows[it] }
}
