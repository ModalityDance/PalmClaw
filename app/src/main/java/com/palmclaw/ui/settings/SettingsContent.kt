package com.palmclaw.ui

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.DownloadManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.text.method.LinkMovementMethod
import android.view.WindowManager
import android.widget.Toast
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.palmclaw.R
import com.palmclaw.config.AppLimits
import com.palmclaw.config.AppSession
import com.palmclaw.providers.ProviderCatalog
import com.palmclaw.tools.AndroidUserActionBridge
import com.palmclaw.tools.AndroidUserActionRequester
import com.palmclaw.tools.hasAllFilesAccess
import com.palmclaw.tools.hasPermission
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock


/**
 * Settings pages, page metadata, and long-form guide/about rendering.
 */
internal enum class SessionSettingsPage {
    Menu,
    Configure,
    Diagnostics
}

internal enum class SettingsPanelPage {
    Home,
    Permissions,
    AlwaysOn,
    Runtime,
    Provider,
    Channels,
    Cron,
    Heartbeat,
    Mcp,
    Guide,
    About;

    fun title(isChinese: Boolean): String = when (this) {
        Home -> localizedText("Settings", useChinese = isChinese)
        Permissions -> localizedText("Permissions", "权限", useChinese = isChinese)
        AlwaysOn -> localizedText("Always-on", useChinese = isChinese)
        Runtime -> localizedText("Runtime", useChinese = isChinese)
        Provider -> localizedText("Provider", "提供方", useChinese = isChinese)
        Channels -> localizedText("Channels", useChinese = isChinese)
        Cron -> "Cron"
        Heartbeat -> localizedText("Heartbeat", useChinese = isChinese)
        Mcp -> "MCP"
        Guide -> localizedText("User Guide", useChinese = isChinese)
        About -> localizedText("About", "关于", useChinese = isChinese)
    }

    fun subtitle(isChinese: Boolean): String = when (this) {
        Home -> ""
        Permissions -> localizedText("Device access and special Android permissions", "设备访问与 Android 特殊权限", useChinese = isChinese)
        AlwaysOn -> localizedText("Background service and reliability", useChinese = isChinese)
        Runtime -> localizedText("Limits and logs", useChinese = isChinese)
        Provider -> localizedText("API accounts and models", "API 账号与模型", useChinese = isChinese)
        Channels -> localizedText("Session routes", useChinese = isChinese)
        Cron -> localizedText("Jobs and limits", useChinese = isChinese)
        Heartbeat -> localizedText("Interval and doc", useChinese = isChinese)
        Mcp -> localizedText("Remote servers", useChinese = isChinese)
        Guide -> localizedText("Core features and how to use them", useChinese = isChinese)
        About -> localizedText("Version, updates, and project links", "版本、更新与项目链接", useChinese = isChinese)
    }
}

internal data class SettingsMenuItem(
    val page: SettingsPanelPage,
    val title: String,
    val subtitle: String
)

internal data class SettingsMenuGroup(
    val title: String,
    val subtitle: String? = null,
    val items: List<SettingsMenuItem>
)

@Composable
internal fun AlwaysOnModeContent(
    state: ChatUiState,
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
                            val intent = if (!state.alwaysOnBatteryOptimizationIgnored) {
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
                state.settingsInfo?.takeIf { it.isNotBlank() }?.let { info ->
                    Text(
                        text = localizedUiMessage(info, state.settingsUseChinese),
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
                        checked = state.alwaysOnEnabled,
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
                        checked = state.alwaysOnKeepScreenAwake,
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
                AlwaysOnStatusRow(uiLabel("Service"), uiLabel(if (state.alwaysOnServiceRunning) "Running" else "Off"))
                AlwaysOnStatusRow(uiLabel("Gateway"), uiLabel(if (state.alwaysOnGatewayRunning) "Ready" else "Stopped"))
                AlwaysOnStatusRow(uiLabel("Adapters"), state.alwaysOnActiveAdapterCount.toString())
                AlwaysOnStatusRow(uiLabel("Network"), uiLabel(if (state.alwaysOnNetworkConnected) "Connected" else "Offline"))
                AlwaysOnStatusRow(uiLabel("Charging"), uiLabel(if (state.alwaysOnCharging) "Yes" else "No"))
                AlwaysOnStatusRow(
                    uiLabel("Battery optimization"),
                    uiLabel(if (state.alwaysOnBatteryOptimizationIgnored) "Ignored" else "On")
                )
                AlwaysOnStatusRow(
                    uiLabel("Exact alarm"),
                    uiLabel(if (state.alwaysOnExactAlarmAllowed) "Allowed" else "Unavailable")
                )
                AlwaysOnStatusRow(
                    uiLabel("Notification"),
                    uiLabel(if (state.alwaysOnNotificationActive) "Visible" else "Hidden")
                )
                if (state.alwaysOnLastError.isNotBlank()) {
                    Text(
                        text = "${uiLabel("Last Error")}: ${localizedUiMessage(state.alwaysOnLastError, state.settingsUseChinese)}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
internal fun AboutContent(
    state: ChatUiState,
    onCheckUpdate: () -> Unit,
    onNotifyUpdateDownloadStarted: () -> Unit,
    onNotifyUpdateDownloadFallback: (String) -> Unit
) {
    val context = LocalContext.current
    val aboutInfo = remember(context.applicationContext) {
        readInstalledAppAboutInfo(context.applicationContext)
    }
    val currentVersion = state.settingsCurrentVersion.ifBlank { aboutInfo.versionName }
    val latestVersion = state.settingsLatestVersion.ifBlank { currentVersion }
    val downloadUrl = state.settingsUpdateDownloadUrl.ifBlank { PALMCLAW_APK_URL }
    val releasesUrl = state.settingsUpdateReleaseUrl.ifBlank { PALMCLAW_RELEASES_URL }
    val versionSubtitle = when {
        state.settingsUpdateAvailable -> tr(
            "New version $latestVersion is available.",
            "发现新版本 $latestVersion。"
        )
        state.settingsLatestVersion.isNotBlank() -> tr(
            "You're on the latest version.",
            "当前已经是最新版本。"
        )
        else -> tr(
            "Tap to view all releases.",
            "点按可查看全部版本。"
        )
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingsSectionCard(
            title = tr("Version", "版本")
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { openExternalUrl(context, releasesUrl) },
                tonalElevation = 0.dp,
                shape = RoundedCornerShape(16.dp),
                color = if (state.settingsUpdateAvailable) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
                } else {
                    MaterialTheme.colorScheme.surface
                },
                contentColor = if (state.settingsUpdateAvailable) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                border = BorderStroke(
                    1.dp,
                    if (state.settingsUpdateAvailable) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.26f)
                    } else {
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)
                    }
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = aboutInfo.appName,
                            style = MaterialTheme.typography.labelMedium,
                            color = LocalContentColor.current.copy(alpha = 0.78f)
                        )
                        Text(
                            text = "v$currentVersion",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = versionSubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalContentColor.current.copy(alpha = 0.78f)
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                        contentDescription = null,
                        tint = LocalContentColor.current.copy(alpha = 0.72f)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SettingsActionButton(
                    text = if (state.settingsUpdateChecking) {
                        tr("Checking...", "检查中...")
                    } else {
                        tr("Check Update", "检查更新")
                    },
                    icon = Icons.Rounded.Refresh,
                    onClick = onCheckUpdate,
                    enabled = !state.settingsUpdateChecking
                )
                if (state.settingsUpdateAvailable) {
                    SettingsActionButton(
                        text = tr("Download", "下载"),
                        icon = Icons.AutoMirrored.Rounded.ArrowForward,
                        onClick = {
                            val started = enqueueAppUpdateDownload(
                                context = context,
                                downloadUrl = downloadUrl,
                                versionName = latestVersion,
                                useChinese = state.settingsUseChinese
                            )
                            if (started) {
                                onNotifyUpdateDownloadStarted()
                            } else {
                                onNotifyUpdateDownloadFallback(releasesUrl)
                            }
                        }
                    )
                } else {
                    SettingsActionButton(
                        text = tr("Releases", "发布页"),
                        icon = Icons.AutoMirrored.Rounded.ArrowForward,
                        onClick = { openExternalUrl(context, releasesUrl) }
                    )
                }
            }
        }

        SettingsSectionCard(
            title = tr("Project", "项目"),
        ) {
            AboutLinkButton(
                label = tr("Website", "网站"),
                url = PALMCLAW_WEBSITE_URL
            )
            AboutLinkButton(
                label = "GitHub",
                url = PALMCLAW_GITHUB_URL
            )
            AboutLinkButton(
                label = tr("Issues", "问题反馈"),
                url = PALMCLAW_ISSUES_URL
            )
        }
    }
}

@Composable
internal fun PermissionsContent(
    state: ChatUiState,
    dashboard: PermissionsDashboardState,
    onRequestPermissions: (Array<String>) -> Unit,
    onRefreshStatus: () -> Unit
) {
    val context = LocalContext.current
    val isChinese = state.settingsUseChinese
    val notificationActionLabel = if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        !dashboard.notificationPermissionGranted
    ) {
        tr("Grant", "授权")
    } else {
        tr("Manage", "管理")
    }
    val mediaRequestPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO
        )
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    val allFilesActionLabel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        tr("Manage", "管理")
    } else {
        tr("Grant", "授权")
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SettingsSectionCard(
            title = tr("Permissions", "权限"),
            actions = {
                SettingsSectionIconButton(
                    icon = Icons.Rounded.Refresh,
                    contentDescription = uiLabel("Refresh"),
                    onClick = onRefreshStatus,
                    containerSize = 30.dp,
                    iconSize = 12.dp
                )
            }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "${dashboard.readyCount}/${dashboard.totalCount}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = tr("Ready now", "当前已就绪"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                PermissionInlineAction(
                    text = tr("App Settings", "应用设置"),
                    onClick = { openAppDetailsSettings(context) }
                )
            }
        }

        SettingsSectionCard(
            title = tr("Special", "特殊访问")
        ) {
            PermissionRow(
                title = tr("Notifications", "通知"),
                subtitle = tr("Alerts and status", "提醒与状态"),
                statusText = if (dashboard.notificationsEnabled) {
                    localizedText("On", "已开", useChinese = isChinese)
                } else {
                    localizedText("Off", "未开", useChinese = isChinese)
                },
                granted = dashboard.notificationsEnabled,
                actionText = notificationActionLabel,
                onAction = {
                    if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        !dashboard.notificationPermissionGranted
                    ) {
                        onRequestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                    } else {
                        openNotificationSettings(context)
                    }
                }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.10f))
            PermissionRow(
                title = tr("All Files Access", "所有文件访问"),
                subtitle = tr("Shared storage", "共享存储"),
                statusText = if (dashboard.allFilesAccessGranted) {
                    localizedText("On", "已开", useChinese = isChinese)
                } else {
                    localizedText("Off", "未开", useChinese = isChinese)
                },
                granted = dashboard.allFilesAccessGranted,
                actionText = allFilesActionLabel,
                onAction = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        openAllFilesAccessSettings(context)
                    } else {
                        onRequestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
                    }
                }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.10f))
            PermissionRow(
                title = tr("Battery Optimization", "电池优化"),
                subtitle = tr("Background stability", "后台稳定性"),
                statusText = if (dashboard.batteryOptimizationIgnored) {
                    localizedText("On", "已开", useChinese = isChinese)
                } else {
                    localizedText("Off", "未开", useChinese = isChinese)
                },
                granted = dashboard.batteryOptimizationIgnored,
                actionText = tr("Manage", "管理"),
                onAction = {
                    openBatteryOptimizationSettings(
                        context = context,
                        ignored = dashboard.batteryOptimizationIgnored
                    )
                }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.10f))
            PermissionRow(
                title = tr("Exact Alarms", "精确闹钟"),
                subtitle = tr("Cron and heartbeat", "Cron 与 Heartbeat"),
                statusText = if (dashboard.exactAlarmAllowed) {
                    localizedText("On", "已开", useChinese = isChinese)
                } else {
                    localizedText("Off", "未开", useChinese = isChinese)
                },
                granted = dashboard.exactAlarmAllowed,
                actionText = tr("Manage", "管理"),
                onAction = { openExactAlarmSettings(context) }
            )
        }

        SettingsSectionCard(
            title = tr("Runtime", "运行时")
        ) {
            PermissionRow(
                title = tr("Microphone", "麦克风"),
                subtitle = tr("Voice", "语音"),
                statusText = if (dashboard.microphoneGranted) {
                    localizedText("On", "已开", useChinese = isChinese)
                } else {
                    localizedText("Off", "未开", useChinese = isChinese)
                },
                granted = dashboard.microphoneGranted,
                actionText = if (dashboard.microphoneGranted) tr("Manage", "管理") else tr("Grant", "授权"),
                onAction = {
                    if (dashboard.microphoneGranted) {
                        openAppDetailsSettings(context)
                    } else {
                        onRequestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO))
                    }
                }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.10f))
            PermissionRow(
                title = tr("Camera", "相机"),
                subtitle = tr("Capture", "拍摄"),
                statusText = if (dashboard.cameraGranted) {
                    localizedText("On", "已开", useChinese = isChinese)
                } else {
                    localizedText("Off", "未开", useChinese = isChinese)
                },
                granted = dashboard.cameraGranted,
                actionText = if (dashboard.cameraGranted) tr("Manage", "管理") else tr("Grant", "授权"),
                onAction = {
                    if (dashboard.cameraGranted) {
                        openAppDetailsSettings(context)
                    } else {
                        onRequestPermissions(arrayOf(Manifest.permission.CAMERA))
                    }
                }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.10f))
            PermissionRow(
                title = tr("Location", "位置"),
                subtitle = tr("Location", "定位"),
                statusText = dashboard.locationStatus.label(isChinese),
                granted = dashboard.locationStatus.allGranted,
                partial = dashboard.locationStatus.partiallyGranted,
                actionText = if (dashboard.locationStatus.allGranted) tr("Manage", "管理") else tr("Grant", "授权"),
                onAction = {
                    if (dashboard.locationStatus.allGranted) {
                        openAppDetailsSettings(context)
                    } else {
                        onRequestPermissions(
                            arrayOf(
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                Manifest.permission.ACCESS_FINE_LOCATION
                            )
                        )
                    }
                }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.10f))
            PermissionRow(
                title = tr("Bluetooth", "蓝牙"),
                subtitle = tr("Nearby devices", "附近设备"),
                statusText = dashboard.bluetoothStatus.label(isChinese),
                granted = dashboard.bluetoothStatus.allGranted,
                partial = dashboard.bluetoothStatus.partiallyGranted,
                actionText = if (dashboard.bluetoothStatus.allGranted) tr("Manage", "管理") else tr("Grant", "授权"),
                onAction = {
                    if (dashboard.bluetoothStatus.allGranted) {
                        openAppDetailsSettings(context)
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        onRequestPermissions(
                            arrayOf(
                                Manifest.permission.BLUETOOTH_SCAN,
                                Manifest.permission.BLUETOOTH_CONNECT
                            )
                        )
                    } else {
                        onRequestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
                    }
                }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.10f))
            PermissionRow(
                title = tr("Contacts", "联系人"),
                subtitle = tr("Read and write", "读写"),
                statusText = dashboard.contactsStatus.label(isChinese),
                granted = dashboard.contactsStatus.allGranted,
                partial = dashboard.contactsStatus.partiallyGranted,
                actionText = if (dashboard.contactsStatus.allGranted) tr("Manage", "管理") else tr("Grant", "授权"),
                onAction = {
                    if (dashboard.contactsStatus.allGranted) {
                        openAppDetailsSettings(context)
                    } else {
                        onRequestPermissions(
                            arrayOf(
                                Manifest.permission.READ_CONTACTS,
                                Manifest.permission.WRITE_CONTACTS
                            )
                        )
                    }
                }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.10f))
            PermissionRow(
                title = tr("Calendar", "日历"),
                subtitle = tr("Events", "日程"),
                statusText = dashboard.calendarStatus.label(isChinese),
                granted = dashboard.calendarStatus.allGranted,
                partial = dashboard.calendarStatus.partiallyGranted,
                actionText = if (dashboard.calendarStatus.allGranted) tr("Manage", "管理") else tr("Grant", "授权"),
                onAction = {
                    if (dashboard.calendarStatus.allGranted) {
                        openAppDetailsSettings(context)
                    } else {
                        onRequestPermissions(
                            arrayOf(
                                Manifest.permission.READ_CALENDAR,
                                Manifest.permission.WRITE_CALENDAR
                            )
                        )
                    }
                }
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.10f))
            PermissionRow(
                title = tr("Media Library", "媒体库"),
                subtitle = tr("Images, video, audio", "图片、视频、音频"),
                statusText = dashboard.mediaStatus.label(isChinese),
                granted = dashboard.mediaStatus.allGranted,
                partial = dashboard.mediaStatus.partiallyGranted,
                actionText = if (dashboard.mediaStatus.allGranted) tr("Manage", "管理") else tr("Grant", "授权"),
                onAction = {
                    if (dashboard.mediaStatus.allGranted) {
                        openAppDetailsSettings(context)
                    } else {
                        onRequestPermissions(mediaRequestPermissions)
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsContent(
    state: ChatUiState,
    page: SettingsPanelPage,
    permissionsDashboard: PermissionsDashboardState,
    onNavigate: (SettingsPanelPage) -> Unit,
    onCreateSessionRequest: () -> Unit,
    onRequestPermissions: (Array<String>) -> Unit,
    onRefreshPermissionsStatus: () -> Unit,
    revealApiKey: Boolean,
    onRevealToggle: () -> Unit,
    onStartNewProviderDraft: () -> Unit,
    onSelectProviderConfig: (String) -> Unit,
    onDeleteProviderConfig: (String) -> Unit,
    onSetActiveProviderConfig: (String) -> Unit,
    onProviderChange: (String) -> Unit,
    onProviderCustomNameChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onTestProvider: () -> Unit,
    onSaveProviderDraft: () -> Unit,
    onClearProviderTokenStats: () -> Unit,
    onMaxRoundsChange: (String) -> Unit,
    onToolResultMaxCharsChange: (String) -> Unit,
    onMemoryConsolidationWindowChange: (String) -> Unit,
    onLlmCallTimeoutSecondsChange: (String) -> Unit,
    onDefaultToolTimeoutSecondsChange: (String) -> Unit,
    onContextMessagesChange: (String) -> Unit,
    onCronEnabledChange: (Boolean) -> Unit,
    onCronMinEveryMsChange: (String) -> Unit,
    onCronMaxJobsChange: (String) -> Unit,
    onRefreshCronJobs: () -> Unit,
    onSetCronJobEnabled: (String, Boolean) -> Unit,
    onRunCronJobNow: (String) -> Unit,
    onRemoveCronJob: (String) -> Unit,
    onHeartbeatEnabledChange: (Boolean) -> Unit,
    onHeartbeatIntervalSecondsChange: (String) -> Unit,
    onSetSessionChannelEnabled: (String, Boolean) -> Unit,
    onMcpEnabledChange: (Boolean) -> Unit,
    onAddMcpServer: () -> Unit,
    onRemoveMcpServer: (String) -> Unit,
    onMcpServerNameChange: (String, String) -> Unit,
    onMcpServerUrlChange: (String, String) -> Unit,
    onMcpAuthTokenChange: (String, String) -> Unit,
    onMcpToolTimeoutSecondsChange: (String, String) -> Unit,
    onTriggerHeartbeatNow: () -> Unit,
    onOpenHeartbeatEditor: () -> Unit,
    onRefreshCronLogs: () -> Unit,
    onClearCronLogs: () -> Unit,
    onRefreshAgentLogs: () -> Unit,
    onClearAgentLogs: () -> Unit,
    onCheckUpdate: () -> Unit,
    onNotifyUpdateDownloadStarted: () -> Unit,
    onNotifyUpdateDownloadFallback: (String) -> Unit,
    onSaveCurrentPage: (SettingsPanelPage) -> Unit,
    onAlwaysOnEnabledChange: (Boolean) -> Unit,
    onAlwaysOnKeepScreenAwakeChange: (Boolean) -> Unit,
    onRefreshAlwaysOnStatus: () -> Unit
) {
    val context = LocalContext.current
    val homeGroups = listOf(
        SettingsMenuGroup(
            title = tr("General", "常规"),
            items = listOf(
                SettingsMenuItem(SettingsPanelPage.Provider, tr("Provider", "提供方"), tr("Models and accounts", "模型与账号")),
                SettingsMenuItem(SettingsPanelPage.Runtime, tr("Runtime", ""), tr("Limits and logs", "限制与日志")),
                SettingsMenuItem(SettingsPanelPage.Permissions, tr("Permissions", "权限"), tr("Device and access", "设备与访问")),
                SettingsMenuItem(SettingsPanelPage.AlwaysOn, tr("Always-on", ""), tr("Background support", "后台支持"))
            )
        ),
        SettingsMenuGroup(
            title = tr("Functions", "功能"),
            items = listOf(
                SettingsMenuItem(SettingsPanelPage.Channels, tr("Channels", ""), tr("Session routes", "会话路由")),
                SettingsMenuItem(SettingsPanelPage.Mcp, "MCP", tr("Tool servers", "工具服务")),
                SettingsMenuItem(SettingsPanelPage.Cron, "Cron", tr("Scheduled jobs", "定时任务")),
                SettingsMenuItem(SettingsPanelPage.Heartbeat, tr("Heartbeat", ""), tr("Periodic prompt", "周期触发"))
            )
        ),
        SettingsMenuGroup(
            title = tr("Help", "帮助"),
            items = listOf(
                SettingsMenuItem(SettingsPanelPage.Guide, tr("User Guide", ""), tr("How to use", "使用说明")),
                SettingsMenuItem(SettingsPanelPage.About, tr("About", "关于"), tr("Version and links", "版本与链接"))
            )
        )
    )
    var showCronLogs by rememberSaveable(page) { mutableStateOf(false) }
    var showProviderEditor by rememberSaveable(page) { mutableStateOf(false) }
    var pendingCloseProviderEditor by rememberSaveable(page) { mutableStateOf(false) }
    var providerMenuExpanded by rememberSaveable(page) { mutableStateOf(false) }
    var guideSectionName by rememberSaveable(page) { mutableStateOf(UserGuideSection.Overview.name) }
    var settingsConfirmationState by remember(page) { mutableStateOf<SettingsConfirmationState?>(null) }
    val providerOptions = remember { ProviderCatalog.all() }
    val guideSection = runCatching { UserGuideSection.valueOf(guideSectionName) }
        .getOrDefault(UserGuideSection.Overview)
    fun confirmSettingsAction(
        title: String,
        message: String,
        confirmLabel: String,
        onConfirm: () -> Unit
    ) {
        settingsConfirmationState = SettingsConfirmationState(
            title = title,
            message = message,
            confirmLabel = confirmLabel,
            onConfirm = onConfirm
        )
    }
    val autoSaveKey: Any? = when (page) {
        SettingsPanelPage.AlwaysOn -> listOf(
            state.alwaysOnEnabled,
            state.alwaysOnKeepScreenAwake
        )
        SettingsPanelPage.Provider -> null
        SettingsPanelPage.Runtime -> listOf(
            state.settingsMaxToolRounds,
            state.settingsToolResultMaxChars,
            state.settingsMemoryConsolidationWindow,
            state.settingsLlmCallTimeoutSeconds,
            state.settingsLlmConnectTimeoutSeconds,
            state.settingsLlmReadTimeoutSeconds,
            state.settingsDefaultToolTimeoutSeconds,
            state.settingsContextMessages,
            state.settingsToolArgsPreviewMaxChars
        )
        SettingsPanelPage.Cron -> listOf(
            state.settingsCronEnabled,
            state.settingsCronMinEveryMs,
            state.settingsCronMaxJobs
        )
        SettingsPanelPage.Heartbeat -> listOf(
            state.settingsHeartbeatEnabled,
            state.settingsHeartbeatIntervalSeconds
        )
        SettingsPanelPage.Mcp -> listOf(
            state.settingsMcpEnabled,
            state.settingsMcpServers
        )
        else -> null
    }
    var autoSavePrimed by rememberSaveable(page) { mutableStateOf(false) }

    LaunchedEffect(page, autoSaveKey) {
        if (autoSaveKey == null) return@LaunchedEffect
        if (!autoSavePrimed) {
            autoSavePrimed = true
            return@LaunchedEffect
        }
        delay(650)
        onSaveCurrentPage(page)
    }

    LaunchedEffect(showProviderEditor, pendingCloseProviderEditor, state.settingsSaving, state.settingsInfo) {
        if (!showProviderEditor || !pendingCloseProviderEditor || state.settingsSaving) return@LaunchedEffect
        when (state.settingsInfo?.trim().orEmpty()) {
            "Provider saved." -> {
                pendingCloseProviderEditor = false
                showProviderEditor = false
                providerMenuExpanded = false
            }
            else -> {
                if (state.settingsInfo?.startsWith("Save failed") == true) {
                    pendingCloseProviderEditor = false
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        when (page) {
            SettingsPanelPage.Home -> {
                homeGroups.forEach { group ->
                    SettingsHomeGroupCard(
                        group = group,
                        onNavigate = onNavigate
                    )
                }
            }

            SettingsPanelPage.Permissions -> {
                PermissionsContent(
                    state = state,
                    dashboard = permissionsDashboard,
                    onRequestPermissions = onRequestPermissions,
                    onRefreshStatus = onRefreshPermissionsStatus
                )
            }

            SettingsPanelPage.AlwaysOn -> {
                AlwaysOnModeContent(
                    state = state,
                    onEnabledChange = onAlwaysOnEnabledChange,
                    onKeepScreenAwakeChange = onAlwaysOnKeepScreenAwakeChange,
                    onRefreshStatus = onRefreshAlwaysOnStatus
                )
            }

            SettingsPanelPage.Provider -> {
                val selectedProvider = ProviderCatalog.resolve(state.settingsProvider)
                val providerPortalUrl = providerApiPortalUrl(selectedProvider.id)
                val isEditingSavedConfig = state.settingsEditingProviderConfigId.isNotBlank()
                SettingsSectionCard(
                    title = tr("Provider", "提供方"),
                    subtitle = tr("Add the API account uses for chat.", "添加用于聊天的账号。"),
                    actions = {
                        OutlinedButton(
                            onClick = {
                                onStartNewProviderDraft()
                                pendingCloseProviderEditor = false
                                providerMenuExpanded = false
                                showProviderEditor = true
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            modifier = Modifier.height(36.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.28f),
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ),
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.32f)
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = tr("Add", "新增"),
                                maxLines = 1
                            )
                        }
                    }
                ) {
                    if (state.settingsProviderConfigs.isEmpty()) {
                        Surface(
                            tonalElevation = 0.dp,
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.22f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = tr("No API account yet. Add one below, test it, then save it.", "还没有 API 账号。先在下方添加，测试后再保存。"),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            state.settingsProviderConfigs.forEach { config ->
                                val providerServiceTitle = providerConfigServiceTitle(config)
                                val providerModelTitle = providerConfigModelTitle(config)
                                val deleteProviderTitle = localizedText(
                                    "Delete Provider",
                                    "删除提供方",
                                    useChinese = state.settingsUseChinese
                                )
                                val deleteProviderLabel = localizedText(
                                    "Delete",
                                    "删除",
                                    useChinese = state.settingsUseChinese
                                )
                                val deleteProviderMessage = irreversibleConfirmMessage(
                                    prompt = localizedText(
                                        "Delete %1\$s / %2\$s?",
                                        "删除 %1\$s / %2\$s？",
                                        useChinese = state.settingsUseChinese
                                    ).format(providerServiceTitle, providerModelTitle),
                                    useChinese = state.settingsUseChinese
                                )
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    tonalElevation = if (config.enabled) 2.dp else 0.dp,
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (config.enabled) {
                                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    }
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = providerConfigServiceTitle(config),
                                                modifier = Modifier.weight(1f),
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                ProviderActionButton(
                                                    icon = Icons.Outlined.Edit,
                                                    contentDescription = tr("Edit Provider", "编辑提供方"),
                                                    onClick = {
                                                        onSelectProviderConfig(config.id)
                                                        providerMenuExpanded = false
                                                        showProviderEditor = true
                                                        pendingCloseProviderEditor = false
                                                    }
                                                )
                                                ProviderActionButton(
                                                    icon = Icons.Rounded.CheckCircle,
                                                    contentDescription = tr("Use Provider", "启用提供方"),
                                                    onClick = {
                                                        if (!config.enabled) {
                                                            onSetActiveProviderConfig(config.id)
                                                        }
                                                    },
                                                    tint = if (config.enabled) {
                                                        MaterialTheme.colorScheme.primary
                                                    } else {
                                                        MaterialTheme.colorScheme.onSurfaceVariant
                                                    }
                                                )
                                                ProviderActionButton(
                                                    icon = Icons.Outlined.DeleteOutline,
                                                    contentDescription = tr("Delete Provider", "删除提供方"),
                                                    onClick = {
                                                        confirmSettingsAction(
                                                            title = deleteProviderTitle,
                                                            message = deleteProviderMessage,
                                                            confirmLabel = deleteProviderLabel
                                                        ) {
                                                            onDeleteProviderConfig(config.id)
                                                        }
                                                    },
                                                    tint = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        }
                                        Text(
                                            text = providerConfigModelTitle(config),
                                            modifier = Modifier.fillMaxWidth(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (showProviderEditor) {
                    var clearApiKeyOnNextFocus by rememberSaveable(
                        state.settingsEditingProviderConfigId,
                        state.settingsProvider
                    ) { mutableStateOf(true) }
                    AlertDialog(
                        onDismissRequest = {
                            pendingCloseProviderEditor = false
                            showProviderEditor = false
                            providerMenuExpanded = false
                        },
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        textContentColor = MaterialTheme.colorScheme.onSurface,
                        title = {
                            Text(
                                if (isEditingSavedConfig) {
                                    tr("Edit Provider", "编辑提供方")
                                } else {
                                    tr("Add Provider", "新增提供方")
                                }
                            )
                        },
                        text = {
                            Column(
                                modifier = Modifier.verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                ExposedDropdownMenuBox(
                                    expanded = providerMenuExpanded,
                                    onExpandedChange = { providerMenuExpanded = !providerMenuExpanded }
                                ) {
                                    SettingsSelectField(
                                        value = providerDisplayTitle(selectedProvider.id),
                                        modifier = Modifier
                                            .menuAnchor()
                                            .fillMaxWidth(),
                                        label = tr("Service", "服务"),
                                        trailingIcon = {
                                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerMenuExpanded)
                                        }
                                    )
                                    DropdownMenu(
                                        expanded = providerMenuExpanded,
                                        onDismissRequest = { providerMenuExpanded = false },
                                        shape = settingsTextFieldShape(),
                                        containerColor = MaterialTheme.colorScheme.surface,
                                        tonalElevation = 0.dp,
                                        shadowElevation = 0.dp,
                                        border = settingsDropdownMenuBorder()
                                    ) {
                                        providerOptions.forEach { option ->
                                            DropdownMenuItem(
                                                text = {
                                                    ProviderDropdownText(providerId = option.id)
                                                },
                                                onClick = {
                                                    onProviderChange(option.id)
                                                    providerMenuExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                                OutlinedButton(
                                    onClick = {
                                        providerPortalUrl?.let { openExternalUrl(context, it) }
                                    },
                                    enabled = providerPortalUrl != null,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.20f),
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                        disabledContainerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.12f),
                                        disabledContentColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.42f)
                                    )
                                ) {
                                    Text(
                                        text = providerPortalButtonText(
                                            useChinese = state.settingsUseChinese,
                                            providerTitle = providerDisplayTitle(selectedProvider.id),
                                            enabled = providerPortalUrl != null
                                        ),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                OutlinedTextField(
                                    value = state.settingsBaseUrl,
                                    onValueChange = onBaseUrlChange,
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text(uiLabel("Endpoint URL")) },
                                    singleLine = true,
                                    shape = settingsTextFieldShape(),
                                    textStyle = MaterialTheme.typography.bodyMedium,
                                    colors = settingsTextFieldColors()
                                )
                                if (selectedProvider.id == "custom") {
                                    OutlinedTextField(
                                        value = state.settingsProviderCustomName,
                                        onValueChange = onProviderCustomNameChange,
                                        modifier = Modifier.fillMaxWidth(),
                                        label = {
                                            Text(
                                                localizedText(
                                                    "Custom Name",
                                                    "自定义名称",
                                                    useChinese = state.settingsUseChinese
                                                )
                                            )
                                        },
                                        singleLine = true,
                                        shape = settingsTextFieldShape(),
                                        textStyle = MaterialTheme.typography.bodyMedium,
                                        colors = settingsTextFieldColors()
                                    )
                                }
                                ProviderModelField(
                                    providerId = selectedProvider.id,
                                    value = state.settingsModel,
                                    onValueChange = onModelChange,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = state.settingsApiKey,
                                    onValueChange = onApiKeyChange,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .onFocusChanged { focusState ->
                                            if (focusState.isFocused && clearApiKeyOnNextFocus) {
                                                if (state.settingsApiKey.isNotBlank()) {
                                                    onApiKeyChange("")
                                                }
                                                clearApiKeyOnNextFocus = false
                                            }
                                        },
                                    label = { Text(uiLabel("API Key")) },
                                    singleLine = true,
                                    visualTransformation = if (revealApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                                    shape = settingsTextFieldShape(),
                                    textStyle = MaterialTheme.typography.bodyMedium,
                                    colors = settingsTextFieldColors()
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    SettingsActionButton(
                                        text = if (revealApiKey) uiLabel("Hide Key") else uiLabel("Show Key"),
                                        icon = if (revealApiKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                        onClick = onRevealToggle
                                    )
                                    SettingsActionButton(
                                        text = if (state.settingsProviderTesting) uiLabel("Testing...") else uiLabel("Test API"),
                                        icon = Icons.Rounded.Refresh,
                                        onClick = onTestProvider,
                                        enabled = !state.settingsProviderTesting
                                    )
                                }
                                state.settingsInfo?.takeIf { it.isNotBlank() }?.let { info ->
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.28f),
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                    ) {
                                        Text(
                                            text = localizedUiMessage(info, state.settingsUseChinese),
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    onSaveProviderDraft()
                                    pendingCloseProviderEditor = true
                                },
                                enabled = !state.settingsSaving &&
                                    state.settingsBaseUrl.isNotBlank() &&
                                    state.settingsModel.isNotBlank()
                            ) {
                                Text(tr("Save", "保存"))
                            }
                        },
                        dismissButton = {
                            OutlinedButton(
                                onClick = {
                                    pendingCloseProviderEditor = false
                                    showProviderEditor = false
                                    providerMenuExpanded = false
                                }
                            ) {
                                Text(tr("Cancel", "取消"))
                            }
                        }
                    )
                }
                val inputTokens = state.settingsTokenInput.coerceAtLeast(0L)
                val outputTokens = state.settingsTokenOutput.coerceAtLeast(0L)
                val totalTokens = state.settingsTokenTotal.coerceAtLeast(0L)
                val cachedInputTokens = state.settingsTokenCachedInput.coerceAtLeast(0L)
                val requests = state.settingsTokenRequests.coerceAtLeast(0L)
                val clearTokenUsageTitle = localizedText(
                    "Clear Token Usage",
                    "清除 Token 统计",
                    useChinese = state.settingsUseChinese
                )
                val clearTokenUsageMessage = irreversibleConfirmMessage(
                    prompt = localizedText(
                        "Clear token usage totals?",
                        "清除 Token 统计？",
                        useChinese = state.settingsUseChinese
                    ),
                    useChinese = state.settingsUseChinese
                )
                val clearTokenUsageLabel = localizedText(
                    "Clear",
                    "清除",
                    useChinese = state.settingsUseChinese
                )
                val cacheHitRate = if (inputTokens > 0L) {
                    (cachedInputTokens.toDouble() / inputTokens.toDouble()) * 100.0
                } else {
                    0.0
                }
                SettingsSectionCard(
                    title = uiLabel("Token Usage"),
                    subtitle = uiLabel("Current totals for requests"),
                    actions = {
                        MinimalActionIconButton(
                            onClick = {
                                confirmSettingsAction(
                                    title = clearTokenUsageTitle,
                                    message = clearTokenUsageMessage,
                                    confirmLabel = clearTokenUsageLabel
                                ) {
                                    onClearProviderTokenStats()
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteOutline,
                                contentDescription = uiLabel("Clear")
                            )
                        }
                    }
                ) {
                    SettingsValueRow(uiLabel("Input"), inputTokens.toString())
                    SettingsValueRow(uiLabel("Output"), outputTokens.toString())
                    SettingsValueRow(uiLabel("Total"), totalTokens.toString())
                    SettingsValueRow(uiLabel("Cached Input"), cachedInputTokens.toString())
                    SettingsValueRow(uiLabel("Cache Hit Rate"), "${"%.1f".format(cacheHitRate)}%")
                    SettingsValueRow(uiLabel("Requests"), requests.toString())
                }
            }

            SettingsPanelPage.Runtime -> {
                val contextMessagesValue = state.settingsContextMessages.toIntOrNull()
                    ?.coerceIn(AppLimits.MIN_CONTEXT_MESSAGES, AppLimits.MAX_CONTEXT_MESSAGES)
                    ?: AppLimits.DEFAULT_CONTEXT_MESSAGES
                RuntimeSliderSetting(
                    title = uiLabel("Context Messages"),
                    description = uiLabel("Recent messages kept for each model turn"),
                    value = contextMessagesValue,
                    min = AppLimits.MIN_CONTEXT_MESSAGES,
                    max = AppLimits.MAX_CONTEXT_MESSAGES,
                    stepSize = 1,
                    onValueChange = { onContextMessagesChange(it.toString()) }
                )

                val maxRoundsValue = state.settingsMaxToolRounds.toIntOrNull()
                    ?.coerceIn(AppLimits.MIN_MAX_TOOL_ROUNDS, AppLimits.MAX_MAX_TOOL_ROUNDS)
                    ?: AppLimits.DEFAULT_MAX_TOOL_ROUNDS
                RuntimeSliderSetting(
                    title = uiLabel("Max Tool Rounds"),
                    description = uiLabel("How many tool-call loops one run may use"),
                    value = maxRoundsValue,
                    min = AppLimits.MIN_MAX_TOOL_ROUNDS,
                    max = AppLimits.MAX_MAX_TOOL_ROUNDS,
                    stepSize = 1,
                    onValueChange = { onMaxRoundsChange(it.toString()) }
                )

                val toolResultMaxCharsValue = state.settingsToolResultMaxChars.toIntOrNull()
                    ?.coerceIn(AppLimits.MIN_TOOL_RESULT_MAX_CHARS, AppLimits.MAX_TOOL_RESULT_MAX_CHARS)
                    ?: AppLimits.DEFAULT_TOOL_RESULT_MAX_CHARS
                RuntimeSliderSetting(
                    title = uiLabel("Tool Result Max Chars"),
                    description = uiLabel("Maximum tool output kept in chat context"),
                    value = toolResultMaxCharsValue,
                    min = AppLimits.MIN_TOOL_RESULT_MAX_CHARS,
                    max = AppLimits.MAX_TOOL_RESULT_MAX_CHARS,
                    stepSize = 100,
                    onValueChange = { onToolResultMaxCharsChange(it.toString()) }
                )

                val llmCallTimeoutValue = state.settingsLlmCallTimeoutSeconds.toIntOrNull()
                    ?.coerceIn(
                        AppLimits.MIN_LLM_CALL_TIMEOUT_SECONDS,
                        AppLimits.MAX_LLM_CALL_TIMEOUT_SECONDS
                    )
                    ?: AppLimits.DEFAULT_LLM_CALL_TIMEOUT_SECONDS
                RuntimeSliderSetting(
                    title = uiLabel("LLM Call Timeout (sec)"),
                    description = uiLabel("Time limit for one model request"),
                    value = llmCallTimeoutValue,
                    min = AppLimits.MIN_LLM_CALL_TIMEOUT_SECONDS,
                    max = AppLimits.MAX_LLM_CALL_TIMEOUT_SECONDS,
                    stepSize = 5,
                    onValueChange = { onLlmCallTimeoutSecondsChange(it.toString()) }
                )

                val toolTimeoutValue = state.settingsDefaultToolTimeoutSeconds.toIntOrNull()
                    ?.coerceIn(AppLimits.MIN_TOOL_TIMEOUT_SECONDS, AppLimits.MAX_TOOL_TIMEOUT_SECONDS)
                    ?: AppLimits.DEFAULT_TOOL_TIMEOUT_SECONDS
                RuntimeSliderSetting(
                    title = uiLabel("Default Tool Timeout (sec)"),
                    description = uiLabel("Fallback limit when a tool has no own timeout"),
                    value = toolTimeoutValue,
                    min = AppLimits.MIN_TOOL_TIMEOUT_SECONDS,
                    max = AppLimits.MAX_TOOL_TIMEOUT_SECONDS,
                    stepSize = 5,
                    onValueChange = { onDefaultToolTimeoutSecondsChange(it.toString()) }
                )

                val memoryWindowValue = state.settingsMemoryConsolidationWindow.toIntOrNull()
                    ?.coerceIn(
                        AppLimits.MIN_MEMORY_CONSOLIDATION_WINDOW,
                        AppLimits.MAX_MEMORY_CONSOLIDATION_WINDOW
                    )
                    ?: AppLimits.DEFAULT_MEMORY_CONSOLIDATION_WINDOW
                RuntimeSliderSetting(
                    title = uiLabel("Memory Consolidation Window"),
                    description = uiLabel("Message threshold before memory is compacted"),
                    value = memoryWindowValue,
                    min = AppLimits.MIN_MEMORY_CONSOLIDATION_WINDOW,
                    max = AppLimits.MAX_MEMORY_CONSOLIDATION_WINDOW,
                    stepSize = 10,
                    onValueChange = { onMemoryConsolidationWindowChange(it.toString()) }
                )

                ScrollableLogWindow(
                    title = uiLabel("Agent Logs"),
                    content = state.settingsAgentLogs,
                    emptyText = uiLabel("No agent logs yet"),
                    actions = {
                        val clearAgentLogsTitle = localizedText(
                            "Clear Agent Logs",
                            "清除 Agent 日志",
                            useChinese = state.settingsUseChinese
                        )
                        val clearAgentLogsMessage = irreversibleConfirmMessage(
                            prompt = localizedText(
                                "Clear agent logs?",
                                "清除 Agent 日志？",
                                useChinese = state.settingsUseChinese
                            ),
                            useChinese = state.settingsUseChinese
                        )
                        val clearAgentLogsLabel = localizedText(
                            "Clear",
                            "清除",
                            useChinese = state.settingsUseChinese
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SettingsActionButton(
                                text = uiLabel("Refresh"),
                                icon = Icons.Rounded.Refresh,
                                onClick = onRefreshAgentLogs
                            )
                            SettingsActionButton(
                                text = uiLabel("Clear"),
                                icon = Icons.Outlined.DeleteOutline,
                                onClick = {
                                    confirmSettingsAction(
                                        title = clearAgentLogsTitle,
                                        message = clearAgentLogsMessage,
                                        confirmLabel = clearAgentLogsLabel
                                    ) {
                                        onClearAgentLogs()
                                    }
                                }
                            )
                        }
                    }
                )
            }

            SettingsPanelPage.Cron -> {
                SettingsSectionCard(
                    title = uiLabel("Scheduler"),
                    subtitle = uiLabel("Enable cron and set basic limits")
                ) {
                    SettingsToggleRow(
                        title = uiLabel("Cron scheduler"),
                        checked = state.settingsCronEnabled,
                        onCheckedChange = onCronEnabledChange
                    )
                    OutlinedTextField(
                        value = state.settingsCronMinEveryMs,
                        onValueChange = onCronMinEveryMsChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(uiLabel("Min Interval (ms)")) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = settingsTextFieldShape(),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        colors = settingsTextFieldColors()
                    )
                    OutlinedTextField(
                        value = state.settingsCronMaxJobs,
                        onValueChange = onCronMaxJobsChange,
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
                            onClick = onRefreshCronJobs
                        )
                        SettingsActionButton(
                            text = if (showCronLogs) uiLabel("Hide Logs") else uiLabel("Show Logs"),
                            icon = if (showCronLogs) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                            onClick = {
                                val next = !showCronLogs
                                showCronLogs = next
                                if (next) onRefreshCronLogs()
                            }
                        )
                    }
                ) {
                    if (state.settingsCronJobsLoading) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(uiLabel("Loading cron jobs..."), style = MaterialTheme.typography.bodySmall)
                        }
                    } else if (state.settingsCronJobs.isEmpty()) {
                        Text(
                            text = uiLabel("No cron jobs yet"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        state.settingsCronJobs.forEach { job ->
                            val removeJobTitle = localizedText(
                                "Remove Job",
                                "移除任务",
                                useChinese = state.settingsUseChinese
                            )
                            val removeJobLabel = localizedText(
                                "Remove",
                                "移除",
                                useChinese = state.settingsUseChinese
                            )
                            val removeJobMessage = irreversibleConfirmMessage(
                                prompt = localizedText(
                                    "Remove '%s'?",
                                    "移除 '%s'？",
                                    useChinese = state.settingsUseChinese
                                ).format(job.name),
                                useChinese = state.settingsUseChinese
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
                                            onCheckedChange = { enabled -> onSetCronJobEnabled(job.id, enabled) }
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
                                            value = localizedUiMessage(it, state.settingsUseChinese),
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
                                            onClick = { onRunCronJobNow(job.id) }
                                        )
                                        SettingsActionButton(
                                            text = uiLabel("Remove"),
                                            icon = Icons.Outlined.DeleteOutline,
                                            onClick = {
                                                confirmSettingsAction(
                                                    title = removeJobTitle,
                                                    message = removeJobMessage,
                                                    confirmLabel = removeJobLabel
                                                ) {
                                                    onRemoveCronJob(job.id)
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (showCronLogs) {
                    val clearCronLogsTitle = localizedText(
                        "Clear Cron Logs",
                        "清除 Cron 日志",
                        useChinese = state.settingsUseChinese
                    )
                val clearCronLogsMessage = irreversibleConfirmMessage(
                    prompt = localizedText(
                        "Clear cron logs?",
                        "清除 Cron 日志？",
                        useChinese = state.settingsUseChinese
                    ),
                    useChinese = state.settingsUseChinese
                )
                    val clearCronLogsLabel = localizedText(
                        "Clear",
                        "清除",
                        useChinese = state.settingsUseChinese
                    )
                    ScrollableLogWindow(
                        title = uiLabel("Cron Logs"),
                        content = state.settingsCronLogs,
                        emptyText = uiLabel("No cron logs yet"),
                        actions = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SettingsActionButton(
                                    text = uiLabel("Refresh"),
                                    icon = Icons.Rounded.Refresh,
                                    onClick = onRefreshCronLogs
                                )
                                SettingsActionButton(
                                    text = uiLabel("Clear"),
                                icon = Icons.Outlined.DeleteOutline,
                                onClick = {
                                    confirmSettingsAction(
                                        title = clearCronLogsTitle,
                                        message = clearCronLogsMessage,
                                        confirmLabel = clearCronLogsLabel
                                    ) {
                                        onClearCronLogs()
                                    }
                                    }
                                )
                            }
                        }
                    )
                }
            }

            SettingsPanelPage.Heartbeat -> {
                SettingsSectionCard(
                    title = uiLabel("Heartbeat"),
                    subtitle = uiLabel("Periodic prompt driven by HEARTBEAT.md")
                ) {
                    SettingsToggleRow(
                        title = uiLabel("Heartbeat"),
                        checked = state.settingsHeartbeatEnabled,
                        onCheckedChange = onHeartbeatEnabledChange
                    )
                    OutlinedTextField(
                        value = state.settingsHeartbeatIntervalSeconds,
                        onValueChange = onHeartbeatIntervalSecondsChange,
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
                            onClick = onTriggerHeartbeatNow
                        )
                        SettingsActionButton(
                            text = uiLabel("Edit Doc"),
                            icon = Icons.Rounded.Description,
                            onClick = onOpenHeartbeatEditor
                        )
                    }
                }
            }

            SettingsPanelPage.Channels -> {
                val nonLocalSessions = state.sessions.filterNot { it.isLocal }
                val boundCount = state.settingsConnectedChannels.size
                val readyCount = state.settingsConnectedChannels.count {
                    it.status.startsWith("Ready", ignoreCase = true) ||
                        it.status.startsWith("Experimental", ignoreCase = true)
                }
                val issueCount = state.settingsConnectedChannels.count {
                    !it.status.startsWith("Ready", ignoreCase = true) &&
                        !it.status.startsWith("Experimental", ignoreCase = true)
                }
                val unboundCount = nonLocalSessions.count { session ->
                    val isTelegramPending =
                        session.boundChannel.equals("telegram", ignoreCase = true) &&
                            session.boundTelegramBotToken.isNotBlank() &&
                            session.boundChatId.isBlank()
                    val isFeishuPending =
                        session.boundChannel.equals("feishu", ignoreCase = true) &&
                            session.boundFeishuAppId.isNotBlank() &&
                            session.boundFeishuAppSecret.isNotBlank() &&
                            session.boundChatId.isBlank()
                    val isEmailPending =
                        session.boundChannel.equals("email", ignoreCase = true) &&
                            session.boundEmailConsentGranted &&
                            session.boundEmailImapHost.isNotBlank() &&
                            session.boundEmailImapUsername.isNotBlank() &&
                            session.boundEmailImapPassword.isNotBlank() &&
                            session.boundEmailSmtpHost.isNotBlank() &&
                            session.boundEmailSmtpUsername.isNotBlank() &&
                            session.boundEmailSmtpPassword.isNotBlank() &&
                            session.boundChatId.isBlank()
                    val isWeComPending =
                        session.boundChannel.equals("wecom", ignoreCase = true) &&
                            session.boundWeComBotId.isNotBlank() &&
                            session.boundWeComSecret.isNotBlank() &&
                            session.boundChatId.isBlank()
                    session.boundChannel.isBlank() || (
                        session.boundChatId.isBlank() &&
                            !isTelegramPending &&
                            !isFeishuPending &&
                            !isEmailPending &&
                            !isWeComPending
                        )
                }
                val telegramBound = state.settingsConnectedChannels.count { it.channel.equals("telegram", ignoreCase = true) }
                val discordBound = state.settingsConnectedChannels.count { it.channel.equals("discord", ignoreCase = true) }
                val slackBound = state.settingsConnectedChannels.count { it.channel.equals("slack", ignoreCase = true) }
                val feishuBound = state.settingsConnectedChannels.count { it.channel.equals("feishu", ignoreCase = true) }
                val emailBound = state.settingsConnectedChannels.count { it.channel.equals("email", ignoreCase = true) }
                val wecomBound = state.settingsConnectedChannels.count { it.channel.equals("wecom", ignoreCase = true) }
                SettingsSectionCard(
                    title = tr("Session Routes", "会话路由"),
                    subtitle = if (nonLocalSessions.isEmpty()) {
                        tr("Create a session first, then connect it to a channel", "先创建一个会话，再把它连接到渠道")
                    } else {
                        tr("Manage channel bindings for each session", "管理每个会话的渠道绑定")
                    }
                ) {
                    if (nonLocalSessions.isEmpty()) {
                        Surface(
                            tonalElevation = 0.dp,
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.22f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = tr("No user-created sessions yet", "还没有用户创建的会话"),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = tr("Create a session from the chat sidebar first", "先从聊天侧边栏创建一个会话"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = tr(
                                        "After that, open Session Settings for that session and configure its channel binding",
                                        "然后打开该会话的会话设置，完成渠道绑定。"
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                SettingsActionButton(
                                    text = tr("Create Session", "创建会话"),
                                    icon = Icons.Rounded.Add,
                                    onClick = onCreateSessionRequest
                                )
                            }
                        }
                    } else {
                        nonLocalSessions.forEach { session ->
                            val isTelegramPending =
                                session.boundChannel.equals("telegram", ignoreCase = true) &&
                                    session.boundTelegramBotToken.isNotBlank() &&
                                    session.boundChatId.isBlank()
                            val isFeishuPending =
                                session.boundChannel.equals("feishu", ignoreCase = true) &&
                                    session.boundFeishuAppId.isNotBlank() &&
                                    session.boundFeishuAppSecret.isNotBlank() &&
                                    session.boundChatId.isBlank()
                            val isEmailPending =
                                session.boundChannel.equals("email", ignoreCase = true) &&
                                    session.boundEmailConsentGranted &&
                                    session.boundEmailImapHost.isNotBlank() &&
                                    session.boundEmailImapUsername.isNotBlank() &&
                                    session.boundEmailImapPassword.isNotBlank() &&
                                    session.boundEmailSmtpHost.isNotBlank() &&
                                    session.boundEmailSmtpUsername.isNotBlank() &&
                                    session.boundEmailSmtpPassword.isNotBlank() &&
                                    session.boundChatId.isBlank()
                            val isWeComPending =
                                session.boundChannel.equals("wecom", ignoreCase = true) &&
                                    session.boundWeComBotId.isNotBlank() &&
                                    session.boundWeComSecret.isNotBlank() &&
                                    session.boundChatId.isBlank()
                            val hasBinding = session.boundChannel.isNotBlank() &&
                                (
                                    session.boundChatId.isNotBlank() ||
                                        isTelegramPending ||
                                        isFeishuPending ||
                                        isEmailPending ||
                                        isWeComPending
                                    )
                            val channelSummary = if (hasBinding) {
                                channelDisplayLabel(session.boundChannel)
                            } else {
                                tr("Not configured", "")
                            }
                            val status = state.settingsConnectedChannels
                                .firstOrNull { it.sessionId == session.id }
                                ?.status
                                ?: if (hasBinding) uiLabel("Configured") else uiLabel("Not configured")
                            val connectionSummary = if (hasBinding) {
                                buildString {
                                    append(
                                        when {
                                            session.boundChatId.isNotBlank() -> session.boundChatId
                                            isTelegramPending || isFeishuPending || isEmailPending || isWeComPending -> tr("Pending detection", "")
                                            else -> tr("Not configured", "")
                                        }
                                    )
                                    append(" · ")
                                    append(uiLabel(status))
                                    if (!session.boundEnabled) {
                                        append(" · ")
                                        append(tr("Off", ""))
                                    }
                                }
                            } else {
                                tr("Not configured", "")
                            }
                            Surface(
                                tonalElevation = 1.dp,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.22f)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(
                                            text = session.title,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = channelSummary,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = connectionSummary,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    PalmClawSwitch(
                                        checked = hasBinding && session.boundEnabled,
                                        onCheckedChange = { checked ->
                                            if (hasBinding) {
                                                onSetSessionChannelEnabled(session.id, checked)
                                            }
                                        },
                                        enabled = hasBinding
                                    )
                                }
                            }
                        }
                    }
                }
                SettingsSectionCard(
                    title = uiLabel("Connection Diagnostics"),
                    subtitle = uiLabel("Session and route status")
                ) {
                    SettingsValueRow(uiLabel("Gateway"), uiLabel(if (state.settingsGatewayEnabled) "Enabled" else "Disabled"))
                    SettingsValueRow(uiLabel("Sessions"), nonLocalSessions.size.toString())
                    SettingsValueRow(uiLabel("Bound"), boundCount.toString())
                    SettingsValueRow(uiLabel("Ready"), readyCount.toString())
                    SettingsValueRow(uiLabel("Issues"), issueCount.toString())
                    SettingsValueRow(uiLabel("Unbound"), unboundCount.toString())
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    SettingsValueRow(uiLabel("Telegram"), telegramBound.toString())
                    SettingsValueRow(uiLabel("Discord"), discordBound.toString())
                    SettingsValueRow(uiLabel("Slack"), slackBound.toString())
                    SettingsValueRow(uiLabel("Feishu"), feishuBound.toString())
                    SettingsValueRow(uiLabel("Email"), emailBound.toString())
                    SettingsValueRow(uiLabel("WeCom"), wecomBound.toString())
                }
            }

            SettingsPanelPage.Mcp -> {
                SettingsSectionCard(
                    title = uiLabel("MCP Remote"),
                    subtitle = tr(
                        "Remote HTTPS only. Local HTTP allowed",
                        "远程仅支持 HTTPS，本地可用 HTTP"
                    )
                ) {
                    SettingsToggleRow(
                        title = uiLabel("Enable MCP Remote"),
                        checked = state.settingsMcpEnabled,
                        onCheckedChange = onMcpEnabledChange
                    )
                    SettingsActionButton(
                        text = if (revealApiKey) uiLabel("Hide Tokens") else uiLabel("Show Tokens"),
                        icon = if (revealApiKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        onClick = onRevealToggle
                    )
                }
                SettingsSectionCard(
                    title = uiLabel("Servers"),
                    actions = {
                        SettingsActionButton(
                            text = uiLabel("Add Server"),
                            icon = Icons.Rounded.Add,
                            onClick = onAddMcpServer
                        )
                    }
                ) {
                    if (state.settingsMcpServers.isEmpty()) {
                        Text(
                            text = uiLabel("No MCP servers configured"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    state.settingsMcpServers.forEachIndexed { index, server ->
                        val serverDisplayName = server.serverName.trim().ifBlank {
                            "${uiLabel("Server")} ${index + 1}"
                        }
                        val removeServerTitle = localizedText(
                            "Remove Server",
                            "移除 Server",
                            useChinese = state.settingsUseChinese
                        )
                        val removeServerLabel = localizedText(
                            "Remove",
                            "移除",
                            useChinese = state.settingsUseChinese
                        )
                        val removeServerMessage = irreversibleConfirmMessage(
                            prompt = localizedText(
                                "Remove '%s'?",
                                "移除 '%s'？",
                                useChinese = state.settingsUseChinese
                            ).format(serverDisplayName),
                            useChinese = state.settingsUseChinese
                        )
                        val serverUsableLabel = uiLabel(if (server.usable) "Usable" else "Unavailable")
                        val serverStatusLabel = uiLabel(server.status)
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
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Text(
                                            text = "${uiLabel("Server")} ${index + 1}",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = "$serverUsableLabel · ${uiLabel("Status")}: $serverStatusLabel",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = when (server.status.lowercase()) {
                                                "connected" -> if (server.usable) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.tertiary
                                                }
                                                "error" -> MaterialTheme.colorScheme.error
                                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        )
                                    }
                                    SettingsActionButton(
                                        text = uiLabel("Remove"),
                                        icon = Icons.Outlined.DeleteOutline,
                                        onClick = {
                                            confirmSettingsAction(
                                                title = removeServerTitle,
                                                message = removeServerMessage,
                                                confirmLabel = removeServerLabel
                                            ) {
                                                onRemoveMcpServer(server.id)
                                            }
                                        }
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    SettingsInfoBlock(
                                        label = uiLabel("Status"),
                                        value = server.status,
                                        modifier = Modifier.weight(1f),
                                        valueColor = when (server.status.lowercase()) {
                                            "connected" -> if (server.usable) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.tertiary
                                            }
                                            "error" -> MaterialTheme.colorScheme.error
                                            else -> MaterialTheme.colorScheme.onSurface
                                        },
                                        maxLines = 1
                                    )
                                    SettingsInfoBlock(
                                        label = uiLabel("Tools"),
                                        value = server.toolCount.toString(),
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1
                                    )
                                }
                                server.detail.takeIf { it.isNotBlank() }?.let {
                                    SettingsInfoBlock(
                                        label = uiLabel("Detail"),
                                        value = localizedUiMessage(it, state.settingsUseChinese),
                                        maxLines = 3
                                    )
                                }
                                OutlinedTextField(
                                    value = server.serverName,
                                    onValueChange = { value -> onMcpServerNameChange(server.id, value) },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text(uiLabel("Server Name")) },
                                    singleLine = true,
                                    shape = settingsTextFieldShape(),
                                    textStyle = MaterialTheme.typography.bodyMedium,
                                    colors = settingsTextFieldColors()
                                )
                                OutlinedTextField(
                                    value = server.serverUrl,
                                    onValueChange = { value -> onMcpServerUrlChange(server.id, value) },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text(uiLabel("Endpoint URL")) },
                                    singleLine = true,
                                    shape = settingsTextFieldShape(),
                                    textStyle = MaterialTheme.typography.bodyMedium,
                                    colors = settingsTextFieldColors()
                                )
                                OutlinedTextField(
                                    value = server.authToken,
                                    onValueChange = { value -> onMcpAuthTokenChange(server.id, value) },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text(uiLabel("Auth Token")) },
                                    singleLine = true,
                                    visualTransformation = if (revealApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                                    shape = settingsTextFieldShape(),
                                    textStyle = MaterialTheme.typography.bodyMedium,
                                    colors = settingsTextFieldColors()
                                )
                                OutlinedTextField(
                                    value = server.toolTimeoutSeconds,
                                    onValueChange = { value -> onMcpToolTimeoutSecondsChange(server.id, value) },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text(uiLabel("Tool Timeout (sec)")) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    shape = settingsTextFieldShape(),
                                    textStyle = MaterialTheme.typography.bodyMedium,
                                    colors = settingsTextFieldColors()
                                )
                            }
                        }
                    }
                }
            }

            SettingsPanelPage.Guide -> {
                UserGuideContent(
                    isChinese = state.settingsUseChinese,
                    selected = guideSection,
                    onSelect = { guideSectionName = it.name }
                )
            }

            SettingsPanelPage.About -> {
                AboutContent(
                    state = state,
                    onCheckUpdate = onCheckUpdate,
                    onNotifyUpdateDownloadStarted = onNotifyUpdateDownloadStarted,
                    onNotifyUpdateDownloadFallback = onNotifyUpdateDownloadFallback
                )
            }

        }
        settingsConfirmationState?.let { confirmation ->
            AlertDialog(
                onDismissRequest = { settingsConfirmationState = null },
                properties = DialogProperties(
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true
                ),
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                textContentColor = MaterialTheme.colorScheme.onSurface,
                title = { Text(confirmation.title) },
                text = { DialogBodyText(confirmation.message) },
                confirmButton = {
                    Button(
                        onClick = {
                            val confirmedAction = confirmation.onConfirm
                            settingsConfirmationState = null
                            confirmedAction()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) {
                        Text(confirmation.confirmLabel)
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = { settingsConfirmationState = null }) {
                        Text(tr("Cancel", "取消"))
                    }
                }
            )
        }
    }
}

@Composable
internal fun RuntimeSliderSetting(
    title: String,
    description: String,
    value: Int,
    min: Int,
    max: Int,
    stepSize: Int,
    onValueChange: (Int) -> Unit
) {
    val safeStep = stepSize.coerceAtLeast(1)
    val clamped = value.coerceIn(min, max)
    val points = ((max - min) / safeStep) + 1
    val sliderSteps = (points - 2).coerceAtLeast(0)
    val sliderValue = ((clamped - min) / safeStep.toFloat()).coerceIn(0f, (points - 1).toFloat())

    Surface(
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.34f),
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = uiLabel(title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Text(
                        text = clamped.toString(),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            if (description.isNotBlank()) {
                Text(
                    text = uiLabel(description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Slider(
                value = sliderValue,
                onValueChange = { raw ->
                    val snapped = raw.roundToInt().coerceIn(0, points - 1)
                    val mapped = (min + snapped * safeStep).coerceIn(min, max)
                    onValueChange(mapped)
                },
                valueRange = 0f..(points - 1).toFloat(),
                steps = sliderSteps
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = min.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = max.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

internal enum class UserGuideSection {
    Overview,
    Agent,
    Tools,
    Memory,
    Sessions,
    Channels,
    Skills,
    Cron,
    Heartbeat,
    AlwaysOn,
    Mcp;

    fun title(isChinese: Boolean): String = if (isChinese) {
        when (this) {
            Overview -> "概览"
            Agent -> "智能体"
            Tools -> "工具"
            Memory -> "记忆"
            Sessions -> "会话"
            Channels -> "渠道"
            Skills -> "技能"
            Cron -> "Cron"
            Heartbeat -> "Heartbeat"
            AlwaysOn -> "常驻"
            Mcp -> "MCP"
        }
    } else {
        when (this) {
            Overview -> "Overview"
            Agent -> "Agent"
            Tools -> "Tools"
            Memory -> "Memory"
            Sessions -> "Sessions"
            Channels -> "Channels"
            Skills -> "Skills"
            Cron -> "Cron"
            Heartbeat -> "Heartbeat"
            AlwaysOn -> "Always-on"
            Mcp -> "MCP"
        }
    }

    fun content(isChinese: Boolean): List<String> = if (isChinese) {
        when (this) {
            Overview -> listOf(
                "PalmClaw 是一个按会话组织的本地 AI 助手。你可以在不同会话里运行智能体、连接渠道、安排任务，并把重要信息保存到记忆中。",
                "日常使用建议先从本地会话开始。先确认模型提供方可用，再按需要启用渠道、Cron、Heartbeat 或 MCP。",
                "如果你希望手机在后台持续处理远程消息，可以开启常驻模式。普通聊天和工具使用，常规模式通常已经足够。"
            )
            Agent -> listOf(
                "智能体是核心执行者。你发送消息后，它会结合当前会话上下文、可用工具和记忆来决定下一步动作。",
                "好的提示通常包含目标、约束和期望输出格式。请求越清晰，结果通常越稳定。",
                "如果结果看起来不对，先检查当前会话、提供方配置、可用工具和最近消息，不要一开始就重置所有设置。"
            )
            Tools -> listOf(
                "工具让智能体执行真实动作，例如读取文件、发送消息、检查状态，或控制自动化能力。",
                "不同工具的作用范围不同。有些只影响当前会话，有些会修改全局设置；使用前先确认目标。",
                "如果你不确定当前有哪些工具可用，可以先看工具说明，或让智能体先列出相关选项。"
            )
            Memory -> listOf(
                "记忆分为两层：共享记忆和会话历史。共享记忆适合长期事实，会话历史则保存某个会话里的过程和结论。",
                "如果某条信息只和一个会话相关，就留在该会话历史里；只有需要跨会话共享时，再写入共享记忆。",
                "随着对话变长，应用可以自动整理记忆，让长会话保持更轻，同时保留重要信息。"
            )
            Sessions -> listOf(
                "会话是组织工作的基本单位。每个会话都有自己的消息历史，也可以有独立的渠道绑定，用来承载不同主题或联系人。",
                "如果你同时处理不同项目、不同人，或不同平台，建议拆成独立会话。这样上下文更干净，也能减少发错地方的风险。",
                "本地会话适合管理、诊断和控制；绑定远程渠道的会话更适合通过 Telegram、邮件、企业微信等渠道进行真实对话。"
            )
            Channels -> listOf(
                "渠道用于把会话连接到外部平台。通常分两步完成：先保存凭据，再检测目标并完成绑定。",
                "理想情况下，一个会话应尽量只对应一条清晰的外部通信路径，这样路由更容易理解，也能减少误操作。",
                "如果连接看起来不对，先看 Connection，再看 Configure。前者展示当前状态，后者负责修改配置。"
            )
            Skills -> listOf(
                "技能会给智能体增加额外的工作流和知识，适合封装可重复任务、固定流程，或特定领域的指导。",
                "如果你经常做同一类工作，例如结构化总结、固定 API 流程或标准审查，技能会让行为更稳定。",
                "大多数用户第一天并不需要技能。先把会话、工具和渠道跑通，再在确实有帮助时加入技能。"
            )
            Cron -> listOf(
                "Cron 适合做定时任务，例如提醒、检查，或按会话触发的消息分发。",
                "配置时先确认全局调度器已经开启，再检查每个任务的计划、下次运行时间和最近状态。",
                "如果某个任务行为异常，先看任务本身是否启用、调度是否合理，以及目标会话或渠道是否可用。"
            )
            Heartbeat -> listOf(
                "Heartbeat 会按固定间隔运行提示词，内容来自 HEARTBEAT.md，适合做例行检查、日报总结或自驱提醒。",
                "如果想快速验证效果，先手动触发一次，确认输出合适后再开启定时运行。",
                "Heartbeat 更适合轻量、可重复的任务；更强交互或更严格的流程，通常更适合放到普通会话或 Cron。"
            )
            AlwaysOn -> listOf(
                "常驻模式会尽可能让应用在后台持续工作，适合你需要更稳定远程回复的场景。",
                "重要提示：即使开启常驻，也无法保证长时间运行的绝对稳定性。建议经常打开应用，或在条件允许时保持亮屏。",
                "它在充电、网络稳定且关闭电池优化时效果最好。",
                "即使开启常驻模式，手机端应用也仍然不同于云服务器。网络条件、系统限制和省电策略仍会影响长期稳定性。"
            )
            Mcp -> listOf(
                "MCP 用于接入外部服务端能力，让智能体可以调用远程工具。",
                "只有确实需要远程工具时再添加服务器。配置越精简，越容易排查问题。",
                "如果某个 MCP 服务器看起来不可用，先检查 URL、认证 Token 和工具超时，再确认服务器本身是否健康。"
            )
        }
    } else {
        when (this) {
            Overview -> listOf(
                "PalmClaw is a session-based local AI assistant. You can run the agent in different sessions, connect channels, schedule jobs, and keep important information in memory.",
                "For everyday use, start with the local session. Make sure your provider works first, then enable channels, cron, heartbeat, or MCP only when needed.",
                "Use Always-on when you want the phone to keep handling remote messages in background. For normal chat and tool use, regular mode is usually enough."
            )
            Agent -> listOf(
                "The agent is the core worker. When you send a message, it uses the current session context, available tools, and memory to decide what to do next.",
                "Good prompts usually include a goal, constraints, and the desired output format. The clearer the request, the more stable the result.",
                "If the result looks wrong, check the current session, provider setup, available tools, and recent messages before resetting everything."
            )
            Tools -> listOf(
                "Tools let the agent perform real actions such as reading files, sending messages, checking status, or controlling automation features.",
                "Different tools have different scopes. Some affect only the current session, while others change global settings. Confirm the target before using global-setting tools.",
                "If you are unsure what is available, check the tool descriptions or ask the agent to list the relevant options first."
            )
            Memory -> listOf(
                "Memory has two layers: shared memory and session history. Shared memory is for long-term facts, while session history keeps the process and conclusions of a specific session.",
                "If something matters only to one session, keep it in that session's history. Put information into shared memory only when it should be visible across sessions.",
                "As conversations grow, the app can consolidate memory automatically so long chats stay lighter while important information remains available."
            )
            Sessions -> listOf(
                "Sessions are the basic unit for organizing work. Each session has its own message history, may have its own channel binding, and can be used for different topics or contacts.",
                "If you work on different projects, people, or platforms, split them into separate sessions. This keeps context cleaner and reduces the chance of sending to the wrong place.",
                "The local session is good for admin, diagnostics, and control. Bound remote sessions are better for real conversations through Telegram, email, WeCom, and similar channels."
            )
            Channels -> listOf(
                "Channels connect a session to an external platform. Setup usually happens in two steps: save credentials first, then detect the target and finish binding.",
                "A session should ideally map to one clear external communication path. That keeps routing easier to understand and reduces mistakes.",
                "If a connection looks wrong, check Connection first and Configure second. Connection shows the current state, while Configure is where you change setup."
            )
            Skills -> listOf(
                "Skills extend the agent with extra workflows and knowledge. They are useful for packaging repeatable tasks, fixed procedures, or domain-specific guidance.",
                "If you repeatedly do the same kind of work, such as structured summaries, API routines, or standard reviews, skills can make behavior much more consistent.",
                "Most users do not need skills on day one. Get sessions, tools, and channels working first, then add skills only when they clearly help."
            )
            Cron -> listOf(
                "Cron is for scheduled work such as reminders, checks, or session-based message dispatches.",
                "When configuring it, first make sure the global scheduler is enabled, then verify each job's schedule, next run time, and latest status.",
                "If a job does not behave as expected, check whether the job itself is enabled, whether the schedule makes sense, and whether the target session or channel is available."
            )
            Heartbeat -> listOf(
                "Heartbeat runs a prompt on a fixed interval, using content from HEARTBEAT.md. It is useful for routine checks, daily summaries, or self-driven reminders.",
                "To validate behavior quickly, trigger it manually first and enable scheduling only after the output looks right.",
                "Heartbeat works best for lightweight and repeatable jobs. More interactive or strict workflows are usually better handled through regular sessions or cron."
            )
            AlwaysOn -> listOf(
                "Always-on keeps the app working in background as reliably as possible. It is useful when you need more stable remote replies.",
                "Important: Even with Always-on enabled, absolute long-term stability cannot be guaranteed. Open the app regularly, or keep the screen awake when possible.",
                "It works best while charging, on a stable network, and with battery optimization disabled.",
                "Even in Always-on mode, the app is still not the same as a cloud server. Network conditions, OS limits, and power-saving rules can still affect long-term reliability."
            )
            Mcp -> listOf(
                "MCP connects external server-side capabilities so the agent can use remote tools.",
                "Add servers only when you really need remote tools. The smaller the setup, the easier it is to troubleshoot.",
                "If an MCP server looks unavailable, first check the URL, auth token, and tool timeout, then confirm the server itself is healthy."
            )
        }
    }
}

@Composable
internal fun UserGuideContent(
    isChinese: Boolean,
    selected: UserGuideSection,
    onSelect: (UserGuideSection) -> Unit
) {
    val sections = remember {
        listOf(
            UserGuideSection.Overview,
            UserGuideSection.Agent,
            UserGuideSection.Tools,
            UserGuideSection.Memory,
            UserGuideSection.Sessions,
            UserGuideSection.Channels,
            UserGuideSection.Skills,
            UserGuideSection.Cron,
            UserGuideSection.Heartbeat,
            UserGuideSection.Mcp,
            UserGuideSection.AlwaysOn
        )
    }

    Surface(
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.26f),
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier.width(104.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                sections.forEach { section ->
                    val active = section == selected
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(section) },
                        tonalElevation = if (active) 1.dp else 0.dp,
                        shape = RoundedCornerShape(10.dp),
                        color = if (active) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.18f)
                        }
                    ) {
                        Text(
                            text = section.title(isChinese),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (active) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }

            Surface(
                modifier = Modifier.weight(1f),
                tonalElevation = 0.dp,
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = selected.title(isChinese),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    selected.content(isChinese).forEach { paragraph ->
                        Text(
                            text = paragraph,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 20.sp
                        )
                    }
                }
            }
        }
    }
}

