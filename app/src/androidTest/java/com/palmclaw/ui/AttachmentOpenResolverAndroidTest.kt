package com.palmclaw.ui

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AttachmentOpenResolverAndroidTest {

    @Test
    fun `resolver maps references to uri schemes`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val localFile = java.io.File(context.filesDir, "resolver-test.txt").apply {
            parentFile?.mkdirs()
            writeText("ok")
        }
        assertEquals("content", AttachmentOpenResolver.toUri(context, "content://media/external/file/7")?.scheme)
        assertEquals("https", AttachmentOpenResolver.toUri(context, "https://example.com/report.pdf")?.scheme)
        assertEquals("content", AttachmentOpenResolver.toUri(context, localFile.absolutePath)?.scheme)
    }

    @Test
    fun `resolver prefers explicit mime type and falls back by kind`() {
        val explicit = AttachmentOpenResolver.resolveMimeType(
            UiAttachment(
                reference = "/storage/emulated/0/Download/report.bin",
                kind = UiAttachmentKind.File,
                label = "report.bin",
                mimeType = "application/octet-stream"
            )
        )
        val fallback = AttachmentOpenResolver.resolveMimeType(
            UiAttachment(
                reference = "/storage/emulated/0/Download/report.pdf",
                kind = UiAttachmentKind.File,
                label = "report.pdf"
            )
        )

        assertEquals("application/octet-stream", explicit)
        assertNotNull(fallback)
        assertEquals("application/pdf", fallback)
    }
}
