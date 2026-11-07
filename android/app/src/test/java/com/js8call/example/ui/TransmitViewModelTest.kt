package com.js8call.example.ui

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class TransmitViewModelTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @Test
    fun recognizesDirectSavedMessages() {
        assertEquals("k1abc" to "Hello there", outgoingSavedMessage("msg Hello there", "k1abc"))
        assertEquals("@GROUP" to "HELLO", outgoingSavedMessage("MSG HELLO", "@GROUP"))
        assertEquals("W1AW" to "HELLO", outgoingSavedMessage("W1AW MSG HELLO", "K1ABC"))
        assertEquals("K1ABC/P" to "HELLO", outgoingSavedMessage("K1ABC/P MSG HELLO", null))
        assertEquals("@DX/NA" to "HELLO", outgoingSavedMessage("@DX/NA MSG HELLO", null))
    }

    @Test
    fun threadTextAddressesOnce() {
        assertEquals("W1AW SNR?", threadText("W1AW", "SNR?"))
        assertEquals("W1AW K1ABC SAYS HI", threadText("W1AW", "K1ABC SAYS HI"))
        assertEquals("W1AW SNR?", threadText("W1AW", "w1aw snr?"))
        assertEquals("W1AW SNR?", threadText("W1AW", "snr?"))
        assertEquals("`RAW TEXT", threadText("W1AW", "`RAW TEXT"))
    }

    @Test
    fun threadBubbleShowsTheTextWithoutTheThreadCall() {
        assertEquals("SNR?", threadBubbleText("W1AW", threadText("W1AW", "SNR?")))
        assertEquals("SNR?", threadBubbleText("W1AW", threadText("W1AW", "w1aw snr?")))
        assertEquals("K1ABC SAYS HI", threadBubbleText("W1AW", threadText("W1AW", "K1ABC SAYS HI")))
        assertEquals("HELLO", threadBubbleText("W1AW", threadText("W1AW", "MSG HELLO")))
        assertEquals("`RAW TEXT", threadBubbleText("W1AW", threadText("W1AW", "`RAW TEXT")))
    }

    @Test
    fun ignoresOtherTrafficAndRelayCommands() {
        assertNull(outgoingSavedMessage("MSG TO:W1AW HELLO", "K1ABC"))
        assertNull(outgoingSavedMessage("K1ABC MSG TO:W1AW HELLO", null))
        assertNull(outgoingSavedMessage("MSGS HELLO", "K1ABC"))
        assertNull(outgoingSavedMessage("HELLO", "K1ABC"))
        assertNull(outgoingSavedMessage("MSG HELLO", null))
        assertNull(outgoingSavedMessage("MSG", "K1ABC"))
    }

    @Test
    fun completionRemovesActiveEntryNotNewHigherPriorityEntry() {
        val viewModel = TransmitViewModel(Application())
        viewModel.queueMessage("FIRST", "K1ABC")
        val active = viewModel.getNextMessage()!!
        viewModel.beginTransmission(active)
        viewModel.startTransmitting()
        viewModel.queueMessage("URGENT", "W1AW", priority = 2)
        viewModel.transmissionComplete()
        assertEquals("URGENT", viewModel.getNextMessage()?.text)
    }
}
