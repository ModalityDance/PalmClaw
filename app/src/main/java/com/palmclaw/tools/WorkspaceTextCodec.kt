package com.palmclaw.tools

import com.ibm.icu.text.CharsetDetector
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal enum class WorkspaceTextAccessMode {
    READ_ONLY,
    MUTATION
}

internal enum class WorkspaceTextEncodingSource(val metadataValue: String) {
    BOM("bom"),
    EXPLICIT("explicit"),
    UTF8("utf8"),
    DETECTED("detected")
}

internal data class WorkspaceTextEncodingCandidate(
    val charset: String,
    val confidence: Int
)

internal fun interface LegacyCharsetDetector {
    fun detect(bytes: ByteArray): List<WorkspaceTextEncodingCandidate>
}

internal class IcuLegacyCharsetDetector : LegacyCharsetDetector {
    override fun detect(bytes: ByteArray): List<WorkspaceTextEncodingCandidate> {
        if (bytes.isEmpty()) return emptyList()

        return runCatching {
            val detector = CharsetDetector()
            CharsetDetector.getAllDetectableCharsets().forEach { name ->
                detector.setDetectableCharset(name, canonicalDetectedCharsetName(name) != null)
            }
            detector.setText(bytes)
            detector.detectAll()
                .mapNotNull { match ->
                    canonicalDetectedCharsetName(match.name)?.let { canonicalName ->
                        WorkspaceTextEncodingCandidate(
                            charset = canonicalName,
                            confidence = match.confidence.coerceIn(0, 100)
                        )
                    }
                }
                .groupBy { it.charset }
                .map { (charset, matches) ->
                    WorkspaceTextEncodingCandidate(
                        charset = charset,
                        confidence = matches.maxOf { it.confidence }
                    )
                }
                .sortedByDescending { it.confidence }
        }.getOrDefault(emptyList())
    }

}

internal data class WorkspaceTextFormat internal constructor(
    internal val charset: Charset,
    internal val bom: ByteArray? = null
) {
    val charsetName: String = charset.name()

    fun withoutBom(): WorkspaceTextFormat = copy(bom = null)

    companion object {
        val UTF8 = WorkspaceTextFormat(StandardCharsets.UTF_8)
    }
}

internal sealed interface WorkspaceTextDecodeResult {
    data class Success(
        val text: String,
        val format: WorkspaceTextFormat,
        val source: WorkspaceTextEncodingSource,
        val confidence: Int? = null,
        val candidates: List<WorkspaceTextEncodingCandidate> = emptyList()
    ) : WorkspaceTextDecodeResult

    data class Unsupported(
        val code: String,
        val message: String,
        val nextStep: String,
        val source: WorkspaceTextEncodingSource? = null,
        val confidence: Int? = null,
        val candidates: List<WorkspaceTextEncodingCandidate> = emptyList()
    ) : WorkspaceTextDecodeResult
}

internal sealed interface WorkspaceTextEncodeResult {
    data class Success(val bytes: ByteArray) : WorkspaceTextEncodeResult

    data class Unsupported(
        val code: String,
        val message: String,
        val nextStep: String
    ) : WorkspaceTextEncodeResult
}

internal class WorkspaceTextCodec(
    private val legacyCharsetDetector: LegacyCharsetDetector
) {
    fun decode(
        bytes: ByteArray,
        encodingHint: String? = null,
        accessMode: WorkspaceTextAccessMode
    ): WorkspaceTextDecodeResult {
        val requested = resolveRequestedCharset(encodingHint)
        if (requested is RequestedCharset.Unsupported) {
            return WorkspaceTextDecodeResult.Unsupported(
                code = "unsupported_text_encoding",
                message = "Unsupported text encoding '${requested.raw}'.",
                nextStep = "Use auto or one of: ${supportedCharsetNames().joinToString(", ")}."
            )
        }

        val bom = detectBom(bytes)
        if (bom != null) {
            val requestedCharset = (requested as? RequestedCharset.Explicit)?.charset
            if (requestedCharset != null && requestedCharset.name() != bom.charset.name()) {
                return WorkspaceTextDecodeResult.Unsupported(
                    code = "encoding_bom_conflict",
                    message = "Requested encoding ${requestedCharset.name()} conflicts with ${bom.charset.name()} BOM.",
                    nextStep = "Remove the encoding hint or use ${bom.charset.name()}."
                )
            }
            return decodeDeterministically(
                bytes = bytes.copyOfRange(bom.bytes.size, bytes.size),
                format = WorkspaceTextFormat(bom.charset, bom.bytes),
                source = WorkspaceTextEncodingSource.BOM
            )
        }

        val requestedCharset = (requested as? RequestedCharset.Explicit)?.charset
        if (requestedCharset != null) {
            return decodeDeterministically(
                bytes = bytes,
                format = WorkspaceTextFormat(requestedCharset),
                source = WorkspaceTextEncodingSource.EXPLICIT
            )
        }

        val utf8Text = decodeStrict(bytes, StandardCharsets.UTF_8)
        val utf8ContainsForbiddenControl = utf8Text?.let(::containsForbiddenControl) == true
        if (utf8Text != null && !utf8ContainsForbiddenControl) {
            return WorkspaceTextDecodeResult.Success(
                text = utf8Text,
                format = WorkspaceTextFormat.UTF8,
                source = WorkspaceTextEncodingSource.UTF8
            )
        }

        val normalizedCandidates = legacyCharsetDetector.detect(bytes)
            .mapNotNull(::canonicalizeCandidate)
            .groupBy { it.charset }
            .map { (charset, matches) ->
                WorkspaceTextEncodingCandidate(charset, matches.maxOf { it.confidence })
            }
            .sortedByDescending { it.confidence }
        val reportedCandidates = normalizedCandidates.take(MAX_REPORTED_CANDIDATES)

        val detected = normalizedCandidates.firstNotNullOfOrNull { candidate ->
            val charset = charsetOrNull(candidate.charset) ?: return@firstNotNullOfOrNull null
            val decoded = decodeStrict(bytes, charset) ?: return@firstNotNullOfOrNull null
            if (containsForbiddenControl(decoded) || !roundTrips(bytes, decoded, charset)) {
                return@firstNotNullOfOrNull null
            }
            DetectedText(decoded, charset, candidate)
        }

        if (detected == null) {
            if (utf8ContainsForbiddenControl) return binaryContentResult()
            return uncertainDetectionResult(
                candidates = reportedCandidates,
                confidence = reportedCandidates.firstOrNull()?.confidence
            )
        }

        if (accessMode == WorkspaceTextAccessMode.MUTATION) {
            return WorkspaceTextDecodeResult.Unsupported(
                code = "encoding_required_for_mutation",
                message = "A statistically detected encoding cannot be used to modify a file.",
                nextStep = "Retry with encoding='${detected.charset.name()}' to confirm the file encoding.",
                source = WorkspaceTextEncodingSource.DETECTED,
                confidence = detected.candidate.confidence,
                candidates = reportedCandidates
            )
        }

        if (detected.candidate.confidence < MINIMUM_DETECTION_CONFIDENCE) {
            return uncertainDetectionResult(
                candidates = reportedCandidates,
                confidence = detected.candidate.confidence,
            )
        }

        return WorkspaceTextDecodeResult.Success(
            text = detected.text,
            format = WorkspaceTextFormat(detected.charset),
            source = WorkspaceTextEncodingSource.DETECTED,
            confidence = detected.candidate.confidence,
            candidates = reportedCandidates
        )
    }

    fun encode(text: String, format: WorkspaceTextFormat): WorkspaceTextEncodeResult {
        val body = runCatching {
            val buffer = format.charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(text))
            ByteArray(buffer.remaining()).also { output -> buffer.get(output) }
        }.getOrElse {
            return WorkspaceTextEncodeResult.Unsupported(
                code = "text_not_representable",
                message = "Text cannot be represented in ${format.charsetName} without data loss.",
                nextStep = "Use characters supported by ${format.charsetName}, or overwrite the file to convert it to UTF-8."
            )
        }
        return WorkspaceTextEncodeResult.Success((format.bom ?: ByteArray(0)) + body)
    }

    private fun decodeDeterministically(
        bytes: ByteArray,
        format: WorkspaceTextFormat,
        source: WorkspaceTextEncodingSource
    ): WorkspaceTextDecodeResult {
        val text = decodeStrict(bytes, format.charset)
            ?: return invalidEncodingResult(format.charsetName)
        if (containsForbiddenControl(text)) return binaryContentResult()
        return WorkspaceTextDecodeResult.Success(
            text = text,
            format = format,
            source = source
        )
    }

    private fun resolveRequestedCharset(raw: String?): RequestedCharset {
        val normalized = raw?.trim().orEmpty()
        if (normalized.isBlank() || normalized.equals("auto", ignoreCase = true)) {
            return RequestedCharset.Auto
        }
        val charset = runCatching { Charset.forName(normalized) }.getOrNull()
            ?: return RequestedCharset.Unsupported(normalized)
        val supported = allSupportedCharsets().firstOrNull { it.name() == charset.name() }
            ?: return RequestedCharset.Unsupported(normalized)
        return RequestedCharset.Explicit(supported)
    }

    private fun detectBom(bytes: ByteArray): BomMatch? {
        return BOM_DEFINITIONS.firstOrNull { definition -> bytes.startsWith(definition.bytes) }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        return prefix.indices.all { index -> this[index] == prefix[index] }
    }

    private fun decodeStrict(bytes: ByteArray, charset: Charset): String? {
        return runCatching {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()
    }

    private fun roundTrips(bytes: ByteArray, text: String, charset: Charset): Boolean {
        val encoded = runCatching {
            val buffer = charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(text))
            ByteArray(buffer.remaining()).also { output -> buffer.get(output) }
        }.getOrNull() ?: return false
        return bytes.contentEquals(encoded)
    }

    private fun invalidEncodingResult(charsetName: String): WorkspaceTextDecodeResult.Unsupported {
        return WorkspaceTextDecodeResult.Unsupported(
            code = "invalid_text_encoding",
            message = "File bytes are not valid $charsetName text.",
            nextStep = "Use the correct encoding hint or convert the file to UTF-8, then retry."
        )
    }

    private fun uncertainDetectionResult(
        candidates: List<WorkspaceTextEncodingCandidate>,
        confidence: Int?
    ): WorkspaceTextDecodeResult.Unsupported {
        return WorkspaceTextDecodeResult.Unsupported(
            code = "encoding_detection_uncertain",
            message = "Text encoding could not be detected with sufficient confidence.",
            nextStep = "Retry with an explicit encoding hint or convert the file to UTF-8.",
            source = WorkspaceTextEncodingSource.DETECTED,
            confidence = confidence,
            candidates = candidates
        )
    }

    private fun binaryContentResult(): WorkspaceTextDecodeResult.Unsupported {
        return WorkspaceTextDecodeResult.Unsupported(
            code = "unsupported_binary_or_encoding",
            message = "Decoded content contains binary control characters.",
            nextStep = "Use a text file or convert the content to plain text, then retry."
        )
    }

    private sealed interface RequestedCharset {
        data object Auto : RequestedCharset
        data class Explicit(val charset: Charset) : RequestedCharset
        data class Unsupported(val raw: String) : RequestedCharset
    }

    private data class DetectedText(
        val text: String,
        val charset: Charset,
        val candidate: WorkspaceTextEncodingCandidate
    )

    companion object {
        val default: WorkspaceTextCodec by lazy {
            WorkspaceTextCodec(IcuLegacyCharsetDetector())
        }

        private const val MINIMUM_DETECTION_CONFIDENCE = 50
        private const val MAX_REPORTED_CANDIDATES = 3
        private val BOM_DEFINITIONS = buildList {
            charsetOrNull("UTF-32LE")?.let {
                add(BomMatch(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x00), it))
            }
            charsetOrNull("UTF-32BE")?.let {
                add(BomMatch(byteArrayOf(0x00, 0x00, 0xFE.toByte(), 0xFF.toByte()), it))
            }
            add(BomMatch(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), StandardCharsets.UTF_8))
            add(BomMatch(byteArrayOf(0xFF.toByte(), 0xFE.toByte()), StandardCharsets.UTF_16LE))
            add(BomMatch(byteArrayOf(0xFE.toByte(), 0xFF.toByte()), StandardCharsets.UTF_16BE))
        }

        private fun allSupportedCharsets(): List<Charset> {
            return (listOf(StandardCharsets.UTF_8) +
                BOM_DEFINITIONS.map { it.charset } +
                EXPLICIT_LEGACY_CHARSETS.mapNotNull(::charsetOrNull))
                .distinctBy { it.name() }
        }

        private fun supportedCharsetNames(): List<String> {
            return allSupportedCharsets().map { it.name() }
        }
    }
}

internal fun normalizeWorkspaceTextLineEndings(text: String): String {
    return text.replace("\r\n", "\n").replace('\r', '\n')
}

private data class BomMatch(val bytes: ByteArray, val charset: Charset)

private val EXPLICIT_LEGACY_CHARSETS = listOf(
    "Big5",
    "GBK",
    "GB18030",
    "Shift_JIS",
    "windows-1252"
)

private fun canonicalizeCandidate(
    candidate: WorkspaceTextEncodingCandidate
): WorkspaceTextEncodingCandidate? {
    val canonical = canonicalDetectedCharsetName(candidate.charset) ?: return null
    return WorkspaceTextEncodingCandidate(
        charset = canonical,
        confidence = candidate.confidence.coerceIn(0, 100)
    )
}

private fun canonicalDetectedCharsetName(raw: String): String? {
    return when (raw.trim().lowercase().replace('_', '-')) {
        "big5", "big-5" -> "Big5"
        "gb18030" -> "GB18030"
        "shift-jis", "shiftjis", "sjis", "ms-kanji" -> "Shift_JIS"
        "windows-1252", "cp1252", "iso-8859-1" -> "windows-1252"
        "utf-16le" -> "UTF-16LE"
        "utf-16be" -> "UTF-16BE"
        "utf-32le" -> "UTF-32LE"
        "utf-32be" -> "UTF-32BE"
        else -> null
    }
}

private fun charsetOrNull(name: String): Charset? {
    return runCatching { Charset.forName(name) }.getOrNull()
}

private fun containsForbiddenControl(text: String): Boolean {
    return text.any { character ->
        character.code in 0x00..0x1F && character !in ALLOWED_TEXT_CONTROLS
    }
}

private val ALLOWED_TEXT_CONTROLS = setOf('\n', '\r', '\t')
