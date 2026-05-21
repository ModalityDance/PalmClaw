package com.palmclaw.tools

import com.palmclaw.attachments.AttachmentTooLargeException
import com.palmclaw.attachments.BoundedStreamCopy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedStreamCopyTest {
    @Test
    fun `copy succeeds when stream is within limit`() {
        val output = ByteArrayOutputStream()

        val copied = BoundedStreamCopy.copy(
            input = ByteArrayInputStream("hello".toByteArray(Charsets.UTF_8)),
            output = output,
            maxBytes = 5L
        )

        assertEquals(5L, copied)
        assertEquals("hello", output.toString("UTF-8"))
    }

    @Test
    fun `copy fails before writing beyond configured limit`() {
        val output = ByteArrayOutputStream()

        val failure = runCatching {
            BoundedStreamCopy.copy(
                input = ByteArrayInputStream("toolarge".toByteArray(Charsets.UTF_8)),
                output = output,
                maxBytes = 3L
            )
        }.exceptionOrNull()

        assertTrue(failure is AttachmentTooLargeException)
        assertEquals("too", output.toString("UTF-8"))
    }
}
