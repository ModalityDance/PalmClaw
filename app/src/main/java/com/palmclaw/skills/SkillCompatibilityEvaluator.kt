package com.palmclaw.skills

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.json.JSONObject
import java.util.Locale

class SkillCompatibilityEvaluator {
    fun evaluate(
        hasSkillFile: Boolean,
        frontmatter: Map<String, String>,
        metadataJson: JSONObject,
        relativePaths: List<String>
    ): SkillCompatibilityResult {
        return evaluate(
            hasSkillFile = hasSkillFile,
            frontmatter = frontmatter,
            metadataJson = metadataJson.toString(),
            relativePaths = relativePaths
        )
    }

    fun evaluate(
        hasSkillFile: Boolean,
        frontmatter: Map<String, String>,
        metadataJson: String?,
        relativePaths: List<String>
    ): SkillCompatibilityResult {
        if (!hasSkillFile) {
            return SkillCompatibilityResult(
                status = SkillCompatibilityStatus.Invalid,
                reasons = listOf("SKILL.md is missing.")
            )
        }

        val reasons = mutableListOf<String>()
        val metadata = parseMetadataJson(metadataJson)
        val requires = metadata.objectValue("requires")
        val palmclawMetadata = metadata.objectValue("palmclaw") ?: metadata

        val platformsAllowed = resolvePlatformsAllowed(palmclawMetadata)
        if (platformsAllowed == false) {
            reasons += "metadata.palmclaw.platforms does not include Android/mobile."
        }

        val requiredBins = requires.optStringArray("bins")
        if (requiredBins.isNotEmpty()) {
            reasons += "Requires external CLI tools: ${requiredBins.joinToString(", ")}."
        }

        val requiredEnv = requires.optStringArray("env")
        if (requiredEnv.isNotEmpty()) {
            reasons += "Requires environment variables: ${requiredEnv.joinToString(", ")}."
        }

        if (relativePaths.any { it.startsWith("scripts/", ignoreCase = true) }) {
            reasons += "Contains scripts/ automation files."
        }

        val desktopMarkers = setOf(
            "package.json",
            "requirements.txt",
            "cargo.toml",
            "go.mod"
        )
        relativePaths
            .firstOrNull { path -> desktopMarkers.any { marker -> path.equals(marker, ignoreCase = true) } }
            ?.let { markerPath ->
                reasons += "Contains desktop/runtime dependency file: $markerPath."
            }

        relativePaths
            .firstOrNull { path ->
                path.endsWith(".sh", ignoreCase = true) ||
                    path.endsWith(".ps1", ignoreCase = true) ||
                    path.endsWith(".bat", ignoreCase = true) ||
                    path.endsWith(".cmd", ignoreCase = true)
            }
            ?.let { scriptPath ->
                reasons += "Contains desktop shell script: $scriptPath."
            }

        if (reasons.isNotEmpty()) {
            return SkillCompatibilityResult(
                status = SkillCompatibilityStatus.DesktopRequired,
                reasons = reasons
            )
        }

        val documentationOnly = relativePaths.all { path ->
            path.equals("SKILL.md", ignoreCase = true) ||
                path.startsWith("references/", ignoreCase = true) ||
                path.startsWith("assets/", ignoreCase = true) ||
                path.endsWith(".md", ignoreCase = true) ||
                path.endsWith(".txt", ignoreCase = true) ||
                path.endsWith(".json", ignoreCase = true)
        }
        if (documentationOnly) {
            return SkillCompatibilityResult(
                status = SkillCompatibilityStatus.LikelyCompatible,
                reasons = listOf("Instruction-only skill with no obvious desktop/runtime dependencies.")
            )
        }

        if (frontmatter.isEmpty()) {
            return SkillCompatibilityResult(
                status = SkillCompatibilityStatus.Unknown,
                reasons = listOf("Frontmatter metadata is missing or incomplete.")
            )
        }

        return SkillCompatibilityResult(
            status = SkillCompatibilityStatus.Compatible,
            reasons = listOf("No mobile incompatibility signals were detected.")
        )
    }

    private fun parseMetadataJson(raw: String?): JsonObject {
        if (raw.isNullOrBlank()) return JsonObject(emptyMap())
        return runCatching { compatibilityJson.parseToJsonElement(raw) as? JsonObject }
            .getOrNull()
            ?: JsonObject(emptyMap())
    }

    private fun resolvePlatformsAllowed(palmclawMetadata: JsonObject?): Boolean? {
        val platforms = palmclawMetadata?.get("platforms") ?: return null
        val values = when (platforms) {
            is JsonArray -> platforms.mapNotNull { element ->
                element.stringValue().trim().lowercase(Locale.US).ifBlank { null }
            }

            is JsonPrimitive -> platforms.contentOrNull.orEmpty().split(',', ' ')
                .map { it.trim().lowercase(Locale.US) }
                .filter { it.isNotBlank() }

            else -> emptyList()
        }
        if (values.isEmpty()) return null
        return values.any {
            it == "all" ||
                it == "android" ||
                it == "mobile" ||
                it == "palmclaw-mobile"
        }
    }

    private fun JsonObject?.optStringArray(key: String): List<String> {
        val array = this?.get(key) as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            element.stringValue().trim().ifBlank { null }
        }
    }

    private fun JsonObject.objectValue(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonElement.stringValue(): String {
        return (this as? JsonPrimitive)?.contentOrNull.orEmpty()
    }

    private companion object {
        val compatibilityJson = Json {
            ignoreUnknownKeys = true
        }
    }
}
