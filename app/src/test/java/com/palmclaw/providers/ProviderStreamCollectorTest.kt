package com.palmclaw.providers

import java.io.IOException
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ProviderStreamCollectorTest {

    @Test
    fun `collectStreamResponse returns the final response after delta events`() {
        val expected = LlmResponse(
            assistant = AssistantMessage(content = "done")
        )

        val result = runBlocking {
            collectStreamResponse(
                flowOf(
                    LlmStreamEvent.DeltaText("hel"),
                    LlmStreamEvent.DeltaText("lo"),
                    LlmStreamEvent.Final(expected)
                )
            )
        }

        assertEquals(expected, result)
    }

    @Test
    fun `collectStreamResponse rethrows provider throwable when stream ends with error`() {
        val expected = IllegalStateException("bad chunk")

        try {
            runBlocking {
                collectStreamResponse(
                    flowOf(LlmStreamEvent.Error("bad chunk", expected))
                )
            }
            fail("Expected provider throwable to be rethrown")
        } catch (error: IllegalStateException) {
            assertEquals("bad chunk", error.message)
        }
    }

    @Test
    fun `collectStreamResponse wraps error message when throwable is absent`() {
        try {
            runBlocking {
                collectStreamResponse(
                    flowOf(LlmStreamEvent.Error("network unavailable"))
                )
            }
            fail("Expected IOException")
        } catch (error: IOException) {
            assertEquals("network unavailable", error.message)
        }
    }

    @Test
    fun `collectStreamResponse fails when stream finishes without final response`() {
        try {
            runBlocking {
                collectStreamResponse(emptyFlow())
            }
            fail("Expected IOException")
        } catch (error: IOException) {
            assertEquals("Stream finished without a final response.", error.message)
        }
    }
}
