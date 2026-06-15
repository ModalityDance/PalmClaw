package com.palmclaw.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class RemoteDocumentSupportAndroidTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun readRemoteDocxWithoutExtensionMetadataStillParsesBySignature() {
        val body = minimalDocx("Remote DOCX")

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

    private fun minimalDocx(text: String): ByteArray {
        val escaped = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        return ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("[Content_Types].xml"))
                zip.write(
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                        <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                        <Default Extension="xml" ContentType="application/xml"/>
                        <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                    </Types>
                    """.trimIndent().toByteArray()
                )
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("word/document.xml"))
                zip.write(
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                        <w:body>
                            <w:p><w:r><w:t>$escaped</w:t></w:r></w:p>
                        </w:body>
                    </w:document>
                    """.trimIndent().toByteArray()
                )
                zip.closeEntry()
            }
            output.toByteArray()
        }
    }
}
