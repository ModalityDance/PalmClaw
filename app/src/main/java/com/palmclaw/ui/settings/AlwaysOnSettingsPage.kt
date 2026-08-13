package com.palmclaw.ui

import android.content.Intent
import android.net.Uri
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
import com.palmclaw.ui.domain.AlwaysOnUiStatus

@Composable
internal fun AlwaysOnModeContent(
    state: AlwaysOnSettingsState,
    onEnabledChange: (Boolean) -> Unit,
    onKeepScreenAwakeChange: (Boolean) -> Unit,
    onRefreshStatus: () -> Unit
) {
    val context = LocalContext.current
    val runtimeStatus = state.runtimeStatus
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
                    text = tr(
                        "Improve background channel reliability.",
                        "提升后台渠道可靠性。"
                    ),
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
                        "4. Keep the persistent notification visible so Android can show that background mode is active.",
                        "4. 保持常驻通知可见，让 Android 明确显示后台模式仍在运行。"
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = tr(
                        "5. If the app stays visible while charging, Keep Screen Awake prevents the screen from sleeping.",
                        "5. 充电并保持应用可见时，可开启保持亮屏以防止屏幕休眠。"
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = tr(
                        "Important: Android may still stop background work. After Force stop, PalmClaw cannot recover until you open it again.",
                        "重要提醒：Android 仍可能停止后台任务。用户执行强制停止后，必须重新打开 PalmClaw 才能恢复。"
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
                            text = tr("Always-on mode", "常驻模式"),
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
                            text = tr("Keep Screen Awake", "保持屏幕常亮"),
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
                        text = tr("Status", "状态"),
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
                AlwaysOnStatusRow(
                    tr("Desired mode", "期望模式"),
                    tr(
                        if (runtimeStatus.desired) "Enabled" else "Disabled",
                        if (runtimeStatus.desired) "已启用" else "已关闭"
                    )
                )
                AlwaysOnStatusRow(
                    tr("Availability", "可用状态"),
                    alwaysOnPhaseLabel(runtimeStatus.phase)
                )
                AlwaysOnStatusRow(
                    tr("Foreground shell", "前台服务外壳"),
                    alwaysOnLifecycleLabel(runtimeStatus.shell)
                )
                AlwaysOnStatusRow(
                    tr("Persistent notification", "常驻通知"),
                    tr(
                        if (runtimeStatus.notificationVisible) "Visible" else "Hidden",
                        if (runtimeStatus.notificationVisible) "可见" else "不可见"
                    )
                )
                if (runtimeStatus.desired && !runtimeStatus.notificationVisible) {
                    Text(
                        text = tr(
                            "The service can still run, but its ongoing notification is hidden " +
                                "from the notification drawer. Enable notifications for visible status and Stop control.",
                            "服务仍可继续运行，但常驻通知不会显示在通知抽屉中。" +
                                "请开启通知，以查看运行状态并使用停止控制。"
                        ),
                        color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                AlwaysOnStatusRow(
                    tr("Runtime", "运行时"),
                    alwaysOnLifecycleLabel(runtimeStatus.runtime)
                )
                AlwaysOnStatusRow(
                    tr("Gateway", "渠道网关"),
                    alwaysOnLifecycleLabel(runtimeStatus.gateway)
                )
                AlwaysOnStatusRow(
                    tr("Channels", "渠道"),
                    tr(
                        "${runtimeStatus.channels.ready}/${runtimeStatus.channels.configured} ready",
                        "已就绪 ${runtimeStatus.channels.ready}/${runtimeStatus.channels.configured}"
                    )
                )
                if (runtimeStatus.channels.reconnecting > 0) {
                    AlwaysOnStatusRow(
                        tr("Reconnecting", "正在重连"),
                        runtimeStatus.channels.reconnecting.toString()
                    )
                }
                if (runtimeStatus.channels.blocked > 0) {
                    AlwaysOnStatusRow(
                        tr("Blocked", "已阻塞"),
                        runtimeStatus.channels.blocked.toString()
                    )
                }
                runtimeStatus.waitingFor?.let { waiting ->
                    AlwaysOnStatusRow(tr("Waiting for", "正在等待"), alwaysOnWaitingLabel(waiting))
                }
                AlwaysOnStatusRow(
                    tr("Network", "网络"),
                    alwaysOnNetworkLabel(runtimeStatus.network)
                )
                AlwaysOnStatusRow(
                    uiLabel("Charging"),
                    uiLabel(if (state.charging) "Yes" else "No")
                )
                AlwaysOnStatusRow(
                    uiLabel("Battery optimization"),
                    uiLabel(if (state.batteryOptimizationIgnored) "Ignored" else "On")
                )
                runtimeStatus.actionRequired?.let { action ->
                    Text(
                        text = "${tr("Action required", "需要处理")}: " +
                            alwaysOnActionLabel(action.reason),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                    action.message?.takeIf { it.isNotBlank() }?.let { message ->
                        Text(
                            text = localizedUiMessage(message, state.useChinese),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                if (runtimeStatus.lastError.isNotBlank()) {
                    Text(
                        text = "${uiLabel("Last Error")}: ${localizedUiMessage(runtimeStatus.lastError, state.useChinese)}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun alwaysOnPhaseLabel(phase: AlwaysOnUiStatus.Phase): String = when (phase) {
    AlwaysOnUiStatus.Phase.DISABLED -> tr("Disabled", "已关闭")
    AlwaysOnUiStatus.Phase.STARTING -> tr("Starting", "正在启动")
    AlwaysOnUiStatus.Phase.ONLINE -> tr("Online", "在线")
    AlwaysOnUiStatus.Phase.DEGRADED -> tr("Degraded", "部分可用")
    AlwaysOnUiStatus.Phase.RECOVERING -> tr("Recovering", "正在恢复")
    AlwaysOnUiStatus.Phase.ACTION_REQUIRED -> tr("Action required", "需要处理")
}

@Composable
private fun alwaysOnLifecycleLabel(state: AlwaysOnUiStatus.LifecycleState): String = when (state) {
    AlwaysOnUiStatus.LifecycleState.STOPPED -> tr("Stopped", "已停止")
    AlwaysOnUiStatus.LifecycleState.STARTING -> tr("Starting", "正在启动")
    AlwaysOnUiStatus.LifecycleState.RUNNING -> tr("Running", "运行中")
}

@Composable
private fun alwaysOnNetworkLabel(state: AlwaysOnUiStatus.NetworkState): String = when (state) {
    AlwaysOnUiStatus.NetworkState.UNKNOWN -> tr("Unknown", "未知")
    AlwaysOnUiStatus.NetworkState.OFFLINE -> tr("Offline", "离线")
    AlwaysOnUiStatus.NetworkState.ONLINE -> tr("Connected", "已连接")
}

@Composable
private fun alwaysOnWaitingLabel(reason: AlwaysOnUiStatus.WaitingReason): String = when (reason) {
    AlwaysOnUiStatus.WaitingReason.NETWORK -> tr("network", "网络")
    AlwaysOnUiStatus.WaitingReason.USER_FOREGROUND -> tr("the app to be opened", "用户打开应用")
    AlwaysOnUiStatus.WaitingReason.SHELL -> tr("foreground shell", "前台服务外壳")
    AlwaysOnUiStatus.WaitingReason.RUNTIME -> tr("runtime", "运行时")
    AlwaysOnUiStatus.WaitingReason.GATEWAY -> tr("gateway", "渠道网关")
    AlwaysOnUiStatus.WaitingReason.CHANNELS -> tr("channels", "渠道")
}

@Composable
private fun alwaysOnActionLabel(reason: AlwaysOnUiStatus.ActionRequiredReason): String =
    when (reason) {
        AlwaysOnUiStatus.ActionRequiredReason.NO_CHANNEL_CONFIGURED ->
            tr("Configure at least one channel.", "请至少配置一个渠道。")
        AlwaysOnUiStatus.ActionRequiredReason.SYSTEM_RESTRICTED ->
            tr(
                "Allow background operation in system settings.",
                "请在系统设置中允许后台运行。"
            )
        AlwaysOnUiStatus.ActionRequiredReason.BACKGROUND_START_RESTRICTED ->
            tr(
                "Open PalmClaw to resume background operation.",
                "请打开 PalmClaw 以恢复后台运行。"
            )
        AlwaysOnUiStatus.ActionRequiredReason.ALL_CHANNELS_BLOCKED ->
            tr(
                "Check channel credentials and permissions.",
                "请检查渠道凭据和权限。"
            )
        AlwaysOnUiStatus.ActionRequiredReason.GATEWAY_BLOCKED ->
            tr("Check the channel gateway configuration.", "请检查渠道网关配置。")
    }
