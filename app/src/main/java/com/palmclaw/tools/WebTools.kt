package com.palmclaw.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.min

fun createWebToolSet(client: OkHttpClient): List<Tool> {
    return listOf(
        WebSearchTool(client),
        WebFetchTool(client)
    )
}

private class WebSearchTool(
    private val client: OkHttpClient
) : Tool {
    override val name: String = "web_search"
    override val description: String =
        "Search the web and return title/url/snippet results."

    override val jsonSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        put("required", Json.parseToJsonElement("[\"query\"]"))
        put(
            "properties",
            Json.parseToJsonElement(
                """
                {
                  "query":{"type":"string","description":"Search query"},
                  "count":{"type":"integer","minimum":1,"maximum":10}
                }
                """.trimIndent()
            )
        )
    }

    override suspend fun run(argumentsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val args = Json.decodeFromString<Args>(argumentsJson)
        val query = args.query.trim()
        if (query.isBlank()) {
            return@withContext error("web_search failed: query is empty")
        }
        val count = (args.count ?: DEFAULT_COUNT).coerceIn(1, 10)
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://duckduckgo.com/html/?q=$encoded"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .get()
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@use error("web_search failed: HTTP ${response.code}")
                }
                val items = parseDuckResults(body, count)
                if (items.isEmpty()) {
                    return@use ok("No results for: $query")
                }
                val content = buildString {
                    appendLine("Results for: $query")
                    appendLine()
                    items.forEachIndexed { index, item ->
                        appendLine("${index + 1}. ${item.title}")
                        appendLine("   ${item.url}")
                        if (item.snippet.isNotBlank()) {
                            appendLine("   ${item.snippet}")
                        }
                    }
                }.trimEnd()
                ToolResult(
                    toolCallId = "",
                    content = content,
                    isError = false,
                    metadata = buildJsonObject {
                        put("source", "duckduckgo")
                        put("count", items.size)
                    }
                )
            }
        }.getOrElse { t ->
            error("web_search failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun parseDuckResults(html: String, limit: Int): List<SearchItem> {
        val regex = Regex(
            """<a[^>]*class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>([\s\S]*?)(?=<a[^>]*class="result__a"|$)""",
            setOf(RegexOption.IGNORE_CASE)
        )
        val snippetRegex = Regex(
            """<a[^>]*class="result__snippet"[^>]*>(.*?)</a>|<div[^>]*class="result__snippet"[^>]*>(.*?)</div>""",
            setOf(RegexOption.IGNORE_CASE)
        )
        val results = mutableListOf<SearchItem>()
        for (m in regex.findAll(html)) {
            if (results.size >= limit) break
            val rawUrl = m.groupValues.getOrNull(1).orEmpty()
            val title = normalize(stripTags(m.groupValues.getOrNull(2).orEmpty()))
            if (title.isBlank()) continue
            val section = m.groupValues.getOrNull(3).orEmpty()
            val snippetMatch = snippetRegex.find(section)
            val snippet = normalize(
                stripTags(
                    snippetMatch?.groupValues?.getOrNull(1).orEmpty().ifBlank {
                        snippetMatch?.groupValues?.getOrNull(2).orEmpty()
                    }
                )
            )
            val finalUrl = normalizeDuckResultUrl(rawUrl)
            if (finalUrl.isBlank()) continue
            results += SearchItem(title = title, url = finalUrl, snippet = snippet)
        }
        return results
    }

    private fun normalizeDuckResultUrl(rawUrl: String): String {
        if (rawUrl.isBlank()) return ""
        val decodedRaw = htmlUnescape(rawUrl)
        val url = if (decodedRaw.startsWith("//")) "https:$decodedRaw" else decodedRaw
        val parsed = url.toHttpUrlOrNull() ?: return url
        val uddg = parsed.queryParameter("uddg")
        return if (!uddg.isNullOrBlank()) {
            runCatching { URLDecoder.decode(uddg, "UTF-8") }.getOrDefault(uddg)
        } else {
            url
        }
    }

    @Serializable
    private data class Args(
        val query: String,
        val count: Int? = null
    )
}

private class WebFetchTool(
    private val client: OkHttpClient
) : Tool {
    override val name: String = "web_fetch"
    override val description: String =
        "Fetch URL and extract readable content (text/markdown)."

    override val jsonSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        put("required", Json.parseToJsonElement("[\"url\"]"))
        put(
            "properties",
            Json.parseToJsonElement(
                """
                {
                  "url":{"type":"string","description":"URL to fetch"},
                  "extractMode":{"type":"string","enum":["markdown","text"]},
                  "maxChars":{"type":"integer","minimum":100}
                }
                """.trimIndent()
            )
        )
    }

    override suspend fun run(argumentsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val args = Json.decodeFromString<Args>(argumentsJson)
        val url = args.url.trim()
        val parsed = url.toHttpUrlOrNull()
        if (parsed == null || (parsed.scheme.lowercase(Locale.US) !in setOf("http", "https"))) {
            return@withContext error("web_fetch failed: only http/https URL is allowed")
        }
        val extractMode = (args.extractMode ?: "markdown").lowercase(Locale.US)
        if (extractMode != "markdown" && extractMode != "text") {
            return@withContext error("web_fetch failed: extractMode must be markdown or text")
        }
        val maxChars = (args.maxChars ?: DEFAULT_MAX_FETCH_CHARS).coerceIn(100, MAX_FETCH_CHARS)

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@use error("web_fetch failed: HTTP ${response.code}")
                }
                val ctype = response.header("Content-Type").orEmpty()
                val finalUrl = response.request.url.toString()
                val extracted = normalize(
                    when {
                    ctype.contains("application/json", ignoreCase = true) -> prettyJsonOrRaw(body)
                    ctype.contains("text/html", ignoreCase = true) || looksLikeHtml(body) -> {
                        val title = extractTitle(body)
                        val content = if (extractMode == "markdown") htmlToMarkdown(body) else htmlToText(body)
                        if (title.isBlank()) content else "# $title\n\n$content"
                    }

                    else -> body
                    }
                )

                val truncated = extracted.length > maxChars
                val text = if (truncated) extracted.take(maxChars) + "\n...[truncated]" else extracted
                ToolResult(
                    toolCallId = "",
                    content = buildString {
                        appendLine("url=$url")
                        appendLine("finalUrl=$finalUrl")
                        appendLine("status=${response.code}")
                        appendLine("extractMode=$extractMode")
                        appendLine("truncated=$truncated")
                        appendLine("length=${min(extracted.length, maxChars)}")
                        appendLine()
                        appendLine(text.ifBlank { "(empty)" })
                    }.trimEnd(),
                    isError = false,
                    metadata = buildJsonObject {
                        put("status", response.code)
                        put("final_url", finalUrl)
                        put("extract_mode", extractMode)
                        put("truncated", truncated)
                    }
                )
            }
        }.getOrElse { t ->
            error("web_fetch failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun prettyJsonOrRaw(raw: String): String {
        return runCatching {
            Json.parseToJsonElement(raw).toString()
        }.getOrElse { raw }
    }

    private fun looksLikeHtml(text: String): Boolean {
        val head = text.trimStart().take(200).lowercase(Locale.US)
        return head.startsWith("<!doctype html") || head.startsWith("<html")
    }

    private fun extractTitle(html: String): String {
        val m = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()
        return normalize(stripTags(m))
    }

    private fun htmlToText(html: String): String {
        val noScript = html
            .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
        return normalize(stripTags(noScript))
    }

    private fun htmlToMarkdown(html: String): String {
        var text = html
        text = Regex("<a\\s+[^>]*href=[\"']([^\"']+)[\"'][^>]*>([\\s\\S]*?)</a>", RegexOption.IGNORE_CASE)
            .replace(text) { mr ->
                val href = mr.groupValues.getOrElse(1) { "" }
                val title = normalize(stripTags(mr.groupValues.getOrElse(2) { "" }))
                if (title.isBlank()) "" else "[$title]($href)"
            }
        text = Regex("<h([1-6])[^>]*>([\\s\\S]*?)</h\\1>", RegexOption.IGNORE_CASE)
            .replace(text) { mr ->
                val level = mr.groupValues.getOrElse(1) { "1" }.toIntOrNull()?.coerceIn(1, 6) ?: 1
                val title = normalize(stripTags(mr.groupValues.getOrElse(2) { "" }))
                "\n${"#".repeat(level)} $title\n"
            }
        text = Regex("<li[^>]*>([\\s\\S]*?)</li>", RegexOption.IGNORE_CASE)
            .replace(text) { mr ->
                "\n- ${normalize(stripTags(mr.groupValues.getOrElse(1) { "" }))}"
            }
        text = Regex("</(p|div|section|article)>", RegexOption.IGNORE_CASE).replace(text, "\n\n")
        text = Regex("<(br|hr)\\s*/?>", RegexOption.IGNORE_CASE).replace(text, "\n")
        return normalize(stripTags(text))
    }

    @Serializable
    private data class Args(
        val url: String,
        val extractMode: String? = null,
        val maxChars: Int? = null
    )
}

private data class SearchItem(
    val title: String,
    val url: String,
    val snippet: String
)

private fun stripTags(text: String): String {
    return text
        .replace(Regex("<[^>]+>"), " ")
        .let(::htmlUnescape)
}

private fun normalize(text: String): String {
    return text
        .replace(Regex("[ \t]+"), " ")
        .replace("\r", "\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

private fun htmlUnescape(text: String): String {
    return text
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
}

private fun ok(content: String): ToolResult {
    return ToolResult(toolCallId = "", content = content, isError = false)
}

private fun error(content: String): ToolResult {
    return ToolResult(toolCallId = "", content = content, isError = true)
}

private const val USER_AGENT = "palmclaw/1.0 (+android)"
private const val DEFAULT_COUNT = 5
private const val DEFAULT_MAX_FETCH_CHARS = 50_000
private const val MAX_FETCH_CHARS = 200_000
