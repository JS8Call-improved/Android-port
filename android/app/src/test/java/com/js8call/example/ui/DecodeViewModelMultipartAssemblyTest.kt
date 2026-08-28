package com.js8call.example.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DecodeViewModelMultipartAssemblyTest {
    // JS8 frame type bits: 0x1 first, 0x2 last, 0x4 data.
    private fun directed(text: String) = DecodeFrame(text, 0x1)
    private fun data(text: String) = DecodeFrame(text, 0x4)

    @Test
    fun dataFramesConcatenateWithoutASpace() {
        // On air: freetext "N5EKS GRID EM10SM87MJ" split mid-token across two frames.
        assertEquals(
            "N5EKS GRID EM10SM87MJ",
            assembleMultipartDecodeText(listOf(data("N5EKS GRID EM10"), data("SM87MJ")))
        )
    }

    @Test
    fun bufferedCommandArgumentGetsASpace() {
        // GRID strips the separator before packing its argument, so the frames meet flush.
        assertEquals(
            "NT5DF: N5EKS GRID EM13TE",
            assembleMultipartDecodeText(listOf(directed("NT5DF: N5EKS GRID"), data("EM13TE")))
        )
    }

    @Test
    fun unbufferedCommandKeepsItsOwnSpace() {
        // STATUS is not buffered, so its data frame arrives with a leading space.
        assertEquals(
            "NT5DF: N5EKS STATUS IDLE AND MONITORING",
            assembleMultipartDecodeText(listOf(directed("NT5DF: N5EKS STATUS"), data(" IDLE AND MONITORING")))
        )
    }

    @Test
    fun groupTargetHeaderGetsASpace() {
        assertEquals(
            "2W0OXE: @RAYNET TEST",
            assembleMultipartDecodeText(listOf(directed("2W0OXE: @RAYNET"), data("TEST")))
        )
    }

    @Test
    fun directedHeaderThenManyDataFramesReassembles() {
        assertEquals(
            "NT5DF: N5EKS MSG HELLO WORLD HOW ARE YOU",
            assembleMultipartDecodeText(
                listOf(
                    directed("NT5DF: N5EKS MSG"),
                    data("HELLO WORL"),
                    data("D HOW ARE Y"),
                    data("OU")
                )
            )
        )
    }

    @Test
    fun aBlankedHelperFrameLeavesNoLeadingSpace() {
        // normalizeCompoundDirectedHelpers blanks a helper frame but leaves it in the list.
        assertEquals(
            "NT5DF: N5EKS GRID EM13",
            assembleMultipartDecodeText(listOf(directed(""), data("NT5DF: N5EKS GRID EM13")))
        )
    }

    @Test
    fun singleFramePassesThrough() {
        assertEquals(
            "K0OG: KN4CRD SNR +2",
            assembleMultipartDecodeText(listOf(directed("K0OG: KN4CRD SNR +2")))
        )
    }
}
