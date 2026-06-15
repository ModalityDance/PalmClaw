package com.palmclaw.skills

import com.palmclaw.attachments.AttachmentTooLargeException
import com.palmclaw.attachments.BoundedStreamCopy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale

private const val DEFAULT_MAX_SKILL_DOWNLOAD_BYTES_VALUE: Long = 100L * 1024L * 1024L

class ClawHubClient(
    private val client: OkHttpClient,
    private val maxDownloadBytes: Long = DEFAULT_MAX_SKILL_DOWNLOAD_BYTES_VALUE
) {
    suspend fun fetchBrowseSections(): Pair<List<ClawHubSkillCard>, List<ClawHubSkillCard>> = withContext(Dispatchers.IO) {
        val skills = fetchRegistrySkills()
        val staffPicks = skills
            .filter { it.featured }
            .sortedByDescending { it.downloadMetric }
            .take(8)
            .map { it.toCard() }
            .ifEmpty { skills.sortedByDescending { it.downloadMetric }.take(6).map { it.toCard() } }
        val popular = skills
            .sortedByDescending { it.downloadMetric }
            .take(12)
            .map { it.toCard() }
        staffPicks to popular
    }

    suspend fun searchSkills(query: String): List<ClawHubSkillCard> = withContext(Dispatchers.IO) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return@withContext emptyList()
        val remoteResults = runCatching {
            fetchRegistrySearchSkills(normalizedQuery)
        }.getOrDefault(emptyList())
        if (remoteResults.isNotEmpty()) {
            return@withContext remoteResults.take(24).map { it.toCard() }
        }
        val tokenGroups = buildSearchTokenGroups(normalizedQuery)
        fetchRegistrySkills()
            .mapNotNull { skill ->
                val score = skill.searchScore(tokenGroups)
                if (score > 0.0) skill to score else null
            }
            .sortedWith(
                compareByDescending<Pair<RegistrySkillRecord, Double>> { it.second }
                    .thenByDescending { it.first.downloadMetric }
                    .thenBy { it.first.title.lowercase(Locale.US) }
            )
            .take(24)
            .map { it.first.toCard() }
    }

    private fun buildSearchTokenGroups(query: String): List<List<String>> {
        return query.lowercase(Locale.US)
            .split(Regex("\\s+"))
            .mapNotNull { token ->
                val trimmed = token.trim()
                if (trimmed.isBlank()) {
                    null
                } else {
                    val aliases = SEARCH_ALIASES
                        .filterKeys { key -> trimmed.contains(key) }
                        .values
                        .flatten()
                    (listOf(trimmed) + aliases).distinct()
                }
            }
    }

    suspend fun fetchSkillDetail(detailUrl: String): ClawHubSkillDetail = withContext(Dispatchers.IO) {
        val normalizedUrl = normalizeDetailUrl(detailUrl)
        val html = fetchText(normalizedUrl)
        parseDetailHtml(normalizedUrl, html)
    }

    suspend fun downloadSkillZip(downloadUrl: String, targetFile: File): File = withContext(Dispatchers.IO) {
        if (!ClawHubDownloadPolicy.isAllowed(downloadUrl)) {
            throw IOException("ClawHub download URL must use HTTPS.")
        }
        targetFile.parentFile?.mkdirs()
        val request = Request.Builder()
            .url(downloadUrl)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("ClawHub download failed: HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("ClawHub download failed: empty body")
            if (body.contentLength() > maxDownloadBytes) {
                throw AttachmentTooLargeException(maxDownloadBytes)
            }
            targetFile.outputStream().use { output ->
                body.byteStream().use { input ->
                    BoundedStreamCopy.copy(
                        input = input,
                        output = output,
                        maxBytes = maxDownloadBytes
                    )
                }
            }
        }
        targetFile
    }

    private fun fetchRegistrySkills(): List<RegistrySkillRecord> {
        val request = Request.Builder()
            .url("$REGISTRY_API_BASE/skills")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("ClawHub browse failed: HTTP ${response.code}")
            }
            val payload = response.body?.string().orEmpty()
            return parseRegistryPayload(payload)
        }
    }

    private fun fetchRegistrySearchSkills(query: String): List<RegistrySkillRecord> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val request = Request.Builder()
            .url("$REGISTRY_API_BASE/search?q=$encodedQuery&limit=24")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("ClawHub search failed: HTTP ${response.code}")
            }
            val payload = response.body?.string().orEmpty()
            return parseRegistryPayload(payload)
        }
    }

    internal fun parseRegistryPayload(payload: String): List<RegistrySkillRecord> {
        val root = runCatching { registryJson.parseToJsonElement(payload) }.getOrNull()
            ?: return emptyList()
        val rootObject = root as? JsonObject
        val items = rootObject?.arrayValue("skills")
            ?: rootObject?.arrayValue("items")
            ?: rootObject?.arrayValue("results")
            ?: rootObject?.arrayValue("data")
            ?: root as? JsonArray
            ?: return emptyList()

        return buildList {
            for (rawElement in items) {
                val rawItem = rawElement as? JsonObject ?: continue
                val item = rawItem.objectValue("skill")
                    ?: rawItem.objectValue("item")
                    ?: rawItem.objectValue("record")
                    ?: rawItem
                val slug = item.stringValue("slug").trim()
                    .ifBlank { item.stringValue("name").trim() }
                    .ifBlank { rawItem.stringValue("slug").trim() }
                if (slug.isBlank()) continue
                val owner = item.stringValue("owner").trim().ifBlank {
                    item.stringValue("ownerUsername").trim().ifBlank {
                        item.stringValue("authorHandle").trim().trimStart('@').ifBlank {
                            rawItem.stringValue("owner").trim()
                        }
                    }
                }
                val title = item.stringValue("title").trim().ifBlank {
                    item.stringValue("displayName").trim().ifBlank {
                        item.stringValue("name").trim().ifBlank {
                            rawItem.stringValue("title").trim().ifBlank { slug }
                        }
                    }
                }
                val summary = item.stringValue("summary").trim().ifBlank {
                    item.stringValue("description").trim().ifBlank {
                        rawItem.stringValue("summary").trim().ifBlank {
                            rawItem.stringValue("description").trim()
                        }
                    }
                }
                val author = item.stringValue("author").trim().ifBlank {
                    item.stringValue("authorName").trim().ifBlank {
                        owner.ifBlank { "Unknown" }
                    }
                }
                val version = item.stringValue("version").trim()
                val license = item.stringValue("license").trim()
                val downloads = item.valueText("downloads")
                    .ifBlank { item.valueText("allTimeInstalls") }
                    .ifBlank { rawItem.valueText("downloads") }
                val featured = item.booleanValue("staffPick") ||
                    item.booleanValue("featured") ||
                    item.booleanValue("isStaffPick") ||
                    item.booleanValue("curated")
                val detailUrl = item.stringValue("url").trim().ifBlank {
                    when {
                        owner.isNotBlank() -> "$CLAW_HUB_BASE_URL/$owner/$slug"
                        else -> "$CLAW_HUB_BASE_URL/skills/$slug"
                    }
                }
                add(
                    RegistrySkillRecord(
                        slug = slug,
                        title = title,
                        summary = summary,
                        author = author,
                        version = version,
                        license = license,
                        downloads = downloads,
                        detailUrl = detailUrl,
                        featured = featured
                    )
                )
            }
        }
    }

    private fun JsonObject.arrayValue(key: String): JsonArray? = this[key] as? JsonArray

    private fun JsonObject.objectValue(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.stringValue(key: String): String {
        return (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    }

    private fun JsonObject.booleanValue(key: String): Boolean {
        return (this[key] as? JsonPrimitive)?.booleanOrNull ?: false
    }

    private fun JsonObject.valueText(key: String): String {
        return when (val value = this[key]) {
            is JsonPrimitive -> value.contentOrNull.orEmpty()
            is JsonElement -> value.toString()
            else -> ""
        }
    }

    internal fun parseDetailHtml(detailUrl: String, html: String): ClawHubSkillDetail {
        val document = Jsoup.parse(html, detailUrl)
        val text = document.text().replace(Regex("\\s+"), " ").trim()
        val title = document.selectFirst("h1")?.text()?.trim()
            ?.ifBlank { null }
            ?: extractBetween(text, "# ", " v")
            ?: extractBetween(text, "ClawHub ", " v")
            ?: "Skill"
        val downloadUrl = extractDownloadUrl(document, text, html)
        val author = document.select("a[href]").firstOrNull { anchor ->
            anchor.text().trim().startsWith("@")
        }?.text()?.trim()?.trimStart('@').orEmpty().ifBlank {
            extractAfter(text, "by", "MIT-")
                ?.substringAfter('@')
                ?.trim()
                .orEmpty()
        }
        val version = extractRegex(text, Regex("\\bv\\s*([0-9][A-Za-z0-9._-]*)")) ?: ""
        val summary = document.select("h1 + p, meta[name=description], meta[property=og:description]")
            .firstOrNull()
            ?.let { element ->
                element.attr("content").ifBlank { element.text() }.trim()
            }
            ?.ifBlank { null }
            ?: title
        val license = extractRegex(text, Regex("\\b(MIT-0|MIT|Apache-2\\.0|GPL-[0-9.]+|BSD-[0-9-]+)\\b")) ?: ""
        val downloads = extractRegex(text, Regex("([0-9.]+k?|[0-9]+)\\s*·\\s*[0-9]+\\s*current", RegexOption.IGNORE_CASE))
            ?: ""
        val signals = mutableListOf<String>()
        extractAfter(text, "VirusTotal", "OpenClaw")?.takeIf { it.isNotBlank() }?.let {
            signals += "VirusTotal: ${it.take(48).trim()}"
        }
        extractAfter(text, "OpenClaw", "Current version")?.takeIf { it.isNotBlank() }?.let {
            signals += "OpenClaw: ${it.take(64).trim()}"
        }
        val runtimeRequirements = mutableListOf<String>()
        extractAfter(text, "Runtime requirements", "Files")?.takeIf { it.isNotBlank() }?.let {
            runtimeRequirements += it.split(' ')
                .map { token -> token.trim() }
                .filter { token -> token.isNotBlank() && token.length < 48 }
        }
        return ClawHubSkillDetail(
            slug = deriveSlugFromUrl(detailUrl),
            title = title,
            summary = summary.ifBlank { title },
            author = author.ifBlank { "Unknown" },
            version = version,
            license = license,
            downloads = downloads,
            detailUrl = detailUrl,
            downloadUrl = downloadUrl,
            securitySignals = signals.distinct(),
            runtimeRequirements = runtimeRequirements.distinct()
        )
    }

    private fun fetchText(url: String): String {
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("ClawHub request failed: HTTP ${response.code}")
            }
            return response.body?.string().orEmpty()
        }
    }

    private fun extractDownloadUrl(document: org.jsoup.nodes.Document, text: String, html: String): String {
        val anchors = document.select("a[href]")
        return anchors.firstOrNull { anchor ->
            anchor.text().trim().equals("Download zip", ignoreCase = true)
        }?.absUrl("href").orEmpty().ifBlank {
            anchors.firstOrNull { anchor ->
                val label = anchor.text().lowercase(Locale.US)
                val href = anchor.attr("href").lowercase(Locale.US)
                (label.contains("download") || label.contains("install")) &&
                    (href.endsWith(".zip") || href.contains("/download") || href.contains("download"))
            }?.absUrl("href").orEmpty()
        }.ifBlank {
            anchors.firstOrNull { anchor ->
                anchor.attr("href").lowercase(Locale.US).substringBefore('?').endsWith(".zip")
            }?.absUrl("href").orEmpty()
        }.ifBlank {
            extractRegex(text, Regex("(https?://[^\\s\"']+\\.zip(?:\\?[^\\s\"']*)?)")).orEmpty()
        }.ifBlank {
            extractRegex(
                html,
                Regex(""""(?:downloadUrl|download_url|zipUrl|zip_url)"\s*:\s*"([^"]+)"""")
            )?.replace("\\/", "/").orEmpty()
        }.ifBlank {
            extractRegex(html, Regex("""(https?://[^"'\s<>]+\.zip(?:\?[^"'\s<>]*)?)""")).orEmpty()
        }
    }

    private fun normalizeDetailUrl(detailUrl: String): String {
        val trimmed = detailUrl.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }
        return if (trimmed.startsWith("/")) {
            CLAW_HUB_BASE_URL + trimmed
        } else {
            "$CLAW_HUB_BASE_URL/$trimmed"
        }
    }

    private fun deriveSlugFromUrl(url: String): String {
        return url.substringAfterLast('/').substringBefore('?').ifBlank { "skill" }
    }

    private fun extractBetween(text: String, start: String, end: String): String? {
        val startIndex = text.indexOf(start)
        if (startIndex < 0) return null
        val contentStart = startIndex + start.length
        val endIndex = text.indexOf(end, contentStart)
        if (endIndex <= contentStart) return null
        return text.substring(contentStart, endIndex).trim().ifBlank { null }
    }

    private fun extractAfter(text: String, marker: String, endMarker: String): String? {
        val startIndex = if (marker.isBlank()) 0 else text.indexOf(marker).takeIf { it >= 0 }?.plus(marker.length) ?: return null
        val endIndex = if (endMarker.isBlank()) text.length else text.indexOf(endMarker, startIndex).takeIf { it > startIndex } ?: text.length
        return text.substring(startIndex, endIndex).trim().ifBlank { null }
    }

    private fun extractRegex(text: String, pattern: Regex): String? {
        return pattern.find(text)?.groupValues?.getOrNull(1)?.trim()?.ifBlank { null }
    }

    internal data class RegistrySkillRecord(
        val slug: String,
        val title: String,
        val summary: String,
        val author: String,
        val version: String,
        val license: String,
        val downloads: String,
        val detailUrl: String,
        val featured: Boolean
    ) {
        val downloadMetric: Double
            get() = downloads.lowercase(Locale.US)
                .removeSuffix("current")
                .trim()
                .let { value ->
                    when {
                        value.endsWith("k") -> value.removeSuffix("k").toDoubleOrNull()?.times(1000.0) ?: 0.0
                        else -> value.toDoubleOrNull() ?: 0.0
                    }
                }

        fun searchScore(tokenGroups: List<List<String>>): Double {
            if (tokenGroups.isEmpty()) return 0.0
            val titleText = title.lowercase(Locale.US)
            val slugText = slug.lowercase(Locale.US)
            val authorText = author.lowercase(Locale.US)
            val summaryText = summary.lowercase(Locale.US)
            val searchableText = "$titleText $slugText $authorText $summaryText"
            val words = searchableText.split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }
            val scores = tokenGroups.map { group ->
                group.maxOf { token -> tokenSearchScore(token, titleText, slugText, authorText, searchableText, words) }
            }
            if (scores.any { it <= 0.0 }) return 0.0
            return scores.sum()
        }

        private fun tokenSearchScore(
            token: String,
            titleText: String,
            slugText: String,
            authorText: String,
            searchableText: String,
            words: List<String>
        ): Double {
            return when {
                titleText == token || slugText == token -> 8.0
                titleText.startsWith(token) || slugText.startsWith(token) -> 6.0
                titleText.contains(token) || slugText.contains(token) -> 4.0
                authorText.contains(token) -> 2.0
                searchableText.contains(token) -> 1.0
                token.length >= 4 && words.any { word -> isFuzzyMatch(token, word) } -> 0.65
                else -> 0.0
            }
        }

        private fun isFuzzyMatch(token: String, word: String): Boolean {
            val lengthDelta = kotlin.math.abs(token.length - word.length)
            if (lengthDelta > 2) return false
            val maxDistance = if (token.length <= 5) 1 else 2
            return levenshteinDistanceAtMost(token, word, maxDistance) <= maxDistance
        }

        private fun levenshteinDistanceAtMost(left: String, right: String, maxDistance: Int): Int {
            var previous = IntArray(right.length + 1) { it }
            var current = IntArray(right.length + 1)
            for (leftIndex in left.indices) {
                current[0] = leftIndex + 1
                var rowMin = current[0]
                for (rightIndex in right.indices) {
                    val substitutionCost = if (left[leftIndex] == right[rightIndex]) 0 else 1
                    current[rightIndex + 1] = minOf(
                        current[rightIndex] + 1,
                        previous[rightIndex + 1] + 1,
                        previous[rightIndex] + substitutionCost
                    )
                    rowMin = minOf(rowMin, current[rightIndex + 1])
                }
                if (rowMin > maxDistance) return maxDistance + 1
                val nextPrevious = previous
                previous = current
                current = nextPrevious
            }
            return previous[right.length]
        }

        fun toCard(): ClawHubSkillCard {
            return ClawHubSkillCard(
                slug = slug,
                title = title,
                summary = summary,
                author = author,
                version = version,
                license = license,
                downloads = downloads,
                detailUrl = detailUrl
            )
        }
    }

    companion object {
        const val CLAW_HUB_BASE_URL = "https://clawhub.ai"
        const val REGISTRY_API_BASE = "https://wry-manatee-359.convex.site/api/v1"
        const val DEFAULT_MAX_SKILL_DOWNLOAD_BYTES: Long = DEFAULT_MAX_SKILL_DOWNLOAD_BYTES_VALUE
        private val registryJson = Json {
            ignoreUnknownKeys = true
        }
        private val SEARCH_ALIASES = mapOf(
            "天气" to listOf("weather", "forecast"),
            "搜索" to listOf("search", "web"),
            "网页" to listOf("web", "browser"),
            "浏览器" to listOf("browser", "web"),
            "翻译" to listOf("translate", "translation"),
            "邮件" to listOf("email", "mail"),
            "邮箱" to listOf("email", "mail"),
            "日历" to listOf("calendar"),
            "表格" to listOf("sheet", "spreadsheet", "excel"),
            "文档" to listOf("document", "docs"),
            "图片" to listOf("image", "photo"),
            "视频" to listOf("video"),
            "代码" to listOf("code", "coding"),
            "开发" to listOf("development", "dev"),
            "总结" to listOf("summary", "summarize"),
            "摘要" to listOf("summary", "summarize"),
            "笔记" to listOf("note", "notes"),
            "任务" to listOf("task", "todo"),
            "微信" to listOf("wechat", "wecom"),
            "飞书" to listOf("feishu", "lark"),
            "企业微信" to listOf("wecom", "wechat"),
            "数据库" to listOf("database", "sql"),
            "新闻" to listOf("news"),
            "金融" to listOf("finance"),
            "股票" to listOf("stock", "finance")
        )
    }
}

internal object ClawHubDownloadPolicy {
    fun isAllowed(url: String): Boolean {
        val parsed = url.trim().toHttpUrlOrNull() ?: return false
        return parsed.scheme.equals("https", ignoreCase = true)
    }
}
