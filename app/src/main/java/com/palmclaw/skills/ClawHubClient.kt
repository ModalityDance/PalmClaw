package com.palmclaw.skills

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.io.IOException
import java.util.Locale

class ClawHubClient(
    private val client: OkHttpClient
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

    suspend fun fetchSkillDetail(detailUrl: String): ClawHubSkillDetail = withContext(Dispatchers.IO) {
        val normalizedUrl = normalizeDetailUrl(detailUrl)
        val html = fetchText(normalizedUrl)
        parseDetailHtml(normalizedUrl, html)
    }

    suspend fun downloadSkillZip(downloadUrl: String, targetFile: File): File = withContext(Dispatchers.IO) {
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
            targetFile.outputStream().use { output ->
                body.byteStream().copyTo(output)
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

    internal fun parseRegistryPayload(payload: String): List<RegistrySkillRecord> {
        val root = runCatching { JSONObject(payload) }.getOrNull()
        val items = when {
            root == null -> JSONArray(payload)
            root.has("skills") -> root.optJSONArray("skills")
            root.has("items") -> root.optJSONArray("items")
            root.has("results") -> root.optJSONArray("results")
            root.has("data") -> root.optJSONArray("data")
            else -> null
        } ?: JSONArray(payload)

        return buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val slug = item.optString("slug").trim()
                    .ifBlank { item.optString("name").trim() }
                if (slug.isBlank()) continue
                val owner = item.optString("owner").trim().ifBlank {
                    item.optString("ownerUsername").trim().ifBlank {
                        item.optString("authorHandle").trim().trimStart('@')
                    }
                }
                val title = item.optString("title").trim().ifBlank {
                    item.optString("displayName").trim().ifBlank {
                        item.optString("name").trim().ifBlank { slug }
                    }
                }
                val summary = item.optString("summary").trim().ifBlank {
                    item.optString("description").trim()
                }
                val author = item.optString("author").trim().ifBlank {
                    item.optString("authorName").trim().ifBlank {
                        owner.ifBlank { "Unknown" }
                    }
                }
                val version = item.optString("version").trim()
                val license = item.optString("license").trim()
                val downloads = item.opt("downloads")?.toString().orEmpty()
                    .ifBlank { item.opt("allTimeInstalls")?.toString().orEmpty() }
                val featured = item.optBoolean("staffPick") ||
                    item.optBoolean("featured") ||
                    item.optBoolean("isStaffPick") ||
                    item.optBoolean("curated")
                val detailUrl = item.optString("url").trim().ifBlank {
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

    internal fun parseDetailHtml(detailUrl: String, html: String): ClawHubSkillDetail {
        val document = Jsoup.parse(html, detailUrl)
        val text = document.text().replace(Regex("\\s+"), " ").trim()
        val title = document.selectFirst("h1")?.text()?.trim()
            ?.ifBlank { null }
            ?: extractBetween(text, "# ", " v")
            ?: extractBetween(text, "ClawHub ", " v")
            ?: "Skill"
        val downloadUrl = document.select("a[href]").firstOrNull {
            it.text().trim().equals("Download zip", ignoreCase = true)
        }?.absUrl("href").orEmpty().ifBlank {
            throw IOException("ClawHub detail missing download link.")
        }
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
    }
}
