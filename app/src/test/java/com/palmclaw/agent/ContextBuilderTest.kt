package com.palmclaw.agent

import com.palmclaw.storage.entities.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBuilderTest {

    private val builder = ContextBuilder()

    @Test
    fun `build keeps valid conversation chain and drops skipped assistant and orphan tool messages`() {
        val result = builder.build(
            sessionId = "session-1",
            messages = listOf(
                message(id = 1L, role = "assistant", content = "[Error] timeout"),
                message(id = 2L, role = "user", content = "hi"),
                message(
                    id = 3L,
                    role = "assistant",
                    content = "[tool call]",
                    toolCallJson = """[{"id":"call-1","name":"weather","argumentsJson":"{\"city\":\"HK\"}"}]"""
                ),
                message(
                    id = 4L,
                    role = "tool",
                    content = "sunny",
                    toolResultJson = """{"toolCallId":"call-1","content":"sunny","isError":false}"""
                ),
                message(
                    id = 5L,
                    role = "tool",
                    content = "orphan",
                    toolResultJson = """{"toolCallId":"call-999","content":"orphan","isError":false}"""
                ),
                message(id = 6L, role = "internal_user", content = "heartbeat task")
            ),
            maxHistoryMessages = 20,
            longTermMemory = "",
            activeSkillsContent = "",
            skillsSummary = ""
        )

        assertEquals(5, result.size)
        assertEquals("system", result[0].role)
        assertEquals("user", result[1].role)
        assertEquals("hi", result[1].content)
        assertEquals("assistant", result[2].role)
        assertNotNull(result[2].toolCalls)
        assertEquals("call-1", result[2].toolCalls!!.single().id)
        assertEquals("tool", result[3].role)
        assertEquals("call-1", result[3].toolCallId)
        assertEquals("user", result[4].role)
        assertEquals("heartbeat task", result[4].content)
    }

    @Test
    fun `build system prompt includes runtime metadata memory and skills sections`() {
        val builderWithWorkspace = ContextBuilder(
            workspaceContextProvider = {
                ContextBuilder.WorkspaceContext(
                    workspaceRoot = "/workspace/session-42",
                    docsDir = "/workspace/session-42/docs",
                    scratchDir = "/workspace/session-42/scratch",
                    artifactsDir = "/workspace/session-42/artifacts",
                    sharedWorkspaceRoot = "/workspace"
                )
            }
        )
        val result = builderWithWorkspace.build(
            sessionId = "session-42",
            messages = emptyList(),
            maxHistoryMessages = 10,
            longTermMemory = "Remember this",
            activeSkillsContent = "skill-body",
            skillsSummary = "- skill-a",
            systemPolicyTemplate = "Custom policy"
        )

        val systemPrompt = result.single().content
        assertTrue(systemPrompt.startsWith("Custom policy"))
        assertTrue(systemPrompt.contains("This is the real current date and time"))
        assertTrue(systemPrompt.contains("current_time_local="))
        assertTrue(systemPrompt.contains("current_time_iso8601="))
        assertTrue(systemPrompt.contains("timezone_id="))
        assertTrue(systemPrompt.contains("session_id=session-42"))
        assertTrue(systemPrompt.contains("current_workspace_root=/workspace/session-42"))
        assertTrue(systemPrompt.contains("current_workspace_docs_dir=/workspace/session-42/docs"))
        assertTrue(systemPrompt.contains("current_workspace_scratch_dir=/workspace/session-42/scratch"))
        assertTrue(systemPrompt.contains("current_workspace_artifacts_dir=/workspace/session-42/artifacts"))
        assertTrue(systemPrompt.contains("shared_workspace_root=/workspace"))
        assertTrue(systemPrompt.contains("Local relative file paths default to current_workspace_root."))
        assertTrue(systemPrompt.contains("Use session:// to explicitly target the current session workspace."))
        assertTrue(systemPrompt.contains("Use shared:// to explicitly target shared app storage."))
        assertTrue(systemPrompt.contains("## Long-term Memory\nRemember this"))
        assertTrue(systemPrompt.contains("## Active Skills\nskill-body"))
        assertTrue(systemPrompt.contains("## Skills"))
        assertTrue(systemPrompt.contains("- skill-a"))
    }

    @Test
    fun `build keeps tool result directly after tool call even if assistant delivery message was inserted before stored tool result`() {
        val result = builder.build(
            sessionId = "session-1",
            messages = listOf(
                message(id = 1L, role = "user", content = "send this file"),
                message(
                    id = 2L,
                    role = "assistant",
                    content = "[tool call]",
                    toolCallJson = """[{"id":"call-2","name":"message","argumentsJson":"{\"content\":\"\",\"attachments\":[]}"}]"""
                ),
                message(id = 3L, role = "assistant", content = "delivered file to user"),
                message(
                    id = 4L,
                    role = "tool",
                    content = "Message sent",
                    toolResultJson = """{"toolCallId":"call-2","content":"Message sent","isError":false}"""
                ),
                message(id = 5L, role = "user", content = "thanks")
            ),
            maxHistoryMessages = 20,
            longTermMemory = "",
            activeSkillsContent = "",
            skillsSummary = ""
        )

        assertEquals("assistant", result[2].role)
        assertEquals("tool", result[3].role)
        assertEquals("call-2", result[3].toolCallId)
        assertEquals("assistant", result[4].role)
        assertEquals("delivered file to user", result[4].content)
        assertEquals("user", result[5].role)
        assertEquals("thanks", result[5].content)
    }

    private fun message(
        role: String,
        content: String,
        toolCallJson: String? = null,
        toolResultJson: String? = null,
        id: Long
    ): MessageEntity {
        return MessageEntity(
            id = id,
            sessionId = "session-1",
            role = role,
            content = content,
            createdAt = id,
            toolCallJson = toolCallJson,
            toolResultJson = toolResultJson
        )
    }
}
