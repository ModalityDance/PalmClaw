package com.palmclaw.agent

import com.palmclaw.providers.ProtocolMetadata
import com.palmclaw.providers.ToolCall
import com.palmclaw.storage.entities.MessageEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class AssistantTraceCodecTest {
    private fun metadata(signature: String) = ProtocolMetadata(
        "scope", Json.parseToJsonElement("""{"google":{"thought_signature":"$signature"}}""").jsonObject
    )

    @Test
    fun `old arrays envelopes and absent metadata remain readable`() {
        val array = """[{"id":"a","name":"read","argumentsJson":"{}"}]"""
        assertEquals("a", AssistantTraceCodec.decode(array).toolCalls.single().id)
        val envelope = """{"toolCalls":$array,"reasoningContent":"reason"}"""
        val trace = AssistantTraceCodec.decode(envelope)
        assertEquals("reason", trace.reasoningContent)
        assertNull(trace.protocolMetadata)
        assertNull(trace.toolCalls.single().protocolMetadata)
        assertNull(AssistantTraceCodec.encode(StoredAssistantTrace()))
        assertEquals(StoredAssistantTrace(), AssistantTraceCodec.decode(null))
    }

    @Test
    fun `message-only protocol state survives persistence`() {
        val trace = StoredAssistantTrace(protocolMetadata = metadata("message-state"))
        assertEquals(trace, AssistantTraceCodec.decode(AssistantTraceCodec.encode(trace)))
        val history = build(listOf(message(1, "assistant", "done", trace)))
        assertEquals(trace.protocolMetadata, history.last().protocolMetadata)
    }

    @Test
    fun `original assistant content is restored instead of the UI placeholder`() {
        val trace = StoredAssistantTrace(listOf(ToolCall("a", "write", "{}", metadata("state"))), content = "")
        val history = build(listOf(message(1, "user", "write"), message(2, "assistant", "[tool call]", trace), result(3, "a")))
        assertEquals("", history[2].content)
        val withText = trace.copy(content = "Checking now")
        val second = build(listOf(message(1, "user", "write"), message(2, "assistant", "UI text", withText), result(3, "a")))
        assertEquals("Checking now", second[2].content)
    }

    @Test
    fun `small history window retains all sequential steps of current turn`() {
        val first = StoredAssistantTrace(listOf(ToolCall("a", "write", "{}", metadata("first"))))
        val second = StoredAssistantTrace(listOf(ToolCall("b", "read", "{}", metadata("second"))))
        val history = build(listOf(
            message(1, "user", "old question"), message(2, "assistant", "old answer"),
            message(3, "user", "write then read"),
            message(4, "assistant", "[tool call]", first), result(5, "a"),
            message(6, "assistant", "[tool call]", second), result(7, "b")
        ), limit = 1)
        assertEquals(listOf("system", "user", "assistant", "tool", "assistant", "tool"), history.map { it.role })
        assertEquals(metadata("first"), history[2].toolCalls!!.single().protocolMetadata)
        assertEquals(metadata("second"), history[4].toolCalls!!.single().protocolMetadata)
    }

    @Test
    fun `parallel results follow original call order with signatures on their own calls`() {
        val trace = StoredAssistantTrace(listOf(
            ToolCall("a", "read", "{}", metadata("first")), ToolCall("b", "read", "{}")
        ))
        val history = build(listOf(message(1, "user", "read"), message(2, "assistant", "[tool call]", trace),
            result(3, "b"), result(4, "a")))
        assertEquals(listOf("a", "b"), history.filter { it.role == "tool" }.map { it.toolCallId })
        assertEquals(metadata("first"), history[2].toolCalls!![0].protocolMetadata)
        assertNull(history[2].toolCalls!![1].protocolMetadata)
    }

    @Test
    fun `incomplete parallel chain is not sent as a partial signed chain`() {
        val trace = StoredAssistantTrace(listOf(
            ToolCall("a", "read", "{}", metadata("first")), ToolCall("b", "read", "{}")
        ))
        val error = assertThrows(IllegalStateException::class.java) {
            build(listOf(message(1, "user", "read"), message(2, "assistant", "[tool call]", trace), result(3, "a")))
        }
        assertTrue(error.message!!.contains("Existing tool results remain saved"))
    }

    @Test
    fun `corrupted trace fails explicitly without exposing stored values`() {
        val bad = message(1, "assistant", "[tool call]").copy(toolCallJson = """{"toolCalls":"private-state"}""")
        val error = assertThrows(IllegalStateException::class.java) { build(listOf(bad)) }
        assertFalse(error.toString().contains("private-state"))
        assertNull(error.cause)
    }

    private fun build(messages: List<MessageEntity>, limit: Int = 20) =
        ContextBuilder().build("s", messages, limit, "", "", "")

    private fun message(id: Long, role: String, content: String, trace: StoredAssistantTrace? = null) =
        MessageEntity(id = id, sessionId = "s", role = role, content = content, createdAt = id,
            toolCallJson = trace?.let(AssistantTraceCodec::encode))

    private fun result(id: Long, call: String) = message(id, "tool", "ok").copy(
        toolResultJson = """{"toolCallId":"$call","content":"ok","isError":false}"""
    )
}
