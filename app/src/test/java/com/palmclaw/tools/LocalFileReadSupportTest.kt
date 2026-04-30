package com.palmclaw.tools

import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.odftoolkit.odfdom.doc.OdfTextDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LocalFileReadSupportTest {

    @Test
    fun `read decodes utf16 text files`() {
        val file = createTempFile(suffix = ".txt")
        file.writeBytes(
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Hello\n世界".toByteArray(StandardCharsets.UTF_16LE)
        )

        val result = LocalFileReadSupport.read(file)

        require(result is LocalFileReadResult.Success)
        assertEquals("text", result.sourceType)
        assertEquals("UTF-16LE", result.charset)
        assertTrue(result.text.contains("Hello"))
        assertTrue(result.text.contains("世界"))
    }

    @Test
    fun `read decodes big5 text files without mojibake`() {
        val file = createTempFile(suffix = ".txt")
        file.writeBytes("繁體中文內容".toByteArray(Charset.forName("Big5")))

        val result = LocalFileReadSupport.read(file)

        require(result is LocalFileReadResult.Success)
        assertEquals("text", result.sourceType)
        assertEquals("Big5", result.charset)
        assertTrue(result.text.contains("繁體中文內容"))
        assertFalse(result.text.contains("锟"))
    }

    @Test
    fun `read extracts basic docx text`() {
        val file = createTempFile(suffix = ".docx")
        XWPFDocument().use { document ->
            document.createParagraph().createRun().setText("Hello")
            document.createParagraph().createRun().setText("World")
            file.outputStream().use { document.write(it) }
        }

        val result = LocalFileReadSupport.read(file)

        require(result is LocalFileReadResult.Success)
        assertEquals("docx", result.sourceType)
        assertTrue(result.text.contains("Hello"))
        assertTrue(result.text.contains("World"))
    }

    @Test
    fun `read detects docx by zip signature even without docx extension`() {
        val file = createTempFile(suffix = ".tmp")
        XWPFDocument().use { document ->
            document.createParagraph().createRun().setText("Signature DOCX")
            file.outputStream().use { document.write(it) }
        }

        val result = LocalFileReadSupport.read(file)

        require(result is LocalFileReadResult.Success)
        assertEquals("docx", result.sourceType)
        assertTrue(result.text.contains("Signature DOCX"))
    }

    @Test
    fun `read extracts unicode docx text without mojibake`() {
        val file = createTempFile(suffix = ".docx")
        XWPFDocument().use { document ->
            document.createParagraph().createRun().setText("中文测试")
            document.createParagraph().createRun().setText("第二行")
            file.outputStream().use { document.write(it) }
        }

        val result = LocalFileReadSupport.read(file)

        require(result is LocalFileReadResult.Success)
        assertEquals("docx", result.sourceType)
        assertTrue(result.text.contains("中文测试"))
        assertTrue(result.text.contains("第二行"))
        assertFalse(result.text.contains("锟"))
    }

    @Test
    fun `read extracts docx text from xml fallback when poi cannot parse`() {
        val file = createTempFile(suffix = ".docx")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            val xml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    <w:p><w:r><w:t>你好，世界</w:t></w:r></w:p>
                    <w:p><w:r><w:t xml:space="preserve">A &amp; B</w:t></w:r></w:p>
                  </w:body>
                </w:document>
            """.trimIndent()
            zip.write(xml.toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
        }

        val result = LocalFileReadSupport.read(file)

        require(result is LocalFileReadResult.Success)
        assertEquals("docx", result.sourceType)
        assertTrue(result.text.contains("你好，世界"))
        assertTrue(result.text.contains("A & B"))
    }

    @Test
    fun `read returns clear unsupported error for pdf`() {
        val file = createTempFile(suffix = ".pdf")
        file.writeBytes("%PDF-1.7".toByteArray(StandardCharsets.US_ASCII))

        val result = LocalFileReadSupport.read(file)

        require(result is LocalFileReadResult.Unsupported)
        assertEquals("pdf_context_required", result.code)
        assertTrue(result.message.contains("PDF"))
    }

    @Test
    fun `read returns clear unsupported error for binary image`() {
        val file = createTempFile(suffix = ".jpg")
        file.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10))

        val result = LocalFileReadSupport.read(file)

        require(result is LocalFileReadResult.Unsupported)
        assertEquals("unsupported_binary_format", result.code)
        assertTrue(result.message.contains("jpg"))
    }

    @Test
    fun `read detects pdf by signature even without pdf extension`() {
        val file = createTempFile(suffix = ".tmp")
        file.writeBytes("%PDF-1.7\n".toByteArray(StandardCharsets.US_ASCII))

        val result = LocalFileReadSupport.read(file)

        require(result is LocalFileReadResult.Unsupported)
        assertEquals("pdf_context_required", result.code)
    }

    @Test
    fun `read docx uses broad word xml fallback when focused parts are empty`() {
        val file = createTempFile(suffix = ".docx")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body></w:body>
                </w:document>
                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("word/glossary/document.xml"))
            zip.write(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <w:glossaryDocument xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:docPart><w:docPartBody><w:p><w:r><w:t>Fallback text</w:t></w:r></w:p></w:docPartBody></w:docPart>
                </w:glossaryDocument>
                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            )
            zip.closeEntry()
        }

        val result = LocalFileReadSupport.read(file)

        require(result is LocalFileReadResult.Success)
        assertEquals("docx", result.sourceType)
        assertTrue(result.text.contains("Fallback text"))
    }

    @Test
    fun `read extracts basic xls text`() {
        val file = createTempFile(suffix = ".xls")
        HSSFWorkbook().use { workbook ->
            val sheet = workbook.createSheet("Sheet1")
            val row = sheet.createRow(0)
            row.createCell(0).setCellValue("Hello")
            row.createCell(1).setCellValue("123")
            file.outputStream().use { workbook.write(it) }
        }

        val result = LocalFileReadSupport.read(file)

        require(result is LocalFileReadResult.Success)
        assertEquals("xls", result.sourceType)
        assertTrue(result.text.contains("Sheet1"))
        assertTrue(result.text.contains("Hello"))
        assertTrue(result.text.contains("123"))
    }

    @Test
    fun `read extracts basic xlsx text`() {
        val file = createTempFile(suffix = ".xlsx")
        XSSFWorkbook().use { workbook ->
            val sheet = workbook.createSheet("SheetA")
            val row = sheet.createRow(0)
            row.createCell(0).setCellValue("Alpha")
            row.createCell(1).setCellValue("42")
            file.outputStream().use { workbook.write(it) }
        }

        val result = LocalFileReadSupport.read(file)

        require(result is LocalFileReadResult.Success)
        assertEquals("xlsx", result.sourceType)
        assertTrue(result.text.contains("SheetA"))
        assertTrue(result.text.contains("Alpha"))
        assertTrue(result.text.contains("42"))
    }

    @Test
    fun `read extracts basic odt text`() {
        val file = createTempFile(suffix = ".odt")
        OdfTextDocument.newTextDocument().use { document ->
            document.addText("Hello ODT")
            document.save(file)
        }

        val result = LocalFileReadSupport.read(file)

        require(result is LocalFileReadResult.Success)
        assertEquals("odt", result.sourceType)
        assertTrue(result.text.contains("Hello ODT"))
    }
}
