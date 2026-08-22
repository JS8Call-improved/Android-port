package com.js8call.example.util

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Js8GroupsTest {

    /**
     * Drift guard. kBaseCalls is what the protocol packs group callsigns
     * against, so a divergence is a wire-format bug.
     */
    @Test
    fun kotlinListMatchesNativeBaseCalls() {
        val source = findVaricodeSource()
        assertNotNull(
            "Could not locate core/src/protocol/varicode.cpp from ${File("").absolutePath}",
            source
        )

        val body = source!!.readText()
        val start = body.indexOf("kBaseCalls")
        assertTrue("kBaseCalls not found in ${source.path}", start >= 0)
        val keysStart = body.indexOf("keys = {", start)
        val keysEnd = body.indexOf("};", keysStart)
        assertTrue("kBaseCalls keys block is malformed", keysStart in 0 until keysEnd)

        val native = Regex("\"([^\"]+)\"")
            .findAll(body.substring(keysStart, keysEnd))
            .map { it.groupValues[1] }
            .filter { it != "<....>" }
            .toList()

        assertTrue("Parsed too few entries from the native table", native.size > 40)
        assertEquals(
            "Kotlin and native well-known group lists disagree",
            native,
            Js8Groups.WELL_KNOWN
        )
    }

    private fun findVaricodeSource(): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "core/src/protocol/varicode.cpp")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        return null
    }
}
