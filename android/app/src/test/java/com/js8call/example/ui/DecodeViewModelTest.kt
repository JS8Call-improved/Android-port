package com.js8call.example.ui

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.js8call.example.model.DecodedMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DecodeViewModelTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private fun decode(text: String) = DecodedMessage(
        utc = 0, snr = 0, dt = 0f, frequency = 0f, text = text,
        type = 3, quality = 0f, mode = 0
    )

    private fun texts(viewModel: DecodeViewModel) = viewModel.decodes.value.orEmpty().map { it.text }

    @Test
    fun newestDecodeGoesLast() {
        val viewModel = DecodeViewModel(Application())
        viewModel.addDecode(decode("K1ABC: CQ"))
        viewModel.addDecode(decode("W1AW: CQ"))

        assertEquals(listOf("K1ABC: CQ", "W1AW: CQ"), texts(viewModel))
    }

    @Test
    fun overflowDropsTheOldest() {
        val viewModel = DecodeViewModel(Application())
        repeat(1001) { viewModel.addDecode(decode("K${it}ABC: CQ")) }

        val texts = texts(viewModel)
        assertEquals(1000, texts.size)
        assertEquals("K1ABC: CQ", texts.first())
        assertEquals("K1000ABC: CQ", texts.last())
    }

    @Test
    fun outgoingIsMarkedAndAppended() {
        val viewModel = DecodeViewModel(Application())
        viewModel.addDecode(decode("K1ABC: N0CALL SNR?"))
        viewModel.addOutgoing("K1ABC SNR -10", 1500f, 123015)

        val last = viewModel.decodes.value.orEmpty().last()
        assertTrue(last.outgoing)
        assertEquals("K1ABC SNR -10", last.text)
        assertEquals(123015, last.utc)
        assertEquals(1500f, last.frequency)
    }

    @Test
    fun heardCallsignsAreNewestFirstAndSkipOutgoing() {
        val viewModel = DecodeViewModel(Application())
        viewModel.addDecode(decode("K1ABC: CQ"))
        viewModel.addDecode(decode("W1AW: CQ"))
        viewModel.addOutgoing("KE5XYZ HELLO", 1500f, 0)
        viewModel.addDecode(decode("K1ABC: W1AW HI"))

        assertEquals(listOf("K1ABC", "W1AW"), viewModel.heardCallsigns())
    }
}
