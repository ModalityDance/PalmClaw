package com.palmclaw.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * Dialogs and sheets used by the chat route but not tied to the main transcript layout.
 */
@Composable
internal fun PendingUserConfirmationDialog(
    title: String,
    message: String,
    confirmLabel: String,
    cancelLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        ),
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = { Text(title.ifBlank { uiLabel("Confirm") }) },
        text = { DialogBodyText(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(cancelLabel) }
        }
    )
}

@Composable
internal fun CreateSessionDialog(
    sessionName: String,
    onSessionNameChange: (String) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = { Text(tr("Create Session", "")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = tr(
                        "Create a separate workspace for a task, person, or channel.",
                        ""
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = sessionName,
                    onValueChange = onSessionNameChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(tr("Session Name", "")) },
                    singleLine = true,
                    placeholder = { Text(tr("Example: Research", "")) },
                    shape = settingsTextFieldShape(),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = settingsTextFieldColors()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onCreate,
                enabled = sessionName.isNotBlank()
            ) {
                Text(tr("Create", ""))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(tr("Cancel", ""))
            }
        }
    )
}

@Composable
internal fun RenameSessionDialog(
    sessionName: String,
    onSessionNameChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = { Text(uiLabel("Rename Session")) },
        text = {
            OutlinedTextField(
                value = sessionName,
                onValueChange = onSessionNameChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(uiLabel("Session Name")) },
                singleLine = true,
                shape = settingsTextFieldShape(),
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = settingsTextFieldColors()
            )
        },
        confirmButton = {
            Button(onClick = onSave) { Text(uiLabel("Save")) }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text(uiLabel("Cancel")) }
        }
    )
}

@Composable
internal fun DeleteSessionDialog(
    title: String,
    message: String,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        ),
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = { Text(title) },
        text = { DialogBodyText(message) },
        confirmButton = {
            Button(
                onClick = onDelete,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Text(uiLabel("Delete"))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text(uiLabel("Cancel")) }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HeartbeatEditorSheet(
    heartbeatDoc: String,
    saving: Boolean,
    onHeartbeatDocChange: (String) -> Unit,
    onClose: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = uiLabel("Edit HEARTBEAT.md"),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            OutlinedTextField(
                value = heartbeatDoc,
                onValueChange = onHeartbeatDocChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 12,
                maxLines = 20,
                singleLine = false,
                shape = settingsTextFieldShape(),
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = settingsTextFieldColors()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SettingsActionButton(
                    text = uiLabel("Close"),
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    onClick = onClose
                )
                if (saving) {
                    Text(
                        text = uiLabel("Saving..."),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
internal fun AppUpdateDialogs(
    context: Context,
    state: ChatUiState,
    useChinese: Boolean,
    onDismissPrompt: () -> Unit,
    onDismissNotice: () -> Unit,
    onDownloadStarted: () -> Unit,
    onDownloadFallback: (String) -> Unit
) {
    if (state.settingsUpdatePromptVisible) {
        val latestVersion = state.settingsLatestVersion.ifBlank {
            state.settingsCurrentVersion.ifBlank { "latest" }
        }
        val downloadUrl = state.settingsUpdateDownloadUrl.ifBlank { PALMCLAW_APK_URL }
        val releaseUrl = state.settingsUpdateReleaseUrl.ifBlank { PALMCLAW_RELEASES_URL }
        AlertDialog(
            onDismissRequest = onDismissPrompt,
            properties = DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            ),
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text(tr("Update Available", "发现新版本")) },
            text = {
                DialogBodyText(
                    text = tr(
                        "PalmClaw $latestVersion is available. Download it now?",
                        "PalmClaw $latestVersion 已可用，现在下载吗？"
                    )
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDismissPrompt()
                        val started = enqueueAppUpdateDownload(
                            context = context,
                            downloadUrl = downloadUrl,
                            versionName = latestVersion,
                            useChinese = useChinese
                        )
                        if (started) onDownloadStarted() else onDownloadFallback(releaseUrl)
                    }
                ) {
                    Text(tr("Download", "下载"))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = onDismissPrompt) {
                    Text(tr("Later", "稍后"))
                }
            }
        )
    }

    if (state.settingsUpdateNoticeVisible) {
        AlertDialog(
            onDismissRequest = onDismissNotice,
            properties = DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            ),
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text(state.settingsUpdateNoticeTitle) },
            text = { DialogBodyText(text = state.settingsUpdateNoticeMessage) },
            confirmButton = {
                val actionUrl = state.settingsUpdateNoticeActionUrl.trim()
                val actionLabel = state.settingsUpdateNoticeActionLabel.trim()
                if (actionUrl.isNotBlank() && actionLabel.isNotBlank()) {
                    Button(
                        onClick = {
                            openExternalUrl(context, actionUrl)
                            onDismissNotice()
                        }
                    ) {
                        Text(actionLabel)
                    }
                } else {
                    Button(onClick = onDismissNotice) { Text(tr("OK", "确定")) }
                }
            },
            dismissButton = {
                val actionUrl = state.settingsUpdateNoticeActionUrl.trim()
                if (actionUrl.isNotBlank()) {
                    OutlinedButton(onClick = onDismissNotice) {
                        Text(tr("Close", "关闭"))
                    }
                }
            }
        )
    }
}
