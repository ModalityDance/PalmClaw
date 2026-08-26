package com.palmclaw.tools

import org.apache.poi.hssf.usermodel.HSSFWorkbook
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
    fun `read prefers valid utf8 multilingual text over legacy candidates`() {
        val file = createTempFile(suffix = ".txt")
        val expected = "PalmClaw 中文测试 繁體中文 日本語 emoji 🐾"
        file.writeBytes(expected.toByteArray(StandardCharsets.UTF_8))

        val result = LocalFileReadSupport.read(file)

        require(result is LocalFileReadResult.Success)
        assertEquals("text", result.sourceType)
        assertEquals("UTF-8", result.charset)
        assertEquals(expected, result.text)
    }

    @Test
    fun `read accepts symbol only utf8 text`() {
        val file = createTempFile(suffix = ".txt")
        val expected = "🐾✨"
        file.writeBytes(expected.toByteArray(StandardCharsets.UTF_8))

        val result = LocalFileReadSupport.read(file)

        require(result is LocalFileReadResult.Success)
        assertEquals("UTF-8", result.charset)
        assertEquals(expected, result.text)
    }

    @Test
    fun `read applies an explicit encoding before strict utf8`() {
        val file = createTempFile(suffix = ".txt")
        val expected = "PalmClaw ASCII"
        file.writeBytes(expected.toByteArray(StandardCharsets.UTF_8))

        val result = LocalFileReadSupport.read(file, "windows-1252")

        require(result is LocalFileReadResult.Success)
        assertEquals("windows-1252", result.charset)
        assertEquals(WorkspaceTextEncodingSource.EXPLICIT, result.encodingSource)
        assertEquals(expected, result.text)
    }

    @Test
    fun `read preserves logical lines across crlf cr and lf endings`() {
        val file = createTempFile(suffix = ".txt")
        file.writeBytes("第一行\r\n第二行\r第三行\n第四行".toByteArray(StandardCharsets.UTF_8))

        val result = LocalFileReadSupport.read(file)

        require(result is LocalFileReadResult.Success)
        assertEquals("第一行\n第二行\n第三行\n第四行", result.text)
        assertEquals(4, result.text.lines().size)
    }

    @Test
    fun `read decodes utf32 text files when bom is present`() {
        val expected = "Hello 世界 🐾"
        val fixtures = listOf(
            byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x00) to Charset.forName("UTF-32LE"),
            byteArrayOf(0x00, 0x00, 0xFE.toByte(), 0xFF.toByte()) to Charset.forName("UTF-32BE")
        )

        fixtures.forEach { (bom, charset) ->
            val file = createTempFile(suffix = ".txt")
            file.writeBytes(bom + expected.toByteArray(charset))

            val result = LocalFileReadSupport.read(file)

            require(result is LocalFileReadResult.Success)
            assertEquals(charset.name(), result.charset)
            assertEquals(expected, result.text)
        }
    }

    @Test
    fun `read decodes utf16 text files`() {
        val expected = "Hello\n世界"
        val fixtures = listOf(
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()) to StandardCharsets.UTF_16LE,
            byteArrayOf(0xFE.toByte(), 0xFF.toByte()) to StandardCharsets.UTF_16BE
        )

        fixtures.forEach { (bom, charset) ->
            val file = createTempFile(suffix = ".txt")
            file.writeBytes(bom + expected.toByteArray(charset))

            val result = LocalFileReadSupport.read(file)

            require(result is LocalFileReadResult.Success)
            assertEquals("text", result.sourceType)
            assertEquals(charset.name(), result.charset)
            assertEquals(expected, result.text)
        }
    }

    @Test
    fun `read decodes utf8 bom without exposing it as text`() {
        val file = createTempFile(suffix = ".txt")
        val expected = "Hello 中文"
        file.writeBytes(
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
                expected.toByteArray(StandardCharsets.UTF_8)
        )

        val result = LocalFileReadSupport.read(file)

        require(result is LocalFileReadResult.Success)
        assertEquals("UTF-8", result.charset)
        assertEquals(expected, result.text)
    }

    @Test
    fun `read decodes big5 text files with explicit encoding`() {
        val file = createTempFile(suffix = ".txt")
        file.writeBytes("繁體中文內容".toByteArray(Charset.forName("Big5")))

        val result = LocalFileReadSupport.read(file, "Big5")

        require(result is LocalFileReadResult.Success)
        assertEquals("text", result.sourceType)
        assertEquals("Big5", result.charset)
        assertTrue(result.text.contains("繁體中文內容"))
        assertFalse(result.text.contains("锟"))
    }

    @Test
    fun `read uses high confidence statistical detection for legacy text`() {
        val file = createTempFile(suffix = ".txt")
        val expected = "繁體中文內容與檔案編碼測試，這是一段足夠長的繁體中文。".repeat(20)
        file.writeBytes(expected.toByteArray(Charset.forName("Big5")))

        val result = LocalFileReadSupport.read(file)

        require(result is LocalFileReadResult.Success)
        assertEquals("Big5", result.charset)
        assertEquals(WorkspaceTextEncodingSource.DETECTED, result.encodingSource)
        assertTrue((result.encodingConfidence ?: 0) >= 50)
        assertTrue(result.encodingCandidates.any { it.charset == "Big5" })
        assertEquals(expected, result.text)
    }

    @Test
    fun `read supports explicit legacy encoding fixtures`() {
        val fixtures = listOf(
            "GBK" to "简体中文编码",
            "Shift_JIS" to "日本語テスト",
            "windows-1252" to "café – résumé"
        )

        fixtures.forEach { (encoding, expected) ->
            val charset = Charset.forName(encoding)
            val file = createTempFile(suffix = ".txt")
            file.writeBytes(expected.toByteArray(charset))

            val result = LocalFileReadSupport.read(file, encoding)

            require(result is LocalFileReadResult.Success)
            assertEquals(charset.name(), result.charset)
            assertEquals(expected, result.text)
        }
    }

    @Test
    fun `read decodes gb18030 text files without mojibake`() {
        val file = createTempFile(suffix = ".txt")
        val expected = "简体中文𠀀编码测试与文件内容。".repeat(20)
        file.writeBytes(expected.toByteArray(Charset.forName("GB18030")))

        val result = LocalFileReadSupport.read(file)

        require(result is LocalFileReadResult.Success)
        assertEquals("text", result.sourceType)
        assertEquals("GB18030", result.charset)
        assertEquals(expected, result.text)
    }

    @Test
    fun `read extracts basic docx text`() {
        val file = createTempFile(suffix = ".docx")
        writeMinimalDocx(file, "Hello", "World")

        val result = LocalFileReadSupport.read(file)

        require(result is LocalFileReadResult.Success)
        assertEquals("docx", result.sourceType)
        assertTrue(result.text.contains("Hello"))
        assertTrue(result.text.contains("World"))
    }

    @Test
    fun `read detects docx by zip signature even without docx extension`() {
        val file = createTempFile(suffix = ".tmp")
        writeMinimalDocx(file, "Signature DOCX")

        val result = LocalFileReadSupport.read(file)

        require(result is LocalFileReadResult.Success)
        assertEquals("docx", result.sourceType)
        assertTrue(result.text.contains("Signature DOCX"))
    }

    @Test
    fun `read extracts unicode docx text without mojibake`() {
        val file = createTempFile(suffix = ".docx")
        writeMinimalDocx(file, "中文测试", "第二行")

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
        writeMinimalXlsx(file, sheetName = "SheetA", values = listOf("Alpha", "42"))

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

    private fun writeMinimalDocx(file: File, vararg paragraphs: String) {
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            val body = paragraphs.joinToString("\n") { paragraph ->
                "<w:p><w:r><w:t>${escapeXml(paragraph)}</w:t></w:r></w:p>"
            }
            zip.write(
                """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body>
                    $body
                  </w:body>
                </w:document>
                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            )
            zip.closeEntry()
        }
    }

    private fun writeMinimalXlsx(file: File, sheetName: String, values: List<String>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("xl/workbook.xml"))
            zip.write(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheets>
                    <sheet name="${escapeXml(sheetName)}" sheetId="1"/>
                  </sheets>
                </workbook>
                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
            val sharedStrings = values.joinToString("\n") { value ->
                "<si><t>${escapeXml(value)}</t></si>"
            }
            zip.write(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  $sharedStrings
                </sst>
                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            )
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            val cells = values.mapIndexed { index, _ ->
                val column = ('A'.code + index).toChar()
                """<c r="${column}1" t="s"><v>$index</v></c>"""
            }.joinToString("")
            zip.write(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetData>
                    <row r="1">$cells</row>
                  </sheetData>
                </worksheet>
                """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            )
            zip.closeEntry()
        }
    }

    private fun escapeXml(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }
}
