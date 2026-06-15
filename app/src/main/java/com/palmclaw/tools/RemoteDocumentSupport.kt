package com.palmclaw.tools

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

internal object RemoteDocumentSupport {
    fun readFromResponse(
        context: Context,
        url: String,
        contentType: String,
        bodyBytes: ByteArray
    ): LocalFileReadResult? {
        val extension = inferDocumentExtension(url, contentType) ?: "bin"
        val tempFile = createTempFile(prefix = "remote-doc-", suffix = ".$extension")
        return try {
            tempFile.writeBytes(bodyBytes)
            when (val result = LocalFileReadSupport.read(context, tempFile)) {
                is LocalFileReadResult.Success -> {
                    LocalFileReadResult.Success(
                        text = result.text,
                        sourceType = result.sourceType,
                        charset = result.charset,
                        note = buildString {
                            append("Extracted from remote document")
                            result.note?.let {
                                append("; ")
                                append(it)
                            }
                        }
                    )
                }
                is LocalFileReadResult.Unsupported -> result
                is LocalFileReadResult.Failure -> result
            }
        } finally {
            tempFile.delete()
        }
    }

    fun inferDocumentExtension(url: String, contentType: String): String? {
        val normalizedContentType = contentType.substringBefore(';').trim().lowercase(Locale.US)
        MIME_TO_EXTENSION[normalizedContentType]?.let { return it }

        val path = url.toHttpUrlOrNull()?.encodedPath.orEmpty()
        val lastSegment = path.substringAfterLast('/', "")
        val extension = lastSegment.substringAfterLast('.', "").lowercase(Locale.US)
        return extension.takeIf { it in SUPPORTED_REMOTE_EXTENSIONS }
    }

    fun looksLikeUnsupportedBinary(url: String, contentType: String): Boolean {
        val normalizedContentType = contentType.substringBefore(';').trim().lowercase(Locale.US)
        if (normalizedContentType.isBlank()) {
            val extension = inferExtensionFromUrl(url)
            return extension in KNOWN_BINARY_EXTENSIONS && extension !in SUPPORTED_REMOTE_EXTENSIONS
        }
        if (
            normalizedContentType.startsWith("image/") ||
            normalizedContentType.startsWith("audio/") ||
            normalizedContentType.startsWith("video/")
        ) {
            return true
        }
        if (normalizedContentType in TEXT_LIKE_CONTENT_TYPES || MIME_TO_EXTENSION.containsKey(normalizedContentType)) {
            return false
        }
        val extension = inferExtensionFromUrl(url)
        return extension in KNOWN_BINARY_EXTENSIONS || normalizedContentType == "application/octet-stream"
    }

    fun decodeRemoteText(bodyBytes: ByteArray, contentType: String): DecodedRemoteText {
        if (bodyBytes.isEmpty()) {
            return DecodedRemoteText(text = "", charset = StandardCharsets.UTF_8.name())
        }
        decodeBom(bodyBytes)?.let {
            return DecodedRemoteText(text = it, charset = "bom")
        }
        val candidates = buildList {
            parseCharset(contentType)?.let(::add)
            add(StandardCharsets.UTF_8)
            add(StandardCharsets.UTF_16LE)
            add(StandardCharsets.UTF_16BE)
            add(Charset.forName("Big5"))
            add(Charset.forName("GBK"))
            add(Charset.forName("GB18030"))
            add(Charset.forName("Shift_JIS"))
            add(Charset.forName("windows-1252"))
        }.distinctBy { it.name() }

        val decoded = candidates.mapNotNull { charset ->
            strictDecode(bodyBytes, charset)?.let { text ->
                val score = scoreDecodedText(text)
                if (score >= MIN_TEXT_SCORE) {
                    DecodedRemoteText(text = text, charset = charset.name(), score = score)
                } else {
                    null
                }
            }
        }.maxByOrNull { it.score }

        return decoded ?: DecodedRemoteText(
            text = bodyBytes.toString(StandardCharsets.UTF_8),
            charset = StandardCharsets.UTF_8.name()
        )
    }

    private fun inferExtensionFromUrl(url: String): String {
        val path = url.toHttpUrlOrNull()?.encodedPath.orEmpty()
        val lastSegment = path.substringAfterLast('/', "")
        return lastSegment.substringAfterLast('.', "").lowercase(Locale.US)
    }

    private fun parseCharset(contentType: String): Charset? {
        val marker = "charset="
        val index = contentType.lowercase(Locale.US).indexOf(marker)
        if (index < 0) return null
        val raw = contentType.substring(index + marker.length)
            .substringBefore(';')
            .trim()
            .trim('"', '\'')
        if (raw.isBlank()) return null
        return runCatching { Charset.forName(raw) }.getOrNull()
    }

    private fun decodeBom(bytes: ByteArray): String? {
        return when {
            bytes.size >= 3 &&
                bytes[0] == 0xEF.toByte() &&
                bytes[1] == 0xBB.toByte() &&
                bytes[2] == 0xBF.toByte() -> strictDecode(bytes.copyOfRange(3, bytes.size), StandardCharsets.UTF_8)
            bytes.size >= 2 &&
                bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xFE.toByte() -> strictDecode(bytes.copyOfRange(2, bytes.size), StandardCharsets.UTF_16LE)
            bytes.size >= 2 &&
                bytes[0] == 0xFE.toByte() &&
                bytes[1] == 0xFF.toByte() -> strictDecode(bytes.copyOfRange(2, bytes.size), StandardCharsets.UTF_16BE)
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

    private val MIME_TO_EXTENSION = mapOf(
        "application/pdf" to "pdf",
        "application/msword" to "doc",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
        "application/vnd.ms-excel" to "xls",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
        "application/vnd.ms-powerpoint" to "ppt",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "pptx",
        "application/vnd.oasis.opendocument.text" to "odt"
    )

    private val SUPPORTED_REMOTE_EXTENSIONS = setOf(
        "pdf",
        "doc",
        "docx",
        "xls",
        "xlsx",
        "ppt",
        "pptx",
        "odt"
    )
    private val KNOWN_BINARY_EXTENSIONS = SUPPORTED_REMOTE_EXTENSIONS + setOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic",
        "mp3", "wav", "m4a", "aac", "ogg", "flac",
        "mp4", "mov", "avi", "mkv", "webm",
        "zip", "rar", "7z", "tar", "gz",
        "apk", "so", "dex", "db", "sqlite"
    )
    private val TEXT_LIKE_CONTENT_TYPES = setOf(
        "text/plain",
        "text/html",
        "text/markdown",
        "text/csv",
        "application/json",
        "application/xml",
        "text/xml"
    )
    private const val MIN_TEXT_SCORE = 4
}

internal data class DecodedRemoteText(
    val text: String,
    val charset: String,
    val score: Int = 0
)
