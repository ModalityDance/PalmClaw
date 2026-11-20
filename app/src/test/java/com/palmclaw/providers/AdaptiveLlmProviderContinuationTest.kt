package com.palmclaw.providers

import com.palmclaw.config.AppConfig
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class AdaptiveLlmProviderContinuationTest {
    @Test
    fun `successful target is pinned and later rejection does not fall back`(): Unit = runBlocking {
        val profile = ProviderProfile("pin-test", "Test", "https://pin.test/chat/completions",
            alternateBaseUrls = listOf("https://pin.test/other/chat/completions"))
        val visited = mutableListOf<ProviderExecutionTarget>()
        val provider = AdaptiveLlmProvider(profile, config(profile), OkHttpClient(), delegateFactory = { target ->
            fake {
                visited.add(target)
                if (visited.size == 1) LlmResponse(AssistantMessage("done"))
                else throw ProviderHttpException("Test", 400, "invalid request")
            }
        })
        provider.chat(listOf(ChatMessage("user", "first")), emptyList())
        try {
            provider.chat(listOf(ChatMessage("user", "second")), emptyList())
            fail("Expected rejection")
        } catch (_: ProviderHttpException) {
            assertEquals(2, visited.size)
            assertEquals(visited[0], visited[1])
        }
    }

    @Test
    fun `current signed chain cannot cross provider protocol before dispatch`(): Unit = runBlocking {
        val profile = ProviderProfile("other-protocol-test", "Test", "https://other.test/v1/messages",
            defaultProtocol = ProviderProtocol.Anthropic)
        var dispatches = 0
        val provider = AdaptiveLlmProvider(profile, config(profile).copy(providerProtocol = ProviderProtocol.Anthropic),
            OkHttpClient(), delegateFactory = {
                fake { dispatches++; LlmResponse(AssistantMessage("done")) }
            })
        val metadata = ProtocolMetadata("original-scope",
            Json.parseToJsonElement("""{"google":{"thought_signature":"private-state"}}""").jsonObject)
        val history = listOf(ChatMessage("user", "write"), ChatMessage("assistant", "",
            toolCalls = listOf(ToolCall("a", "write", "{}", metadata))), ChatMessage("tool", "written", "a"))
        try {
            provider.chat(history, emptyList())
            fail("Expected scope rejection")
        } catch (error: IOException) {
            assertEquals(0, dispatches)
            assertTrue(error.message!!.contains("Previously completed tool actions remain completed"))
            assertFalse(error.message!!.contains("private-state"))
        }
    }

    private fun config(profile: ProviderProfile) = AppConfig(
        providerName = profile.id, apiKey = "test", model = "test-model", baseUrl = profile.baseUrl,
        activeProviderConfigId = profile.id
    )

    private fun fake(response: () -> LlmResponse) = object : LlmProvider {
        override suspend fun chat(messages: List<ChatMessage>, toolsSpec: List<ToolSpec>) = response()
        override fun chatStream(messages: List<ChatMessage>, toolsSpec: List<ToolSpec>): Flow<LlmStreamEvent> =
            flow { emit(LlmStreamEvent.Final(response())) }
    }
}
