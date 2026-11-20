package com.js8call.example.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.js8call.example.util.Js8Speed
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeedLabelsTest {
    @Test
    fun highSpeedLabelsMatchDesktop() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("40", context.getString(Js8Speed.SPEED_40.label))
        assertEquals("60", context.getString(Js8Speed.SPEED_60.label))
    }
}
