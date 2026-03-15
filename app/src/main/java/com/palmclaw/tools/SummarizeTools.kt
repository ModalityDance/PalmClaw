package com.palmclaw.tools

import android.content.Context
import com.palmclaw.config.AppStoragePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

fun createSummarizeToolSet(
    context: Context,
    client: OkHttpClient
): List<Tool> {
    return listOf(SummarizeExtractTool(context, client))
}

private class SummarizeExtractTool(
    context: Context,
    private val client: OkHttpClient
) : Tool {
    override val name: String = "summarize"
    override val description: String =
        "Extract text from URL/YouTube/local file and return quick summary + extracted content."
    override val jsonSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        put("required", Json.parseToJsonElement("[\"source\"]"))
        put(
            "properties",
            Json.parseToJsonElement(
                """
                {
                  "source":{"type":"string","description":"URL or local file path under app workspace"},
                  "extractOnly":{"type":"boolean"},
                  "length":{"type":"string","description":"short|medium|long|xl|xxl|number"},
                  "maxChars":{"type":"integer"},
                  "youtube":{"type":"string","enum":["auto","off"]},
                  "includeQuickSummary":{"type":"boolean"}
                }
                """.trimIndent()
            )
        )
    }

    private val appRoot: File = AppStoragePaths.storageRoot(context).canonicalFile
    private val rootPath = appRoot.path
    private val rootPrefix = rootPath + File.separator
    private val ignoreCase = System.getProperty("os.name").lowercase(Locale.US).contains("win")
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun run(argumentsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val args = Json.decodeFromString<Args>(argumentsJson)
        val source = args.source.trim()
        if (source.isBlank()) return@withContext error("summarize failed: source is empty")

        val maxChars = (args.maxChars ?: DEFAULT_MAX_EXTRACT_CHARS).coerceIn(500, MAX_EXTRACT_CHARS)
        val extractOnly = args.extractOnly ?: false
        val includeQuickSummary = args.includeQuickSummary ?: !extractOnly
        val length = args.length.orEmpty()

        runCatching {
            when {
                source.startsWith("http://", ignoreCase = true) ||
                    source.startsWith("https://", ignoreCase = true) -> {
                    val youtubeMode = args.youtube ?: "auto"
                    if (youtubeMode.equals("auto", ignoreCase = true) && isYouTubeUrl(source)) {
                        summarizeYouTube(source, maxChars, includeQuickSummary, length)
                    } else {
                        summarizeUrl(source, maxChars, includeQuickSummary, length)
                    }
                }

                else -> summarizeLocalFile(source, maxChars, includeQuickSummary, length)
            }
        }.getOrElse { t ->
            error("summarize failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun summarizeUrl(
        url: String,
        maxChars: Int,
        includeQuickSummary: Boolean,
        length: String
    ): ToolResult {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return error("summarize url failed: http=${response.code}")
            }
            val contentType = response.header("Content-Type").orEmpty()
            val body = response.body?.string().orEmpty()
            val extracted = when {
                contentType.contains("text/html", ignoreCase = true) -> htmlToText(body)
                else -> body
            }.normalize()
            val (trimmed, truncated) = trimTo(extracted, maxChars)
            return buildResult(
                sourceType = "url",
                source = url,
                extracted = trimmed,
                includeQuickSummary = includeQuickSummary,
                length = length,
                note = if (truncated) "content truncated to $maxChars chars" else null,
                metadata = buildJsonObject {
                    put("status", response.code)
                    put("content_type", contentType)
                    put("truncated", truncated)
                }
            )
        }
    }

    private fun summarizeYouTube(
        url: String,
        maxChars: Int,
        includeQuickSummary: Boolean,
        length: String
    ): ToolResult {
        val encoded = URLEncoder.encode(url, "UTF-8")
        val oEmbedUrl = "https://www.youtube.com/oembed?url=$encoded&format=json"

        var title = ""
        var author = ""
        runCatching {
            val request = Request.Builder().url(oEmbedUrl).header("User-Agent", USER_AGENT).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use
                val text = response.body?.string().orEmpty()
                val obj = json.parseToJsonElement(text).jsonObject
                title = obj["title"]?.jsonPrimitive?.content.orEmpty()
                author = obj["author_name"]?.jsonPrimitive?.content.orEmpty()
            }
        }

        var description = ""
        runCatching {
            val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use
                val html = response.body?.string().orEmpty()
                description = extractMetaDescription(html).orEmpty()
            }
        }

        val extracted = buildString {
            if (title.isNotBlank()) appendLine("Title: $title")
            if (author.isNotBlank()) appendLine("Author: $author")
            if (description.isNotBlank()) {
                appendLine()
                appendLine("Description:")
                appendLine(description)
            }
            if (isBlank()) {
                append("No transcript provider configured. Only metadata fallback is available.")
            } else {
                appendLine()
                append("Transcript note: metadata fallback only.")
            }
        }.normalize()

        val (trimmed, truncated) = trimTo(extracted, maxChars)
        return buildResult(
            sourceType = "youtube",
            source = url,
            extracted = trimmed,
            includeQuickSummary = includeQuickSummary,
            length = length,
            note = if (truncated) "content truncated to $maxChars chars" else null,
            metadata = buildJsonObject {
                put("has_title", title.isNotBlank())
                put("has_author", author.isNotBlank())
                put("has_description", description.isNotBlank())
                put("transcript_available", false)
            }
        )
    }

    private fun summarizeLocalFile(
        rawPath: String,
        maxChars: Int,
        includeQuickSummary: Boolean,
        length: String
    ): ToolResult {
        val file = resolveLocalPath(rawPath)
        if (!file.exists()) return error("summarize file failed: file not found")
        if (!file.isFile) return error("summarize file failed: path is not a file")
        if (isLikelyBinary(file)) return error("summarize file failed: binary/unsupported file")

        val text = runCatching { file.readText(Charsets.UTF_8) }
            .getOrElse { t -> return error("summarize file failed: ${t.message ?: t.javaClass.simpleName}") }
            .normalize()
        val (trimmed, truncated) = trimTo(text, maxChars)
        return buildResult(
            sourceType = "local_file",
            source = relative(file),
            extracted = trimmed,
            includeQuickSummary = includeQuickSummary,
            length = length,
            note = if (truncated) "content truncated to $maxChars chars" else null,
            metadata = buildJsonObject {
                put("file_bytes", file.length())
                put("truncated", truncated)
            }
        )
    }

    private fun buildResult(
        sourceType: String,
        source: String,
        extracted: String,
        includeQuickSummary: Boolean,
        length: String,
        note: String?,
        metadata: JsonObject
    ): ToolResult {
        val summary = if (includeQuickSummary) quickSummary(extracted, length) else ""
        val content = buildString {
            appendLine("source_type=$sourceType")
            appendLine("source=$source")
            if (!note.isNullOrBlank()) appendLine("note=$note")
            if (summary.isNotBlank()) {
                appendLine()
                appendLine("quick_summary:")
                appendLine(summary)
            }
            appendLine()
            appendLine("extracted_text:")
            append(extracted.ifBlank { "(empty)" })
        }
        return ToolResult(
            toolCallId = "",
            content = content,
            isError = false,
            metadata = metadata
        )
    }

    private fun quickSummary(text: String, length: String): String {
        if (text.isBlank()) return "(empty)"
        val budget = when (length.lowercase(Locale.US)) {
            "short" -> 500
            "medium" -> 1000
            "long" -> 1800
            "xl" -> 2800
            "xxl" -> 4200
            else -> length.toIntOrNull()?.coerceIn(300, 8000) ?: 1000
        }
        val sentences = text.split(SENTENCE_SPLIT_REGEX).map { it.trim() }.filter { it.isNotBlank() }
        if (sentences.isEmpty()) return text.take(budget)
        val builder = StringBuilder()
        for (sentence in sentences) {
            val add = if (builder.isEmpty()) sentence else " $sentence"
            if (builder.length + add.length > budget) break
            builder.append(add)
        }
        val out = builder.toString().trim()
        return if (out.isBlank()) text.take(budget) else out
    }

    private fun htmlToText(html: String): String {
        return html
            .replace(Regex("(?is)<script\\b[^>]*>.*?</script>"), " ")
            .replace(Regex("(?is)<style\\b[^>]*>.*?</style>"), " ")
            .replace(Regex("(?is)<noscript\\b[^>]*>.*?</noscript>"), " ")
            .replace(Regex("(?is)<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .normalize()
    }

    private fun extractMetaDescription(html: String): String? {
        val patterns = listOf(
            Regex("(?is)<meta\\s+name=[\"']description[\"']\\s+content=[\"'](.*?)[\"']"),
            Regex("(?is)<meta\\s+property=[\"']og:description[\"']\\s+content=[\"'](.*?)[\"']")
        )
        for (pattern in patterns) {
            val match = pattern.find(html)?.groupValues?.getOrNull(1).orEmpty().trim()
            if (match.isNotBlank()) return match.normalize()
        }
        return null
    }

    private fun trimTo(text: String, maxChars: Int): Pair<String, Boolean> {
        if (text.length <= maxChars) return text to false
        return text.take(maxChars) to true
    }

    private fun String.normalize(): String {
        return replace("\r", "\n")
            .replace(Regex("[\\t\\u000B\\f]+"), " ")
            .replace(Regex(" {2,}"), " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun isYouTubeUrl(url: String): Boolean {
        return runCatching {
            val host = URI(url).host?.lowercase(Locale.US).orEmpty()
            host.contains("youtube.com") || host.contains("youtu.be")
        }.getOrDefault(false)
    }

    private fun resolveLocalPath(rawPath: String): File {
        val input = rawPath.trim()
        val candidate = File(input).let { if (it.isAbsolute) it else File(appRoot, input) }.canonicalFile
        if (!isUnderRoot(candidate)) throw SecurityException("local path escapes app workspace")
        return candidate
    }

    private fun isUnderRoot(file: File): Boolean {
        val path = file.path
        if (path.equals(rootPath, ignoreCase = ignoreCase)) return true
        return path.startsWith(rootPrefix, ignoreCase = ignoreCase)
    }

    private fun relative(file: File): String {
        return appRoot.toPath().relativize(file.canonicalFile.toPath()).toString().replace('\\', '/')
    }

    private fun isLikelyBinary(file: File): Boolean {
        val ext = file.extension.lowercase(Locale.US)
        return ext in BINARY_EXTENSIONS
    }

    private fun error(message: String): ToolResult {
        return ToolResult(
            toolCallId = "",
            content = message,
            isError = true
        )
    }

    @Serializable
    private data class Args(
        val source: String,
        val extractOnly: Boolean? = null,
        val length: String? = null,
        val maxChars: Int? = null,
        val youtube: String? = null,
        val includeQuickSummary: Boolean? = null
    )

    companion object {
        private const val DEFAULT_MAX_EXTRACT_CHARS = 12_000
        private const val MAX_EXTRACT_CHARS = 120_000
        private const val USER_AGENT = "palmclaw/1.0 (+android)"
        private val SENTENCE_SPLIT_REGEX = Regex("(?<=[.!?])\\s+")
        private val BINARY_EXTENSIONS = setOf(
            "pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx",
            "jpg", "jpeg", "png", "gif", "webp", "bmp",
            "mp3", "wav", "m4a", "aac",
            "mp4", "mov", "avi", "mkv",
            "zip", "rar", "7z", "apk", "so", "dex", "db"
        )
    }
}
