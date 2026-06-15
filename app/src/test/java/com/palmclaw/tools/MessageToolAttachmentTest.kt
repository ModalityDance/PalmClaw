package com.palmclaw.tools

import com.palmclaw.bus.MessageAttachmentKind
import com.palmclaw.bus.OutboundMessage
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
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

    @Test
    fun `message tool atomically captures per turn context`() = runBlocking {
        val tool = MessageTool()
        val captured = mutableListOf<OutboundMessage>()
        tool.setSendCallback { outbound -> captured += outbound }

        val first = launch {
            tool.startTurnWithContext(channel = "telegram", chatId = "chat-a", adapterKey = "adapter-a")
            try {
                yield()
                val result = tool.run("""{"content":"first"}""")
                assertFalse(result.isError)
            } finally {
                tool.finishTurn()
            }
        }
        val second = launch {
            tool.startTurnWithContext(channel = "discord", chatId = "chat-b", adapterKey = "adapter-b")
            try {
                val result = tool.run("""{"content":"second"}""")
                assertFalse(result.isError)
            } finally {
                tool.finishTurn()
            }
        }

        first.join()
        second.join()

        assertEquals(
            setOf("telegram:chat-a:adapter-a", "discord:chat-b:adapter-b"),
            captured.map { "${it.channel}:${it.chatId}:${it.metadata["adapter_key"]}" }.toSet()
        )
    }
}
