package com.palmclaw.tools

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.odftoolkit.odfdom.doc.OdfTextDocument
import org.apache.poi.hslf.extractor.PowerPointExtractor
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.hwpf.extractor.WordExtractor
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xslf.usermodel.XSLFTextShape
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.extractor.XWPFWordExtractor
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.io.File
import java.net.URLConnection
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipFile

internal sealed interface LocalFileReadResult {
    data class Success(
        val text: String,
        val sourceType: String,
        val charset: String? = null,
        val note: String? = null
    ) : LocalFileReadResult

    data class Unsupported(
        val code: String,
        val message: String,
        val nextStep: String
    ) : LocalFileReadResult

    data class Failure(
        val code: String,
        val message: String,
        val nextStep: String
    ) : LocalFileReadResult
}

internal object LocalFileReadSupport {
    fun read(file: File): LocalFileReadResult = readInternal(context = null, file = file)

    fun read(context: Context, file: File): LocalFileReadResult {
        return readInternal(context = context.applicationContext, file = file)
    }

    private fun readInternal(context: Context?, file: File): LocalFileReadResult {
        val extension = file.extension.lowercase(Locale.US)
        if (file.length() > MAX_SUPPORTED_FILE_BYTES) {
            return LocalFileReadResult.Unsupported(
                code = "file_too_large",
                message = "File is too large to extract safely (${file.length()} bytes).",
                nextStep = "Use a smaller file or export only the needed part, then retry."
            )
        }
        val detectedFormat = detectReadableFormat(file, extension)
        return when {
            detectedFormat in WORDPROCESSOR_XML_EXTENSIONS -> readDocx(file)
            detectedFormat in PRESENTATION_XML_EXTENSIONS -> readPptx(file)
            detectedFormat in SPREADSHEET_XML_EXTENSIONS -> readXlsx(file)
            detectedFormat in OPEN_DOCUMENT_TEXT_EXTENSIONS -> readOpenDocumentText(file)
            detectedFormat == "pdf" -> context?.let { readPdf(it, file) } ?: LocalFileReadResult.Unsupported(
                code = "pdf_context_required",
                message = "PDF extraction requires an Android app context.",
                nextStep = "Run the read tool inside the app, or provide a non-PDF text/document file."
            )
            detectedFormat == "doc" -> readDoc(file)
            detectedFormat == "xls" -> readXls(file)
            detectedFormat == "ppt" -> readPpt(file)
            detectedFormat in KNOWN_BINARY_EXTENSIONS -> LocalFileReadResult.Unsupported(
                code = "unsupported_binary_format",
                message = "Binary file format '${detectedFormat.ifBlank { "unknown" }}' is not supported by read.",
                nextStep = "Use a text/document file, or add a parser for this format."
            )
            else -> readTextLike(file)
        }
    }

    private fun detectReadableFormat(file: File, extension: String): String {
        if (extension in WORDPROCESSOR_XML_EXTENSIONS ||
            extension in PRESENTATION_XML_EXTENSIONS ||
            extension in SPREADSHEET_XML_EXTENSIONS ||
            extension in OPEN_DOCUMENT_TEXT_EXTENSIONS ||
            extension == "pdf" ||
            extension == "doc" ||
            extension == "xls" ||
            extension == "ppt" ||
            extension in KNOWN_BINARY_EXTENSIONS
        ) {
            return extension
        }

        val header = readHeaderBytes(file, HEADER_PROBE_BYTES)
        if (looksLikePdf(header)) return "pdf"
        if (looksLikeZip(header)) {
            detectZipDocumentFormat(file)?.let { return it }
        }
        return extension
    }

    private fun readHeaderBytes(file: File, maxBytes: Int): ByteArray {
        return runCatching {
            file.inputStream().use { input -> input.readNBytes(maxBytes) }
        }.getOrDefault(ByteArray(0))
    }

    private fun looksLikePdf(header: ByteArray): Boolean {
        return header.size >= 5 &&
            header[0] == '%'.code.toByte() &&
            header[1] == 'P'.code.toByte() &&
            header[2] == 'D'.code.toByte() &&
            header[3] == 'F'.code.toByte() &&
            header[4] == '-'.code.toByte()
    }

    private fun looksLikeZip(header: ByteArray): Boolean {
        if (header.size < 4) return false
        if (header[0] != 'P'.code.toByte() || header[1] != 'K'.code.toByte()) return false
        return (header[2] == 0x03.toByte() && header[3] == 0x04.toByte()) ||
            (header[2] == 0x05.toByte() && header[3] == 0x06.toByte()) ||
            (header[2] == 0x07.toByte() && header[3] == 0x08.toByte())
    }

    private fun detectZipDocumentFormat(file: File): String? {
        return runCatching {
            ZipFile(file).use { zip ->
                when {
                    zip.getEntry("word/document.xml") != null -> "docx"
                    zip.getEntry("xl/workbook.xml") != null -> "xlsx"
                    zip.getEntry("ppt/presentation.xml") != null -> "pptx"
                    isOdtZip(zip) -> "odt"
                    else -> null
                }
            }
        }.getOrNull()
    }

    private fun isOdtZip(zip: ZipFile): Boolean {
        val mimetypeEntry = zip.getEntry("mimetype")
        if (mimetypeEntry != null) {
            val mimetype = zip.getInputStream(mimetypeEntry)
                .use { input ->
                    input.readBytes().toString(StandardCharsets.US_ASCII).trim()
                }
            if (mimetype.equals("application/vnd.oasis.opendocument.text", ignoreCase = true)) {
                return true
            }
        }
        return zip.getEntry("content.xml") != null &&
            zip.getEntry("META-INF/manifest.xml") != null
    }

    private fun readTextLike(file: File): LocalFileReadResult {
        val bytes = runCatching { file.readBytes() }.getOrElse { error ->
            return LocalFileReadResult.Failure(
                code = "read_failed",
                message = error.message ?: error.javaClass.simpleName,
                nextStep = "Check file permissions and retry."
            )
        }
        val decoded = decodeBestEffort(bytes)
            ?: return LocalFileReadResult.Unsupported(
                code = "unsupported_binary_or_encoding",
                message = buildUnsupportedMessage(file),
                nextStep = "Convert it to UTF-8 text, docx/xlsx/pptx/odt, then retry."
            )
        val normalized = normalizeExtractedText(decoded.text)
        return LocalFileReadResult.Success(
            text = normalized.ifBlank { "(empty file)" },
            sourceType = "text",
            charset = decoded.charset.name()
        )
    }

    private fun readPdf(context: Context, file: File): LocalFileReadResult {
        return runCatching {
            ensurePdfBoxInitialized(context)
            PDDocument.load(file).use { document ->
                if (document.isEncrypted) {
                    return LocalFileReadResult.Unsupported(
                        code = "encrypted_document",
                        message = "Encrypted PDF is not supported by read.",
                        nextStep = "Remove PDF encryption or export the document to plain text, then retry."
                    )
                }
                val text = PDFTextStripper().getText(document)
                LocalFileReadResult.Success(
                    text = normalizeExtractedText(text).ifBlank { "(empty pdf)" },
                    sourceType = "pdf",
                    charset = "PDFText",
                    note = "Extracted from PDF text content."
                )
            }
        }.getOrElse { error ->
            LocalFileReadResult.Failure(
                code = "pdf_extract_failed",
                message = error.message ?: error.javaClass.simpleName,
                nextStep = "Verify the PDF is not corrupted or password-protected, then retry."
            )
        }
    }

    private fun readDoc(file: File): LocalFileReadResult {
        return runCatching {
            HWPFDocument(file.inputStream()).use { document ->
                WordExtractor(document).use { extractor ->
                    LocalFileReadResult.Success(
                        text = normalizeExtractedText(extractor.text.orEmpty()).ifBlank { "(empty document)" },
                        sourceType = "doc",
                        charset = "Binary Office",
                        note = "Extracted from Word binary document."
                    )
                }
            }
        }.getOrElse { error ->
            LocalFileReadResult.Failure(
                code = "doc_extract_failed",
                message = error.message ?: error.javaClass.simpleName,
                nextStep = "Verify the DOC file is not corrupted, then retry."
            )
        }
    }

    private fun readDocx(file: File): LocalFileReadResult {
        val xmlResult = readDocxFromXmlParts(file)
        val poiResult = readDocxWithPoi(file)

        val successful = buildList {
            (xmlResult as? LocalFileReadResult.Success)?.let(::add)
            (poiResult as? LocalFileReadResult.Success)?.let(::add)
        }
        if (successful.isNotEmpty()) {
            val best = successful.maxByOrNull { scoreDocxExtraction(it.text) }!!
            if (!isDocxEmptyText(best.text)) {
                return best
            }
            val broadFallback = readDocxFromAllWordXmlParts(file)
            if (broadFallback is LocalFileReadResult.Success && !isDocxEmptyText(broadFallback.text)) {
                return broadFallback
            }
            return best
        }

        val details = buildList {
            (xmlResult as? LocalFileReadResult.Failure)?.message?.let { add("xml=$it") }
            (poiResult as? LocalFileReadResult.Failure)?.message?.let { add("poi=$it") }
        }
        return LocalFileReadResult.Failure(
            code = "docx_extract_failed",
            message = details.joinToString(" | ").ifBlank { "No DOCX extractor produced readable content." },
            nextStep = "Verify the DOCX file is not corrupted, then retry."
        )
    }

    private fun readDocxWithPoi(file: File): LocalFileReadResult {
        return runCatching {
            XWPFDocument(file.inputStream()).use { document ->
                XWPFWordExtractor(document).use { extractor ->
                    LocalFileReadResult.Success(
                        text = normalizeExtractedText(extractor.text.orEmpty()).ifBlank { "(empty document)" },
                        sourceType = "docx",
                        charset = "OOXML",
                        note = "Extracted with the OOXML Word parser."
                    )
                }
            }
        }.getOrElse { error ->
            LocalFileReadResult.Failure(
                code = "docx_poi_extract_failed",
                message = error.message ?: error.javaClass.simpleName,
                nextStep = "Verify the DOCX file is not corrupted, then retry."
            )
        }
    }

    private fun readDocxFromXmlParts(file: File): LocalFileReadResult {
        val extracted = runCatching {
            ZipFile(file).use { zip ->
                val entries = zip.entries().asSequence()
                    .filter { !it.isDirectory }
                    .filter { entry -> isDocxTextPart(entry.name) }
                    .sortedBy { it.name }
                    .toList()

                if (entries.isEmpty()) return@use ""

                entries.joinToString("\n\n") { entry ->
                    extractWordprocessingMlText(decodeZipEntry(zip, entry))
                }
            }
        }.getOrElse { error ->
            return LocalFileReadResult.Failure(
                code = "docx_xml_extract_failed",
                message = error.message ?: error.javaClass.simpleName,
                nextStep = "Verify the DOCX file is not corrupted, then retry."
            )
        }

        return LocalFileReadResult.Success(
            text = normalizeExtractedText(extracted).ifBlank { "(empty document)" },
            sourceType = "docx",
            charset = "OOXML-XML",
            note = "Extracted from OOXML Word XML parts."
        )
    }

    private fun readDocxFromAllWordXmlParts(file: File): LocalFileReadResult {
        return readZipXmlText(
            file = file,
            entryPrefix = "word/",
            entrySuffix = ".xml",
            sourceType = "docx",
            xmlExtractor = { xml -> extractWordprocessingMlText(xml) },
            note = "Extracted from all OOXML Word XML parts."
        )
    }

    private fun isDocxEmptyText(text: String): Boolean {
        return text.trim() == "(empty document)"
    }

    private fun isDocxTextPart(entryName: String): Boolean {
        return DOCX_TEXT_PART_PATTERNS.any { pattern -> pattern.matches(entryName) }
    }

    private fun extractWordprocessingMlText(xml: String): String {
        return unescapeXml(
            xml
                .replace(Regex("<w:tab\\b[^>]*/>"), "\t")
                .replace(Regex("<w:br\\b[^>]*/>"), "\n")
                .replace(Regex("<w:cr\\b[^>]*/>"), "\n")
                .replace("</w:p>", "\n")
                .replace("</w:tr>", "\n")
                .replace("</w:tbl>", "\n")
                .replace(Regex("<[^>]+>"), "")
        )
    }

    private fun scoreDocxExtraction(text: String): Int {
        if (text == "(empty document)") return Int.MIN_VALUE / 2

        val replacementPenalty = text.count { it == '\uFFFD' } * 40
        val mojibakePenalty = DOCX_MOJIBAKE_MARKERS.sumOf { marker ->
            literalOccurrences(text, marker) * 120
        }
        return scoreDecodedText(text) - replacementPenalty - mojibakePenalty
    }

    private fun literalOccurrences(source: String, token: String): Int {
        if (token.isEmpty()) return 0
        var count = 0
        var fromIndex = 0
        while (true) {
            val idx = source.indexOf(token, fromIndex)
            if (idx < 0) break
            count += 1
            fromIndex = idx + token.length
        }
        return count
    }

    private fun readPpt(file: File): LocalFileReadResult {
        return runCatching {
            val inputStream = file.inputStream()
            inputStream.use { stream ->
                val extractor = PowerPointExtractor(stream)
                try {
                    LocalFileReadResult.Success(
                        text = normalizeExtractedText(extractor.getText().orEmpty()).ifBlank { "(empty presentation)" },
                        sourceType = "ppt",
                        charset = "Binary Office",
                        note = "Extracted from PowerPoint binary presentation."
                    )
                } finally {
                    extractor.close()
                }
            }
        }.getOrElse { error ->
            LocalFileReadResult.Failure(
                code = "ppt_extract_failed",
                message = error.message ?: error.javaClass.simpleName,
                nextStep = "Verify the PPT file is not corrupted, then retry."
            )
        }
    }

    private fun readPptx(file: File): LocalFileReadResult {
        return runCatching {
            XMLSlideShow(file.inputStream()).use { slideShow ->
                val extracted = buildString {
                    slideShow.slides.forEachIndexed { index, slide ->
                        appendLine("[slide ${index + 1}]")
                        val slideText = slide.shapes.asSequence()
                            .filterIsInstance<XSLFTextShape>()
                            .mapNotNull { shape -> shape.text?.trim()?.takeIf { it.isNotBlank() } }
                            .joinToString("\n")
                        appendLine(slideText.ifBlank { "(empty slide)" })
                        if (index < slideShow.slides.size - 1) {
                            appendLine()
                        }
                    }
                }
                LocalFileReadResult.Success(
                    text = normalizeExtractedText(extracted).ifBlank { "(empty presentation)" },
                    sourceType = "pptx",
                    charset = "OOXML",
                    note = "Extracted with the OOXML PowerPoint parser."
                )
            }
        }.getOrElse { error ->
            LocalFileReadResult.Failure(
                code = "pptx_extract_failed",
                message = error.message ?: error.javaClass.simpleName,
                nextStep = "Verify the PPTX file is not corrupted, then retry."
            )
        }
    }

    private fun readXls(file: File): LocalFileReadResult {
        return runCatching {
            HSSFWorkbook(file.inputStream()).use { workbook ->
                val formatter = DataFormatter(Locale.US)
                val extracted = buildString {
                    for (sheetIndex in 0 until workbook.numberOfSheets) {
                        val sheet = workbook.getSheetAt(sheetIndex)
                        appendLine("[${sheet.sheetName}]")
                        val rows = sheet.rowIterator().asSequence().map { row ->
                            row.cellIterator().asSequence()
                                .map { cell -> formatter.formatCellValue(cell).trim() }
                                .joinToString("\t")
                        }.filter { it.isNotBlank() }.toList()
                        appendLine(rows.joinToString("\n").ifBlank { "(empty sheet)" })
                        if (sheetIndex < workbook.numberOfSheets - 1) {
                            appendLine()
                        }
                    }
                }
                LocalFileReadResult.Success(
                    text = normalizeExtractedText(extracted).ifBlank { "(empty spreadsheet)" },
                    sourceType = "xls",
                    charset = "Binary Office",
                    note = "Extracted from Excel binary workbook."
                )
            }
        }.getOrElse { error ->
            LocalFileReadResult.Failure(
                code = "xls_extract_failed",
                message = error.message ?: error.javaClass.simpleName,
                nextStep = "Verify the XLS file is not corrupted, then retry."
            )
        }
    }

    private fun readXlsx(file: File): LocalFileReadResult {
        return runCatching {
            XSSFWorkbook(file.inputStream()).use { workbook ->
                val formatter = DataFormatter(Locale.US)
                val extracted = buildString {
                    for (sheetIndex in 0 until workbook.numberOfSheets) {
                        val sheet = workbook.getSheetAt(sheetIndex)
                        appendLine("[${sheet.sheetName}]")
                        val rows = sheet.iterator().asSequence().mapNotNull { row ->
                            val firstCell = row.firstCellNum.toInt().coerceAtLeast(0)
                            val lastCellExclusive = row.lastCellNum.toInt().coerceAtLeast(firstCell)
                            if (lastCellExclusive <= firstCell) {
                                null
                            } else {
                                (firstCell until lastCellExclusive)
                                    .map { columnIndex ->
                                        val cell = row.getCell(columnIndex)
                                        if (cell == null) {
                                            ""
                                        } else {
                                            formatter.formatCellValue(cell).trim()
                                        }
                                    }
                                    .joinToString("\t")
                                    .trimEnd()
                                    .ifBlank { null }
                            }
                        }.toList()
                        appendLine(rows.joinToString("\n").ifBlank { "(empty sheet)" })
                        if (sheetIndex < workbook.numberOfSheets - 1) {
                            appendLine()
                        }
                    }
                }
                LocalFileReadResult.Success(
                    text = normalizeExtractedText(extracted).ifBlank { "(empty spreadsheet)" },
                    sourceType = "xlsx",
                    charset = "OOXML",
                    note = "Extracted with the OOXML Excel parser."
                )
            }
        }.getOrElse { error ->
            LocalFileReadResult.Failure(
                code = "xlsx_extract_failed",
                message = error.message ?: error.javaClass.simpleName,
                nextStep = "Verify the XLSX file is not corrupted, then retry."
            )
        }
    }

    private fun readOpenDocumentText(file: File): LocalFileReadResult {
        return runCatching {
            OdfTextDocument.loadDocument(file).use { document ->
                val text = document.getContentRoot()?.textContent.orEmpty()
                LocalFileReadResult.Success(
                    text = normalizeExtractedText(text).ifBlank { "(empty document)" },
                    sourceType = "odt",
                    charset = "ODFDOM",
                    note = "Extracted with the ODF Toolkit text parser."
                )
            }
        }.getOrElse { error ->
            LocalFileReadResult.Failure(
                code = "odt_extract_failed",
                message = error.message ?: error.javaClass.simpleName,
                nextStep = "Verify the ODT file is not corrupted, then retry."
            )
        }
    }

    private fun readZipXmlText(
        file: File,
        entryNames: List<String> = emptyList(),
        entryPrefix: String? = null,
        entrySuffix: String? = null,
        sourceType: String,
        xmlExtractor: (String) -> String,
        note: String
    ): LocalFileReadResult {
        val extracted = runCatching {
            ZipFile(file).use { zip ->
                val entries = zip.entries().asSequence()
                    .filter { !it.isDirectory }
                    .filter { entry ->
                        when {
                            entryNames.isNotEmpty() -> entry.name in entryNames
                            entryPrefix != null && entrySuffix != null -> entry.name.startsWith(entryPrefix) && entry.name.endsWith(entrySuffix)
                            else -> false
                        }
                    }
                    .sortedBy { it.name }
                    .toList()
                if (entries.isEmpty()) return@use ""
                entries.joinToString("\n\n") { entry ->
                    xmlExtractor(decodeZipEntry(zip, entry))
                }
            }
        }.getOrElse { error ->
            return LocalFileReadResult.Failure(
                code = "${sourceType}_extract_failed",
                message = error.message ?: error.javaClass.simpleName,
                nextStep = "Verify the file is not corrupted and retry."
            )
        }
        return LocalFileReadResult.Success(
            text = normalizeExtractedText(extracted).ifBlank { "(empty document)" },
            sourceType = sourceType,
            charset = "UTF-8",
            note = note
        )
    }

    private fun decodeZipEntry(zip: ZipFile, entry: java.util.zip.ZipEntry): String {
        val bytes = zip.getInputStream(entry).use { it.readBytes() }
        decodeXmlWithDeclaredEncoding(bytes)?.let { return it }
        return decodeBestEffort(bytes)?.text
            ?: throw IllegalArgumentException("Unsupported encoding inside ${entry.name}")
    }

    private fun decodeXmlWithDeclaredEncoding(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return ""

        decodeBom(bytes)?.let { return it.text }

        val probe = bytes.copyOfRange(0, minOf(bytes.size, XML_DECLARATION_SCAN_BYTES))
            .toString(StandardCharsets.ISO_8859_1)
        if (!probe.contains("<?xml", ignoreCase = true)) {
            return null
        }

        val declaredEncoding = XML_DECLARED_ENCODING_REGEX
            .find(probe)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            .orEmpty()

        if (declaredEncoding.isNotBlank()) {
            val declaredCharset = runCatching { Charset.forName(declaredEncoding) }.getOrNull()
            if (declaredCharset != null) {
                strictDecode(bytes, declaredCharset)?.let { return it }
            }
        }

        return strictDecode(bytes, StandardCharsets.UTF_8)
            ?: strictDecode(bytes, StandardCharsets.UTF_16LE)
            ?: strictDecode(bytes, StandardCharsets.UTF_16BE)
    }

    private fun decodeBestEffort(bytes: ByteArray): DecodedText? {
        if (bytes.isEmpty()) return DecodedText("", StandardCharsets.UTF_8)
        decodeBom(bytes)?.let { return it }

        val candidates = buildList {
            add(StandardCharsets.UTF_8)
            add(StandardCharsets.UTF_16LE)
            add(StandardCharsets.UTF_16BE)
            add(Charset.forName("UTF-32LE"))
            add(Charset.forName("UTF-32BE"))
            add(Charset.forName("Big5"))
            add(Charset.forName("GBK"))
            add(Charset.forName("GB18030"))
            add(Charset.forName("Shift_JIS"))
            add(Charset.forName("windows-1252"))
        }.distinctBy { it.name() }

        val decodedCandidates = candidates.mapNotNull { charset ->
            strictDecode(bytes, charset)?.let { text ->
                val score = scoreDecodedText(text)
                if (score >= MIN_TEXT_SCORE) DecodedCandidate(DecodedText(text, charset), score) else null
            }
        }
        return decodedCandidates.maxByOrNull { it.score }?.decoded
    }

    private fun decodeBom(bytes: ByteArray): DecodedText? {
        return when {
            bytes.size >= 3 &&
                bytes[0] == 0xEF.toByte() &&
                bytes[1] == 0xBB.toByte() &&
                bytes[2] == 0xBF.toByte() -> {
                strictDecode(bytes.copyOfRange(3, bytes.size), StandardCharsets.UTF_8)?.let {
                    DecodedText(it, StandardCharsets.UTF_8)
                }
            }
            bytes.size >= 2 &&
                bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xFE.toByte() -> {
                strictDecode(bytes.copyOfRange(2, bytes.size), StandardCharsets.UTF_16LE)?.let {
                    DecodedText(it, StandardCharsets.UTF_16LE)
                }
            }
            bytes.size >= 2 &&
                bytes[0] == 0xFE.toByte() &&
                bytes[1] == 0xFF.toByte() -> {
                strictDecode(bytes.copyOfRange(2, bytes.size), StandardCharsets.UTF_16BE)?.let {
                    DecodedText(it, StandardCharsets.UTF_16BE)
                }
            }
            else -> null
        }
    }

    private fun strictDecode(bytes: ByteArray, charset: Charset): String? {
        return runCatching {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()
    }

    private fun scoreDecodedText(text: String): Int {
        if (text.isBlank()) return 10
        val allowedControls = setOf('\n', '\r', '\t')
        var printable = 0
        var suspicious = 0
        text.forEach { ch ->
            when {
                ch == '\uFFFD' -> suspicious += 6
                ch == '\u0000' -> suspicious += 6
                ch.isISOControl() && ch !in allowedControls -> suspicious += 3
                else -> printable += 1
            }
        }
        return printable - suspicious
    }

    private fun normalizeExtractedText(text: String): String {
        return text
            .replace("\r", "\n")
            .replace(Regex("[\\t\\u000B\\f]{2,}"), " ")
            .replace(Regex(" {2,}"), " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .lines()
            .joinToString("\n") { it.trimEnd() }
            .trim()
    }

    private fun unescapeXml(value: String): String {
        return value
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }

    private fun buildUnsupportedMessage(file: File): String {
        val extension = file.extension.lowercase(Locale.US).ifBlank { "unknown" }
        val mimeType = URLConnection.guessContentTypeFromName(file.name).orEmpty()
        return if (mimeType.isNotBlank()) {
            "File looks like binary or uses an unsupported text encoding (ext=$extension, mime=$mimeType)."
        } else {
            "File looks like binary or uses an unsupported text encoding (ext=$extension)."
        }
    }

    @Volatile
    private var pdfBoxInitialized = false

    private fun ensurePdfBoxInitialized(context: Context) {
        if (pdfBoxInitialized) return
        synchronized(this) {
            if (pdfBoxInitialized) return
            PDFBoxResourceLoader.init(context.applicationContext)
            pdfBoxInitialized = true
        }
    }

    private data class DecodedText(
        val text: String,
        val charset: Charset
    )

    private data class DecodedCandidate(
        val decoded: DecodedText,
        val score: Int
    )

    private const val MIN_TEXT_SCORE = 4
    private const val HEADER_PROBE_BYTES = 4096
    private const val XML_DECLARATION_SCAN_BYTES = 512
    private const val MAX_SUPPORTED_FILE_BYTES = 25L * 1024L * 1024L

    private val XML_DECLARED_ENCODING_REGEX = Regex(
        """encoding\\s*=\\s*[\"']([^\"']+)[\"']""",
        RegexOption.IGNORE_CASE
    )

    private val DOCX_TEXT_PART_PATTERNS = listOf(
        Regex("""^word/document\\.xml$"""),
        Regex("""^word/header\\d*\\.xml$"""),
        Regex("""^word/footer\\d*\\.xml$"""),
        Regex("""^word/footnotes\\.xml$"""),
        Regex("""^word/endnotes\\.xml$"""),
        Regex("""^word/comments\\d*\\.xml$""")
    )

    private val DOCX_MOJIBAKE_CODEPOINTS = intArrayOf(
        0x951F,
        0x70EB,
        0x95BF,
        0x9227,
        0x95C1,
        0x95B8,
        0x6FDE,
        0x9359
    )

    private val DOCX_MOJIBAKE_MARKERS: List<String> =
        DOCX_MOJIBAKE_CODEPOINTS.map { codePoint -> String(Character.toChars(codePoint)) } +
            listOf("\u00C3", "\u00C2")

    private val WORDPROCESSOR_XML_EXTENSIONS = setOf("docx")
    private val PRESENTATION_XML_EXTENSIONS = setOf("pptx")
    private val SPREADSHEET_XML_EXTENSIONS = setOf("xlsx")
    private val OPEN_DOCUMENT_TEXT_EXTENSIONS = setOf("odt")
    private val KNOWN_BINARY_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic",
        "mp3", "wav", "m4a", "aac", "ogg", "flac",
        "mp4", "mov", "avi", "mkv", "webm",
        "zip", "rar", "7z", "tar", "gz",
        "apk", "so", "dex", "db", "sqlite"
    )
}
