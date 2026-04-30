package com.palmclaw.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.palmclaw.skills.SkillCompatibilityStatus
import com.palmclaw.skills.SkillSource
import com.palmclaw.ui.ChatUiState
import com.palmclaw.ui.SettingsSectionCard
import com.palmclaw.ui.SettingsSectionIconButton
import com.palmclaw.ui.SettingsValueRow
import com.palmclaw.ui.tr
import com.palmclaw.ui.uiLabel
import java.util.Locale

@Composable
internal fun SkillsSettingsSection(
    state: ChatUiState,
    onSkillEnabledChange: (String, Boolean) -> Unit,
    onSkillAllowIncompatibleChange: (String, Boolean) -> Unit,
    onSelectInstalledSkill: (String) -> Unit,
    onClearInstalledSkillSelection: () -> Unit,
    onRefreshSkills: () -> Unit,
    onRefreshClawHub: () -> Unit,
    onOpenClawHubSkillDetail: (String) -> Unit,
    onClearClawHubSkillDetail: () -> Unit,
    onStageClawHubSkillInstall: (String) -> Unit,
    onConfirmStagedSkillInstall: () -> Unit,
    onDismissStagedSkillReview: () -> Unit,
    onDeleteInstalledSkill: (String) -> Unit
) {
    SettingsSectionCard(
        title = tr("Installed Skills", "已安装技能"),
        subtitle = tr("Enable or disable what the agent may select.", "管理 Agent 可选择的技能。"),
        actions = {
            SettingsSectionIconButton(
                icon = Icons.Rounded.Refresh,
                contentDescription = uiLabel("Refresh"),
                onClick = onRefreshSkills
            )
        }
    ) {
        SettingsValueRow(tr("Installed", "已安装"), state.settingsInstalledSkills.size.toString())
        SettingsValueRow(
            tr("Enabled", "已启用"),
            state.settingsInstalledSkills.count { it.enabled }.toString()
        )
        if (state.settingsSkillsLoading) {
            Text(
                text = tr("Refreshing installed skills...", "正在刷新已安装技能..."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (state.settingsInstalledSkills.isEmpty()) {
            Text(
                text = tr("No skills found yet.", "还没有检测到技能。"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            state.settingsInstalledSkills.forEachIndexed { index, skill ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.10f))
                }
                InstalledSkillCard(
                    skill = skill,
                    selected = state.settingsSelectedSkillName == skill.name,
                    actionInFlight = state.settingsSkillActionInFlight,
                    onEnabledChange = { enabled -> onSkillEnabledChange(skill.name, enabled) },
                    onToggleDetails = {
                        if (state.settingsSelectedSkillName == skill.name) {
                            onClearInstalledSkillSelection()
                        } else {
                            onSelectInstalledSkill(skill.name)
                        }
                    },
                    onToggleAllowIncompatible = {
                        onSkillAllowIncompatibleChange(skill.name, !skill.allowIncompatible)
                    },
                    onDelete = { onDeleteInstalledSkill(skill.name) }
                )
            }
        }
    }

    state.settingsSelectedSkillDetail?.let { detail ->
        InstalledSkillDetailDialog(
            detail = detail,
            onDismiss = onClearInstalledSkillSelection
        )
    }

    SettingsSectionCard(
        title = "ClawHub",
        subtitle = tr("Browse and install skills.", "浏览并安装技能。"),
        actions = {
            SettingsSectionIconButton(
                icon = Icons.Rounded.Refresh,
                contentDescription = uiLabel("Refresh"),
                onClick = onRefreshClawHub
            )
        }
    ) {
        if (state.settingsClawHubLoading) {
            Text(
                text = tr("Refreshing ClawHub listings...", "正在刷新 ClawHub 列表..."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = tr("Staff Picks", "精选"),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
        renderClawHubCards(
            cards = state.settingsClawHubStaffPicks,
            onOpenDetail = onOpenClawHubSkillDetail
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.10f))
        Text(
            text = tr("Popular", "热门"),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
        renderClawHubCards(
            cards = state.settingsClawHubPopular,
            onOpenDetail = onOpenClawHubSkillDetail
        )
    }

    state.settingsSelectedClawHubDetail?.let { detail ->
        ClawHubSkillDetailDialog(
            detail = detail,
            actionInFlight = state.settingsSkillActionInFlight,
            onDismiss = onClearClawHubSkillDetail,
            onDownload = { onStageClawHubSkillInstall(detail.detailUrl) }
        )
    }

    state.settingsStagedSkillReview?.let { review ->
        SettingsSectionCard(
            title = tr("Install Review", "安装审查"),
            subtitle = review.suggestedName,
            actions = {
                SettingsSectionIconButton(
                    icon = Icons.Outlined.DeleteOutline,
                    contentDescription = uiLabel("Discard"),
                    onClick = onDismissStagedSkillReview
                )
            }
        ) {
            SettingsValueRow(uiLabel("Compatibility"), skillCompatibilityLabel(review.compatibilityStatus))
            if (review.compatibilityReasons.isNotEmpty()) {
                Text(
                    text = review.compatibilityReasons.joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (review.compatibilityStatus in setOf(
                    SkillCompatibilityStatus.Unknown,
                    SkillCompatibilityStatus.DesktopRequired
                )
            ) {
                Text(
                    text = tr(
                        "This skill will be installed disabled. Review it first, then use Force enable if you still want it active on mobile.",
                        "这个技能会先以禁用状态安装。请先审查内容，如仍要在移动端启用，再手动点击强制启用。"
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = review.previewText,
                style = MaterialTheme.typography.bodySmall
            )
            review.files
                .filterNot { it.relativePath == "." }
                .forEach { file ->
                    Text(
                        text = if (file.isDirectory) file.relativePath else "${file.relativePath} (${formatBytes(file.sizeBytes)})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            TextButton(
                onClick = onConfirmStagedSkillInstall,
                enabled = !state.settingsSkillActionInFlight
            ) {
                Text(
                    if (review.compatibilityStatus in setOf(
                            SkillCompatibilityStatus.Unknown,
                            SkillCompatibilityStatus.DesktopRequired
                        )
                    ) {
                        tr("Install only", "仅安装")
                    } else {
                        tr("Install and enable", "安装并启用")
                    }
                )
            }
        }
    }
}

@Composable
private fun InstalledSkillCard(
    skill: UiSkillConfig,
    selected: Boolean,
    actionInFlight: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onToggleDetails: () -> Unit,
    onToggleAllowIncompatible: () -> Unit,
    onDelete: () -> Unit
) {
    val canToggle = canToggleSkillEnabled(skill) && !actionInFlight
    val canDelete = skill.source == SkillSource.ClawHub
    val canOverrideCompatibility = skill.compatibilityStatus in setOf(
        SkillCompatibilityStatus.Unknown,
        SkillCompatibilityStatus.DesktopRequired
    )
    val sourceText = skillSourceLabel(skill.source)

    Surface(
        tonalElevation = if (selected) 2.dp else 0.dp,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.34f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = skill.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = sourceText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onToggleDetails) {
                    Icon(
                        imageVector = Icons.Rounded.Description,
                        contentDescription = tr("Details", "详情")
                    )
                }
                if (canDelete) {
                    IconButton(
                        onClick = onDelete,
                        enabled = !actionInFlight
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = tr("Delete", "删除")
                        )
                    }
                }
                Switch(
                    checked = skill.enabled,
                    onCheckedChange = onEnabledChange,
                    enabled = canToggle
                )
            }

            if (canOverrideCompatibility) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onToggleAllowIncompatible,
                        enabled = !actionInFlight
                    ) {
                        Text(
                            if (skill.allowIncompatible) {
                                tr("Disable force", "取消强制")
                            } else {
                                tr("Force enable", "强制启用")
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InstalledSkillDetailDialog(
    detail: UiSkillConfig,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(detail.displayName) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                detail.description.takeIf { it.isNotBlank() }?.let { description ->
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                SettingsValueRow(uiLabel("Source"), skillSourceLabel(detail.source))
                SettingsValueRow(uiLabel("Compatibility"), skillCompatibilityLabel(detail.compatibilityStatus))
                if (detail.requirementsStatus.isNotBlank()) {
                    SettingsValueRow(uiLabel("Requirements"), detail.requirementsStatus)
                }
                detail.manifestVersion.takeIf { it.isNotBlank() }?.let { version ->
                    SettingsValueRow(uiLabel("Version"), version)
                }
                detail.manifestAuthor.takeIf { it.isNotBlank() }?.let { author ->
                    SettingsValueRow(uiLabel("Author"), author)
                }
                detail.manifestSourceUrl.takeIf { it.isNotBlank() }?.let { sourceUrl ->
                    SettingsValueRow(uiLabel("Source URL"), sourceUrl)
                }
                if (detail.compatibilityReasons.isNotEmpty()) {
                    Text(
                        text = detail.compatibilityReasons.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (detail.frontmatter.isNotEmpty()) {
                    Text(
                        text = tr("Frontmatter", "前置信息"),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    detail.frontmatter.entries.forEach { entry ->
                        SettingsValueRow(entry.key, entry.value)
                    }
                }
                if (detail.files.isNotEmpty()) {
                    Text(
                        text = tr("Files", "文件结构"),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    detail.files
                        .filterNot { it.relativePath == "." }
                        .forEach { file ->
                            Text(
                                text = if (file.isDirectory) {
                                    file.relativePath
                                } else {
                                    "${file.relativePath} (${formatBytes(file.sizeBytes)})"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            file.previewText?.takeIf { it.isNotBlank() }?.let { preview ->
                                Text(
                                    text = preview,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(start = 12.dp, bottom = 8.dp)
                                )
                            }
                        }
                }
                if (detail.manifestSecuritySignals.isNotEmpty()) {
                    Text(
                        text = tr("Security signals", "安全信号"),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    detail.manifestSecuritySignals.forEach { signal ->
                        Text(
                            text = signal,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(uiLabel("Close"))
            }
        }
    )
}

@Composable
private fun ClawHubSkillDetailDialog(
    detail: UiClawHubSkillDetail,
    actionInFlight: Boolean,
    onDismiss: () -> Unit,
    onDownload: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(detail.title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SettingsValueRow(uiLabel("Author"), detail.author)
                detail.version.takeIf { it.isNotBlank() }?.let { version ->
                    SettingsValueRow(uiLabel("Version"), version)
                }
                detail.license.takeIf { it.isNotBlank() }?.let { license ->
                    SettingsValueRow(uiLabel("License"), license)
                }
                detail.downloads.takeIf { it.isNotBlank() }?.let { downloads ->
                    SettingsValueRow(uiLabel("Downloads"), downloads)
                }
                Text(
                    text = detail.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (detail.securitySignals.isNotEmpty()) {
                    Text(
                        text = tr("Security signals", "安全信号"),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    detail.securitySignals.forEach { signal ->
                        Text(
                            text = signal,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDownload,
                enabled = !actionInFlight
            ) {
                Text(tr("Download and review", "下载并审查"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(uiLabel("Close"))
            }
        }
    )
}

@Composable
private fun renderClawHubCards(
    cards: List<UiClawHubSkillCard>,
    onOpenDetail: (String) -> Unit
) {
    if (cards.isEmpty()) {
        Text(
            text = tr("Nothing loaded yet.", "暂时没有加载到内容。"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    cards.forEach { card ->
        ClawHubSkillCard(
            card = card,
            onClick = { onOpenDetail(card.detailUrl) }
        )
    }
}

@Composable
private fun ClawHubSkillCard(
    card: UiClawHubSkillCard,
    onClick: () -> Unit
) {
    val unknownAuthor = tr("Unknown author", "未知作者")
    val metadataText = buildString {
        append(card.author.ifBlank { unknownAuthor })
        if (card.version.isNotBlank()) append(" · v${card.version}")
        if (card.downloads.isNotBlank()) append(" · ${card.downloads}")
    }

    Surface(
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = card.title,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = metadataText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onClick) {
                Icon(
                    imageVector = Icons.Rounded.Description,
                    contentDescription = tr("Details", "详情")
                )
            }
        }
    }
}

@Composable
private fun skillSourceLabel(source: SkillSource): String {
    return when (source) {
        SkillSource.Builtin -> tr("Built-in", "内置")
        SkillSource.Local -> tr("Local", "本地")
        SkillSource.ClawHub -> "ClawHub"
    }
}

@Composable
private fun skillCompatibilityLabel(status: SkillCompatibilityStatus): String {
    return when (status) {
        SkillCompatibilityStatus.Compatible -> tr("Compatible", "兼容")
        SkillCompatibilityStatus.LikelyCompatible -> tr("Likely compatible", "基本兼容")
        SkillCompatibilityStatus.Unknown -> tr("Unknown", "未知")
        SkillCompatibilityStatus.DesktopRequired -> tr("Desktop required", "需要桌面端")
        SkillCompatibilityStatus.Invalid -> tr("Invalid", "无效")
    }
}

private fun canToggleSkillEnabled(skill: UiSkillConfig): Boolean {
    return when (skill.compatibilityStatus) {
        SkillCompatibilityStatus.Compatible,
        SkillCompatibilityStatus.LikelyCompatible -> true
        SkillCompatibilityStatus.Unknown,
        SkillCompatibilityStatus.DesktopRequired -> skill.allowIncompatible
        SkillCompatibilityStatus.Invalid -> false
    }
}

private fun formatBytes(value: Long): String {
    if (value <= 0L) return "0 B"
    val kb = 1024L
    val mb = kb * 1024L
    return when {
        value >= mb -> String.format(Locale.US, "%.1f MB", value.toDouble() / mb.toDouble())
        value >= kb -> String.format(Locale.US, "%.1f KB", value.toDouble() / kb.toDouble())
        else -> "$value B"
    }
}
