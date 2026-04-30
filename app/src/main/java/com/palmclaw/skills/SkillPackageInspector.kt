package com.palmclaw.skills

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.Locale

class SkillPackageInspector(
    private val compatibilityEvaluator: SkillCompatibilityEvaluator = SkillCompatibilityEvaluator()
) {
    fun inspectDirectory(
        rootDir: File,
        source: SkillSource,
        enabled: Boolean,
        allowIncompatible: Boolean,
        manifest: InstalledSkillManifest? = null,
        assetPath: String? = null
    ): SkillCatalogEntry? {
        val skillFile = File(rootDir, "SKILL.md")
        if (!skillFile.exists()) return null

        val content = runCatching { skillFile.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        val frontmatter = parseFrontmatter(content)
        val metadataJson = parseMetadataJson(frontmatter["metadata"])
        val resolvedName = frontmatter["name"]?.trim()?.ifBlank { rootDir.name } ?: rootDir.name
        val displayName = resolvedName
        val description = frontmatter["description"].orEmpty().ifBlank { displayName }
        val always = frontmatter["always"]?.trim()?.equals("true", ignoreCase = true) == true ||
            metadataJson.optBoolean("always")
        val files = collectFiles(rootDir)
        val compatibility = compatibilityEvaluator.evaluate(
            hasSkillFile = true,
            frontmatter = frontmatter,
            metadataJson = metadataJson,
            relativePaths = files
                .mapNotNull { file ->
                    runCatching { file.relativeTo(rootDir).invariantSeparatorsPath }.getOrNull()
                }
                .filter { it.isNotBlank() }
        )
        val requirementsStatus = buildRequirementsStatus(metadataJson)

        return SkillCatalogEntry(
            name = resolvedName,
            displayName = displayName,
            description = description,
            path = assetPath ?: skillFile.absolutePath,
            source = source,
            enabled = enabled,
            allowIncompatible = allowIncompatible,
            always = always,
            compatibilityStatus = compatibility.status,
            compatibilityReasons = compatibility.reasons,
            requirementsStatus = requirementsStatus,
            files = files.map { file ->
                val relativePath = file.relativeTo(rootDir).invariantSeparatorsPath.ifBlank { "." }
                SkillFileEntry(
                    relativePath = relativePath,
                    isDirectory = file.isDirectory,
                    sizeBytes = if (file.isFile) file.length() else 0L,
                    previewText = if (file.isFile && isTextPreviewable(relativePath)) {
                        readPreview(file)
                    } else {
                        null
                    },
                    previewable = file.isFile && isTextPreviewable(relativePath)
                )
            },
            metadata = SkillMetadata(
                name = resolvedName,
                displayName = displayName,
                description = description,
                always = always,
                frontmatter = frontmatter,
                metadataJson = metadataJson
            ),
            manifest = manifest
        )
    }

    fun parseFrontmatter(content: String): Map<String, String> {
        if (!content.startsWith("---")) return emptyMap()
        val end = content.indexOf("\n---", startIndex = 3)
        if (end <= 0) return emptyMap()
        val frontmatter = content.substring(3, end).trim()
        val map = linkedMapOf<String, String>()
        var currentKey: String? = null
        val currentValueLines = mutableListOf<String>()

        fun flushCurrent() {
            val key = currentKey ?: return
            val combined = currentValueLines.joinToString("\n").trim()
            map[key] = if (combined.contains('\n')) combined else combined.trim('"', '\'')
            currentKey = null
            currentValueLines.clear()
        }

        frontmatter.lineSequence().forEach { rawLine ->
            val line = rawLine.rstrip()
            val keyMatch = Regex("^([A-Za-z0-9_.-]+):(.*)$").matchEntire(line)
            if (keyMatch != null && !rawLine.startsWith("  ") && !rawLine.startsWith("\t")) {
                flushCurrent()
                currentKey = keyMatch.groupValues[1].trim()
                val inlineValue = keyMatch.groupValues[2].trim()
                if (inlineValue.isNotBlank() && inlineValue != "|" && inlineValue != ">") {
                    currentValueLines += inlineValue
                    flushCurrent()
                }
                return@forEach
            }

            if (currentKey != null) {
                currentValueLines += rawLine.trimStart()
            }
        }
        flushCurrent()
        return map
    }

    fun stripFrontmatter(content: String): String {
        if (!content.startsWith("---")) return content
        val end = content.indexOf("\n---", startIndex = 3)
        return if (end > 0) content.substring(end + 4).trim() else content
    }

    fun parseMetadataJson(raw: String?): JSONObject {
        if (raw.isNullOrBlank()) return JSONObject()
        return try {
            val parsed = JSONObject(raw)
            when {
                parsed.has("palmclaw") -> {
                    val palmclaw = parsed.optJSONObject("palmclaw") ?: JSONObject()
                    if (parsed.has("requires") && !palmclaw.has("requires")) {
                        palmclaw.put("requires", parsed.optJSONObject("requires"))
                    }
                    if (parsed.has("always") && !palmclaw.has("always")) {
                        palmclaw.put("always", parsed.optBoolean("always"))
                    }
                    palmclaw
                }

                parsed.length() == 1 -> {
                    val firstKey = parsed.keys().asSequence().firstOrNull()
                    firstKey?.let { key ->
                        parsed.optJSONObject(key) ?: parsed
                    } ?: JSONObject()
                }

                else -> parsed
            }
        } catch (t: Throwable) {
            Log.w("SkillPackageInspector", "Failed to parse skill metadata JSON: ${t.message}")
            JSONObject()
        }
    }

    fun readPreview(file: File, maxChars: Int = 4_000): String? {
        return runCatching {
            file.readText(Charsets.UTF_8)
                .replace("\r\n", "\n")
                .take(maxChars)
        }.getOrNull()
    }

    private fun collectFiles(rootDir: File): List<File> {
        return buildList {
            add(rootDir)
            rootDir.walkTopDown()
                .filter { it != rootDir }
                .filterNot { it.name.startsWith('.') && it.name != "SKILL.md" }
                .sortedBy { it.relativeTo(rootDir).invariantSeparatorsPath.lowercase(Locale.US) }
                .forEach { add(it) }
        }
    }

    private fun buildRequirementsStatus(metadataJson: JSONObject): SkillRequirementsStatus {
        val requires = metadataJson.optJSONObject("requires") ?: return SkillRequirementsStatus(true, "No declared runtime requirements.")
        val parts = mutableListOf<String>()
        requires.optJSONArray("bins")?.let { bins ->
            val values = buildList {
                for (index in 0 until bins.length()) {
                    val value = bins.optString(index).trim()
                    if (value.isNotBlank()) add(value)
                }
            }
            if (values.isNotEmpty()) {
                parts += "CLI: ${values.joinToString(", ")}"
            }
        }
        requires.optJSONArray("env")?.let { env ->
            val values = buildList {
                for (index in 0 until env.length()) {
                    val value = env.optString(index).trim()
                    if (value.isNotBlank()) add(value)
                }
            }
            if (values.isNotEmpty()) {
                parts += "ENV: ${values.joinToString(", ")}"
            }
        }
        return if (parts.isEmpty()) {
            SkillRequirementsStatus(true, "No declared runtime requirements.")
        } else {
            SkillRequirementsStatus(false, parts.joinToString(" | "))
        }
    }

    private fun isTextPreviewable(relativePath: String): Boolean {
        return relativePath.endsWith(".md", ignoreCase = true) ||
            relativePath.endsWith(".txt", ignoreCase = true) ||
            relativePath.endsWith(".json", ignoreCase = true) ||
            relativePath.endsWith(".yaml", ignoreCase = true) ||
            relativePath.endsWith(".yml", ignoreCase = true) ||
            relativePath.endsWith(".xml", ignoreCase = true) ||
            relativePath.endsWith(".kt", ignoreCase = true) ||
            relativePath.endsWith(".py", ignoreCase = true) ||
            relativePath.endsWith(".js", ignoreCase = true) ||
            relativePath.endsWith(".ts", ignoreCase = true) ||
            relativePath.endsWith(".sh", ignoreCase = true) ||
            relativePath.endsWith(".ps1", ignoreCase = true) ||
            relativePath.equals("SKILL.md", ignoreCase = true)
    }

    private fun String.rstrip(): String = replace(Regex("\\s+$"), "")
}
