package com.palmclaw.ui

import android.app.Application
import com.palmclaw.config.ConfigStore
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

internal const val PALMCLAW_LATEST_RELEASE_API_URL =
    "https://api.github.com/repos/ModalityDance/PalmClaw/releases/latest"

internal data class UpdateCheckResult(
    val currentVersion: String,
    val latestVersion: String,
    val releaseUrl: String,
    val downloadUrl: String,
    val updateAvailable: Boolean
)

internal class AppUpdateCoordinator(
    private val app: Application,
    private val scope: CoroutineScope,
    private val stateStore: ChatStateStore,
    private val configStore: ConfigStore,
    private val updateCheckClient: OkHttpClient
) {
    fun bootstrapAutomaticCheck() = runAppUpdateCheck(automatic = true)

    fun checkAppUpdate() = runAppUpdateCheck(automatic = false)

    fun dismissAppUpdatePrompt() {
        stateStore.updateAppUpdate {
            if (!it.settingsUpdatePromptVisible) it else it.copy(settingsUpdatePromptVisible = false)
        }
    }

    fun dismissAppUpdateNotice() {
        stateStore.updateAppUpdate {
            if (!it.settingsUpdateNoticeVisible) {
                it
            } else {
                it.copy(
                    settingsUpdateNoticeVisible = false,
                    settingsUpdateNoticeTitle = "",
                    settingsUpdateNoticeMessage = "",
                    settingsUpdateNoticeActionLabel = "",
                    settingsUpdateNoticeActionUrl = ""
                )
            }
        }
    }

    fun notifyAppUpdateDownloadStarted() {
        val useChinese = stateStore.value.settingsUseChinese
        showAppUpdateNotice(
            title = localizedText("Download Started", "已开始下载", useChinese = useChinese),
            message = localizedText(
                "The update is downloading in the background. You can check progress in the system notification.",
                "更新正在后台下载中，你可以在系统通知里查看进度。",
                useChinese = useChinese
            )
        )
    }

    fun notifyAppUpdateDownloadFallback(releaseUrl: String) {
        val useChinese = stateStore.value.settingsUseChinese
        showAppUpdateNotice(
            title = localizedText("Manual Download", "手动下载", useChinese = useChinese),
            message = localizedText(
                "Could not start the system download. Open the releases page instead.",
                "无法直接启动系统下载，可以改为打开版本发布页。",
                useChinese = useChinese
            ),
            actionLabel = localizedText("Open Releases", "打开发布页", useChinese = useChinese),
            actionUrl = releaseUrl
        )
    }

    private fun fetchLatestReleaseInfo(): UpdateCheckResult {
        val currentVersion = readInstalledVersionName()
        val request = Request.Builder()
            .url(PALMCLAW_LATEST_RELEASE_API_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "PalmClaw-Android")
            .get()
            .build()
        updateCheckClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("HTTP ${response.code}")
            }
            val raw = response.body?.string().orEmpty()
            val root = JSONObject(raw)
            val tagName = root.optString("tag_name").trim()
            val latestVersion = normalizeVersionLabel(
                if (tagName.isNotBlank()) tagName else root.optString("name").trim()
            ).ifBlank { currentVersion }
            val releaseUrl = root.optString("html_url").trim()
            val assets = root.optJSONArray("assets")
            var downloadUrl = ""
            if (assets != null) {
                for (index in 0 until assets.length()) {
                    val asset = assets.optJSONObject(index) ?: continue
                    val assetName = asset.optString("name").trim()
                    if (assetName.endsWith(".apk", ignoreCase = true)) {
                        downloadUrl = asset.optString("browser_download_url").trim()
                        break
                    }
                }
            }
            return UpdateCheckResult(
                currentVersion = currentVersion,
                latestVersion = latestVersion,
                releaseUrl = releaseUrl,
                downloadUrl = downloadUrl,
                updateAvailable = compareVersionNames(latestVersion, currentVersion) > 0
            )
        }
    }

    private fun readInstalledVersionName(): String {
        val packageManager = app.packageManager
        return runCatching {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(app.packageName, 0)
                .versionName
                ?.trim()
                .orEmpty()
        }.getOrDefault("").ifBlank { "0.0.0" }
    }

    private fun normalizeVersionLabel(raw: String): String {
        return raw.trim().removePrefix("v").removePrefix("V")
    }

    private fun compareVersionNames(left: String, right: String): Int {
        val leftParts = Regex("\\d+").findAll(left).map { it.value.toIntOrNull() ?: 0 }.toList()
        val rightParts = Regex("\\d+").findAll(right).map { it.value.toIntOrNull() ?: 0 }.toList()
        val maxSize = maxOf(leftParts.size, rightParts.size)
        for (index in 0 until maxSize) {
            val l = leftParts.getOrElse(index) { 0 }
            val r = rightParts.getOrElse(index) { 0 }
            if (l != r) return l.compareTo(r)
        }
        return 0
    }

    private fun runAppUpdateCheck(automatic: Boolean) {
        if (stateStore.value.settingsUpdateChecking) return
        val nowMs = System.currentTimeMillis()
        if (automatic && !shouldRunAutoUpdateCheck(nowMs)) return
        if (automatic) {
            configStore.setLastAutoUpdateCheckAtMs(nowMs)
        }
        stateStore.updateAppUpdate { state ->
            state.copy(
                settingsUpdateChecking = true,
                settingsInfo = if (automatic) state.settingsInfo else null
            )
        }
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { fetchLatestReleaseInfo() }
                val showPrompt = result.updateAvailable && (!automatic || shouldShowAutoUpdatePrompt(nowMs))
                if (automatic && showPrompt) {
                    configStore.setLastAutoUpdatePromptAtMs(nowMs)
                }
                stateStore.updateAppUpdate { state ->
                    state.copy(
                        settingsUpdateChecking = false,
                        settingsCurrentVersion = result.currentVersion,
                        settingsLatestVersion = result.latestVersion,
                        settingsUpdateReleaseUrl = result.releaseUrl,
                        settingsUpdateDownloadUrl = result.downloadUrl,
                        settingsUpdateAvailable = result.updateAvailable,
                        settingsUpdatePromptVisible = if (result.updateAvailable) showPrompt else false,
                        settingsInfo = if (automatic) state.settingsInfo else null
                    )
                }
                if (!automatic && result.updateAvailable) {
                    dismissAppUpdateNotice()
                } else if (!automatic) {
                    val useChinese = stateStore.value.settingsUseChinese
                    showAppUpdateNotice(
                        title = localizedText("Up to Date", "已是最新版本", useChinese = useChinese),
                        message = localizedText(
                            "You're already on the latest version.",
                            "你当前已经是最新版本。",
                            useChinese = useChinese
                        )
                    )
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                val message = error.message ?: error.javaClass.simpleName
                stateStore.updateAppUpdate { state ->
                    state.copy(
                        settingsUpdateChecking = false,
                        settingsInfo = if (automatic) state.settingsInfo else null
                    )
                }
                if (!automatic) {
                    val useChinese = stateStore.value.settingsUseChinese
                    showAppUpdateNotice(
                        title = localizedText("Update Check Failed", "检查更新失败", useChinese = useChinese),
                        message = localizedText(
                            "Could not check for updates: $message",
                            "无法检查更新：${localizedUiMessage(message, useChinese)}",
                            useChinese = useChinese
                        )
                    )
                }
            }
        }
    }

    private fun showAppUpdateNotice(
        title: String,
        message: String,
        actionLabel: String = "",
        actionUrl: String = ""
    ) {
        stateStore.updateAppUpdate {
            it.copy(
                settingsUpdateNoticeVisible = true,
                settingsUpdateNoticeTitle = title,
                settingsUpdateNoticeMessage = message,
                settingsUpdateNoticeActionLabel = actionLabel,
                settingsUpdateNoticeActionUrl = actionUrl
            )
        }
    }

    private fun shouldRunAutoUpdateCheck(nowMs: Long): Boolean {
        val lastCheckAtMs = configStore.getLastAutoUpdateCheckAtMs()
        return !isSameLocalDay(lastCheckAtMs, nowMs)
    }

    private fun shouldShowAutoUpdatePrompt(nowMs: Long): Boolean {
        val lastPromptAtMs = configStore.getLastAutoUpdatePromptAtMs()
        return !isSameLocalDay(lastPromptAtMs, nowMs)
    }

    private fun isSameLocalDay(firstMs: Long, secondMs: Long): Boolean {
        if (firstMs <= 0L || secondMs <= 0L) return false
        val first = Calendar.getInstance().apply { timeInMillis = firstMs }
        val second = Calendar.getInstance().apply { timeInMillis = secondMs }
        return first.get(Calendar.YEAR) == second.get(Calendar.YEAR) &&
            first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR)
    }
}
