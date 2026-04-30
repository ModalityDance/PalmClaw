package com.palmclaw.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class RemoteDocumentSupportAndroidTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `read remote docx without extension metadata still parses by signature`() {
        val body = ByteArrayOutputStream().use { output ->
            XWPFDocument().use { doc ->
                doc.createParagraph().createRun().setText("Remote DOCX")
                doc.write(output)
            }
            output.toByteArray()
        }

        val result = RemoteDocumentSupport.readFromResponse(
            context = context,
            url = "https://example.com/download?id=123",
            contentType = "application/octet-stream",
            bodyBytes = body
        )

        require(result is LocalFileReadResult.Success)
        assertEquals("docx", result.sourceType)
        assertTrue(result.text.contains("Remote DOCX"))
    }
}
