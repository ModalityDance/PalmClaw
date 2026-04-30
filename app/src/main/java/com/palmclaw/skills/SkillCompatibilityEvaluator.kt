package com.palmclaw.skills

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class SkillCompatibilityEvaluator {
    fun evaluate(
        hasSkillFile: Boolean,
        frontmatter: Map<String, String>,
        metadataJson: JSONObject,
        relativePaths: List<String>
    ): SkillCompatibilityResult {
        if (!hasSkillFile) {
            return SkillCompatibilityResult(
                status = SkillCompatibilityStatus.Invalid,
                reasons = listOf("SKILL.md is missing.")
            )
        }

        val reasons = mutableListOf<String>()
        val requires = metadataJson.optJSONObject("requires")
        val palmclawMetadata = metadataJson.optJSONObject("palmclaw") ?: metadataJson

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

    private fun resolvePlatformsAllowed(palmclawMetadata: JSONObject?): Boolean? {
        val platforms = palmclawMetadata?.opt("platforms") ?: return null
        val values = when (platforms) {
            is JSONArray -> buildList {
                for (index in 0 until platforms.length()) {
                    add(platforms.optString(index).trim().lowercase(Locale.US))
                }
            }

            is String -> platforms.split(',', ' ')
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

    private fun JSONObject?.optStringArray(key: String): List<String> {
        val array = this?.optJSONArray(key) ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val value = array.optString(index).trim()
                if (value.isNotBlank()) {
                    add(value)
                }
            }
        }
    }
}
