package com.palmclaw.bus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageAttachmentTest {

    @Test
    fun `codec round trips normalized structured attachments`() {
        val encoded = MessageAttachmentJsonCodec.encode(
            listOf(
                MessageAttachment(
                    kind = MessageAttachmentKind.File,
                    reference = "/workspace/report.pdf",
                    label = "",
                    mimeType = null,
                    source = MessageAttachmentSource.Unknown
                )
            )
        )

        val decoded = MessageAttachmentJsonCodec.decode(encoded)

        assertEquals(1, decoded.size)
        assertEquals(MessageAttachmentKind.File, decoded.first().kind)
        assertEquals("/workspace/report.pdf", decoded.first().reference)
        assertEquals("report.pdf", decoded.first().label)
        assertEquals("application/pdf", decoded.first().mimeType)
        assertEquals(MessageAttachmentSource.Local, decoded.first().source)
    }

    @Test
    fun `normalize attachments falls back to legacy media references`() {
        val normalized = normalizeMessageAttachments(
            legacyMedia = listOf("https://example.com/image.png")
        )

        assertEquals(1, normalized.size)
        assertEquals(MessageAttachmentKind.Image, normalized.first().kind)
        assertEquals("image.png", normalized.first().label)
        assertEquals(MessageAttachmentSource.Remote, normalized.first().source)
    }

    @Test
    fun `normalize attachments prefers structured attachments over legacy media`() {
        val normalized = normalizeMessageAttachments(
            attachments = listOf(
                MessageAttachment(
                    kind = MessageAttachmentKind.Audio,
                    reference = "content://media/audio/1",
                    label = "voice-note.m4a"
                )
            ),
            legacyMedia = listOf("https://example.com/ignored.jpg")
        )

        assertEquals(1, normalized.size)
        assertEquals("content://media/audio/1", normalized.first().reference)
        assertEquals(MessageAttachmentKind.Audio, normalized.first().kind)
    }

    @Test
    fun `mime type falls back to wildcard for unknown files`() {
        val mimeType = inferMessageAttachmentMimeType(
            reference = "/workspace/blob.unknownext",
            kind = MessageAttachmentKind.File
        )

        assertNotNull(mimeType)
        assertTrue(mimeType.isNotBlank())
        assertEquals("*/*", mimeType)
    }

    @Test
    fun `normalize attachment preserves explicit local workspace path and transfer state`() {
        val normalized = normalizeMessageAttachment(
            MessageAttachment(
                kind = MessageAttachmentKind.File,
                reference = "https://example.com/report.pdf",
                label = "report.pdf",
                transferState = MessageAttachmentTransferState.Uploading,
                failureMessage = "pending retry",
                localWorkspacePath = "/workspace/artifacts/outgoing/report.pdf",
                isRemoteBacked = true
            )
        )

        assertNotNull(normalized)
        assertEquals("https://example.com/report.pdf", normalized!!.reference)
        assertEquals("/workspace/artifacts/outgoing/report.pdf", normalized.localWorkspacePath)
        assertEquals(MessageAttachmentTransferState.Uploading, normalized.transferState)
        assertEquals("pending retry", normalized.failureMessage)
        assertTrue(normalized.isRemoteBacked)
    }
}
