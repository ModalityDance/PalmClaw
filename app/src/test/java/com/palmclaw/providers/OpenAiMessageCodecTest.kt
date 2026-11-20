package com.palmclaw.providers

import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class OpenAiMessageCodecTest {
    private val codec = OpenAiMessageCodec("scope-a", "gemini-test")
    private val extra = Json.parseToJsonElement("""{"google":{"thought_signature":"opaque+/=="}}""").jsonObject

    @Test
    fun `ordinary response retains message and per-call extensions without changing arguments`() {
        val response = codec.decodeResponse(responseBody())
        assertEquals(extra, response.assistant.protocolMetadata!!.extraContent)
        assertEquals(extra, response.assistant.toolCalls[0].protocolMetadata!!.extraContent)
        assertNull(response.assistant.toolCalls[1].protocolMetadata)
        assertEquals("{\"path\":\"test.md\",\"content\":\"hello\"}", response.assistant.toolCalls[0].argumentsJson)
        val request = request(response.assistant)
        assertEquals(extra, request["extra_content"])
        val calls = request["tool_calls"]!!.jsonArray
        assertEquals(extra, calls[0].jsonObject["extra_content"])
        assertFalse(calls[1].jsonObject.containsKey("extra_content"))
        assertFalse(request.toString().contains("scope-a"))
    }

    @Test
    fun `stream retains late signatures on empty deltas and orders parallel calls by index`() {
        val stream = codec.newStream()
        stream.append(chunk("""{"tool_calls":[{"index":1,"id":"b","function":{"name":"read","arguments":"{}"}}]}"""))
        stream.append(chunk("""{"tool_calls":[{"index":0,"id":"a","function":{"name":"write","arguments":"{"}}]}"""))
        stream.append(chunk("""{"tool_calls":[{"index":0,"function":{"arguments":"}"},"extra_content":$extra}]}"""))
        stream.append(chunk("""{"content":"","extra_content":$extra}"""))
        stream.append(chunk("""{"tool_calls":[{"index":0,"extra_content":$extra}]}"""))
        val result = stream.finish().assistant
        assertEquals(listOf("a", "b"), result.toolCalls.map { it.id })
        assertEquals("{}", result.toolCalls[0].argumentsJson)
        assertEquals(extra, result.toolCalls[0].protocolMetadata!!.extraContent)
        assertNull(result.toolCalls[1].protocolMetadata)
        assertEquals(extra, result.protocolMetadata!!.extraContent)
        assertEquals(extra, request(result)["tool_calls"]!!.jsonArray[0].jsonObject["extra_content"])
    }

    @Test
    fun `complete unindexed calls and signature-only message chunks are supported`() {
        val stream = codec.newStream()
        stream.append(chunk("""{"tool_calls":[{"id":"a","function":{"name":"write","arguments":"{}"},"extra_content":$extra},{"id":"b","function":{"name":"read","arguments":"{}"}}]}"""))
        assertEquals(listOf("a", "b"), stream.finish().assistant.toolCalls.map { it.id })
        val text = codec.newStream()
        text.append(chunk("""{"content":"done"}"""))
        text.append(chunk("""{"extra_content":$extra}"""))
        assertEquals(extra, request(text.finish().assistant)["extra_content"])
    }

    @Test
    fun `conflicting opaque values fail instead of concatenating or exposing them`() {
        val stream = codec.newStream()
        stream.append(chunk("""{"extra_content":$extra}"""))
        val error = assertThrows(IOException::class.java) {
            stream.append(chunk("""{"extra_content":{"google":{"thought_signature":"different-secret"}}}"""))
        }
        assertFalse(error.toString().contains("different-secret"))
        assertFalse(error.toString().contains("opaque"))
    }

    @Test
    fun `ambiguous unindexed deltas and incomplete calls do not silently disappear`() {
        val stream = codec.newStream()
        assertThrows(IOException::class.java) {
            stream.append(chunk("""{"tool_calls":[{"extra_content":$extra}]}"""))
        }
        stream.append(chunk("""{"tool_calls":[{"index":0,"id":"a"}]}"""))
        assertThrows(IOException::class.java) { stream.finish() }
    }

    @Test
    fun `changing scope blocks an active signed chain but strips metadata from earlier turns`() {
        val assistant = codec.decodeResponse(responseBody()).assistant
        val history = listOf(ChatMessage("user", "write"), asChat(assistant))
        val other = OpenAiMessageCodec("scope-b", "other")
        assertThrows(IOException::class.java) { other.encodeRequest(history, emptyList(), false, false) }
        assertThrows(IOException::class.java) { validateContinuationScope(history, null) }
        val afterNewUser = other.encodeRequest(history + ChatMessage("user", "next question"), emptyList(), false, false)
        assertFalse(afterNewUser.contains("extra_content"))
        assertFalse(afterNewUser.contains("opaque"))
        assertTrue(afterNewUser.contains("test.md"))
    }

    @Test
    fun `legacy unsigned calls are not rejected locally or given fabricated signatures`() {
        val request = request(AssistantMessage("", listOf(ToolCall("a", "read", "{}"))))
        assertFalse(request.toString().contains("extra_content"))
        assertFalse(request.toString().contains("skip_thought_signature_validator"))
    }

    @Test
    fun `scope changes with config endpoint and model and contains no routing secrets`() {
        val scope = OpenAiMessageCodec.scope("config-a", "https://example.test/chat?key=secret", "model-a")
        assertEquals(scope, OpenAiMessageCodec.scope("config-a", "https://example.test/chat?key=secret", "model-a"))
        assertNotEquals(scope, OpenAiMessageCodec.scope("config-b", "https://example.test/chat?key=secret", "model-a"))
        assertNotEquals(scope, OpenAiMessageCodec.scope("config-a", "https://other.test/chat", "model-a"))
        assertNotEquals(scope, OpenAiMessageCodec.scope("config-a", "https://example.test/chat?key=secret", "model-b"))
        assertFalse(scope.contains("secret"))
    }

    @Test
    fun `large signatures are preserved rather than limited like tool result text`() {
        val signature = "x".repeat(32_000)
        val large = Json.parseToJsonElement("""{"google":{"thought_signature":"$signature"}}""").jsonObject
        val assistant = AssistantMessage("", listOf(ToolCall("a", "write", "{}", ProtocolMetadata("scope-a", large))))
        assertEquals(large, request(assistant)["tool_calls"]!!.jsonArray[0].jsonObject["extra_content"])
    }

    @Test
    fun `malformed response errors do not expose raw signatures`() {
        val error = assertThrows(IOException::class.java) {
            codec.decodeResponse("""{"choices":"private-signature-value"}""")
        }
        assertFalse(error.toString().contains("private-signature-value"))
        assertNull(error.cause)
    }

    private fun request(assistant: AssistantMessage): JsonObject =
        Json.parseToJsonElement(codec.encodeRequest(listOf(asChat(assistant)), emptyList(), false, false))
            .jsonObject["messages"]!!.jsonArray.single().jsonObject

    private fun asChat(assistant: AssistantMessage) = ChatMessage(
        "assistant", assistant.content, toolCalls = assistant.toolCalls,
        reasoningContent = assistant.reasoningContent, protocolMetadata = assistant.protocolMetadata
    )

    private fun chunk(delta: String) = """{"choices":[{"delta":$delta}]}"""

    private fun responseBody() = """{
      "choices":[{"message":{"role":"assistant","extra_content":$extra,"tool_calls":[
        {"id":"a","type":"function","function":{"name":"write","arguments":"{\"path\":\"test.md\",\"content\":\"hello\"}"},"extra_content":$extra},
        {"id":"b","type":"function","function":{"name":"read","arguments":"{}"}}
      ]}}]
    }"""
}
