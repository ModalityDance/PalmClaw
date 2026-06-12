package com.palmclaw.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun AlwaysOnModeContent(
    state: AlwaysOnSettingsState,
    onEnabledChange: (Boolean) -> Unit,
    onKeepScreenAwakeChange: (Boolean) -> Unit,
    onRefreshStatus: () -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            tonalElevation = 1.dp,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.34f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = tr("Keep channels alive in background.", ""),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = tr("Use these tips for best stability:", "建议按以下方式提升稳定性："),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = tr(
                        "1. Turn off battery optimization for this app.",
                        "1. 为本应用关闭电池优化。"
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SettingsActionButton(
                        text = uiLabel("Battery"),
                        icon = Icons.Outlined.Settings,
                        onClick = {
                            val intent = if (!state.batteryOptimizationIgnored) {
                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                            } else {
                                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            }
                            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    )
                    SettingsActionButton(
                        text = tr("Autostart", "自启动"),
                        icon = Icons.Outlined.Settings,
                        onClick = { openAutoStartSettings(context) }
                    )
                }
                Text(
                    text = tr(
                        "2. In system settings, allow this app to autostart.",
                        "2. 在系统设置中允许本应用自启动。"
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = tr(
                        "3. Pin or lock this app in recent tasks so it is less likely to be cleaned.",
                        "3. 在最近任务中锁定本应用，降低被系统清理概率。"
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SettingsActionButton(
                        text = uiLabel("Alarm"),
                        icon = Icons.Rounded.Refresh,
                        onClick = {
                            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                            } else {
                                Intent(Settings.ACTION_DATE_SETTINGS)
                            }
                            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    )
                    SettingsActionButton(
                        text = tr("App settings", "应用设置"),
                        icon = Icons.AutoMirrored.Rounded.ArrowForward,
                        onClick = {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    )
                }
                Text(
                    text = tr(
                        "4. Make sure the notification stays visible and exact alarms are allowed.",
                        "4. 确认通知保持可见，并允许精确闹钟。"
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = tr(
                        "5. When charging, Keep Screen Awake can further improve stability.",
                        "5. 设备充电时可开启保持亮屏，进一步提升稳定性。"
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = tr(
                        "Important: Even with all settings optimized, Android may still stop background work on some devices. Please open the app regularly to keep it healthy.",
                        "重要提醒：即使完成以上设置，部分设备仍可能停止后台任务。建议定期手动打开应用，以提升长期稳定性。"
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                state.info?.takeIf { it.isNotBlank() }?.let { info ->
                    Text(
                        text = localizedUiMessage(info, state.useChinese),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Surface(
            tonalElevation = 1.dp,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.34f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = tr("Always-on Service", ""),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    PalmClawSwitch(
                        checked = state.enabled,
                        onCheckedChange = onEnabledChange
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = tr("Keep Screen Awake", ""),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    PalmClawSwitch(
                        checked = state.keepScreenAwake,
                        onCheckedChange = onKeepScreenAwakeChange
                    )
                }
            }
        }

        Surface(
            tonalElevation = 1.dp,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.34f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = tr("Status", ""),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    SettingsSectionIconButton(
                        icon = Icons.Rounded.Refresh,
                        contentDescription = uiLabel("Refresh"),
                        onClick = onRefreshStatus,
                        containerSize = 30.dp,
                        iconSize = 12.dp
                    )
                }
                AlwaysOnStatusRow(uiLabel("Service"), uiLabel(if (state.serviceRunning) "Running" else "Off"))
                AlwaysOnStatusRow(uiLabel("Gateway"), uiLabel(if (state.gatewayRunning) "Ready" else "Stopped"))
                AlwaysOnStatusRow(uiLabel("Adapters"), state.activeAdapterCount.toString())
                AlwaysOnStatusRow(uiLabel("Network"), uiLabel(if (state.networkConnected) "Connected" else "Offline"))
                AlwaysOnStatusRow(uiLabel("Charging"), uiLabel(if (state.charging) "Yes" else "No"))
                AlwaysOnStatusRow(
                    uiLabel("Battery optimization"),
                    uiLabel(if (state.batteryOptimizationIgnored) "Ignored" else "On")
                )
                AlwaysOnStatusRow(
                    uiLabel("Exact alarm"),
                    uiLabel(if (state.exactAlarmAllowed) "Allowed" else "Unavailable")
                )
                AlwaysOnStatusRow(
                    uiLabel("Notification"),
                    uiLabel(if (state.notificationActive) "Visible" else "Hidden")
                )
                if (state.lastError.isNotBlank()) {
                    Text(
                        text = "${uiLabel("Last Error")}: ${localizedUiMessage(state.lastError, state.useChinese)}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
