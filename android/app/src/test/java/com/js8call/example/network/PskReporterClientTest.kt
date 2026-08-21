package com.js8call.example.network

import org.junit.Assert.assertEquals
import org.junit.Test

class PskReporterClientTest {
    @Test
    fun buildingAnUnsentMessageDoesNotConsumeDescriptors() {
        val client = PskReporterClient("JS8Android-test")
        val descriptors = client.javaClass.getDeclaredField("sendDescriptors").apply {
            isAccessible = true
        }
        val createMessage = client.javaClass.getDeclaredMethod("createBaseMessage").apply {
            isAccessible = true
        }

        descriptors.setInt(client, 3)
        createMessage.invoke(client)

        assertEquals(3, descriptors.getInt(client))
    }
}
