package com.js8call.example.service

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class UvProSessionTest {

    @Test
    fun escapesFlagAndEscapeBytes() {
        val payload = byteArrayOf(0x01, 0x7E, 0x02, 0x7D, 0x03)
        val expected = byteArrayOf(0x7E, 0x00, 0x01, 0x7D, 0x5E, 0x02, 0x7D, 0x5D, 0x03, 0x7E)
        assertArrayEquals(expected, UvProSession.frame(UvProSession.CMD_AUDIO, payload))
    }

    @Test
    fun splitterRecoversFramesAcrossReads() {
        val first = UvProSession.frame(UvProSession.CMD_AUDIO, byteArrayOf(0x7E, 0x10))
        val second = UvProSession.frame(UvProSession.CMD_AUDIO_ALT, byteArrayOf(0x7D, 0x20))
        val stream = first + second
        val frames = ArrayList<Pair<Int, List<Byte>>>()
        val splitter = UvProSession.FrameSplitter()
        // Feed one byte at a time so frames and escapes straddle reads.
        for (b in stream) splitter.push(byteArrayOf(b), 1) { cmd, payload -> frames.add(cmd to payload.toList()) }

        assertEquals(2, frames.size)
        assertEquals(UvProSession.CMD_AUDIO to listOf<Byte>(0x7E, 0x10), frames[0])
        assertEquals(UvProSession.CMD_AUDIO_ALT to listOf<Byte>(0x7D, 0x20), frames[1])
    }

    @Test
    fun endFrameIsTheRadiosUnkeyMessage() {
        val splitter = UvProSession.FrameSplitter()
        var cmd = -1
        splitter.push(UvProSession.END_FRAME, UvProSession.END_FRAME.size) { c, _ -> cmd = c }
        assertEquals(0x01, cmd)
    }

    @Test
    fun encodedAudioSurvivesFraming() {
        val sbc = SbcEncoder().encode(ShortArray(128) { (it * 200).toShort() })
        val framed = UvProSession.frame(UvProSession.CMD_AUDIO, sbc)
        var payload = ByteArray(0)
        UvProSession.FrameSplitter().push(framed, framed.size) { _, p -> payload = p }
        assertArrayEquals(sbc, payload)
        assertEquals(sbc.size, Sbc.frameLength(payload, 0, payload.size))
    }
}
