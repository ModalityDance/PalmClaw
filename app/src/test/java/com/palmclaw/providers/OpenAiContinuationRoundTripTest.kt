package com.palmclaw.providers

import com.palmclaw.agent.AssistantTraceCodec
import com.palmclaw.agent.ContextBuilder
import com.palmclaw.agent.StoredAssistantTrace
import com.palmclaw.agent.ToolCallParser
import com.palmclaw.storage.entities.MessageEntity
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class OpenAiContinuationRoundTripTest {
    @Test
    fun `write result survives storage and model continuation without executing write again`(): Unit = runBlocking {
        val requests = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            val body = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            val step = requests.incrementAndGet()
            val valid = step == 1 || body["messages"]!!.jsonArray.any { message ->
                val calls = message.jsonObject["tool_calls"]?.jsonArray
                calls?.firstOrNull()?.jsonObject?.get("extra_content") == extra
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (valid) 200 else 400).message(if (valid) "OK" else "Bad Request")
                .body((when {
                    !valid -> """{"error":{"message":"Function call is missing a thought_signature"}}"""
                    step == 1 -> response
                    else -> """{"choices":[{"message":{"role":"assistant","content":"Created test.md"}}]}"""
                }).toResponseBody("application/json".toMediaType())).build()
        }.build()
        val firstProvider = provider(client)
        val first = firstProvider.chat(listOf(ChatMessage("user", "write test.md")), emptyList())
        val calls = ToolCallParser().parse(first)
        assertEquals("write", calls.single().name)
        val executed = mutableListOf<String>()
        fun executeToolCalls(response: LlmResponse) {
            ToolCallParser().parse(response).forEach { call ->
                // Fake tool execution; a second execution of the same mutation fails the test.
                assertFalse(executed.contains(call.id))
                executed.add(call.id)
            }
        }
        executeToolCalls(first)
        val trace = AssistantTraceCodec.encode(StoredAssistantTrace(calls,
            protocolMetadata = first.assistant.protocolMetadata, content = first.assistant.content))
        val stored = listOf(
            entity(1, "user", "write test.md"),
            entity(2, "assistant", "[tool call]", trace),
            entity(3, "tool", "written", result = """{"toolCallId":"a","content":"written","isError":false}""")
        )
        // A fresh provider and context builder model recreation after persistence.
        val history = ContextBuilder().build("s", stored, 1, "", "", "")
        val final = provider(client).chat(history, emptyList())
        executeToolCalls(final)
        assertEquals("Created test.md", final.assistant.content)
        assertTrue(final.assistant.toolCalls.isEmpty())
        assertEquals(listOf("a"), executed)
        assertEquals(2, requests.get())
    }

    @Test
    fun `production SSE path preserves signature arriving after tool arguments`(): Unit = runBlocking {
        val sse = listOf(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"a","function":{"name":"write","arguments":"{}"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"extra_content":$extra}]}}]}""",
            "[DONE]"
        ).joinToString("") { "data: $it\n\n" }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", "text/event-stream")
                .body(sse.toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        try {
            val result = withTimeout(5_000) {
                collectStreamResponse(provider(client).chatStream(listOf(ChatMessage("user", "write")), emptyList()))
            }
            assertEquals(extra, result.assistant.toolCalls.single().protocolMetadata!!.extraContent)
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun `signature rejection does not retry without cache hint`(): Unit = runBlocking {
        val requests = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(400).message("Bad Request")
                .body("""{"error":{"message":"missing thought_signature"}}""".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        try {
            provider(client, ProviderCacheMode.ExplicitHint).chat(listOf(ChatMessage("user", "continue")), emptyList())
            fail("Expected a protocol rejection")
        } catch (error: ProviderHttpException) {
            assertTrue(error.isContinuationFailure)
            assertTrue(error.message!!.contains("Previously completed tool actions remain completed"))
            assertEquals(1, requests.get())
        }
    }

    private fun provider(client: OkHttpClient, cache: ProviderCacheMode = ProviderCacheMode.Auto) =
        OpenAiCompatibleProvider("Google AI", "test-key", "gemini-test", client,
            "https://example.test/chat/completions", cacheMode = cache, log = {})

    private fun entity(id: Long, role: String, content: String, trace: String? = null, result: String? = null) =
        MessageEntity(id = id, sessionId = "s", role = role, content = content, createdAt = id,
            toolCallJson = trace, toolResultJson = result)

    private val extra = Json.parseToJsonElement("""{"google":{"thought_signature":"original-signature"}}""").jsonObject
    private val response = """{"choices":[{"message":{"role":"assistant","tool_calls":[
        {"id":"a","type":"function","function":{"name":"write","arguments":"{\"path\":\"test.md\",\"content\":\"hello\"}"},"extra_content":$extra}
    ]}}]}"""
}
