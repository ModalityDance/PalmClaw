package com.palmclaw.ui

import com.palmclaw.bus.MessageAttachment
import com.palmclaw.bus.MessageAttachmentJsonCodec
import com.palmclaw.bus.MessageAttachmentKind
import com.palmclaw.bus.MessageAttachmentTransferState
import com.palmclaw.providers.ToolCall
import com.palmclaw.storage.entities.MessageEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageUiProjectorTest {

    private val uiJson = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    private val projector = MessageUiProjector(
        uiJson = uiJson,
        toolArgsPreviewMaxCharsProvider = { 120 }
    )

    @Test
    fun `shouldDisplayInChat filters debug and empty tool placeholders`() {
        val debugAssistant = MessageEntity(
            id = 1L,
            sessionId = "session-1",
            role = "assistant",
            content = "[debug tool call] trace",
            createdAt = 1L
        )
        val emptyToolPlaceholder = MessageEntity(
            id = 2L,
            sessionId = "session-1",
            role = "assistant",
            content = "[tool call]",
            createdAt = 2L
        )
        val blankTool = MessageEntity(
            id = 3L,
            sessionId = "session-1",
            role = "tool",
            content = "",
            createdAt = 3L
        )

        assertFalse(projector.shouldDisplayInChat(debugAssistant))
        assertFalse(projector.shouldDisplayInChat(emptyToolPlaceholder))
        assertFalse(projector.shouldDisplayInChat(blankTool))
    }

    @Test
    fun `project combines assistant tool call with matching tool result`() {
        val toolCallJson = uiJson.encodeToString(
            listOf(
                ToolCall(
                    id = "call-1",
                    name = "weather_get",
                    argumentsJson = """{"city":"Hong Kong"}"""
                )
            )
        )
        val toolResultJson =
            """{"toolCallId":"call-1","content":"Sunny","isError":false,"metadata":{"mcp_tool":"weather_get"}}"""
        val messages = listOf(
            MessageEntity(
                id = 10L,
                sessionId = "session-1",
                role = "assistant",
                content = "Checking weather",
                createdAt = 100L,
                toolCallJson = toolCallJson
            ),
            MessageEntity(
                id = 11L,
                sessionId = "session-1",
                role = "tool",
                content = "Sunny",
                createdAt = 101L,
                toolResultJson = toolResultJson
            )
        )

        val projected = projector.project(messages)

        assertEquals(1, projected.size)
        assertEquals("tool", projected.first().role)
        assertEquals("weather_get [ok]", projected.first().content)
        assertTrue(projected.first().expandedContent.orEmpty().contains("Tool Result"))
        assertTrue(projected.first().expandedContent.orEmpty().contains("Sunny"))
    }

    @Test
    fun `project extracts media attachment from tool result metadata`() {
        val projected = projector.project(
            listOf(
                MessageEntity(
                    id = 20L,
                    sessionId = "session-1",
                    role = "tool",
                    content = "",
                    createdAt = 200L,
                    toolResultJson =
                        """{"toolCallId":"call-2","content":"saved","isError":false,"metadata":{"action":"capture_photo","output_uri":"content://media/external/images/media/42"}}"""
                )
            )
        )

        assertEquals(1, projected.size)
        assertEquals(1, projected.first().attachments.size)
        assertEquals(UiAttachmentKind.Image, projected.first().attachments.first().kind)
        assertEquals(
            "content://media/external/images/media/42",
            projected.first().attachments.first().reference
        )
    }

    @Test
    fun `project prefers persisted structured attachments for plain messages`() {
        val attachmentJson = MessageAttachmentJsonCodec.encode(
            listOf(
                MessageAttachment(
                    kind = MessageAttachmentKind.File,
                    reference = "/workspace/report.pdf",
                    label = "report.pdf",
                    mimeType = "application/pdf"
                )
            )
        )
        val message = MessageEntity(
            id = 21L,
            sessionId = "session-1",
            role = "user",
            content = "",
            createdAt = 210L,
            attachmentsJson = attachmentJson
        )

        assertTrue(projector.shouldDisplayInChat(message))

        val projected = projector.project(listOf(message))

        assertEquals(1, projected.size)
        assertEquals("", projected.first().content)
        assertEquals(1, projected.first().attachments.size)
        assertEquals(UiAttachmentKind.File, projected.first().attachments.first().kind)
        assertEquals("report.pdf", projected.first().attachments.first().label)
        assertEquals("application/pdf", projected.first().attachments.first().mimeType)
    }

    @Test
    fun `project preserves attachment transfer state and workspace path`() {
        val attachmentJson = MessageAttachmentJsonCodec.encode(
            listOf(
                MessageAttachment(
                    kind = MessageAttachmentKind.File,
                    reference = "https://example.com/report.pdf",
                    label = "report.pdf",
                    mimeType = "application/pdf",
                    transferState = MessageAttachmentTransferState.Failed,
                    failureMessage = "upload failed",
                    localWorkspacePath = "/workspace/artifacts/outgoing/assistant/42/report.pdf",
                    isRemoteBacked = true
                )
            )
        )
        val message = MessageEntity(
            id = 22L,
            sessionId = "session-1",
            role = "assistant",
            content = "",
            createdAt = 220L,
            attachmentsJson = attachmentJson
        )

        val projected = projector.project(listOf(message))

        assertEquals(1, projected.size)
        assertEquals(1, projected.first().attachments.size)
        assertEquals(MessageAttachmentTransferState.Failed, projected.first().attachments.first().transferState)
        assertEquals("upload failed", projected.first().attachments.first().failureMessage)
        assertEquals(
            "/workspace/artifacts/outgoing/assistant/42/report.pdf",
            projected.first().attachments.first().localWorkspacePath
        )
        assertTrue(projected.first().attachments.first().isRemoteBacked)
    }

    @Test
    fun `project leaves pending tool call marked as pending`() {
        val toolCallJson = uiJson.encodeToString(
            listOf(
                ToolCall(
                    id = "call-pending",
                    name = "search_docs",
                    argumentsJson = """{"query":"workspace"}"""
                )
            )
        )

        val projected = projector.project(
            listOf(
                MessageEntity(
                    id = 30L,
                    sessionId = "session-1",
                    role = "assistant",
                    content = "",
                    createdAt = 300L,
                    toolCallJson = toolCallJson
                )
            )
        )

        assertEquals(1, projected.size)
        assertEquals("search_docs [pending]", projected.first().content)
        assertTrue(
            projected.first().expandedContent.orEmpty().contains("(waiting for tool result)")
        )
    }
}
