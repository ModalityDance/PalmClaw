package com.palmclaw.ui

import android.Manifest
import android.app.AlarmManager
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.palmclaw.tools.hasAllFilesAccess
import com.palmclaw.tools.hasPermission

internal const val PALMCLAW_WEBSITE_URL = "https://modalitydance.github.io/PalmClaw/"
internal const val PALMCLAW_GITHUB_URL = "https://github.com/ModalityDance/PalmClaw"
internal const val PALMCLAW_RELEASES_URL = "https://github.com/ModalityDance/PalmClaw/releases"
internal const val PALMCLAW_APK_URL = "https://github.com/ModalityDance/PalmClaw/releases/latest/download/app-release.apk"
internal const val PALMCLAW_ISSUES_URL = "https://github.com/ModalityDance/PalmClaw/issues"

internal data class InstalledAppAboutInfo(
    val appName: String,
    val versionName: String
)

internal data class SettingsConfirmationState(
    val title: String,
    val message: String,
    val confirmLabel: String,
    val onConfirm: () -> Unit
)

internal data class PermissionGroupStatus(
    val grantedCount: Int,
    val totalCount: Int
) {
    val allGranted: Boolean get() = grantedCount >= totalCount
    val partiallyGranted: Boolean get() = grantedCount in 1 until totalCount

    fun label(isChinese: Boolean): String = when {
        allGranted -> localizedText("On", "已开", useChinese = isChinese)
        partiallyGranted -> "$grantedCount/$totalCount"
        else -> localizedText("Off", "未开", useChinese = isChinese)
    }
}

internal data class PermissionsDashboardState(
    val notificationsEnabled: Boolean,
    val notificationPermissionGranted: Boolean,
    val microphoneGranted: Boolean,
    val cameraGranted: Boolean,
    val locationStatus: PermissionGroupStatus,
    val bluetoothStatus: PermissionGroupStatus,
    val contactsStatus: PermissionGroupStatus,
    val calendarStatus: PermissionGroupStatus,
    val mediaStatus: PermissionGroupStatus,
    val allFilesAccessGranted: Boolean,
    val batteryOptimizationIgnored: Boolean,
    val exactAlarmAllowed: Boolean
) {
    val readyCount: Int
        get() = listOf(
            notificationsEnabled,
            microphoneGranted,
            cameraGranted,
            locationStatus.allGranted,
            bluetoothStatus.allGranted,
            contactsStatus.allGranted,
            calendarStatus.allGranted,
            mediaStatus.allGranted,
            allFilesAccessGranted,
            batteryOptimizationIgnored,
            exactAlarmAllowed
        ).count { it }

    val totalCount: Int = 11
}

internal fun readPermissionGroupStatus(
    context: Context,
    permissions: List<String>
): PermissionGroupStatus {
    val normalized = permissions.map { it.trim() }.filter { it.isNotBlank() }.distinct()
    val grantedCount = normalized.count { hasPermission(context, it) }
    return PermissionGroupStatus(
        grantedCount = grantedCount,
        totalCount = normalized.size.coerceAtLeast(1)
    )
}

internal fun readPermissionsDashboardState(context: Context): PermissionsDashboardState {
    val appContext = context.applicationContext
    val notificationPermissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        hasPermission(appContext, Manifest.permission.POST_NOTIFICATIONS)
    val notificationsEnabled = notificationPermissionGranted &&
        NotificationManagerCompat.from(appContext).areNotificationsEnabled()
    val powerManager = appContext.getSystemService(PowerManager::class.java)
    val alarmManager = appContext.getSystemService(AlarmManager::class.java)
    val mediaPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO
        )
    } else {
        listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    val bluetoothPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    return PermissionsDashboardState(
        notificationsEnabled = notificationsEnabled,
        notificationPermissionGranted = notificationPermissionGranted,
        microphoneGranted = hasPermission(appContext, Manifest.permission.RECORD_AUDIO),
        cameraGranted = hasPermission(appContext, Manifest.permission.CAMERA),
        locationStatus = readPermissionGroupStatus(
            appContext,
            listOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        ),
        bluetoothStatus = readPermissionGroupStatus(appContext, bluetoothPermissions),
        contactsStatus = readPermissionGroupStatus(
            appContext,
            listOf(
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.WRITE_CONTACTS
            )
        ),
        calendarStatus = readPermissionGroupStatus(
            appContext,
            listOf(
                Manifest.permission.READ_CALENDAR,
                Manifest.permission.WRITE_CALENDAR
            )
        ),
        mediaStatus = readPermissionGroupStatus(appContext, mediaPermissions),
        allFilesAccessGranted = hasAllFilesAccess(appContext),
        batteryOptimizationIgnored = runCatching {
            powerManager?.isIgnoringBatteryOptimizations(appContext.packageName) == true
        }.getOrDefault(false),
        exactAlarmAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { alarmManager?.canScheduleExactAlarms() == true }.getOrDefault(false)
        } else {
            true
        }
    )
}

internal fun openAppDetailsSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

internal fun openNotificationSettings(context: Context) {
    val primaryIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
    runCatching {
        context.startActivity(primaryIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        openAppDetailsSettings(context)
    }
}

internal fun openAllFilesAccessSettings(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

internal fun openBatteryOptimizationSettings(context: Context, ignored: Boolean) {
    val intent = if (!ignored) {
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    } else {
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

internal fun openExactAlarmSettings(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    } else {
        Intent(Settings.ACTION_DATE_SETTINGS)
    }
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

internal fun readInstalledAppAboutInfo(context: Context): InstalledAppAboutInfo {
    val packageManager = context.packageManager
    val fallback = InstalledAppAboutInfo(
        appName = runCatching { packageManager.getApplicationLabel(context.applicationInfo).toString() }
            .getOrDefault("PalmClaw"),
        versionName = "Unknown"
    )
    return runCatching {
        @Suppress("DEPRECATION")
        val packageInfo = packageManager.getPackageInfo(context.packageName, 0)
        fallback.copy(
            versionName = packageInfo.versionName?.takeIf { it.isNotBlank() } ?: fallback.versionName
        )
    }.getOrDefault(fallback)
}

internal fun openExternalUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

internal fun enqueueAppUpdateDownload(
    context: Context,
    downloadUrl: String,
    versionName: String,
    useChinese: Boolean
): Boolean {
    val normalizedUrl = downloadUrl.trim()
    if (normalizedUrl.isBlank()) return false
    val safeVersion = versionName.trim()
        .ifBlank { "latest" }
        .replace(Regex("[^A-Za-z0-9._-]"), "-")
    val fileName = "PalmClaw-$safeVersion.apk"
    return runCatching {
        val request = DownloadManager.Request(Uri.parse(normalizedUrl))
            .setTitle(localizedText("PalmClaw Update", "PalmClaw 更新", useChinese = useChinese))
            .setDescription(
                localizedText(
                    "Downloading version $versionName",
                    "正在下载版本 $versionName",
                    useChinese = useChinese
                )
            )
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        val manager = context.getSystemService(DownloadManager::class.java)
            ?: error("DownloadManager unavailable")
        manager.enqueue(request)
    }.isSuccess
}

internal fun openAutoStartSettings(context: Context) {
    val appContext = context.applicationContext
    val packageName = appContext.packageName
    val candidates = listOf(
        Intent().setClassName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity"
        ),
        Intent().setClassName(
            "com.coloros.safecenter",
            "com.coloros.safecenter.startupapp.StartupAppListActivity"
        ),
        Intent().setClassName(
            "com.oppo.safe",
            "com.oppo.safe.permission.startup.StartupAppListActivity"
        ),
        Intent().setClassName(
            "com.vivo.permissionmanager",
            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
        ),
        Intent().setClassName(
            "com.huawei.systemmanager",
            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
        ),
        Intent().setClassName(
            "com.transsion.phonemaster",
            "com.cyin.himgr.autostart.AutoStartActivity"
        )
    )
    val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:$packageName")
    }
    val target = candidates.firstOrNull { candidate ->
        runCatching { candidate.resolveActivity(appContext.packageManager) != null }.getOrDefault(false)
    } ?: fallback
    runCatching {
        context.startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        context.startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
