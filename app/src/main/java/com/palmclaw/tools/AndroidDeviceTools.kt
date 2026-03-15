package com.palmclaw.tools

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.CancellationSignal
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

fun createAndroidDeviceToolSet(context: Context): List<Tool> {
    return listOf(
        DeviceStatusControlTool(context),
        DeviceActionControlTool(context)
    )
}

private class DeviceStatusControlTool(
    private val context: Context
) : Tool, TimedTool {
    override val name: String = "device_status"
    override val description: String =
        "Read device status. action=info|permissions|location. Permissions flow can auto-request and continue."
    override val timeoutMs: Long = 120_000L
    override val jsonSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        put("required", Json.parseToJsonElement("[\"action\"]"))
        put(
            "properties",
            Json.parseToJsonElement(
                """
                {
                  "action":{"type":"string","enum":["info","permissions","location"]},
                  "permissions":{"type":"array","items":{"type":"string"}},
                  "request_if_missing":{"type":"boolean"},
                  "provider":{"type":"string","enum":["gps","network","passive","auto"]},
                  "prefer_fine":{"type":"boolean"},
                  "open_settings_if_failed":{"type":"boolean"},
                  "wait_user_confirmation":{"type":"boolean"}
                }
                """.trimIndent()
            )
        )
    }

    override suspend fun run(argumentsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val args = Json.decodeFromString<StatusArgs>(argumentsJson)
        return@withContext when (args.action.trim().lowercase(Locale.US)) {
            "info" -> actionInfo()
            "permissions" -> actionPermissions(args)
            "location" -> actionLocation(args)
            else -> errorResult(
                toolName = name,
                action = args.action,
                code = "unsupported_action",
                message = "Unsupported action '${args.action}'.",
                nextStep = "Use action=info|permissions|location."
            )
        }
    }

    private fun actionInfo(): ToolResult {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivity.activeNetwork
        val cap = connectivity.getNetworkCapabilities(network)
        val transport = when {
            cap == null -> "none"
            cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        val metered = connectivity.isActiveNetworkMetered

        return okResult(
            action = "info",
            message = buildString {
                appendLine("manufacturer=${Build.MANUFACTURER}")
                appendLine("model=${Build.MODEL}")
                appendLine("brand=${Build.BRAND}")
                appendLine("device=${Build.DEVICE}")
                appendLine("sdk_int=${Build.VERSION.SDK_INT}")
                appendLine("android_release=${Build.VERSION.RELEASE}")
                appendLine("battery_percent=$batteryPct")
                appendLine("charging=$charging")
                appendLine("network_transport=$transport")
                appendLine("network_metered=$metered")
                appendLine("time=${nowText()}")
            }.trimEnd()
        ) {
            put("sdk_int", Build.VERSION.SDK_INT)
            put("network_transport", transport)
            put("battery_percent", batteryPct)
            put("charging", charging)
        }
    }

    private suspend fun actionPermissions(args: StatusArgs): ToolResult {
        val candidates = normalizePermissionList(args.permissions)
        val requestIfMissing = args.requestIfMissing ?: true
        val openSettingsIfFailed = args.openSettingsIfFailed ?: true
        val waitUserConfirm = args.waitUserConfirmation ?: true

        var missing = missingPermissions(context, candidates)
        var requestAttempted = false

        if (missing.isNotEmpty() && requestIfMissing) {
            requestAttempted = true
            val permissionsError = ensurePermissionsInteractive(
                context = context,
                toolName = name,
                action = "permissions",
                required = candidates,
                openSettingsIfDenied = openSettingsIfFailed,
                waitUserConfirmation = waitUserConfirm
            )
            if (permissionsError != null) return permissionsError
            missing = missingPermissions(context, candidates)
        }
        val granted = candidates.filterNot(missing::contains)
        val allGranted = missing.isEmpty()
        val nextStep = when {
            allGranted -> null
            requestIfMissing -> "Grant missing permissions in app settings, then retry."
            else -> "Run action=permissions with request_if_missing=true to trigger permission flow."
        }

        return okResult(
            action = "permissions",
            message = buildString {
                appendLine("all_granted=$allGranted")
                appendLine("request_attempted=$requestAttempted")
                appendLine("granted:")
                granted.forEach { appendLine("- $it") }
                appendLine()
                appendLine("missing:")
                if (missing.isEmpty()) appendLine("- (none)") else missing.forEach { appendLine("- $it") }
            }.trimEnd()
        ) {
            put("all_granted", allGranted)
            put("request_attempted", requestAttempted)
            put("requested_count", candidates.size)
            put("granted_count", granted.size)
            put("missing_count", missing.size)
            if (!nextStep.isNullOrBlank()) {
                put("next_step", nextStep)
            }
            putJsonArray("requested") {
                candidates.forEach { add(it) }
            }
            putJsonArray("granted") {
                granted.forEach { add(it) }
            }
            putJsonArray("missing") {
                missing.forEach { add(it) }
            }
        }
    }

    private fun normalizePermissionList(raw: List<String>?): List<String> {
        val explicit = raw.orEmpty().map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (explicit.isNotEmpty()) return explicit
        return defaultPermissionCandidates()
    }

    private fun defaultPermissionCandidates(): List<String> {
        return buildList {
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.READ_CALENDAR)
            add(Manifest.permission.WRITE_CALENDAR)
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VIDEO)
                add(Manifest.permission.READ_MEDIA_AUDIO)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }.distinct()
    }

    private suspend fun actionLocation(args: StatusArgs): ToolResult {
        val wantFine = args.preferFine ?: true
        val required = if (wantFine) {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            listOf(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        val openSettingsIfFailed = args.openSettingsIfFailed ?: true
        val waitUserConfirm = args.waitUserConfirmation ?: true

        val permissionsError = ensurePermissionsInteractive(
            context = context,
            toolName = name,
            action = "location",
            required = required,
            openSettingsIfDenied = openSettingsIfFailed,
            waitUserConfirmation = waitUserConfirm
        )
        if (permissionsError != null) return permissionsError

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var provider = pickProvider(args.provider, lm)
        if (provider == null && openSettingsIfFailed) {
            val opened = launchIntent(context, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            if (opened.isError) {
                return errorResult(
                    toolName = name,
                    action = "location",
                    code = "location_disabled",
                    message = "No enabled location provider.",
                    nextStep = "Enable location service in Android settings and retry."
                )
            }
            if (waitUserConfirm) {
                when (AndroidUserActionBridge.requestUserConfirmation(
                    title = "Location Required",
                    message = "Enable location service in system settings, then return and tap Continue.",
                    confirmLabel = "Continue",
                    cancelLabel = "Cancel"
                )) {
                    true -> Unit
                    false -> {
                        return errorResult(
                            toolName = name,
                            action = "location",
                            code = "user_cancelled",
                            message = "User cancelled location enable flow.",
                            nextStep = "Run again after enabling location service."
                        )
                    }

                    null -> {
                        return errorResult(
                            toolName = name,
                            action = "location",
                            code = "ui_unavailable",
                            message = "Confirmation UI unavailable.",
                            nextStep = "Enable location service manually, then retry."
                        )
                    }
                }
            }
            provider = pickProvider(args.provider, lm)
        }

        if (provider == null) {
            return errorResult(
                toolName = name,
                action = "location",
                code = "location_disabled",
                message = "No enabled location provider.",
                nextStep = "Enable location service and retry."
            )
        }

        val loc = getLocation(lm, provider)
            ?: return errorResult(
                toolName = name,
                action = "location",
                code = "location_unavailable",
                message = "No location available.",
                nextStep = "Try provider=network or retry later."
            )

        return okResult(
            action = "location",
            message = buildString {
                appendLine("provider=$provider")
                appendLine("latitude=${loc.latitude}")
                appendLine("longitude=${loc.longitude}")
                appendLine("accuracy_m=${loc.accuracy}")
                appendLine("time=${nowText(loc.time)}")
            }.trimEnd()
        ) {
            put("provider", provider)
            put("lat", loc.latitude)
            put("lng", loc.longitude)
            put("accuracy_m", loc.accuracy.toDouble())
        }
    }

    private fun pickProvider(requested: String?, lm: LocationManager): String? {
        val req = requested?.trim()?.lowercase(Locale.US) ?: "auto"
        return when (req) {
            "gps" -> if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else null
            "network" -> if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) LocationManager.NETWORK_PROVIDER else null
            "passive" -> if (lm.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)) LocationManager.PASSIVE_PROVIDER else null
            else -> {
                when {
                    lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                    lm.isProviderEnabled(LocationManager.PASSIVE_PROVIDER) -> LocationManager.PASSIVE_PROVIDER
                    else -> null
                }
            }
        }
    }

    private suspend fun getLocation(lm: LocationManager, provider: String): Location? {
        return if (Build.VERSION.SDK_INT >= 30) {
            suspendCancellableCoroutine { cont ->
                val cs = CancellationSignal()
                cont.invokeOnCancellation { cs.cancel() }
                runCatching {
                    lm.getCurrentLocation(
                        provider,
                        cs,
                        ContextCompat.getMainExecutor(context)
                    ) { loc ->
                        if (!cont.isCompleted) cont.resume(loc)
                    }
                }.onFailure {
                    if (!cont.isCompleted) cont.resume(
                        runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
                    )
                }
            }
        } else {
            runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
        }
    }

    @Serializable
    private data class StatusArgs(
        val action: String,
        val permissions: List<String>? = null,
        val request_if_missing: Boolean? = null,
        val provider: String? = null,
        val prefer_fine: Boolean? = null,
        val open_settings_if_failed: Boolean? = null,
        val wait_user_confirmation: Boolean? = null
    ) {
        val requestIfMissing: Boolean? get() = request_if_missing
        val preferFine: Boolean? get() = prefer_fine
        val openSettingsIfFailed: Boolean? get() = open_settings_if_failed
        val waitUserConfirmation: Boolean? get() = wait_user_confirmation
    }
}

private class DeviceActionControlTool(
    private val context: Context
) : Tool, TimedTool {
    override val name: String = "device"
    override val description: String =
        "Perform device actions. action=notify|open_url|share|open_app_settings"
    override val timeoutMs: Long = 120_000L
    override val jsonSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        put("required", Json.parseToJsonElement("[\"action\"]"))
        put(
            "properties",
            Json.parseToJsonElement(
                """
                {
                  "action":{"type":"string","enum":["notify","open_url","share","open_app_settings"]},
                  "title":{"type":"string"},
                  "text":{"type":"string"},
                  "channel_id":{"type":"string"},
                  "channel_name":{"type":"string"},
                  "url":{"type":"string"},
                  "subject":{"type":"string"},
                  "open_settings_if_failed":{"type":"boolean"},
                  "wait_user_confirmation":{"type":"boolean"}
                }
                """.trimIndent()
            )
        )
    }

    override suspend fun run(argumentsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val args = Json.decodeFromString<ActionArgs>(argumentsJson)
        return@withContext when (args.action.trim().lowercase(Locale.US)) {
            "notify" -> actionNotify(args)
            "open_url" -> actionOpenUrl(args)
            "share" -> actionShare(args)
            "open_app_settings" -> actionOpenAppSettings()
            else -> errorResult(
                toolName = name,
                action = args.action,
                code = "unsupported_action",
                message = "Unsupported action '${args.action}'.",
                nextStep = "Use action=notify|open_url|share|open_app_settings."
            )
        }
    }

    private suspend fun actionNotify(args: ActionArgs): ToolResult {
        val title = args.title?.trim().orEmpty()
        val text = args.text?.trim().orEmpty()
        if (title.isBlank() || text.isBlank()) {
            return errorResult(
                toolName = name,
                action = "notify",
                code = "invalid_arguments",
                message = "title and text are required.",
                nextStep = "Pass both title and text."
            )
        }

        if (Build.VERSION.SDK_INT >= 33) {
            val permissionsError = ensurePermissionsInteractive(
                context = context,
                toolName = name,
                action = "notify",
                required = listOf(Manifest.permission.POST_NOTIFICATIONS),
                openSettingsIfDenied = args.openSettingsIfFailed ?: true,
                waitUserConfirmation = args.waitUserConfirmation ?: true
            )
            if (permissionsError != null) return permissionsError
        }

        val channelId = args.channelId?.trim().orEmpty().ifBlank { DEFAULT_CHANNEL_ID }
        val channelName = args.channelName?.trim().orEmpty().ifBlank { DEFAULT_CHANNEL_NAME }
        ensureNotificationChannel(channelId, channelName)

        val id = NEXT_NOTIFICATION_ID.incrementAndGet()
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        return runCatching {
            NotificationManagerCompat.from(context).notify(id, notification)
            okResult(
                action = "notify",
                message = "notification posted: id=$id, channel=$channelId"
            ) {
                put("notification_id", id)
                put("channel_id", channelId)
            }
        }.getOrElse { t ->
            errorResult(
                toolName = name,
                action = "notify",
                code = "notify_failed",
                message = t.message ?: t.javaClass.simpleName,
                nextStep = "Check notification permission and retry."
            )
        }
    }

    private fun actionOpenUrl(args: ActionArgs): ToolResult {
        val url = args.url?.trim().orEmpty()
        val parsed = url.toHttpUrlOrNull()
        if (parsed == null || parsed.scheme.lowercase(Locale.US) !in setOf("http", "https")) {
            return errorResult(
                toolName = name,
                action = "open_url",
                code = "invalid_url",
                message = "Only http/https URL is allowed.",
                nextStep = "Provide a valid http or https URL."
            )
        }

        val result = launchIntent(
            context,
            Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(parsed.toString())
            }
        )
        if (result.isError) {
            return errorResult(
                toolName = name,
                action = "open_url",
                code = "launch_failed",
                message = result.content,
                nextStep = "Verify browser availability and retry."
            )
        }
        return okResult(action = "open_url", message = "launch started")
    }

    private fun actionShare(args: ActionArgs): ToolResult {
        val text = args.text?.trim().orEmpty()
        if (text.isBlank()) {
            return errorResult(
                toolName = name,
                action = "share",
                code = "invalid_arguments",
                message = "text is required.",
                nextStep = "Pass non-empty text."
            )
        }
        val base = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            if (!args.subject.isNullOrBlank()) {
                putExtra(Intent.EXTRA_SUBJECT, args.subject)
            }
        }
        val chooser = Intent.createChooser(base, "Share via")
        val result = launchIntent(context, chooser)
        if (result.isError) {
            return errorResult(
                toolName = name,
                action = "share",
                code = "launch_failed",
                message = result.content,
                nextStep = "Install/enable share targets and retry."
            )
        }
        return okResult(action = "share", message = "launch started")
    }

    private fun actionOpenAppSettings(): ToolResult {
        val result = openAppSettingsIntent(context)
        if (result.isError) {
            return errorResult(
                toolName = name,
                action = "open_app_settings",
                code = "launch_failed",
                message = result.content,
                nextStep = "Open app settings manually from Android system settings."
            )
        }
        return okResult(action = "open_app_settings", message = "launch started")
    }

    private fun ensureNotificationChannel(id: String, name: String) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(id) != null) return
        nm.createNotificationChannel(
            NotificationChannel(id, name, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "PalmClaw notifications"
            }
        )
    }

    @Serializable
    private data class ActionArgs(
        val action: String,
        val title: String? = null,
        val text: String? = null,
        val channel_id: String? = null,
        val channel_name: String? = null,
        val url: String? = null,
        val subject: String? = null,
        val open_settings_if_failed: Boolean? = null,
        val wait_user_confirmation: Boolean? = null
    ) {
        val channelId: String? get() = channel_id
        val channelName: String? get() = channel_name
        val openSettingsIfFailed: Boolean? get() = open_settings_if_failed
        val waitUserConfirmation: Boolean? get() = wait_user_confirmation
    }

    companion object {
        private const val DEFAULT_CHANNEL_ID = "palmclaw_default"
        private const val DEFAULT_CHANNEL_NAME = "PalmClaw"
        private val NEXT_NOTIFICATION_ID = AtomicInteger(1000)
    }
}

private suspend fun ensurePermissionsInteractive(
    context: Context,
    toolName: String,
    action: String,
    required: List<String>,
    openSettingsIfDenied: Boolean,
    waitUserConfirmation: Boolean
): ToolResult? {
    var missing = missingPermissions(context, required)
    if (missing.isEmpty()) return null

    when (AndroidUserActionBridge.requestPermissions(missing)) {
        true -> {
            missing = missingPermissions(context, required)
            if (missing.isEmpty()) return null
        }

        false -> {
            if (!openSettingsIfDenied) {
                return errorResult(
                    toolName = toolName,
                    action = action,
                    code = "permissions_denied",
                    message = "User denied required permissions: ${missing.joinToString(", ")}.",
                    nextStep = "Grant permissions and retry."
                )
            }
        }

        null -> {
            if (!openSettingsIfDenied) {
                return errorResult(
                    toolName = toolName,
                    action = action,
                    code = "ui_unavailable",
                    message = "Permission prompt unavailable. Missing: ${missing.joinToString(", ")}.",
                    nextStep = "Grant permissions from app settings and retry."
                )
            }
        }
    }

    if (!openSettingsIfDenied) {
        return errorResult(
            toolName = toolName,
            action = action,
            code = "permissions_missing",
            message = "Missing required permissions: ${missing.joinToString(", ")}.",
            nextStep = "Grant permissions and retry."
        )
    }

    val openResult = openAppSettingsIntent(context)
    if (openResult.isError) {
        return errorResult(
            toolName = toolName,
            action = action,
            code = "open_settings_failed",
            message = openResult.content,
            nextStep = "Open app settings manually, grant permissions, then retry."
        )
    }

    if (waitUserConfirmation) {
        when (AndroidUserActionBridge.requestUserConfirmation(
            title = "Permission Required",
            message = "Grant the required permission(s) in app settings, then return and tap Continue.",
            confirmLabel = "Continue",
            cancelLabel = "Cancel"
        )) {
            true -> Unit
            false -> {
                return errorResult(
                    toolName = toolName,
                    action = action,
                    code = "user_cancelled",
                    message = "User cancelled permission flow.",
                    nextStep = "Run again after granting permissions."
                )
            }

            null -> {
                return errorResult(
                    toolName = toolName,
                    action = action,
                    code = "ui_unavailable",
                    message = "Confirmation UI unavailable.",
                    nextStep = "Grant permissions manually, then retry."
                )
            }
        }
    }

    missing = missingPermissions(context, required)
    if (missing.isNotEmpty()) {
        return errorResult(
            toolName = toolName,
            action = action,
            code = "permissions_missing",
            message = "Permissions still missing: ${missing.joinToString(", ")}.",
            nextStep = "Grant permissions in app settings, then retry."
        )
    }

    return null
}

private fun openAppSettingsIntent(context: Context): ToolResult {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    return launchIntent(context, intent)
}

private fun okResult(
    action: String,
    message: String,
    extra: (kotlinx.serialization.json.JsonObjectBuilder.() -> Unit)? = null
): ToolResult {
    return ToolResult(
        toolCallId = "",
        content = message,
        isError = false,
        metadata = buildJsonObject {
            put("action", action)
            put("status", "ok")
            extra?.invoke(this)
        }
    )
}

private fun errorResult(
    toolName: String,
    action: String,
    code: String,
    message: String,
    nextStep: String? = null
): ToolResult {
    val text = buildString {
        append("$toolName/$action failed: $message")
        if (!nextStep.isNullOrBlank()) {
            append(" Next: ")
            append(nextStep)
        }
    }
    return ToolResult(
        toolCallId = "",
        content = text,
        isError = true,
        metadata = buildJsonObject {
            put("tool", toolName)
            put("action", action)
            put("status", "error")
            put("error", code)
            put("recoverable", !nextStep.isNullOrBlank())
            if (!nextStep.isNullOrBlank()) {
                put("next_step", nextStep)
            }
        }
    )
}
