package com.palmclaw.ui.settings

import com.palmclaw.skills.SkillCompatibilityStatus
import com.palmclaw.skills.SkillSource

data class UiSkillFileNode(
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val previewText: String? = null,
    val previewable: Boolean = false
)

data class UiSkillConfig(
    val name: String,
    val displayName: String,
    val description: String,
    val source: SkillSource,
    val enabled: Boolean,
    val allowIncompatible: Boolean,
    val always: Boolean,
    val compatibilityStatus: SkillCompatibilityStatus,
    val compatibilityReasons: List<String>,
    val requirementsStatus: String,
    val manifestSourceUrl: String = "",
    val manifestVersion: String = "",
    val manifestAuthor: String = "",
    val manifestSecuritySignals: List<String> = emptyList(),
    val files: List<UiSkillFileNode> = emptyList(),
    val frontmatter: Map<String, String> = emptyMap(),
    val path: String = ""
)

data class UiClawHubSkillCard(
    val slug: String,
    val title: String,
    val summary: String,
    val author: String,
    val version: String,
    val license: String,
    val downloads: String,
    val detailUrl: String
)

data class UiClawHubSkillDetail(
    val slug: String,
    val title: String,
    val summary: String,
    val author: String,
    val version: String,
    val license: String,
    val downloads: String,
    val detailUrl: String,
    val downloadUrl: String,
    val securitySignals: List<String> = emptyList(),
    val runtimeRequirements: List<String> = emptyList()
)

data class UiStagedSkillReview(
    val stagingId: String,
    val suggestedName: String,
    val detail: UiClawHubSkillDetail,
    val compatibilityStatus: SkillCompatibilityStatus,
    val compatibilityReasons: List<String>,
    val files: List<UiSkillFileNode>,
    val previewText: String,
    val stagingDirPath: String
)
