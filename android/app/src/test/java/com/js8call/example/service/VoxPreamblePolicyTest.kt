package com.js8call.example.service

import org.junit.Assert.assertEquals
import org.junit.Test

class VoxPreamblePolicyTest {
    @Test
    fun allRigControlModesDisablePreambleRegardlessOfConfiguredDuration() {
        val rigModes = listOf("network", "hamlib_usb", "rts_ptt", "trusdx_serial", "qmx_serial", "btech_uvpro")
        for (mode in rigModes) {
            for (seconds in 0..5) {
                assertEquals("mode=$mode seconds=$seconds", 0, voxPreambleDurationMs(mode, seconds))
            }
        }
    }

    @Test
    fun unknownModesAlsoFailClosed() {
        assertEquals(0, voxPreambleDurationMs("unexpected", 5))
    }

    @Test
    fun enabledRigControlDisablesPreambleEvenWhenRigTypeIsNone() {
        assertEquals(0, voxPreambleDurationMs("none", 5, rigControlEnabled = true))
    }

    @Test
    fun audioOnlyModeUsesBoundedConfiguredDuration() {
        for (seconds in 0..5) {
            assertEquals(seconds * 1000, voxPreambleDurationMs("none", seconds))
        }
        assertEquals(0, voxPreambleDurationMs("none", -1))
        assertEquals(5000, voxPreambleDurationMs("none", 99))
    }
}
