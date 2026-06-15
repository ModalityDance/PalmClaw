package com.palmclaw.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class RemoteDocumentSupportTest {

    @Test
    fun `infer document extension from content type`() {
        assertEquals(
            "pdf",
            RemoteDocumentSupport.inferDocumentExtension(
                url = "https://example.com/download?id=1",
                contentType = "application/pdf"
            )
        )
        assertEquals(
            "docx",
            RemoteDocumentSupport.inferDocumentExtension(
                url = "https://example.com/file",
                contentType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            )
        )
    }

    @Test
    fun `infer document extension from url suffix`() {
        assertEquals(
            "xlsx",
            RemoteDocumentSupport.inferDocumentExtension(
                url = "https://example.com/report.xlsx?download=1",
                contentType = ""
            )
        )
    }

    @Test
    fun `binary image download is rejected`() {
        assertTrue(
            RemoteDocumentSupport.looksLikeUnsupportedBinary(
                url = "https://example.com/image.jpg",
                contentType = "image/jpeg"
            )
        )
    }

    @Test
    fun `json response is not treated as unsupported binary`() {
        assertFalse(
            RemoteDocumentSupport.looksLikeUnsupportedBinary(
                url = "https://example.com/data",
                contentType = "application/json"
            )
        )
    }

    @Test
    fun `decode remote text respects utf16 bom`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Hello".toByteArray(StandardCharsets.UTF_16LE)

        val decoded = RemoteDocumentSupport.decodeRemoteText(
            bodyBytes = bytes,
            contentType = "text/plain"
        )

        assertEquals("Hello", decoded.text)
    }
}
