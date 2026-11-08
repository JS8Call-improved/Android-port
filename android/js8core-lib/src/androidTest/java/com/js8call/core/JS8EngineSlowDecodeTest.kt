package com.js8call.core

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JS8EngineSlowDecodeTest {

    @Test
    fun decodesTheSlowRecording() {
        val decoded = TestEngine(SUBMODE_SLOW).use { it.decode(readWav("E_1_1.wav")) }
        Log.i(TAG, "E_1_1.wav: $decoded")
        assertEquals("decodes from E_1_1.wav: $decoded", 1, decoded.size)
    }

    private companion object {
        const val TAG = "SlowDecodeTest"
        const val SUBMODE_SLOW = 4
    }
}
