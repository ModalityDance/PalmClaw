package com.palmclaw.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LocalFileReadSupportAndroidTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `read extracts basic pdf text`() {
        val file = File(context.cacheDir, "local-file-read-test.pdf").apply {
            parentFile?.mkdirs()
            delete()
        }
        PDDocument().use { document ->
            val page = PDPage()
            document.addPage(page)
            PDPageContentStream(document, page).use { stream ->
                stream.beginText()
                stream.setFont(PDType1Font.HELVETICA, 12f)
                stream.newLineAtOffset(72f, 720f)
                stream.showText("Hello PDF")
                stream.endText()
            }
            document.save(file)
        }

        val result = LocalFileReadSupport.read(context, file)

        require(result is LocalFileReadResult.Success)
        assertEquals("pdf", result.sourceType)
        assertTrue(result.text.contains("Hello PDF"))
    }
}
