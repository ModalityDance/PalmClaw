package com.palmclaw.ui

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.DownloadManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.media.MediaPlayer
import android.os.Build
import android.os.Environment
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Refresh
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
import com.palmclaw.config.SearchProviderId
import com.palmclaw.tools.AndroidUserActionBridge
import com.palmclaw.tools.AndroidUserActionRequester
import com.palmclaw.tools.hasAllFilesAccess
import com.palmclaw.tools.hasPermission
import com.palmclaw.ui.settings.SkillsSettingsSection
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
    Tools,
    Skills,
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
        Tools -> localizedText("Tools", "工具", useChinese = isChinese)
        Skills -> localizedText("Skills", "技能", useChinese = isChinese)
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
        Tools -> localizedText("Built-in tools and search provider", "内置工具与搜索提供方", useChinese = isChinese)
        Skills -> localizedText("Installed skills and ClawHub", "已安装技能与 ClawHub", useChinese = isChinese)
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
internal fun AboutContent(
    state: UpdateSettingsState,
    onCheckUpdate: () -> Unit,
    onNotifyUpdateDownloadStarted: () -> Unit,
    onNotifyUpdateDownloadFallback: (String) -> Unit
) {
    val context = LocalContext.current
    val aboutInfo = remember(context.applicationContext) {
        readInstalledAppAboutInfo(context.applicationContext)
    }
    val currentVersion = state.currentVersion.ifBlank { aboutInfo.versionName }
    val latestVersion = state.latestVersion.ifBlank { currentVersion }
    val downloadUrl = state.downloadUrl.ifBlank { PALMCLAW_APK_URL }
    val releasesUrl = state.releaseUrl.ifBlank { PALMCLAW_RELEASES_URL }
    val versionSubtitle = when {
        state.available -> tr(
            "New version $latestVersion is available.",
            "发现新版本 $latestVersion。"
        )
        state.latestVersion.isNotBlank() -> tr(
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
                color = if (state.available) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
                } else {
                    MaterialTheme.colorScheme.surface
                },
                contentColor = if (state.available) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                border = BorderStroke(
                    1.dp,
                    if (state.available) {
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
                    text = if (state.checking) {
                        tr("Checking...", "检查中...")
                    } else {
                        tr("Check Update", "检查更新")
                    },
                    icon = Icons.Rounded.Refresh,
                    onClick = onCheckUpdate,
                    enabled = !state.checking
                )
                if (state.available) {
                    SettingsActionButton(
                        text = tr("Download", "下载"),
                        icon = Icons.AutoMirrored.Rounded.ArrowForward,
                        onClick = {
                            val started = enqueueAppUpdateDownload(
                                context = context,
                                downloadUrl = downloadUrl,
                                versionName = latestVersion,
                                useChinese = state.useChinese
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
    useChinese: Boolean,
    dashboard: PermissionsDashboardState,
    onRequestPermissions: (Array<String>) -> Unit,
    onRefreshStatus: () -> Unit
) {
    val context = LocalContext.current
    val isChinese = useChinese
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
    settingsShellState: SettingsShellState,
    providerSettingsState: ProviderSettingsState,
    channelsSettingsState: ChannelsSettingsState,
    skillsDiscoveryState: SkillsDiscoveryState,
    toolSettingsState: ToolSettingsState,
    automationSettingsState: AutomationSettingsState,
    alwaysOnSettingsState: AlwaysOnSettingsState,
    mcpSettingsState: McpSettingsState,
    updateSettingsState: UpdateSettingsState,
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
    onToolEnabledChange: (String, Boolean) -> Unit,
    onSearchProviderChange: (SearchProviderId) -> Unit,
    onSearchBraveApiKeyChange: (String) -> Unit,
    onSearchTavilyApiKeyChange: (String) -> Unit,
    onSearchJinaApiKeyChange: (String) -> Unit,
    onSearchKagiApiKeyChange: (String) -> Unit,
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
    onDeleteInstalledSkill: (String) -> Unit,
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
                SettingsMenuItem(SettingsPanelPage.Tools, tr("Tools", "工具"), tr("Built-in tools and search", "内置工具与搜索")),
                SettingsMenuItem(SettingsPanelPage.Skills, tr("Skills", "技能"), tr("Installed skills and ClawHub", "已安装技能与 ClawHub")),
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
    var guideSectionName by rememberSaveable(page) { mutableStateOf(UserGuideSection.Overview.name) }
    var pageScrollOffsets by rememberSaveable { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var settingsConfirmationState by remember(page) { mutableStateOf<SettingsConfirmationState?>(null) }
    val guideSection = runCatching { UserGuideSection.valueOf(guideSectionName) }
        .getOrDefault(UserGuideSection.Overview)
    val pageScrollKey = page.name
    val pageScrollState = rememberScrollState(initial = pageScrollOffsets[pageScrollKey] ?: 0)
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
            alwaysOnSettingsState.enabled,
            alwaysOnSettingsState.keepScreenAwake
        )
        SettingsPanelPage.Provider -> null
        SettingsPanelPage.Tools -> listOf(
            toolSettingsState.builtInTools,
            toolSettingsState.searchProvider,
            toolSettingsState.searchBraveApiKey,
            toolSettingsState.searchTavilyApiKey,
            toolSettingsState.searchJinaApiKey,
            toolSettingsState.searchKagiApiKey
        )
        SettingsPanelPage.Skills -> listOf(
            skillsDiscoveryState.installedSkills.map { skill ->
                listOf(
                    skill.name,
                    skill.enabled.toString(),
                    skill.allowIncompatible.toString()
                )
            }
        )
        SettingsPanelPage.Runtime -> listOf(
            toolSettingsState.maxToolRounds,
            toolSettingsState.toolResultMaxChars,
            toolSettingsState.memoryConsolidationWindow,
            toolSettingsState.llmCallTimeoutSeconds,
            toolSettingsState.llmConnectTimeoutSeconds,
            toolSettingsState.llmReadTimeoutSeconds,
            toolSettingsState.defaultToolTimeoutSeconds,
            toolSettingsState.contextMessages,
            toolSettingsState.toolArgsPreviewMaxChars
        )
        SettingsPanelPage.Cron -> listOf(
            automationSettingsState.cronEnabled,
            automationSettingsState.cronMinEveryMs,
            automationSettingsState.cronMaxJobs
        )
        SettingsPanelPage.Heartbeat -> listOf(
            automationSettingsState.heartbeatEnabled,
            automationSettingsState.heartbeatIntervalSeconds
        )
        SettingsPanelPage.Mcp -> listOf(
            mcpSettingsState.enabled,
            mcpSettingsState.servers
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

    LaunchedEffect(pageScrollKey) {
        pageScrollState.scrollTo(pageScrollOffsets[pageScrollKey] ?: 0)
    }

    DisposableEffect(pageScrollKey, pageScrollState) {
        onDispose {
            pageScrollOffsets = pageScrollOffsets + (pageScrollKey to pageScrollState.value)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .verticalScroll(pageScrollState),
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
                    useChinese = settingsShellState.useChinese,
                    dashboard = permissionsDashboard,
                    onRequestPermissions = onRequestPermissions,
                    onRefreshStatus = onRefreshPermissionsStatus
                )
            }

            SettingsPanelPage.AlwaysOn -> {
                AlwaysOnModeContent(
                    state = alwaysOnSettingsState,
                    onEnabledChange = onAlwaysOnEnabledChange,
                    onKeepScreenAwakeChange = onAlwaysOnKeepScreenAwakeChange,
                    onRefreshStatus = onRefreshAlwaysOnStatus
                )
            }

            SettingsPanelPage.Provider -> {
                ProviderSettingsPage(
                    state = providerSettingsState,
                    revealApiKey = revealApiKey,
                    useChinese = settingsShellState.useChinese,
                    actions = ProviderSettingsPageActions(
                        onStartNewProviderDraft = onStartNewProviderDraft,
                        onSelectProviderConfig = onSelectProviderConfig,
                        onDeleteProviderConfig = onDeleteProviderConfig,
                        onSetActiveProviderConfig = onSetActiveProviderConfig,
                        onProviderChange = onProviderChange,
                        onProviderCustomNameChange = onProviderCustomNameChange,
                        onModelChange = onModelChange,
                        onApiKeyChange = onApiKeyChange,
                        onBaseUrlChange = onBaseUrlChange,
                        onRevealToggle = onRevealToggle,
                        onTestProvider = onTestProvider,
                        onSaveProviderDraft = onSaveProviderDraft,
                        onClearProviderTokenStats = onClearProviderTokenStats,
                        onRequestConfirmation = { settingsConfirmationState = it }
                    )
                )
            }

            SettingsPanelPage.Tools -> {
                ToolSettingsPage(
                    state = toolSettingsState,
                    actions = ToolSettingsPageActions(
                        onToolEnabledChange = onToolEnabledChange,
                        onSearchProviderChange = onSearchProviderChange,
                        onSearchBraveApiKeyChange = onSearchBraveApiKeyChange,
                        onSearchTavilyApiKeyChange = onSearchTavilyApiKeyChange,
                        onSearchJinaApiKeyChange = onSearchJinaApiKeyChange,
                        onSearchKagiApiKeyChange = onSearchKagiApiKeyChange,
                        onSaveToolsPage = { onSaveCurrentPage(SettingsPanelPage.Tools) }
                    )
                )
            }

            SettingsPanelPage.Skills -> {
                SkillsSettingsSection(
                    state = skillsDiscoveryState,
                    onSkillEnabledChange = onSkillEnabledChange,
                    onSkillAllowIncompatibleChange = onSkillAllowIncompatibleChange,
                    onSelectInstalledSkill = onSelectInstalledSkill,
                    onClearInstalledSkillSelection = onClearInstalledSkillSelection,
                    onRefreshSkills = onRefreshSkills,
                    onImportLocalSkill = onImportLocalSkill,
                    onRefreshClawHub = onRefreshClawHub,
                    onClawHubSearchQueryChange = onClawHubSearchQueryChange,
                    onSearchClawHub = onSearchClawHub,
                    onOpenClawHubSkillDetail = onOpenClawHubSkillDetail,
                    onClearClawHubSkillDetail = onClearClawHubSkillDetail,
                    onStageClawHubSkillInstall = onStageClawHubSkillInstall,
                    onConfirmStagedSkillInstall = onConfirmStagedSkillInstall,
                    onDismissStagedSkillReview = onDismissStagedSkillReview,
                    onDeleteInstalledSkill = onDeleteInstalledSkill
                )
            }

            SettingsPanelPage.Runtime -> {
                val contextMessagesValue = toolSettingsState.contextMessages.toIntOrNull()
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

                val maxRoundsValue = toolSettingsState.maxToolRounds.toIntOrNull()
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

                val toolResultMaxCharsValue = toolSettingsState.toolResultMaxChars.toIntOrNull()
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

                val llmCallTimeoutValue = toolSettingsState.llmCallTimeoutSeconds.toIntOrNull()
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

                val toolTimeoutValue = toolSettingsState.defaultToolTimeoutSeconds.toIntOrNull()
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

                val memoryWindowValue = toolSettingsState.memoryConsolidationWindow.toIntOrNull()
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
                    content = toolSettingsState.agentLogs,
                    emptyText = uiLabel("No agent logs yet"),
                    actions = {
                        val clearAgentLogsTitle = localizedText(
                            "Clear Agent Logs",
                            "清除 Agent 日志",
                            useChinese = settingsShellState.useChinese
                        )
                        val clearAgentLogsMessage = irreversibleConfirmMessage(
                            prompt = localizedText(
                                "Clear agent logs?",
                                "清除 Agent 日志？",
                                useChinese = settingsShellState.useChinese
                            ),
                            useChinese = settingsShellState.useChinese
                        )
                        val clearAgentLogsLabel = localizedText(
                            "Clear",
                            "清除",
                            useChinese = settingsShellState.useChinese
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
                CronSettingsPage(
                    state = automationSettingsState,
                    useChinese = settingsShellState.useChinese,
                    actions = CronSettingsActions(
                        onCronEnabledChange = onCronEnabledChange,
                        onCronMinEveryMsChange = onCronMinEveryMsChange,
                        onCronMaxJobsChange = onCronMaxJobsChange,
                        onRefreshCronJobs = onRefreshCronJobs,
                        onSetCronJobEnabled = onSetCronJobEnabled,
                        onRunCronJobNow = onRunCronJobNow,
                        onRemoveCronJob = onRemoveCronJob,
                        onRefreshCronLogs = onRefreshCronLogs,
                        onClearCronLogs = onClearCronLogs,
                        onRequestConfirmation = { settingsConfirmationState = it }
                    )
                )
            }

            SettingsPanelPage.Heartbeat -> {
                HeartbeatSettingsPage(
                    state = automationSettingsState,
                    actions = HeartbeatSettingsActions(
                        onHeartbeatEnabledChange = onHeartbeatEnabledChange,
                        onHeartbeatIntervalSecondsChange = onHeartbeatIntervalSecondsChange,
                        onTriggerHeartbeatNow = onTriggerHeartbeatNow,
                        onOpenHeartbeatEditor = onOpenHeartbeatEditor
                    )
                )
            }

            SettingsPanelPage.Channels -> {
                ChannelSettingsPage(
                    state = channelsSettingsState,
                    actions = ChannelSettingsActions(
                        onCreateSessionRequest = onCreateSessionRequest,
                        onSetSessionChannelEnabled = onSetSessionChannelEnabled
                    )
                )
            }

            SettingsPanelPage.Mcp -> {
                McpSettingsPage(
                    state = mcpSettingsState,
                    revealApiKey = revealApiKey,
                    useChinese = settingsShellState.useChinese,
                    actions = McpSettingsActions(
                        onMcpEnabledChange = onMcpEnabledChange,
                        onAddMcpServer = onAddMcpServer,
                        onRemoveMcpServer = onRemoveMcpServer,
                        onMcpServerNameChange = onMcpServerNameChange,
                        onMcpServerUrlChange = onMcpServerUrlChange,
                        onMcpAuthTokenChange = onMcpAuthTokenChange,
                        onMcpToolTimeoutSecondsChange = onMcpToolTimeoutSecondsChange,
                        onRevealToggle = onRevealToggle,
                        onRequestConfirmation = { settingsConfirmationState = it }
                    )
                )
            }

            SettingsPanelPage.Guide -> {
                UserGuideContent(
                    isChinese = settingsShellState.useChinese,
                    selected = guideSection,
                    onSelect = { guideSectionName = it.name }
                )
            }

            SettingsPanelPage.About -> {
                AboutContent(
                    state = updateSettingsState,
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
