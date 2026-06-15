package com.palmclaw.ui.settings

import com.palmclaw.skills.ClawHubSkillCard
import com.palmclaw.skills.ClawHubSkillDetail
import com.palmclaw.skills.SkillCatalogEntry
import com.palmclaw.skills.SkillFileEntry
import com.palmclaw.skills.StagedSkillReview

internal object SkillSettingsMapper {
    fun toUiSkillConfig(entry: SkillCatalogEntry): UiSkillConfig {
        return UiSkillConfig(
            name = entry.name,
            displayName = entry.displayName,
            description = entry.description,
            source = entry.source,
            enabled = entry.enabled,
            allowIncompatible = entry.allowIncompatible,
            always = entry.always,
            compatibilityStatus = entry.compatibilityStatus,
            compatibilityReasons = entry.compatibilityReasons,
            requirementsStatus = entry.requirementsStatus.message,
            manifestSourceUrl = entry.manifest?.sourceUrl.orEmpty(),
            manifestVersion = entry.manifest?.version.orEmpty(),
            manifestAuthor = entry.manifest?.author.orEmpty(),
            manifestSecuritySignals = entry.manifest?.securitySignals.orEmpty(),
            files = entry.files.map(::toUiFileNode),
            frontmatter = entry.metadata.frontmatter,
            path = entry.path
        )
    }

    fun toUiClawHubCard(card: ClawHubSkillCard): UiClawHubSkillCard {
        return UiClawHubSkillCard(
            slug = card.slug,
            title = card.title,
            summary = card.summary,
            author = card.author,
            version = card.version,
            license = card.license,
            downloads = card.downloads,
            detailUrl = card.detailUrl
        )
    }

    fun toUiClawHubDetail(detail: ClawHubSkillDetail): UiClawHubSkillDetail {
        return UiClawHubSkillDetail(
            slug = detail.slug,
            title = detail.title,
            summary = detail.summary,
            author = detail.author,
            version = detail.version,
            license = detail.license,
            downloads = detail.downloads,
            detailUrl = detail.detailUrl,
            downloadUrl = detail.downloadUrl,
            securitySignals = detail.securitySignals,
            runtimeRequirements = detail.runtimeRequirements
        )
    }

    fun toUiStagedSkillReview(review: StagedSkillReview): UiStagedSkillReview {
        return UiStagedSkillReview(
            stagingId = review.stagingId,
            suggestedName = review.suggestedName,
            detail = toUiClawHubDetail(review.detail),
            compatibilityStatus = review.compatibilityStatus,
            compatibilityReasons = review.compatibilityReasons,
            files = review.files.map(::toUiFileNode),
            previewText = review.previewText,
            stagingDirPath = review.stagingDirPath
        )
    }

    fun toStagedSkillReview(review: UiStagedSkillReview): StagedSkillReview {
        return StagedSkillReview(
            stagingId = review.stagingId,
            suggestedName = review.suggestedName,
            detail = ClawHubSkillDetail(
                slug = review.detail.slug,
                title = review.detail.title,
                summary = review.detail.summary,
                author = review.detail.author,
                version = review.detail.version,
                license = review.detail.license,
                downloads = review.detail.downloads,
                detailUrl = review.detail.detailUrl,
                downloadUrl = review.detail.downloadUrl,
                securitySignals = review.detail.securitySignals,
                runtimeRequirements = review.detail.runtimeRequirements
            ),
            compatibilityStatus = review.compatibilityStatus,
            compatibilityReasons = review.compatibilityReasons,
            files = review.files.map {
                SkillFileEntry(
                    relativePath = it.relativePath,
                    isDirectory = it.isDirectory,
                    sizeBytes = it.sizeBytes,
                    previewText = it.previewText,
                    previewable = it.previewable
                )
            },
            previewText = review.previewText,
            stagingDirPath = review.stagingDirPath
        )
    }

    private fun toUiFileNode(file: SkillFileEntry): UiSkillFileNode {
        return UiSkillFileNode(
            relativePath = file.relativePath,
            isDirectory = file.isDirectory,
            sizeBytes = file.sizeBytes,
            previewText = file.previewText,
            previewable = file.previewable
        )
    }
}
