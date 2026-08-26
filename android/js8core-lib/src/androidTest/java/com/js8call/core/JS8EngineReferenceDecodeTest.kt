package com.js8call.core

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Feeds the desktop project's reference recordings straight into the engine,
 * so a failure is the decoder rather than the microphone or the radio. The
 * files are named MODE_DEPTH_EXPECTEDDECODES.wav and come from media/tests,
 * packaged as test assets by the androidTest source set.
 *
 * This is a smoke test, not desktop parity: the engine decodes a 13.6 second
 * window at a fixed depth, where the desktop CLI reads the whole file at the
 * depth in the name, so the counts run under them. The counts are logged for
 * comparison. On the emulator the set gives 25 or 26 decodes across 6 of the
 * 7 files against the desktop's 31; one marginal decode in A_2_5 comes and
 * goes with how the ring lands, which is why the bounds below sit under that
 * rather than on it.
 */
@RunWith(AndroidJUnit4::class)
class JS8EngineReferenceDecodeTest {

    @Test
    fun decodesReferenceRecordings() {
        val results = REFERENCES.map { file ->
            val decoded = TestEngine().use { it.decode(readWav(file)) }
            Log.i(TAG, "$file: ${decoded.size} $decoded")
            decoded
        }

        val total = results.sumOf { it.size }
        val files = results.count { it.isNotEmpty() }
        Log.i(TAG, "decoded $total messages across $files of ${results.size} files")
        assertTrue("only $total decodes from the reference set", total >= MIN_DECODES)
        assertTrue("only $files files produced anything", files >= MIN_FILES)
    }

    private companion object {
        const val TAG = "RefDecodeTest"

        // Every submode A recording in media/tests. The engine decodes at a
        // fixed depth, so files that differ only in that digit come back the same.
        val REFERENCES = listOf(
            "A_1_4.wav", "A_2_1.wav", "A_2_3.wav", "A_2_5.wav",
            "A_2_6.wav", "A_2_9.wav", "A_3_3.wav",
        )

        const val MIN_DECODES = 20
        const val MIN_FILES = 5
    }
}
