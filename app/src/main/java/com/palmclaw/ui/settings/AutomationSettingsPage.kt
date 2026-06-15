package com.palmclaw.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

internal data class CronSettingsActions(
    val onCronEnabledChange: (Boolean) -> Unit,
    val onCronMinEveryMsChange: (String) -> Unit,
    val onCronMaxJobsChange: (String) -> Unit,
    val onRefreshCronJobs: () -> Unit,
    val onSetCronJobEnabled: (String, Boolean) -> Unit,
    val onRunCronJobNow: (String) -> Unit,
    val onRemoveCronJob: (String) -> Unit,
    val onRefreshCronLogs: () -> Unit,
    val onClearCronLogs: () -> Unit,
    val onRequestConfirmation: (SettingsConfirmationState) -> Unit
)

internal data class HeartbeatSettingsActions(
    val onHeartbeatEnabledChange: (Boolean) -> Unit,
    val onHeartbeatIntervalSecondsChange: (String) -> Unit,
    val onTriggerHeartbeatNow: () -> Unit,
    val onOpenHeartbeatEditor: () -> Unit
)

@Composable
internal fun CronSettingsPage(
    state: AutomationSettingsState,
    useChinese: Boolean,
    actions: CronSettingsActions
) {
    var showCronLogs by rememberSaveable { mutableStateOf(false) }

    SettingsSectionCard(
        title = uiLabel("Scheduler"),
        subtitle = uiLabel("Enable cron and set basic limits")
    ) {
        SettingsToggleRow(
            title = uiLabel("Cron scheduler"),
            checked = state.cronEnabled,
            onCheckedChange = actions.onCronEnabledChange
        )
        OutlinedTextField(
            value = state.cronMinEveryMs,
            onValueChange = actions.onCronMinEveryMsChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(uiLabel("Min Interval (ms)")) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = settingsTextFieldShape(),
            textStyle = MaterialTheme.typography.bodyMedium,
            colors = settingsTextFieldColors()
        )
        OutlinedTextField(
            value = state.cronMaxJobs,
            onValueChange = actions.onCronMaxJobsChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(uiLabel("Max Jobs")) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = settingsTextFieldShape(),
            textStyle = MaterialTheme.typography.bodyMedium,
            colors = settingsTextFieldColors()
        )
    }

    SettingsSectionCard(
        title = uiLabel("Jobs"),
        actions = {
            SettingsActionButton(
                text = uiLabel("Refresh"),
                icon = Icons.Rounded.Refresh,
                onClick = actions.onRefreshCronJobs
            )
            SettingsActionButton(
                text = if (showCronLogs) uiLabel("Hide Logs") else uiLabel("Show Logs"),
                icon = if (showCronLogs) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                onClick = {
                    val next = !showCronLogs
                    showCronLogs = next
                    if (next) actions.onRefreshCronLogs()
                }
            )
        }
    ) {
        CronJobList(
            state = state,
            useChinese = useChinese,
            actions = actions
        )
    }

    if (showCronLogs) {
        CronLogsWindow(
            state = state,
            useChinese = useChinese,
            actions = actions
        )
    }
}

@Composable
internal fun HeartbeatSettingsPage(
    state: AutomationSettingsState,
    actions: HeartbeatSettingsActions
) {
    SettingsSectionCard(
        title = uiLabel("Heartbeat"),
        subtitle = uiLabel("Periodic prompt driven by HEARTBEAT.md")
    ) {
        SettingsToggleRow(
            title = uiLabel("Heartbeat"),
            checked = state.heartbeatEnabled,
            onCheckedChange = actions.onHeartbeatEnabledChange
        )
        OutlinedTextField(
            value = state.heartbeatIntervalSeconds,
            onValueChange = actions.onHeartbeatIntervalSecondsChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(uiLabel("Interval (sec)")) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = settingsTextFieldShape(),
            textStyle = MaterialTheme.typography.bodyMedium,
            colors = settingsTextFieldColors()
        )
    }
    SettingsSectionCard(
        title = uiLabel("Actions"),
        subtitle = uiLabel("Run it now or edit the heartbeat doc")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SettingsActionButton(
                text = uiLabel("Trigger Now"),
                icon = Icons.Rounded.PlayArrow,
                onClick = actions.onTriggerHeartbeatNow
            )
            SettingsActionButton(
                text = uiLabel("Edit Doc"),
                icon = Icons.Rounded.Description,
                onClick = actions.onOpenHeartbeatEditor
            )
        }
    }
}

@Composable
private fun CronJobList(
    state: AutomationSettingsState,
    useChinese: Boolean,
    actions: CronSettingsActions
) {
    when {
        state.cronJobsLoading -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(uiLabel("Loading cron jobs..."), style = MaterialTheme.typography.bodySmall)
            }
        }
        state.cronJobs.isEmpty() -> {
            Text(
                text = uiLabel("No cron jobs yet"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        else -> {
            state.cronJobs.forEach { job ->
                CronJobCard(
                    job = job,
                    useChinese = useChinese,
                    actions = actions
                )
            }
        }
    }
}

@Composable
private fun CronJobCard(
    job: UiCronJob,
    useChinese: Boolean,
    actions: CronSettingsActions
) {
    val removeJobTitle = localizedText(
        "Remove Job",
        "移除任务",
        useChinese = useChinese
    )
    val removeJobLabel = localizedText(
        "Remove",
        "移除",
        useChinese = useChinese
    )
    val removeJobMessage = irreversibleConfirmMessage(
        prompt = localizedText(
            "Remove '%s'?",
            "移除 '%s'？",
            useChinese = useChinese
        ).format(job.name),
        useChinese = useChinese
    )
    Surface(
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.22f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = job.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                PalmClawSwitch(
                    checked = job.enabled,
                    onCheckedChange = { enabled -> actions.onSetCronJobEnabled(job.id, enabled) }
                )
            }
            SettingsInfoBlock(
                label = uiLabel("Schedule"),
                value = job.schedule,
                maxLines = 3
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                job.nextRunAt?.takeIf { it.isNotBlank() }?.let {
                    SettingsInfoBlock(
                        label = uiLabel("Next Run"),
                        value = it,
                        modifier = Modifier.weight(1f),
                        maxLines = 2
                    )
                }
                job.lastStatus?.takeIf { it.isNotBlank() }?.let {
                    SettingsInfoBlock(
                        label = uiLabel("Last Status"),
                        value = it,
                        modifier = Modifier.weight(1f),
                        maxLines = 2
                    )
                }
            }
            job.lastError?.takeIf { it.isNotBlank() }?.let {
                SettingsInfoBlock(
                    label = uiLabel("Last Error"),
                    value = localizedUiMessage(it, useChinese),
                    valueColor = MaterialTheme.colorScheme.error,
                    maxLines = 3
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SettingsActionButton(
                    text = uiLabel("Run"),
                    icon = Icons.Rounded.PlayArrow,
                    onClick = { actions.onRunCronJobNow(job.id) }
                )
                SettingsActionButton(
                    text = uiLabel("Remove"),
                    icon = Icons.Outlined.DeleteOutline,
                    onClick = {
                        actions.onRequestConfirmation(
                            SettingsConfirmationState(
                                title = removeJobTitle,
                                message = removeJobMessage,
                                confirmLabel = removeJobLabel,
                                onConfirm = { actions.onRemoveCronJob(job.id) }
                            )
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun CronLogsWindow(
    state: AutomationSettingsState,
    useChinese: Boolean,
    actions: CronSettingsActions
) {
    val clearCronLogsTitle = localizedText(
        "Clear Cron Logs",
        "清除 Cron 日志",
        useChinese = useChinese
    )
    val clearCronLogsMessage = irreversibleConfirmMessage(
        prompt = localizedText(
            "Clear cron logs?",
            "清除 Cron 日志？",
            useChinese = useChinese
        ),
        useChinese = useChinese
    )
    val clearCronLogsLabel = localizedText(
        "Clear",
        "清除",
        useChinese = useChinese
    )
    ScrollableLogWindow(
        title = uiLabel("Cron Logs"),
        content = state.cronLogs,
        emptyText = uiLabel("No cron logs yet"),
        actions = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SettingsActionButton(
                    text = uiLabel("Refresh"),
                    icon = Icons.Rounded.Refresh,
                    onClick = actions.onRefreshCronLogs
                )
                SettingsActionButton(
                    text = uiLabel("Clear"),
                    icon = Icons.Outlined.DeleteOutline,
                    onClick = {
                        actions.onRequestConfirmation(
                            SettingsConfirmationState(
                                title = clearCronLogsTitle,
                                message = clearCronLogsMessage,
                                confirmLabel = clearCronLogsLabel,
                                onConfirm = actions.onClearCronLogs
                            )
                        )
                    }
                )
            }
        }
    )
}
