package com.palmclaw.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun hasPermission(context: Context, permission: String): Boolean {
    return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

internal fun missingPermissions(context: Context, permissions: List<String>): List<String> {
    return permissions.distinct().filterNot { hasPermission(context, it) }
}

internal fun permissionError(toolName: String, missing: List<String>): ToolResult {
    val msg = buildString {
        append("$toolName failed: missing permissions: ")
        append(missing.joinToString(", "))
        append(". Please grant them in Android settings and retry.")
    }
    return ToolResult(toolCallId = "", content = msg, isError = true)
}

internal fun launchIntent(context: Context, intent: Intent): ToolResult {
    val safeIntent = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val canOpen = safeIntent.resolveActivity(context.packageManager) != null
    if (!canOpen) {
        return ToolResult(
            toolCallId = "",
            content = "launch failed: no app can handle this action",
            isError = true
        )
    }
    return runCatching {
        context.startActivity(safeIntent)
        ToolResult(toolCallId = "", content = "launch started", isError = false)
    }.getOrElse { t ->
        ToolResult(
            toolCallId = "",
            content = "launch failed: ${t.message ?: t.javaClass.simpleName}",
            isError = true
        )
    }
}

internal fun nowText(timestampMs: Long = System.currentTimeMillis()): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestampMs))
}

internal fun readMediaPermissionsForKind(kind: String): List<String> {
    return if (Build.VERSION.SDK_INT >= 33) {
        when (kind.lowercase(Locale.US)) {
            "images" -> listOf(android.Manifest.permission.READ_MEDIA_IMAGES)
            "videos" -> listOf(android.Manifest.permission.READ_MEDIA_VIDEO)
            "audio" -> listOf(android.Manifest.permission.READ_MEDIA_AUDIO)
            else -> listOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VIDEO,
                android.Manifest.permission.READ_MEDIA_AUDIO
            )
        }
    } else {
        listOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

