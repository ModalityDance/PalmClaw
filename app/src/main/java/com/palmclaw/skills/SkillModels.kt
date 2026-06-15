package com.palmclaw.skills

import kotlinx.serialization.Serializable
import org.json.JSONObject

enum class SkillSource(val wireValue: String) {
    Builtin("builtin"),
    Local("local"),
    ClawHub("clawhub");

    companion object {
        fun fromRaw(raw: String?): SkillSource {
            val normalized = raw?.trim().orEmpty()
            return entries.firstOrNull { it.wireValue.equals(normalized, ignoreCase = true) }
                ?: Local
        }
    }
}

enum class SkillCompatibilityStatus(val wireValue: String) {
    Compatible("compatible"),
    LikelyCompatible("likely_compatible"),
    Unknown("unknown"),
    DesktopRequired("desktop_required"),
    Invalid("invalid");

    companion object {
        fun fromRaw(raw: String?): SkillCompatibilityStatus {
            val normalized = raw?.trim().orEmpty()
            return entries.firstOrNull { it.wireValue.equals(normalized, ignoreCase = true) }
                ?: Unknown
        }
    }
}

data class SkillFileEntry(
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val previewText: String? = null,
    val previewable: Boolean = false
)

data class SkillRequirementsStatus(
    val satisfied: Boolean,
    val message: String = ""
)

data class SkillMetadata(
    val name: String,
    val displayName: String,
    val description: String,
    val always: Boolean,
    val frontmatter: Map<String, String>,
    val metadataJson: JSONObject
)

data class SkillCatalogEntry(
    val name: String,
    val displayName: String,
    val description: String,
    val path: String,
    val source: SkillSource,
    val enabled: Boolean,
    val allowIncompatible: Boolean,
    val always: Boolean,
    val compatibilityStatus: SkillCompatibilityStatus,
    val compatibilityReasons: List<String>,
    val requirementsStatus: SkillRequirementsStatus,
    val files: List<SkillFileEntry>,
    val metadata: SkillMetadata,
    val manifest: InstalledSkillManifest? = null,
    val forceEnabled: Boolean = enabled &&
        allowIncompatible &&
        compatibilityStatus !in setOf(
            SkillCompatibilityStatus.Compatible,
            SkillCompatibilityStatus.LikelyCompatible
        )
)

@Serializable
data class InstalledSkillManifest(
    val source: String = SkillSource.Local.wireValue,
    val sourceUrl: String = "",
    val slug: String = "",
    val version: String = "",
    val author: String = "",
    val installedAtMs: Long = 0L,
    val compatibilityStatus: String = SkillCompatibilityStatus.Unknown.wireValue,
    val compatibilityReasons: List<String> = emptyList(),
    val securitySignals: List<String> = emptyList()
) {
    fun resolvedSource(): SkillSource = SkillSource.fromRaw(source)
    fun resolvedCompatibilityStatus(): SkillCompatibilityStatus =
        SkillCompatibilityStatus.fromRaw(compatibilityStatus)
}

data class SkillCompatibilityResult(
    val status: SkillCompatibilityStatus,
    val reasons: List<String>
)

data class ClawHubSkillCard(
    val slug: String,
    val title: String,
    val summary: String,
    val author: String,
    val version: String,
    val license: String,
    val downloads: String,
    val detailUrl: String
)

data class ClawHubSkillDetail(
    val slug: String,
    val title: String,
    val summary: String,
    val author: String,
    val version: String,
    val license: String,
    val downloads: String,
    val detailUrl: String,
    val downloadUrl: String,
    val securitySignals: List<String>,
    val runtimeRequirements: List<String>
)

data class StagedSkillReview(
    val stagingId: String,
    val suggestedName: String,
    val detail: ClawHubSkillDetail,
    val compatibilityStatus: SkillCompatibilityStatus,
    val compatibilityReasons: List<String>,
    val files: List<SkillFileEntry>,
    val previewText: String,
    val stagingDirPath: String
)
