package com.palmclaw.tools

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.palmclaw.workspace.WorkspacePathResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.util.Locale

fun createAndroidMediaToolSet(
    context: Context,
    pathResolver: WorkspacePathResolver
): List<Tool> {
    return listOf(MediaControlTool(context, pathResolver))
}

private object AudioRuntime {
    var recorder: MediaRecorder? = null
    var currentRecordFile: File? = null
    var recordStartedAtMs: Long = 0L

    var player: MediaPlayer? = null
    var currentPlaybackFile: File? = null
}

private class MediaControlTool(
    private val context: Context,
    private val pathResolver: WorkspacePathResolver
) : Tool, TimedTool {
    override val name: String = "media"
    override val description: String =
        "Unified media tool. action=capture_photo|record_video|list_recent|audio_record|audio_playback|open_app_settings"
    override val timeoutMs: Long = 300_000L
    override val jsonSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        put("required", Json.parseToJsonElement("[\"action\"]"))
        put(
            "properties",
            Json.parseToJsonElement(
                """
                {
                  "action":{"type":"string","enum":["capture_photo","record_video","list_recent","audio_record","audio_playback","open_app_settings"]},
                  "kind":{"type":"string","enum":["images","videos","audio"]},
                  "count":{"type":"integer","minimum":1,"maximum":50},
                  "quality":{"type":"string","enum":["low","high"]},
                  "mode":{"type":"string","enum":["start","stop","status"]},
                  "filename":{"type":"string"},
                  "path":{"type":"string"},
                  "request_if_missing":{"type":"boolean"},
                  "open_settings_if_failed":{"type":"boolean"},
                  "wait_user_confirmation":{"type":"boolean"},
                  "check_output_after_confirm":{"type":"boolean"}
                }
                """.trimIndent()
            )
        )
    }

    override suspend fun run(argumentsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val args = Json.decodeFromString<Args>(argumentsJson)
        return@withContext when (args.action.trim().lowercase(Locale.US)) {
            "capture_photo" -> actionCapturePhoto(args)
            "record_video" -> actionRecordVideo(args)
            "list_recent" -> actionListRecent(args)
            "audio_record" -> actionAudioRecord(args)
            "audio_playback" -> actionAudioPlayback(args)
            "open_app_settings" -> actionOpenAppSettings()
            else -> errorResult(
                action = args.action,
                code = "unsupported_action",
                message = "Unsupported action '${args.action}'.",
                nextStep = "Use action=capture_photo|record_video|list_recent|audio_record|audio_playback|open_app_settings."
            )
        }
    }

    private suspend fun actionCapturePhoto(args: Args): ToolResult {
        val action = "capture_photo"
        val outputUri = createImageUri()
            ?: return errorResult(
                action = action,
                code = "create_uri_failed",
                message = "Cannot create image output URI.",
                nextStep = "Check storage state and retry."
            )

        val launch = launchIntent(
            context,
            Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, outputUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        )
        if (launch.isError) {
            return errorResult(
                action = action,
                code = "launch_failed",
                message = launch.content,
                nextStep = "Ensure a camera app is installed and retry."
            )
        }

        val waitUserConfirmation = args.waitUserConfirmation ?: true
        if (!waitUserConfirmation) {
            return okResult(
                action = action,
                message = "camera launched. output_uri=$outputUri"
            ) {
                put("output_uri", outputUri.toString())
                put("pending_user_action", true)
            }
        }

        when (AndroidUserActionBridge.requestUserConfirmation(
            title = "Capture Photo",
            message = "Complete photo capture in camera, then return and tap Continue.",
            confirmLabel = "Continue",
            cancelLabel = "Cancel"
        )) {
            true -> Unit
            false -> {
                return errorResult(
                    action = action,
                    code = "user_cancelled",
                    message = "User cancelled photo capture flow.",
                    nextStep = "Run again to capture photo."
                )
            }

            null -> {
                return errorResult(
                    action = action,
                    code = "ui_unavailable",
                    message = "Confirmation UI unavailable.",
                    nextStep = "Finish capture manually and run again to verify output."
                )
            }
        }

        val checkOutput = args.checkOutputAfterConfirm ?: true
        val hasOutput = if (checkOutput) mediaUriHasContent(outputUri) else true
        if (!hasOutput) {
            return errorResult(
                action = action,
                code = "capture_not_found",
                message = "No captured photo found at output URI.",
                nextStep = "Retake photo and ensure save is completed in camera app."
            )
        }

        return okResult(
            action = action,
            message = "photo captured. output_uri=$outputUri"
        ) {
            put("output_uri", outputUri.toString())
            put("confirmed", true)
            put("has_output", hasOutput)
        }
    }

    private suspend fun actionRecordVideo(args: Args): ToolResult {
        val action = "record_video"
        val outputUri = createVideoUri()
            ?: return errorResult(
                action = action,
                code = "create_uri_failed",
                message = "Cannot create video output URI.",
                nextStep = "Check storage state and retry."
            )

        val quality = if (args.quality?.lowercase(Locale.US) == "low") 0 else 1
        val launch = launchIntent(
            context,
            Intent(MediaStore.ACTION_VIDEO_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_VIDEO_QUALITY, quality)
                putExtra(MediaStore.EXTRA_OUTPUT, outputUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        )
        if (launch.isError) {
            return errorResult(
                action = action,
                code = "launch_failed",
                message = launch.content,
                nextStep = "Ensure a camera app is installed and retry."
            )
        }

        val waitUserConfirmation = args.waitUserConfirmation ?: true
        if (!waitUserConfirmation) {
            return okResult(
                action = action,
                message = "video recorder launched. output_uri=$outputUri"
            ) {
                put("output_uri", outputUri.toString())
                put("quality", quality)
                put("pending_user_action", true)
            }
        }

        when (AndroidUserActionBridge.requestUserConfirmation(
            title = "Record Video",
            message = "Complete video recording in camera, then return and tap Continue.",
            confirmLabel = "Continue",
            cancelLabel = "Cancel"
        )) {
            true -> Unit
            false -> {
                return errorResult(
                    action = action,
                    code = "user_cancelled",
                    message = "User cancelled video recording flow.",
                    nextStep = "Run again to record video."
                )
            }

            null -> {
                return errorResult(
                    action = action,
                    code = "ui_unavailable",
                    message = "Confirmation UI unavailable.",
                    nextStep = "Finish recording manually and run again to verify output."
                )
            }
        }

        val checkOutput = args.checkOutputAfterConfirm ?: true
        val hasOutput = if (checkOutput) mediaUriHasContent(outputUri) else true
        if (!hasOutput) {
            return errorResult(
                action = action,
                code = "record_not_found",
                message = "No recorded video found at output URI.",
                nextStep = "Retake video and ensure save is completed in camera app."
            )
        }

        return okResult(
            action = action,
            message = "video recorded. output_uri=$outputUri quality=$quality"
        ) {
            put("output_uri", outputUri.toString())
            put("quality", quality)
            put("confirmed", true)
            put("has_output", hasOutput)
        }
    }

    private suspend fun actionListRecent(args: Args): ToolResult {
        val action = "list_recent"
        val kind = args.kind?.lowercase(Locale.US) ?: "images"
        val count = (args.count ?: 10).coerceIn(1, 50)
        val requestIfMissing = args.requestIfMissing ?: true
        val openSettingsIfFailed = args.openSettingsIfFailed ?: true
        val waitUserConfirm = args.waitUserConfirmation ?: true

        val permissionsError = ensurePermissionsInteractive(
            context = context,
            action = action,
            required = readMediaPermissionsForKind(kind),
            requestIfMissing = requestIfMissing,
            openSettingsIfFailed = openSettingsIfFailed,
            waitUserConfirmation = waitUserConfirm
        )
        if (permissionsError != null) return permissionsError

        val (baseUri, label) = when (kind) {
            "videos" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "video"
            "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to "audio"
            else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "image"
        }
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED
        )
        val sort = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        val rows = mutableListOf<RecentItem>()
        context.contentResolver.query(baseUri, projection, null, null, sort)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            while (cursor.moveToNext() && rows.size < count) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol).orEmpty()
                val size = cursor.getLong(sizeCol)
                val dateSec = cursor.getLong(dateCol)
                val itemUri = ContentUris.withAppendedId(baseUri, id).toString()
                rows += RecentItem(
                    id = id,
                    name = name,
                    size = size,
                    dateMs = dateSec * 1000L,
                    uri = itemUri
                )
            }
        }

        val text = if (rows.isEmpty()) {
            "(no media found)"
        } else {
            rows.map { r ->
                "$label id=${r.id} name=${r.name} size=${r.size} date=${nowText(r.dateMs)} uri=${r.uri}"
            }.joinToString("\n")
        }
        return okResult(
            action = action,
            message = text
        ) {
            put("kind", kind)
            put("requested_count", count)
            put("returned_count", rows.size)
            putJsonArray("items") {
                rows.forEach { add(it.summary()) }
            }
        }
    }

    private suspend fun actionAudioRecord(args: Args): ToolResult {
        return when ((args.mode ?: "status").trim().lowercase(Locale.US)) {
            "start" -> actionAudioRecordStart(args)
            "stop" -> actionAudioRecordStop()
            "status" -> actionAudioRecordStatus()
            else -> errorResult(
                action = "audio_record",
                code = "invalid_mode",
                message = "Unsupported mode '${args.mode}'.",
                nextStep = "Use mode=start|stop|status."
            )
        }
    }

    private suspend fun actionAudioRecordStart(args: Args): ToolResult {
        val action = "audio_record"
        val permissionsError = ensurePermissionsInteractive(
            context = context,
            action = action,
            required = listOf(Manifest.permission.RECORD_AUDIO),
            requestIfMissing = args.requestIfMissing ?: true,
            openSettingsIfFailed = args.openSettingsIfFailed ?: true,
            waitUserConfirmation = args.waitUserConfirmation ?: true
        )
        if (permissionsError != null) return permissionsError

        if (AudioRuntime.recorder != null) {
            return errorResult(
                action = action,
                code = "already_recording",
                message = "Recording is already in progress.",
                nextStep = "Use action=audio_record with mode=stop first."
            )
        }

        val mediaDir = File(currentWorkspaceArtifactsDir(), "audio").apply { mkdirs() }
        val requestedName = args.filename?.trim().orEmpty()
        val filename = sanitizeAudioFilename(requestedName.ifBlank { "record_${System.currentTimeMillis()}.m4a" })
        val file = File(mediaDir, filename)
        val displayPath = pathResolver.displayPath(file)

        val recorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(file.absolutePath)
        }

        return runCatching {
            recorder.prepare()
            recorder.start()
            AudioRuntime.recorder = recorder
            AudioRuntime.currentRecordFile = file
            AudioRuntime.recordStartedAtMs = System.currentTimeMillis()
            okResult(
                action = action,
                message = "audio recording started: $displayPath"
            ) {
                put("mode", "start")
                put("path", displayPath)
            }
        }.getOrElse { t ->
            runCatching { recorder.release() }
            errorResult(
                action = action,
                code = "start_failed",
                message = t.message ?: t.javaClass.simpleName,
                nextStep = "Check microphone availability/permission and retry."
            )
        }
    }

    private fun actionAudioRecordStop(): ToolResult {
        val action = "audio_record"
        val recorder = AudioRuntime.recorder
            ?: return errorResult(
                action = action,
                code = "not_recording",
                message = "No active audio recording.",
                nextStep = "Use action=audio_record with mode=start."
            )

        return runCatching {
            recorder.stop()
            recorder.release()
            val file = AudioRuntime.currentRecordFile
            val displayPath = file?.let(pathResolver::displayPath).orEmpty()
            val durationMs = System.currentTimeMillis() - AudioRuntime.recordStartedAtMs
            AudioRuntime.recorder = null
            AudioRuntime.currentRecordFile = null
            AudioRuntime.recordStartedAtMs = 0L
            okResult(
                action = action,
                message = "audio recording stopped: $displayPath duration_ms=$durationMs"
            ) {
                put("mode", "stop")
                put("duration_ms", durationMs)
                put("path", displayPath)
            }
        }.getOrElse { t ->
            runCatching { recorder.release() }
            AudioRuntime.recorder = null
            AudioRuntime.currentRecordFile = null
            AudioRuntime.recordStartedAtMs = 0L
            errorResult(
                action = action,
                code = "stop_failed",
                message = t.message ?: t.javaClass.simpleName,
                nextStep = "Retry stop once; if it still fails, start a new recording."
            )
        }
    }

    private fun actionAudioRecordStatus(): ToolResult {
        val action = "audio_record"
        val recording = AudioRuntime.recorder != null
        val file = AudioRuntime.currentRecordFile
        val displayPath = file?.let(pathResolver::displayPath).orEmpty()
        val durationMs = if (recording) {
            System.currentTimeMillis() - AudioRuntime.recordStartedAtMs
        } else {
            0L
        }
        return okResult(
            action = action,
            message = "recording=$recording path=$displayPath duration_ms=$durationMs"
        ) {
            put("mode", "status")
            put("recording", recording)
            put("path", displayPath)
            put("duration_ms", durationMs)
        }
    }

    private suspend fun actionAudioPlayback(args: Args): ToolResult {
        return when ((args.mode ?: "status").trim().lowercase(Locale.US)) {
            "start" -> actionAudioPlaybackStart(args)
            "stop" -> actionAudioPlaybackStop()
            "status" -> actionAudioPlaybackStatus()
            else -> errorResult(
                action = "audio_playback",
                code = "invalid_mode",
                message = "Unsupported mode '${args.mode}'.",
                nextStep = "Use mode=start|stop|status."
            )
        }
    }

    private fun actionAudioPlaybackStart(args: Args): ToolResult {
        val action = "audio_playback"
        val file = resolveAudioFile(args.path)
            ?: return errorResult(
                action = action,
                code = "file_not_found",
                message = "Audio file not found.",
                nextStep = "Pass a valid path in the current session workspace, shared:// storage, or record audio first."
            )
        if (!file.isFile) {
            return errorResult(
                action = action,
                code = "not_a_file",
                message = "Target path is not a file.",
                nextStep = "Pass a valid audio file path."
            )
        }

        runCatching {
            AudioRuntime.player?.stop()
            AudioRuntime.player?.release()
        }
        AudioRuntime.player = null
        AudioRuntime.currentPlaybackFile = null

        val player = MediaPlayer()
        return runCatching {
            player.setDataSource(file.absolutePath)
            player.prepare()
            player.setOnCompletionListener {
                runCatching { it.release() }
                AudioRuntime.player = null
                AudioRuntime.currentPlaybackFile = null
            }
            player.start()
            AudioRuntime.player = player
            AudioRuntime.currentPlaybackFile = file
            val displayPath = pathResolver.displayPath(file)
            okResult(
                action = action,
                message = "audio playback started: $displayPath"
            ) {
                put("mode", "start")
                put("path", displayPath)
            }
        }.getOrElse { t ->
            runCatching { player.release() }
            errorResult(
                action = action,
                code = "playback_start_failed",
                message = t.message ?: t.javaClass.simpleName,
                nextStep = "Check file format/path and retry."
            )
        }
    }

    private fun actionAudioPlaybackStop(): ToolResult {
        val action = "audio_playback"
        val player = AudioRuntime.player
        if (player == null) {
            AudioRuntime.currentPlaybackFile = null
            return okResult(
                action = action,
                message = "audio playback already stopped"
            ) {
                put("mode", "stop")
                put("stopped", false)
            }
        }

        return runCatching {
            player.stop()
            player.release()
            AudioRuntime.player = null
            AudioRuntime.currentPlaybackFile = null
            okResult(
                action = action,
                message = "audio playback stopped"
            ) {
                put("mode", "stop")
                put("stopped", true)
            }
        }.getOrElse { t ->
            runCatching { player.release() }
            AudioRuntime.player = null
            AudioRuntime.currentPlaybackFile = null
            errorResult(
                action = action,
                code = "playback_stop_failed",
                message = t.message ?: t.javaClass.simpleName,
                nextStep = "Retry with mode=stop."
            )
        }
    }

    private fun actionAudioPlaybackStatus(): ToolResult {
        val action = "audio_playback"
        val player = AudioRuntime.player
        val playing = runCatching { player?.isPlaying ?: false }.getOrDefault(false)
        val file = AudioRuntime.currentPlaybackFile
        val displayPath = file?.let(pathResolver::displayPath).orEmpty()
        return okResult(
            action = action,
            message = "playing=$playing path=$displayPath"
        ) {
            put("mode", "status")
            put("playing", playing)
            put("path", displayPath)
        }
    }

    private fun actionOpenAppSettings(): ToolResult {
        val action = "open_app_settings"
        val result = openAppSettingsIntent(context)
        if (result.isError) {
            return errorResult(
                action = action,
                code = "launch_failed",
                message = result.content,
                nextStep = "Open app settings manually from Android settings."
            )
        }
        return okResult(action = action, message = "app settings opened")
    }

    private fun createImageUri(): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "palmclaw_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PalmClaw")
            }
        }
        return context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    }

    private fun createVideoUri(): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "palmclaw_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/PalmClaw")
            }
        }
        return context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
    }

    private fun mediaUriHasContent(uri: Uri): Boolean {
        val projection = arrayOf(MediaStore.MediaColumns.SIZE)
        return runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@runCatching false
                val sizeCol = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                if (sizeCol < 0) return@runCatching true
                c.getLong(sizeCol) > 0L
            } ?: false
        }.getOrDefault(false)
    }

    private fun sanitizeAudioFilename(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\').trim()
        if (base.isBlank()) return "record_${System.currentTimeMillis()}.m4a"
        return if (base.lowercase(Locale.US).endsWith(".m4a")) base else "$base.m4a"
    }

    private fun currentWorkspaceArtifactsDir(): File {
        return File(pathResolver.currentWorkspaceSnapshot().artifactsDir).canonicalFile
    }

    private fun resolveAudioFile(path: String?): File? {
        if (path.isNullOrBlank()) {
            val currentRecordFile = AudioRuntime.currentRecordFile ?: return null
            return runCatching {
                pathResolver.resolveExisting(currentRecordFile.absolutePath)
            }.getOrNull()
        }
        return runCatching { pathResolver.resolveExisting(path.trim()) }.getOrNull()
    }

    @Serializable
    private data class Args(
        val action: String,
        val kind: String? = null,
        val count: Int? = null,
        val quality: String? = null,
        val mode: String? = null,
        val filename: String? = null,
        val path: String? = null,
        val request_if_missing: Boolean? = null,
        val open_settings_if_failed: Boolean? = null,
        val wait_user_confirmation: Boolean? = null,
        val check_output_after_confirm: Boolean? = null
    ) {
        val requestIfMissing: Boolean? get() = request_if_missing
        val openSettingsIfFailed: Boolean? get() = open_settings_if_failed
        val waitUserConfirmation: Boolean? get() = wait_user_confirmation
        val checkOutputAfterConfirm: Boolean? get() = check_output_after_confirm
    }

    private data class RecentItem(
        val id: Long,
        val name: String,
        val size: Long,
        val dateMs: Long,
        val uri: String
    ) {
        fun summary(): String {
            return "id=$id name=$name size=$size date=${nowText(dateMs)} uri=$uri"
        }
    }
}

private suspend fun ensurePermissionsInteractive(
    context: Context,
    action: String,
    required: List<String>,
    requestIfMissing: Boolean,
    openSettingsIfFailed: Boolean,
    waitUserConfirmation: Boolean
): ToolResult? {
    val needed = required.distinct().filter { it.isNotBlank() }
    if (needed.isEmpty()) return null

    var missing = missingPermissions(context, needed)
    if (missing.isEmpty()) return null

    if (!requestIfMissing) {
        return errorResult(
            action = action,
            code = "permissions_missing",
            message = "Missing required permissions: ${missing.joinToString(", ")}.",
            nextStep = "Set request_if_missing=true or grant permission in app settings, then retry."
        )
    }

    when (AndroidUserActionBridge.requestPermissions(missing)) {
        true -> {
            missing = missingPermissions(context, needed)
            if (missing.isEmpty()) return null
        }

        false -> {
            if (!openSettingsIfFailed) {
                return errorResult(
                    action = action,
                    code = "permissions_denied",
                    message = "User denied required permissions: ${missing.joinToString(", ")}.",
                    nextStep = "Grant permissions and retry."
                )
            }
        }

        null -> {
            if (!openSettingsIfFailed) {
                return errorResult(
                    action = action,
                    code = "ui_unavailable",
                    message = "Permission prompt unavailable. Missing: ${missing.joinToString(", ")}.",
                    nextStep = "Grant permissions from app settings and retry."
                )
            }
        }
    }

    if (!openSettingsIfFailed) {
        return errorResult(
            action = action,
            code = "permissions_missing",
            message = "Missing required permissions: ${missing.joinToString(", ")}.",
            nextStep = "Grant permissions and retry."
        )
    }

    val openResult = openAppSettingsIntent(context)
    if (openResult.isError) {
        return errorResult(
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
                    action = action,
                    code = "user_cancelled",
                    message = "User cancelled permission flow.",
                    nextStep = "Run again after granting permissions."
                )
            }

            null -> {
                return errorResult(
                    action = action,
                    code = "ui_unavailable",
                    message = "Confirmation UI unavailable.",
                    nextStep = "Grant permissions manually, then retry."
                )
            }
        }
    }

    missing = missingPermissions(context, needed)
    if (missing.isNotEmpty()) {
        return errorResult(
            action = action,
            code = "permissions_missing",
            message = "Permissions still missing: ${missing.joinToString(", ")}.",
            nextStep = "Grant permissions in app settings, then retry."
        )
    }
    return null
}

private fun openAppSettingsIntent(context: Context): ToolResult {
    val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
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
            put("tool", "media")
            put("action", action)
            put("status", "ok")
            extra?.invoke(this)
        }
    )
}

private fun errorResult(
    action: String,
    code: String,
    message: String,
    nextStep: String? = null
): ToolResult {
    val text = buildString {
        append("media/$action failed: $message")
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
            put("tool", "media")
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
