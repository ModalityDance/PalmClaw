package com.palmclaw.tools

import com.palmclaw.bus.MessageAttachmentKind
import com.palmclaw.bus.OutboundMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class MessageToolAttachmentTest {

    @Test
    fun `message tool prefers structured attachments over legacy media`() = runBlocking {
        val tool = MessageTool()
        var captured: OutboundMessage? = null
        tool.setContext(channel = "telegram", chatId = "123")
        tool.setSendCallback { outbound -> captured = outbound }

        tool.startTurn()
        try {
            val result = tool.run(
                """
                {
                  "content": "Please send this",
                  "attachments": [
                    {
                      "kind": "file",
                      "reference": "/workspace/report.pdf",
                      "label": "report.pdf",
                      "mimeType": "application/pdf"
                    }
                  ],
                  "media": ["https://example.com/ignored.jpg"]
                }
                """.trimIndent()
            )

            assertFalse(result.isError)
            assertNotNull(captured)
            assertEquals(1, captured!!.normalizedAttachments.size)
            assertEquals("/workspace/report.pdf", captured!!.normalizedAttachments.first().reference)
            assertEquals(MessageAttachmentKind.File, captured!!.normalizedAttachments.first().kind)
        } finally {
            tool.finishTurn()
        }
    }

    @Test
    fun `message tool falls back to legacy media when structured attachments are missing`() = runBlocking {
        val tool = MessageTool()
        var captured: OutboundMessage? = null
        tool.setContext(channel = "discord", chatId = "456")
        tool.setSendCallback { outbound -> captured = outbound }

        tool.startTurn()
        try {
            val result = tool.run(
                """
                {
                  "content": "Legacy attachment send",
                  "media": ["https://example.com/photo.jpg"]
                }
                """.trimIndent()
            )

            assertFalse(result.isError)
            assertNotNull(captured)
            assertEquals(1, captured!!.normalizedAttachments.size)
            assertEquals(MessageAttachmentKind.Image, captured!!.normalizedAttachments.first().kind)
            assertEquals("https://example.com/photo.jpg", captured!!.normalizedAttachments.first().reference)
        } finally {
            tool.finishTurn()
        }
    }

    @Test
    fun `message tool allows attachment only sends`() = runBlocking {
        val tool = MessageTool()
        var captured: OutboundMessage? = null
        tool.setContext(channel = "local", chatId = "session-1")
        tool.setSendCallback { outbound -> captured = outbound }

        tool.startTurn()
        try {
            val result = tool.run(
                """
                {
                  "attachments": [
                    {
                      "kind": "file",
                      "reference": "/workspace/archive.zip",
                      "label": "archive.zip"
                    }
                  ]
                }
                """.trimIndent()
            )

            assertFalse(result.isError)
            assertNotNull(captured)
            assertEquals("", captured!!.content)
            assertEquals(1, captured!!.normalizedAttachments.size)
            assertEquals("/workspace/archive.zip", captured!!.normalizedAttachments.first().reference)
        } finally {
            tool.finishTurn()
        }
    }
}
