package com.palmclaw.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.PathMatcher
import java.util.Locale

fun createFileToolSet(context: Context, rootDir: File): List<Tool> {
    rootDir.mkdirs()
    val engine = FileControlTool(context.applicationContext, FileSandbox(rootDir))
    return listOf(
        FileActionTool(
            name = "list",
            description = "List files/directories in workspace.",
            action = "list",
            schema = schemaFor(
                """
                {
                  "path":{"type":"string"},
                  "recursive":{"type":"boolean"},
                  "max_depth":{"type":"integer","minimum":0},
                  "include_hidden":{"type":"boolean"},
                  "directories_only":{"type":"boolean"},
                  "files_only":{"type":"boolean"},
                  "limit":{"type":"integer","minimum":1}
                }
                """.trimIndent()
            ),
            engine = engine
        ),
        FileActionTool(
            name = "glob",
            description = "Find files by glob pattern.",
            action = "glob",
            schema = schemaFor(
                """
                {
                  "pattern":{"type":"string"},
                  "path":{"type":"string"},
                  "path_base":{"type":"string"},
                  "files_only":{"type":"boolean"},
                  "directories_only":{"type":"boolean"},
                  "include_hidden":{"type":"boolean"},
                  "limit":{"type":"integer","minimum":1}
                }
                """.trimIndent(),
                required = "[\"pattern\"]"
            ),
            engine = engine
        ),
        FileActionTool(
            name = "read",
            description = "Read UTF-8 text file.",
            action = "read",
            schema = schemaFor(
                """
                {
                  "path":{"type":"string"},
                  "start_line":{"type":"integer","minimum":1},
                  "max_lines":{"type":"integer","minimum":1},
                  "max_chars":{"type":"integer","minimum":128}
                }
                """.trimIndent(),
                required = "[\"path\"]"
            ),
            engine = engine
        ),
        FileActionTool(
            name = "write",
            description = "Write UTF-8 text file.",
            action = "write",
            schema = schemaFor(
                """
                {
                  "path":{"type":"string"},
                  "text":{"type":"string"},
                  "mode":{"type":"string","enum":["overwrite","append"]},
                  "wait_user_confirmation":{"type":"boolean"},
                  "open_settings_if_failed":{"type":"boolean"}
                }
                """.trimIndent(),
                required = "[\"path\",\"text\"]"
            ),
            engine = engine
        ),
        FileActionTool(
            name = "edit",
            description = "Edit text file by find/replace.",
            action = "edit",
            schema = schemaFor(
                """
                {
                  "path":{"type":"string"},
                  "find":{"type":"string"},
                  "replace":{"type":"string"},
                  "old_text":{"type":"string"},
                  "new_text":{"type":"string"},
                  "all":{"type":"boolean"},
                  "regex":{"type":"boolean"},
                  "ignore_case":{"type":"boolean"},
                  "wait_user_confirmation":{"type":"boolean"},
                  "open_settings_if_failed":{"type":"boolean"}
                }
                """.trimIndent(),
                required = "[\"path\"]"
            ),
            engine = engine
        ),
        FileActionTool(
            name = "grep",
            description = "Search text in files.",
            action = "grep",
            schema = schemaFor(
                """
                {
                  "query":{"type":"string"},
                  "path":{"type":"string"},
                  "regex":{"type":"boolean"},
                  "ignore_case":{"type":"boolean"},
                  "file_glob":{"type":"string"},
                  "limit":{"type":"integer","minimum":1},
                  "max_file_bytes":{"type":"integer","minimum":1024}
                }
                """.trimIndent(),
                required = "[\"query\"]"
            ),
            engine = engine
        )
    )
}

private fun schemaFor(properties: String, required: String? = null): JsonObject {
    return buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        if (!required.isNullOrBlank()) {
            put("required", Json.parseToJsonElement(required))
        }
        put("properties", Json.parseToJsonElement(properties))
    }
}

private class FileControlTool(
    private val context: Context,
    private val sandbox: FileSandbox
) : Tool, TimedTool {
    override val name: String = "__file_engine"
    override val description: String =
        "Unified file tool in app workspace. action=list|glob|read|write|edit|grep."
    override val timeoutMs: Long = 180_000L
    override val jsonSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        put("required", Json.parseToJsonElement("[\"action\"]"))
        put(
            "properties",
            Json.parseToJsonElement(
                """
                {
                  "action":{"type":"string","enum":["list","glob","read","write","edit","grep"]},
                  "path":{"type":"string"},
                  "path_base":{"type":"string"},
                  "pattern":{"type":"string"},
                  "query":{"type":"string"},
                  "file_glob":{"type":"string"},
                  "recursive":{"type":"boolean"},
                  "max_depth":{"type":"integer","minimum":0},
                  "include_hidden":{"type":"boolean"},
                  "directories_only":{"type":"boolean"},
                  "files_only":{"type":"boolean"},
                  "limit":{"type":"integer","minimum":1},
                  "start_line":{"type":"integer","minimum":1},
                  "max_lines":{"type":"integer","minimum":1},
                  "max_chars":{"type":"integer","minimum":128},
                  "text":{"type":"string"},
                  "mode":{"type":"string","enum":["overwrite","append"]},
                  "find":{"type":"string"},
                  "replace":{"type":"string"},
                  "old_text":{"type":"string"},
                  "new_text":{"type":"string"},
                  "all":{"type":"boolean"},
                  "regex":{"type":"boolean"},
                  "ignore_case":{"type":"boolean"},
                  "max_file_bytes":{"type":"integer","minimum":1024},
                  "wait_user_confirmation":{"type":"boolean"},
                  "open_settings_if_failed":{"type":"boolean"}
                }
                """.trimIndent()
            )
        )
    }

    override suspend fun run(argumentsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val args = Json.decodeFromString<Args>(argumentsJson)
        val action = args.action?.trim().orEmpty().lowercase(Locale.US)
        return@withContext dispatch(action, args)
    }

    suspend fun runWithAction(action: String, argumentsJson: String): ToolResult = withContext(Dispatchers.IO) {
        val args = Json.decodeFromString<Args>(argumentsJson)
        return@withContext dispatch(action.trim().lowercase(Locale.US), args)
    }

    private suspend fun dispatch(action: String, args: Args): ToolResult {
        val rawAction = args.action?.takeIf { it.isNotBlank() } ?: action
        return when (action) {
            "list" -> actionList(args)
            "glob" -> actionGlob(args)
            "read" -> actionRead(args)
            "write" -> actionWrite(args)
            "edit" -> actionEdit(args)
            "grep" -> actionGrep(args)
            else -> errorResult(
                action = rawAction,
                code = "unsupported_action",
                message = "Unsupported action '$rawAction'.",
                nextStep = "Use action=list|glob|read|write|edit|grep."
            )
        }
    }

    private fun actionList(args: Args): ToolResult {
        val baseResolved = resolveExisting("list", args.path ?: ".")
        val base = baseResolved.file ?: return baseResolved.error!!
        if (!base.isDirectory) {
            return errorResult("list", "not_directory", "Path is not a directory.", "Use a directory path.")
        }
        val recursive = args.recursive ?: false
        val includeHidden = args.includeHidden ?: false
        val filesOnly = args.filesOnly ?: false
        val directoriesOnly = args.directoriesOnly ?: false
        if (filesOnly && directoriesOnly) {
            return errorResult(
                "list",
                "invalid_filter",
                "files_only and directories_only cannot both be true.",
                "Set only one filter flag."
            )
        }
        val limit = (args.limit ?: DEFAULT_LIST_LIMIT).coerceIn(1, MAX_LIST_LIMIT)
        val maxDepth = (args.maxDepth ?: DEFAULT_LIST_DEPTH).coerceIn(0, MAX_LIST_DEPTH)

        val entries = mutableListOf<File>()
        if (recursive) {
            val baseDepth = base.toPath().nameCount
            for (file in base.walkTopDown()
                .onEnter { candidate ->
                    if (candidate == base) return@onEnter true
                    val depth = candidate.toPath().nameCount - baseDepth
                    if (depth > maxDepth) return@onEnter false
                    includeHidden || !candidate.name.startsWith(".")
                }
                .drop(1)
            ) {
                if (!includeHidden && file.name.startsWith(".")) continue
                if (filesOnly && !file.isFile) continue
                if (directoriesOnly && !file.isDirectory) continue
                entries += file
                if (entries.size >= limit) break
            }
        } else {
            for (file in base.listFiles().orEmpty().sortedBy { it.name.lowercase(Locale.US) }) {
                if (!includeHidden && file.name.startsWith(".")) continue
                if (filesOnly && !file.isFile) continue
                if (directoriesOnly && !file.isDirectory) continue
                entries += file
                if (entries.size >= limit) break
            }
        }
        val lines = entries.map { if (it.isDirectory) "d ${sandbox.relative(it)}/" else "f ${sandbox.relative(it)} (${it.length()} bytes)" }
        return okResult("list", if (lines.isEmpty()) "(empty)" else lines.joinToString("\n")) {
            put("path", sandbox.relative(base))
            put("count", entries.size)
            put("truncated", entries.size >= limit)
        }
    }

    private fun actionGlob(args: Args): ToolResult {
        val pattern = args.pattern?.trim().orEmpty()
        if (pattern.isBlank()) {
            return errorResult("glob", "missing_pattern", "pattern is required.", "Provide a glob pattern.")
        }
        val baseResolved = resolveExisting("glob", args.pathBase ?: args.path ?: ".")
        val base = baseResolved.file ?: return baseResolved.error!!
        if (!base.isDirectory) {
            return errorResult("glob", "not_directory", "path/path_base must be a directory.", "Use a directory path.")
        }
        val filesOnly = args.filesOnly ?: true
        val directoriesOnly = args.directoriesOnly ?: false
        if (filesOnly && directoriesOnly) {
            return errorResult("glob", "invalid_filter", "files_only and directories_only cannot both be true.", "Set only one filter flag.")
        }
        val includeHidden = args.includeHidden ?: false
        val limit = (args.limit ?: DEFAULT_GLOB_LIMIT).coerceIn(1, MAX_GLOB_LIMIT)
        val matcher = runCatching { FileSystems.getDefault().getPathMatcher("glob:$pattern") }.getOrElse {
            return errorResult("glob", "invalid_pattern", "Invalid glob pattern.", "Fix pattern syntax and retry.")
        }

        val matches = mutableListOf<String>()
        for (file in base.walkTopDown()
            .onEnter { candidate -> candidate == base || includeHidden || !candidate.name.startsWith(".") }
            .drop(1)
        ) {
            if (!includeHidden && file.name.startsWith(".")) continue
            if (filesOnly && !file.isFile) continue
            if (directoriesOnly && !file.isDirectory) continue
            val relFromBase = sandbox.relativeFrom(base, file)
            if (matcher.matches(FileSystems.getDefault().getPath(relFromBase))) {
                matches += sandbox.relative(file)
            }
            if (matches.size >= limit) break
        }
        return okResult("glob", if (matches.isEmpty()) "(no matches)" else matches.joinToString("\n")) {
            put("path", sandbox.relative(base))
            put("count", matches.size)
            put("pattern", pattern)
            put("truncated", matches.size >= limit)
        }
    }

    private fun actionRead(args: Args): ToolResult {
        val rawPath = args.path?.trim().orEmpty()
        if (rawPath.isBlank()) {
            return errorResult("read", "missing_path", "path is required.", "Provide target file path.")
        }
        val fileResolved = resolveExisting("read", rawPath)
        val file = fileResolved.file ?: return fileResolved.error!!
        if (!file.isFile) {
            return errorResult("read", "not_file", "Path is not a file.", "Use action=list for directories.")
        }
        val startLine = (args.startLine ?: 1).coerceAtLeast(1)
        val maxLines = (args.maxLines ?: DEFAULT_READ_MAX_LINES).coerceIn(1, MAX_READ_LINES)
        val maxChars = (args.maxChars ?: DEFAULT_READ_MAX_CHARS).coerceIn(128, MAX_READ_CHARS)

        val lines = runCatching { file.readLines(Charsets.UTF_8) }.getOrElse {
            return errorResult("read", "read_failed", "Failed to read file.", "Verify file encoding then retry.")
        }
        val begin = (startLine - 1).coerceAtMost(lines.size)
        val endExclusive = (begin + maxLines).coerceAtMost(lines.size)
        var content = lines.subList(begin, endExclusive).joinToString("\n")
        var truncated = endExclusive < lines.size
        if (content.length > maxChars) {
            content = content.take(maxChars) + "\n...[truncated]"
            truncated = true
        }
        return okResult("read", content.ifBlank { "(empty file)" }) {
            put("path", sandbox.relative(file))
            put("line_count", endExclusive - begin)
            put("total_lines", lines.size)
            put("truncated", truncated)
        }
    }

    private suspend fun actionWrite(args: Args): ToolResult {
        val rawPath = args.path?.trim().orEmpty()
        if (rawPath.isBlank()) return errorResult("write", "missing_path", "path is required.", "Provide target file path.")
        val text = args.text ?: return errorResult("write", "missing_text", "text is required.", "Provide text to write.")
        if (text.length > MAX_WRITE_CHARS) {
            return errorResult("write", "text_too_large", "text too large (max=$MAX_WRITE_CHARS).", "Split into smaller writes.")
        }
        val mode = (args.mode ?: "overwrite").trim().lowercase(Locale.US)
        if (mode != "overwrite" && mode != "append") {
            return errorResult("write", "invalid_mode", "mode must be overwrite or append.", "Set mode correctly and retry.")
        }
        val fileResolved = resolveForWrite("write", rawPath)
        val file = fileResolved.file ?: return fileResolved.error!!
        if (file.exists() && file.isDirectory) {
            return errorResult("write", "path_is_directory", "Target path is a directory.", "Use a file path.")
        }
        file.parentFile?.mkdirs()

        val writeFn = { if (mode == "append") file.appendText(text, Charsets.UTF_8) else file.writeText(text, Charsets.UTF_8) }
        val failure = runCatching { writeFn() }.exceptionOrNull()
        if (failure != null) {
            val recoveredError = retryAfterPermissionFlow("write", args, failure, writeFn)
            if (recoveredError != null) return recoveredError
        }
        return okResult("write", "write ok: ${sandbox.relative(file)} (${file.length()} bytes)") {
            put("path", sandbox.relative(file))
            put("mode", mode)
            put("bytes", file.length())
        }
    }

    private suspend fun actionEdit(args: Args): ToolResult {
        val rawPath = args.path?.trim().orEmpty()
        if (rawPath.isBlank()) return errorResult("edit", "missing_path", "path is required.", "Provide target file path.")
        val fileResolved = resolveExisting("edit", rawPath)
        val file = fileResolved.file ?: return fileResolved.error!!
        if (!file.isFile) return errorResult("edit", "not_file", "Path is not a file.", "Use a file path.")

        val source = runCatching { file.readText(Charsets.UTF_8) }.getOrElse {
            return errorResult("edit", "read_failed", "Failed to read file.", "Retry or verify file encoding.")
        }
        val find = args.find ?: args.oldText
        val replace = args.replace ?: args.newText
        if (find.isNullOrEmpty()) return errorResult("edit", "missing_find", "find/old_text is required.", "Provide find text.")
        if (replace == null) return errorResult("edit", "missing_replace", "replace/new_text is required.", "Provide replacement text.")

        val all = args.all ?: false
        val regex = args.regex ?: false
        val ignoreCase = args.ignoreCase ?: false
        val result = if (regex) {
            val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
            val pattern = runCatching { Regex(find, options) }.getOrElse {
                return errorResult("edit", "invalid_regex", "Invalid regex pattern.", "Fix regex syntax and retry.")
            }
            val count = pattern.findAll(source).count()
            if (count <= 0) return errorResult("edit", "no_matches", "No matches found.", "Adjust pattern and retry.")
            if (!all && count > 1) return errorResult("edit", "ambiguous_match", "Pattern matches $count places.", "Set all=true or use unique pattern.")
            EditResult(if (all) pattern.replace(source, replace) else pattern.replaceFirst(source, replace), if (all) count else 1)
        } else {
            val count = countOccurrences(source, find, ignoreCase)
            if (count <= 0) return errorResult("edit", "no_matches", "No matches found.", "Adjust find text and retry.")
            if (!all && count > 1) return errorResult("edit", "ambiguous_match", "Text matches $count places.", "Set all=true or use unique snippet.")
            val updated = if (all) source.replace(find, replace, ignoreCase) else {
                val idx = source.indexOf(find, 0, ignoreCase)
                source.replaceRange(idx, idx + find.length, replace)
            }
            EditResult(updated, if (all) count else 1)
        }

        val writeFn = { file.writeText(result.updated, Charsets.UTF_8) }
        val failure = runCatching { writeFn() }.exceptionOrNull()
        if (failure != null) {
            val recoveredError = retryAfterPermissionFlow("edit", args, failure, writeFn)
            if (recoveredError != null) return recoveredError
        }
        return okResult("edit", "edit ok: ${sandbox.relative(file)}, replacements=${result.replacedCount}") {
            put("path", sandbox.relative(file))
            put("replacements", result.replacedCount)
            put("regex", regex)
            put("all", all)
        }
    }

    private fun actionGrep(args: Args): ToolResult {
        val query = args.query?.trim().orEmpty()
        if (query.isBlank()) return errorResult("grep", "missing_query", "query is required.", "Provide search query.")
        val targetResolved = resolveExisting("grep", args.path ?: ".")
        val target = targetResolved.file ?: return targetResolved.error!!
        val limit = (args.limit ?: DEFAULT_GREP_LIMIT).coerceIn(1, MAX_GREP_LIMIT)
        val maxFileBytes = (args.maxFileBytes ?: DEFAULT_GREP_MAX_FILE_BYTES).coerceIn(1_024, MAX_GREP_MAX_FILE_BYTES)
        val ignoreCase = args.ignoreCase ?: true
        val regexMode = args.regex ?: false
        val fileMatcher = compileOptionalMatcher(args.fileGlob) ?: if (!args.fileGlob.isNullOrBlank()) {
            return errorResult("grep", "invalid_file_glob", "Invalid file_glob pattern.", "Fix file_glob syntax and retry.")
        } else null

        val lineMatcher: (String) -> Boolean
        if (regexMode) {
            val regex = runCatching {
                val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
                Regex(query, options)
            }.getOrElse { return errorResult("grep", "invalid_regex", "Invalid regex.", "Fix regex syntax and retry.") }
            lineMatcher = { line: String -> regex.containsMatchIn(line) }
        } else {
            lineMatcher = { line: String -> line.contains(query, ignoreCase = ignoreCase) }
        }

        val matches = mutableListOf<String>()
        var scannedFiles = 0
        for (file in collectTargetFiles(target)) {
            if (matches.size >= limit) break
            if (file.length() > maxFileBytes) continue
            val rel = sandbox.relative(file)
            if (fileMatcher != null && !fileMatcher.matches(FileSystems.getDefault().getPath(rel))) continue
            scannedFiles += 1
            runCatching {
                file.useLines(Charsets.UTF_8) { lines ->
                    var lineNo = 0
                    for (line in lines) {
                        lineNo += 1
                        if (lineMatcher(line)) {
                            matches += "$rel:$lineNo: ${line.take(MAX_GREP_LINE_CHARS)}"
                            if (matches.size >= limit) break
                        }
                    }
                }
            }
        }
        return okResult("grep", if (matches.isEmpty()) "(no matches)" else matches.joinToString("\n")) {
            put("path", sandbox.relative(target))
            put("matches", matches.size)
            put("files_scanned", scannedFiles)
            put("truncated", matches.size >= limit)
        }
    }

    private suspend fun retryAfterPermissionFlow(
        action: String,
        args: Args,
        failure: Throwable,
        retryOperation: () -> Unit
    ): ToolResult? {
        if (!isPermissionIssue(failure)) {
            return errorResult(action, "io_error", failure.message ?: failure.javaClass.simpleName, "Check path/parameters then retry.")
        }
        if (!(args.openSettingsIfFailed ?: true)) {
            return errorResult(action, "permission_denied", "Permission denied.", "Set open_settings_if_failed=true or grant permission manually.")
        }
        val openSettingsResult = openAppSettings()
        if (openSettingsResult.isError) {
            return errorResult(action, "open_settings_failed", openSettingsResult.content, "Open app settings manually, then retry.")
        }
        if (args.waitUserConfirmation ?: true) {
            when (
                AndroidUserActionBridge.requestUserConfirmation(
                    title = "Permission Required",
                    message = "Grant storage permission in app settings, return, then tap Continue.",
                    confirmLabel = "Continue",
                    cancelLabel = "Cancel"
                )
            ) {
                true -> Unit
                false -> return errorResult(action, "user_cancelled", "User cancelled permission flow.", "Grant permission and run again.")
                null -> return errorResult(action, "ui_unavailable", "Confirmation UI unavailable.", "Grant permission manually, then retry.")
            }
        }
        val secondFailure = runCatching { retryOperation() }.exceptionOrNull()
        if (secondFailure != null) {
            return errorResult(action, "permission_still_denied", secondFailure.message ?: secondFailure.javaClass.simpleName, "Check app permissions in settings and retry.")
        }
        return null
    }

    private fun openAppSettings(): ToolResult {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        return launchIntent(context, intent)
    }

    private fun resolveExisting(action: String, rawPath: String): ResolveResult {
        val result = runCatching { sandbox.resolveExisting(rawPath) }
        result.getOrNull()?.let { return ResolveResult(file = it, error = null) }
        val error = result.exceptionOrNull()?.let { pathError(action, rawPath, it) }
            ?: errorResult(action, "path_invalid", "Invalid path: $rawPath", "Check path and retry.")
        return ResolveResult(file = null, error = error)
    }

    private fun resolveForWrite(action: String, rawPath: String): ResolveResult {
        val result = runCatching { sandbox.resolveForWrite(rawPath) }
        result.getOrNull()?.let { return ResolveResult(file = it, error = null) }
        val error = result.exceptionOrNull()?.let { pathError(action, rawPath, it) }
            ?: errorResult(action, "path_invalid", "Invalid path: $rawPath", "Check path and retry.")
        return ResolveResult(file = null, error = error)
    }

    private fun compileOptionalMatcher(glob: String?): PathMatcher? {
        if (glob.isNullOrBlank()) return null
        return runCatching { FileSystems.getDefault().getPathMatcher("glob:$glob") }.getOrNull()
    }

    private fun collectTargetFiles(target: File): List<File> {
        if (target.isFile) return listOf(target)
        if (!target.isDirectory) return emptyList()
        val files = mutableListOf<File>()
        target.walkTopDown()
            .onEnter { file -> file == target || !file.name.startsWith(".") }
            .forEach { file ->
                if (!file.isFile || file.name.startsWith(".")) return@forEach
                files += file
            }
        return files
    }

    private fun countOccurrences(text: String, target: String, ignoreCase: Boolean): Int {
        var count = 0
        var start = 0
        while (start < text.length) {
            val idx = text.indexOf(target, start, ignoreCase)
            if (idx < 0) break
            count += 1
            start = idx + target.length
        }
        return count
    }

    private fun isPermissionIssue(t: Throwable): Boolean {
        val message = t.message.orEmpty()
        if (message.contains("sandbox", ignoreCase = true)) return false
        if (t is SecurityException) return true
        return message.contains("permission", ignoreCase = true) || message.contains("denied", ignoreCase = true)
    }

    private fun okResult(action: String, message: String, extra: (JsonObjectBuilder.() -> Unit)? = null): ToolResult {
        return ToolResult(
            toolCallId = "",
            content = message,
            isError = false,
            metadata = buildJsonObject {
                put("tool", name)
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
        nextStep: String? = null,
        extra: (JsonObjectBuilder.() -> Unit)? = null
    ): ToolResult {
        val text = buildString {
            append("$name/$action failed: $message")
            if (!nextStep.isNullOrBlank()) append(" Next: $nextStep")
        }
        return ToolResult(
            toolCallId = "",
            content = text,
            isError = true,
            metadata = buildJsonObject {
                put("tool", name)
                put("action", action)
                put("status", "error")
                put("error", code)
                put("recoverable", !nextStep.isNullOrBlank())
                if (!nextStep.isNullOrBlank()) put("next_step", nextStep)
                extra?.invoke(this)
            }
        )
    }

    private fun pathError(action: String, rawPath: String, t: Throwable): ToolResult {
        val (code, nextStep) = when (t) {
            is SecurityException -> "path_outside_workspace" to "Use a path under app workspace."
            is IllegalArgumentException -> "path_not_found" to "Check path and retry."
            else -> "path_invalid" to "Fix path and retry."
        }
        return errorResult(action, code, t.message ?: t.javaClass.simpleName, nextStep) { put("path", rawPath) }
    }

    @Serializable
    private data class Args(
        val action: String? = null,
        val path: String? = null,
        @SerialName("path_base")
        val pathBase: String? = null,
        val pattern: String? = null,
        val query: String? = null,
        @SerialName("file_glob")
        val fileGlob: String? = null,
        val recursive: Boolean? = null,
        @SerialName("max_depth")
        val maxDepth: Int? = null,
        @SerialName("include_hidden")
        val includeHidden: Boolean? = null,
        @SerialName("directories_only")
        val directoriesOnly: Boolean? = null,
        @SerialName("files_only")
        val filesOnly: Boolean? = null,
        val limit: Int? = null,
        @SerialName("start_line")
        val startLine: Int? = null,
        @SerialName("max_lines")
        val maxLines: Int? = null,
        @SerialName("max_chars")
        val maxChars: Int? = null,
        val text: String? = null,
        val mode: String? = null,
        val find: String? = null,
        val replace: String? = null,
        @SerialName("old_text")
        val oldText: String? = null,
        @SerialName("new_text")
        val newText: String? = null,
        val all: Boolean? = null,
        val regex: Boolean? = null,
        @SerialName("ignore_case")
        val ignoreCase: Boolean? = null,
        @SerialName("max_file_bytes")
        val maxFileBytes: Int? = null,
        @SerialName("wait_user_confirmation")
        val waitUserConfirmation: Boolean? = null,
        @SerialName("open_settings_if_failed")
        val openSettingsIfFailed: Boolean? = null
    )

    private data class EditResult(
        val updated: String,
        val replacedCount: Int
    )

    private data class ResolveResult(
        val file: File?,
        val error: ToolResult?
    )
}

private class FileActionTool(
    override val name: String,
    override val description: String,
    private val action: String,
    private val schema: JsonObject,
    private val engine: FileControlTool
) : Tool, TimedTool {
    override val jsonSchema: JsonObject = schema
    override val timeoutMs: Long = engine.timeoutMs
    override suspend fun run(argumentsJson: String): ToolResult {
        return engine.runWithAction(action, argumentsJson)
    }
}

private class FileSandbox(rootDir: File) {
    private val root: File = rootDir.canonicalFile
    private val rootPath = root.path
    private val rootPrefix = root.path + File.separator
    private val ignoreCase = System.getProperty("os.name").lowercase(Locale.US).contains("win")

    fun resolveExisting(rawPath: String): File {
        val resolved = resolve(rawPath)
        if (!resolved.exists()) throw IllegalArgumentException("Path does not exist: $rawPath")
        return resolved
    }

    fun resolveForWrite(rawPath: String): File = resolve(rawPath)

    fun relative(file: File): String {
        return root.toPath().relativize(file.canonicalFile.toPath()).toString().replace('\\', '/').ifBlank { "." }
    }

    fun relativeFrom(base: File, file: File): String {
        return base.canonicalFile.toPath().relativize(file.canonicalFile.toPath()).toString().replace('\\', '/').ifBlank { "." }
    }

    private fun resolve(rawPath: String): File {
        val input = rawPath.trim().ifBlank { "." }
        val candidate = File(input).let { if (it.isAbsolute) it else File(root, input) }.canonicalFile
        if (!isUnderRoot(candidate)) throw SecurityException("Path escapes sandbox: $rawPath")
        return candidate
    }

    private fun isUnderRoot(file: File): Boolean {
        val path = file.path
        if (path.equals(rootPath, ignoreCase = ignoreCase)) return true
        return path.startsWith(rootPrefix, ignoreCase = ignoreCase)
    }
}

private const val DEFAULT_LIST_LIMIT = 200
private const val MAX_LIST_LIMIT = 1000
private const val DEFAULT_LIST_DEPTH = 4
private const val MAX_LIST_DEPTH = 20

private const val DEFAULT_GLOB_LIMIT = 200
private const val MAX_GLOB_LIMIT = 2000

private const val DEFAULT_READ_MAX_LINES = 400
private const val MAX_READ_LINES = 5000
private const val DEFAULT_READ_MAX_CHARS = 200_000
private const val MAX_READ_CHARS = 500_000

private const val MAX_WRITE_CHARS = 500_000

private const val DEFAULT_GREP_LIMIT = 200
private const val MAX_GREP_LIMIT = 2000
private const val DEFAULT_GREP_MAX_FILE_BYTES = 1_000_000
private const val MAX_GREP_MAX_FILE_BYTES = 5_000_000
private const val MAX_GREP_LINE_CHARS = 400
