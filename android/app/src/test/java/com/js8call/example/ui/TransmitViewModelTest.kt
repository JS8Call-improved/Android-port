package com.js8call.example.ui

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TransmitViewModelTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @Test
    fun manualDirectedSendClearsComposition() {
        val viewModel = TransmitViewModel(Application())
        viewModel.setComposedMessage("HELLO")
        viewModel.setDirectedTo("K1ABC")

        viewModel.queueMessage("HELLO", "K1ABC")

        assertEquals("", viewModel.composedMessage.value)
        assertEquals("", viewModel.directedTo.value)
        assertEquals("K1ABC", viewModel.queue.value?.single()?.directed)
    }

    @Test
    fun subsequentReplyReplacesClearedDestination() {
        val viewModel = TransmitViewModel(Application())
        viewModel.setDirectedTo("K1ABC")
        viewModel.queueMessage("FIRST", "K1ABC")

        viewModel.setDirectedTo("W1AW")

        assertEquals("W1AW", viewModel.directedTo.value)
    }

    @Test
    fun automatedQueueEntryKeepsComposition() {
        val viewModel = TransmitViewModel(Application())
        viewModel.setComposedMessage("DRAFT")
        viewModel.setDirectedTo("K1ABC")

        viewModel.queueMessage("AUTO", "W1AW", clearComposed = false)

        assertEquals("DRAFT", viewModel.composedMessage.value)
        assertEquals("K1ABC", viewModel.directedTo.value)
    }
}
