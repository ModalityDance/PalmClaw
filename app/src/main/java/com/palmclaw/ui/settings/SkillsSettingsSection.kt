package com.palmclaw.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.palmclaw.skills.SkillCompatibilityStatus
import com.palmclaw.skills.SkillSource
import com.palmclaw.ui.SkillsDiscoveryState
import com.palmclaw.ui.PalmClawSwitch
import com.palmclaw.ui.ProviderActionButton
import com.palmclaw.ui.SettingsSectionCard
import com.palmclaw.ui.SettingsSectionIconButton
import com.palmclaw.ui.SettingsValueRow
import com.palmclaw.ui.settingsTextFieldColors
import com.palmclaw.ui.settingsTextFieldShape
import com.palmclaw.ui.tr
import com.palmclaw.ui.uiLabel
import java.util.Locale

@Composable
internal fun SkillsSettingsSection(
    state: SkillsDiscoveryState,
    onSkillEnabledChange: (String, Boolean) -> Unit,
    onSkillAllowIncompatibleChange: (String, Boolean) -> Unit,
    onSelectInstalledSkill: (String) -> Unit,
    onClearInstalledSkillSelection: () -> Unit,
    onRefreshSkills: () -> Unit,
    onImportLocalSkill: () -> Unit,
    onRefreshClawHub: () -> Unit,
    onClawHubSearchQueryChange: (String) -> Unit,
    onSearchClawHub: () -> Unit,
    onOpenClawHubSkillDetail: (String) -> Unit,
    onClearClawHubSkillDetail: () -> Unit,
    onStageClawHubSkillInstall: (String) -> Unit,
    onConfirmStagedSkillInstall: () -> Unit,
    onDismissStagedSkillReview: () -> Unit,
    onDeleteInstalledSkill: (String) -> Unit
) {
    var showClawHub by rememberSaveable { mutableStateOf(false) }
    var showInstallReview by rememberSaveable { mutableStateOf(false) }
    val showReviewCard = state.stagedSkillReview != null &&
        (state.downloadStatus == null || showInstallReview)

    if (showClawHub) {
        ClawHubBrowseSection(
            state = state,
            onBack = { showClawHub = false },
            onRefreshClawHub = onRefreshClawHub,
            onClawHubSearchQueryChange = onClawHubSearchQueryChange,
            onSearchClawHub = onSearchClawHub,
            onOpenClawHubSkillDetail = onOpenClawHubSkillDetail
        )
    } else {
        InstalledSkillsHomeSection(
            state = state,
            onSkillEnabledChange = onSkillEnabledChange,
            onSkillAllowIncompatibleChange = onSkillAllowIncompatibleChange,
            onSelectInstalledSkill = onSelectInstalledSkill,
            onClearInstalledSkillSelection = onClearInstalledSkillSelection,
            onRefreshSkills = onRefreshSkills,
            onImportLocalSkill = onImportLocalSkill,
            onOpenClawHub = {
                showClawHub = true
                onRefreshClawHub()
            },
            onDeleteInstalledSkill = onDeleteInstalledSkill
        )
    }

    state.downloadStatus?.let { download ->
        SkillDownloadStatusCard(
            download = download,
            hasReview = state.stagedSkillReview != null,
            onOpenReview = { showInstallReview = true }
        )
    }

    state.selectedSkillDetail?.let { detail ->
        InstalledSkillDetailDialog(
            detail = detail,
            onDismiss = onClearInstalledSkillSelection
        )
    }

    state.selectedClawHubDetail?.let { detail ->
        ClawHubSkillDetailDialog(
            detail = detail,
            actionInFlight = state.skillActionInFlight,
            onDismiss = onClearClawHubSkillDetail,
            onDownload = {
                showClawHub = false
                showInstallReview = false
                onStageClawHubSkillInstall(detail.detailUrl)
            }
        )
    }

    if (showReviewCard) {
        state.stagedSkillReview?.let { review ->
            InstallReviewDialog(
                review = review,
                actionInFlight = state.skillActionInFlight,
                onConfirmStagedSkillInstall = onConfirmStagedSkillInstall,
                onDismissStagedSkillReview = {
                    showInstallReview = false
                    onDismissStagedSkillReview()
                }
            )
        }
    }
}

@Composable
private fun InstalledSkillsHomeSection(
    state: SkillsDiscoveryState,
    onSkillEnabledChange: (String, Boolean) -> Unit,
    onSkillAllowIncompatibleChange: (String, Boolean) -> Unit,
    onSelectInstalledSkill: (String) -> Unit,
    onClearInstalledSkillSelection: () -> Unit,
    onRefreshSkills: () -> Unit,
    onImportLocalSkill: () -> Unit,
    onOpenClawHub: () -> Unit,
    onDeleteInstalledSkill: (String) -> Unit
) {
    SettingsSectionCard(
        title = tr("Installed Skills", "已安装技能"),
        subtitle = tr("Enable or disable what the agent may select.", "管理 Agent 可选择的技能。"),
        actions = {
            SettingsSectionIconButton(
                icon = Icons.Rounded.Add,
                contentDescription = tr("Import local skill", "导入本地技能"),
                onClick = onImportLocalSkill,
                containerSize = 32.dp,
                iconSize = 14.dp
            )
            SettingsSectionIconButton(
                icon = Icons.Rounded.Refresh,
                contentDescription = uiLabel("Refresh"),
                onClick = onRefreshSkills,
                containerSize = 32.dp,
                iconSize = 14.dp
            )
        }
    ) {
        SettingsValueRow(tr("Installed", "已安装"), state.installedSkills.size.toString())
        SettingsValueRow(
            tr("Enabled", "已启用"),
            state.installedSkills.count { it.enabled }.toString()
        )
        if (state.skillsLoading) {
            Text(
                text = tr("Refreshing installed skills...", "正在刷新已安装技能..."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        InstalledSkillGroup(
            title = tr("Local skills", "本地技能"),
            emptyText = tr("No local skills found.", "没有本地技能。"),
            skills = state.builtInLocalSkills,
            state = state,
            onSkillEnabledChange = onSkillEnabledChange,
            onSkillAllowIncompatibleChange = onSkillAllowIncompatibleChange,
            onSelectInstalledSkill = onSelectInstalledSkill,
            onClearInstalledSkillSelection = onClearInstalledSkillSelection,
            onDeleteInstalledSkill = onDeleteInstalledSkill
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.10f))
        InstalledSkillGroup(
            title = "ClawHub",
            emptyText = tr("No ClawHub skills installed.", "还没有安装 ClawHub 技能。"),
            skills = state.clawHubInstalledSkills,
            state = state,
            headerAction = {
                TextButton(onClick = onOpenClawHub) {
                    Text(tr("Browse ClawHub", "浏览 ClawHub"))
                }
            },
            onSkillEnabledChange = onSkillEnabledChange,
            onSkillAllowIncompatibleChange = onSkillAllowIncompatibleChange,
            onSelectInstalledSkill = onSelectInstalledSkill,
            onClearInstalledSkillSelection = onClearInstalledSkillSelection,
            onDeleteInstalledSkill = onDeleteInstalledSkill
        )
    }
}

@Composable
private fun ClawHubBrowseSection(
    state: SkillsDiscoveryState,
    onBack: () -> Unit,
    onRefreshClawHub: () -> Unit,
    onClawHubSearchQueryChange: (String) -> Unit,
    onSearchClawHub: () -> Unit,
    onOpenClawHubSkillDetail: (String) -> Unit
) {
    SettingsSectionCard(
        title = "ClawHub",
        subtitle = tr("Browse and install skills on demand.", "按需浏览和安装技能。"),
        actions = {
            TextButton(onClick = onBack) {
                Text(uiLabel("Back"))
            }
            SettingsSectionIconButton(
                icon = Icons.Rounded.Refresh,
                contentDescription = uiLabel("Refresh"),
                onClick = onRefreshClawHub,
                containerSize = 32.dp,
                iconSize = 14.dp
            )
        }
    ) {
        val clawHubSearchQuery = state.clawHubSearchQuery.trim()
        val hasSubmittedSearch = clawHubSearchQuery.isNotBlank() &&
            state.clawHubSearchedQuery == clawHubSearchQuery
        OutlinedTextField(
            value = state.clawHubSearchQuery,
            onValueChange = onClawHubSearchQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(tr("Search ClawHub", "搜索 ClawHub")) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null
                )
            },
            trailingIcon = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (clawHubSearchQuery.isNotBlank()) {
                        ProviderActionButton(
                            icon = Icons.Rounded.Close,
                            contentDescription = tr("Clear search", "清空搜索"),
                            onClick = { onClawHubSearchQueryChange("") }
                        )
                    }
                    ProviderActionButton(
                        icon = Icons.Rounded.Search,
                        contentDescription = tr("Search", "搜索"),
                        onClick = onSearchClawHub,
                        enabled = clawHubSearchQuery.isNotBlank()
                    )
                }
            },
            shape = settingsTextFieldShape(),
            colors = settingsTextFieldColors()
        )
        if (state.clawHubLoading) {
            Text(
                text = tr("Loading ClawHub...", "正在加载 ClawHub..."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (clawHubSearchQuery.isNotBlank()) {
            Text(
                text = tr("Search results", "搜索结果"),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            if (!hasSubmittedSearch && !state.clawHubLoading) {
                Text(
                    text = tr("Tap search to find matching ClawHub skills.", "点击搜索查找匹配的 ClawHub 技能。"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (!state.clawHubLoading && state.clawHubSearchResults.isEmpty()) {
                Text(
                    text = tr("No matching skills found.", "没有找到匹配的技能。"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (hasSubmittedSearch) {
                renderClawHubCards(
                    cards = state.clawHubSearchResults,
                    onOpenDetail = onOpenClawHubSkillDetail
                )
            }
        } else {
            Text(
                text = tr("Staff Picks", "精选"),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            renderClawHubCards(
                cards = state.clawHubStaffPicks,
                onOpenDetail = onOpenClawHubSkillDetail
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.10f))
            Text(
                text = tr("Popular", "热门"),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            renderClawHubCards(
                cards = state.clawHubPopular,
                onOpenDetail = onOpenClawHubSkillDetail
            )
        }
    }
}

@Composable
private fun InstalledSkillGroup(
    title: String,
    emptyText: String,
    skills: List<UiSkillConfig>,
    state: SkillsDiscoveryState,
    headerAction: (@Composable () -> Unit)? = null,
    onSkillEnabledChange: (String, Boolean) -> Unit,
    onSkillAllowIncompatibleChange: (String, Boolean) -> Unit,
    onSelectInstalledSkill: (String) -> Unit,
    onClearInstalledSkillSelection: () -> Unit,
    onDeleteInstalledSkill: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
        headerAction?.invoke()
    }
    if (skills.isEmpty()) {
        Text(
            text = emptyText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    skills.forEachIndexed { index, skill ->
        if (index > 0) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.10f))
        }
        InstalledSkillCard(
            skill = skill,
            selected = state.selectedSkillName == skill.name,
            actionInFlight = state.skillActionInFlight,
            onEnabledChange = { enabled -> onSkillEnabledChange(skill.name, enabled) },
            onToggleDetails = {
                if (state.selectedSkillName == skill.name) {
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

@Composable
private fun SkillDownloadStatusCard(
    download: UiSkillDownloadStatus,
    hasReview: Boolean,
    onOpenReview: () -> Unit
) {
    SettingsSectionCard(
        title = tr("Skill Download", "技能下载"),
        subtitle = download.title
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (download.inProgress) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(vertical = 2.dp)
                        .size(18.dp),
                    strokeWidth = 2.dp
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = skillDownloadStatusLabel(download.status),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                download.error?.takeIf { it.isNotBlank() }?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            if (download.readyForReview && hasReview) {
                TextButton(onClick = onOpenReview) {
                    Text(tr("Review", "审查"))
                }
            }
        }
    }
}

@Composable
private fun InstallReviewDialog(
    review: UiStagedSkillReview,
    actionInFlight: Boolean,
    onConfirmStagedSkillInstall: () -> Unit,
    onDismissStagedSkillReview: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {
            if (!actionInFlight) onDismissStagedSkillReview()
        },
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = tr("Install Review", "安装审查"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = review.suggestedName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ReviewActionsRow(
                    review = review,
                    actionInFlight = actionInFlight,
                    onConfirmStagedSkillInstall = onConfirmStagedSkillInstall,
                    onDismissStagedSkillReview = onDismissStagedSkillReview
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SettingsValueRow(uiLabel("Compatibility"), skillCompatibilityLabel(review.compatibilityStatus))
                    if (review.compatibilityReasons.isNotEmpty()) {
                        Text(
                            text = review.compatibilityReasons.joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = tr(
                            "Install skills only from sources you trust. You are responsible for reviewing the contents and risks.",
                            "只安装你信任来源的技能。内容和风险需要你自行审查负责。"
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                        text = tr("Skill preview", "技能预览"),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    SkillDetailTextBlock(review.previewText, compact = true)
                    Text(
                        text = tr("Package files", "包内文件"),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    review.files
                        .filterNot { it.relativePath == "." }
                        .forEach { file ->
                            Text(
                                text = if (file.isDirectory) {
                                    file.relativePath
                                } else {
                                    "${file.relativePath} (${formatBytes(file.sizeBytes)})"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                }
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
}

@Composable
private fun ReviewActionsRow(
    review: UiStagedSkillReview,
    actionInFlight: Boolean,
    onConfirmStagedSkillInstall: () -> Unit,
    onDismissStagedSkillReview: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(
            onClick = onDismissStagedSkillReview,
            enabled = !actionInFlight
        ) {
            Text(tr("Discard", "丢弃"))
        }
        TextButton(
            onClick = onConfirmStagedSkillInstall,
            enabled = !actionInFlight
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
    val canDelete = skill.source != SkillSource.Builtin && !isProtectedSkillName(skill.name)
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
                ProviderActionButton(
                    icon = Icons.Rounded.Description,
                    contentDescription = tr("Details", "详情"),
                    onClick = onToggleDetails
                )
                if (canDelete) {
                    ProviderActionButton(
                        icon = Icons.Outlined.DeleteOutline,
                        contentDescription = tr("Delete", "删除"),
                        onClick = onDelete,
                        enabled = !actionInFlight
                    )
                }
                PalmClawSwitch(
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
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                detail.description.takeIf { it.isNotBlank() }?.let { description ->
                    SkillDetailTextBlock(
                        text = description,
                        emphasized = true
                    )
                }
                SkillDetailSection(title = tr("Overview", "概览")) {
                    SkillDetailRow(uiLabel("Source"), skillSourceLabel(detail.source))
                    SkillDetailRow(uiLabel("Compatibility"), skillCompatibilityLabel(detail.compatibilityStatus))
                    if (detail.requirementsStatus.isNotBlank()) {
                        SkillDetailRow(uiLabel("Requirements"), detail.requirementsStatus)
                    }
                }
                if (
                    detail.manifestVersion.isNotBlank() ||
                    detail.manifestAuthor.isNotBlank() ||
                    detail.manifestSourceUrl.isNotBlank()
                ) {
                    SkillDetailSection(title = tr("Package", "包信息")) {
                        detail.manifestVersion.takeIf { it.isNotBlank() }?.let { version ->
                            SkillDetailRow(uiLabel("Version"), version)
                        }
                        detail.manifestAuthor.takeIf { it.isNotBlank() }?.let { author ->
                            SkillDetailRow(uiLabel("Author"), author)
                        }
                        detail.manifestSourceUrl.takeIf { it.isNotBlank() }?.let { sourceUrl ->
                            SkillDetailRow(uiLabel("Source URL"), sourceUrl)
                        }
                    }
                }
                if (detail.compatibilityReasons.isNotEmpty()) {
                    SkillDetailSection(title = tr("Compatibility notes", "兼容性说明")) {
                        detail.compatibilityReasons.forEach { reason ->
                            SkillDetailTextBlock(reason)
                        }
                    }
                }
                if (detail.frontmatter.isNotEmpty()) {
                    SkillDetailSection(title = tr("Frontmatter", "前置信息")) {
                        detail.frontmatter.entries.forEach { entry ->
                            SkillDetailRow(entry.key, entry.value)
                        }
                    }
                }
                if (detail.files.isNotEmpty()) {
                    SkillDetailSection(title = tr("Files", "文件结构")) {
                        detail.files
                            .filterNot { it.relativePath == "." }
                            .forEach { file ->
                                SkillDetailRow(
                                    label = if (file.isDirectory) tr("Folder", "文件夹") else tr("File", "文件"),
                                    value = if (file.isDirectory) {
                                        file.relativePath
                                    } else {
                                        "${file.relativePath} (${formatBytes(file.sizeBytes)})"
                                    }
                                )
                                file.previewText?.takeIf { it.isNotBlank() }?.let { preview ->
                                    SkillDetailTextBlock(preview, compact = true)
                                }
                            }
                    }
                }
                if (detail.manifestSecuritySignals.isNotEmpty()) {
                    SkillDetailSection(title = tr("Security signals", "安全信号")) {
                        detail.manifestSecuritySignals.forEach { signal ->
                            SkillDetailTextBlock(signal)
                        }
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
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SkillDetailTextBlock(
                    text = detail.summary,
                    emphasized = true
                )
                SkillDetailSection(title = tr("Overview", "概览")) {
                    SkillDetailRow(uiLabel("Author"), detail.author)
                    detail.version.takeIf { it.isNotBlank() }?.let { version ->
                        SkillDetailRow(uiLabel("Version"), version)
                    }
                    detail.license.takeIf { it.isNotBlank() }?.let { license ->
                        SkillDetailRow(uiLabel("License"), license)
                    }
                    detail.downloads.takeIf { it.isNotBlank() }?.let { downloads ->
                        SkillDetailRow(uiLabel("Downloads"), downloads)
                    }
                }
                if (detail.securitySignals.isNotEmpty()) {
                    SkillDetailSection(title = tr("Security signals", "安全信号")) {
                        detail.securitySignals.forEach { signal ->
                            SkillDetailTextBlock(signal)
                        }
                    }
                }
                if (detail.runtimeRequirements.isNotEmpty()) {
                    SkillDetailSection(title = tr("Runtime requirements", "运行要求")) {
                        detail.runtimeRequirements.forEach { requirement ->
                            SkillDetailTextBlock(requirement, compact = true)
                        }
                    }
                }
                if (detail.downloadUrl.isBlank()) {
                    SkillDetailTextBlock(
                        text = tr(
                            "Cannot use on mobile.",
                            "移动端无法使用。"
                        ),
                        emphasized = true
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDownload,
                enabled = !actionInFlight && detail.downloadUrl.isNotBlank()
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
private fun SkillDetailSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.24f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            content()
        }
    }
}

@Composable
private fun SkillDetailRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.36f),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.64f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SkillDetailTextBlock(
    text: String,
    emphasized: Boolean = false,
    compact: Boolean = false
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (emphasized) {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.44f)
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(
                horizontal = if (compact) 8.dp else 10.dp,
                vertical = if (compact) 6.dp else 8.dp
            ),
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
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
            ProviderActionButton(
                icon = Icons.Rounded.Description,
                contentDescription = tr("Details", "详情"),
                onClick = onClick
            )
        }
    }
}

@Composable
private fun skillSourceLabel(source: SkillSource): String {
    return when (source) {
        SkillSource.Builtin -> tr("Local", "本地")
        SkillSource.Local -> tr("Local", "本地")
        SkillSource.ClawHub -> "ClawHub"
    }
}

@Composable
private fun skillDownloadStatusLabel(status: String): String {
    return when (status) {
        "Downloading..." -> tr("Downloading...", "正在下载...")
        "Ready for review" -> tr("Ready for review", "待审查")
        "Download failed" -> tr("Download failed", "下载失败")
        else -> status
    }
}

private fun isProtectedSkillName(name: String): Boolean {
    return name.equals("channels", ignoreCase = true)
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
