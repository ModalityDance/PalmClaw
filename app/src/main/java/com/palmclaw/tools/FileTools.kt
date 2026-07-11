package com.palmclaw.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import com.palmclaw.workspace.WorkspacePathResolver
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

fun createFileToolSet(context: Context, pathResolver: WorkspacePathResolver): List<Tool> {
    val appContext = context.applicationContext
    return createFileToolSet(
        pathResolver = pathResolver,
        context = appContext,
        fileRenamer = { source, destination -> source.renameTo(destination) },
        fileCopier = ::copyFileContents,
        confirmationRequester = { title, message, confirmLabel ->
            AndroidUserActionBridge.requestUserConfirmation(
                title = title,
                message = message,
                confirmLabel = confirmLabel,
                cancelLabel = "Cancel"
            )
        },
        openAppSettings = {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${appContext.packageName}")
            }
            launchIntent(appContext, intent)
        }
    )
}

internal fun createFileToolSet(pathResolver: WorkspacePathResolver): List<Tool> {
    return createFileToolSet(pathResolver, { _, _, _ -> true })
}

internal fun createFileToolSet(
    pathResolver: WorkspacePathResolver,
    confirmationRequester: suspend (title: String, message: String, confirmLabel: String) -> Boolean?,
    fileRenamer: (source: File, destination: File) -> Boolean = { source, destination ->
        source.renameTo(destination)
    },
    fileCopier: (source: File, destination: File) -> Unit = ::copyFileContents
): List<Tool> {
    return createFileToolSet(
        pathResolver = pathResolver,
        context = null,
        fileRenamer = fileRenamer,
        fileCopier = fileCopier,
        confirmationRequester = confirmationRequester,
        openAppSettings = {
            ToolResult(
                toolCallId = "",
                content = "App settings are unavailable outside Android runtime.",
                isError = true
            )
        }
    )
}

private fun createFileToolSet(
    pathResolver: WorkspacePathResolver,
    context: Context?,
    fileRenamer: (source: File, destination: File) -> Boolean,
    fileCopier: (source: File, destination: File) -> Unit,
    confirmationRequester: suspend (title: String, message: String, confirmLabel: String) -> Boolean?,
    openAppSettings: () -> ToolResult
): List<Tool> {
    val engine = FileControlTool(
        context = context,
        sandbox = FileSandbox(pathResolver),
        fileRenamer = fileRenamer,
        fileCopier = fileCopier,
        confirmationRequester = confirmationRequester,
        openAppSettingsAction = openAppSettings
    )
    return listOf(
        FileActionTool(
            name = "list",
            description = "List files/directories in the current session workspace or shared:// paths.",
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
            description = "Find files by glob pattern in the current session workspace or shared:// paths.",
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
            description = "Read a supported local file from the current session workspace or shared:// paths. Supports text files, formal OOXML extraction for docx/xlsx/pptx, binary Office extraction for doc/xls/ppt, PDF text extraction, and formal ODT extraction.",
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
            description = "Write a UTF-8 text file in the current session workspace or shared:// paths.",
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
            description = "Edit a text file by find/replace in the current session workspace or shared:// paths.",
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
            description = "Search text in files under the current session workspace or shared:// paths.",
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
        ),
        FileActionTool(
            name = "delete",
            description = "Delete a file or directory in the current session workspace or shared:// paths. Non-empty directories require recursive=true.",
            action = "delete",
            schema = schemaFor(
                """
                {
                  "path":{"type":"string"},
                  "recursive":{"type":"boolean"},
                  "open_settings_if_failed":{"type":"boolean"}
                }
                """.trimIndent(),
                required = "[\"path\"]"
            ),
            engine = engine
        ),
        FileActionTool(
            name = "move",
            description = "Move or rename a file or directory within bounded workspace/shared paths. Files support a verified cross-filesystem fallback; directory moves must stay on one filesystem.",
            action = "move",
            schema = schemaFor(
                """
                {
                  "source":{"type":"string"},
                  "destination":{"type":"string"},
                  "overwrite":{"type":"boolean"},
                  "create_parent":{"type":"boolean"},
                  "open_settings_if_failed":{"type":"boolean"}
                }
                """.trimIndent(),
                required = "[\"source\",\"destination\"]"
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

private fun copyFileContents(source: File, destination: File) {
    source.inputStream().use { input ->
        destination.outputStream().use { output -> input.copyTo(output) }
    }
}

private class FileControlTool(
    private val context: Context?,
    private val sandbox: FileSandbox,
    private val fileRenamer: (source: File, destination: File) -> Boolean,
    private val fileCopier: (source: File, destination: File) -> Unit,
    private val confirmationRequester: suspend (title: String, message: String, confirmLabel: String) -> Boolean?,
    private val openAppSettingsAction: () -> ToolResult
) : Tool, TimedTool {
    override val name: String = "__file_engine"
    override val description: String =
        "Unified file tool in the current session workspace, shared:// app storage, and allowed external shared storage. action=list|glob|read|write|edit|grep|delete|move."
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
                  "action":{"type":"string","enum":["list","glob","read","write","edit","grep","delete","move"]},
                  "path":{"type":"string"},
                  "source":{"type":"string"},
                  "destination":{"type":"string"},
                  "path_base":{"type":"string"},
                  "pattern":{"type":"string"},
                  "query":{"type":"string"},
                  "file_glob":{"type":"string"},
                  "recursive":{"type":"boolean"},
                  "overwrite":{"type":"boolean"},
                  "create_parent":{"type":"boolean"},
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
            "delete" -> actionDelete(args)
            "move" -> actionMove(args)
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

        val readResult = context?.let { LocalFileReadSupport.read(it, file) }
            ?: LocalFileReadSupport.read(file)
        val extracted = when (val result = readResult) {
            is LocalFileReadResult.Success -> result
            is LocalFileReadResult.Unsupported -> {
                return errorResult("read", result.code, result.message, result.nextStep)
            }
            is LocalFileReadResult.Failure -> {
                return errorResult("read", result.code, result.message, result.nextStep)
            }
        }
        val lines = extracted.text.lines()
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
            put("source_type", extracted.sourceType)
            extracted.charset?.let { put("charset", it) }
            extracted.note?.let { put("note", it) }
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
        requestExternalWriteConfirmation("write", file)?.let { return it }
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
        requestExternalWriteConfirmation("edit", file)?.let { return it }

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

    private suspend fun actionDelete(args: Args): ToolResult {
        val rawPath = args.path?.trim().orEmpty()
        if (rawPath.isBlank()) {
            return errorResult("delete", "missing_path", "path is required.", "Provide target file or directory path.")
        }
        val targetResolved = resolveExisting("delete", rawPath)
        val target = targetResolved.file ?: return targetResolved.error!!
        if (sandbox.isProtectedRoot(target)) {
            return errorResult(
                "delete",
                "protected_path",
                "Workspace roots cannot be deleted.",
                "Delete a child path instead."
            ) { put("path", sandbox.relative(target)) }
        }
        if (target.isDirectory && target.list()?.isNotEmpty() == true && args.recursive != true) {
            return errorResult(
                "delete",
                "directory_not_empty",
                "Directory is not empty and recursive is false.",
                "Set recursive=true only after reviewing the directory contents."
            ) { put("path", sandbox.relative(target)) }
        }
        if (target.isDirectory && args.recursive == true) {
            sandbox.validateRecursiveTree(target)?.let { issue ->
                return errorResult(
                    "delete",
                    issue.code,
                    issue.message,
                    "Remove symbolic links or aliases from the directory and retry."
                ) { put("path", sandbox.relative(target)) }
            }
            requestDestructiveConfirmation(
                action = "delete",
                title = "Delete Directory",
                message = "Delete this directory and all of its contents?\n${sandbox.relative(target)}",
                confirmLabel = "Delete"
            )?.let { return it }
        }
        requestExternalWriteConfirmation("delete", target)?.let { return it }

        val deleteOperation = {
            val deleted = if (target.isDirectory && args.recursive == true) {
                sandbox.deleteValidatedTree(target)
            } else {
                target.delete()
            }
            if (!deleted || target.exists()) {
                throw IllegalStateException("Failed to delete ${sandbox.relative(target)}")
            }
        }
        val failure = runCatching { deleteOperation() }.exceptionOrNull()
        if (failure != null) {
            val recoveredError = retryAfterPermissionFlow("delete", args, failure, deleteOperation)
            if (recoveredError != null) return recoveredError
        }
        return okResult("delete", "delete ok: ${sandbox.relative(target)}") {
            put("path", sandbox.relative(target))
            put("recursive", args.recursive == true)
        }
    }

    private suspend fun actionMove(args: Args): ToolResult {
        val rawSource = args.source?.trim().orEmpty()
        val rawDestination = args.destination?.trim().orEmpty()
        if (rawSource.isBlank()) {
            return errorResult("move", "missing_source", "source is required.", "Provide the existing source path.")
        }
        if (rawDestination.isBlank()) {
            return errorResult("move", "missing_destination", "destination is required.", "Provide the destination path.")
        }

        val sourceResolved = resolveExisting("move", rawSource)
        val source = sourceResolved.file ?: return sourceResolved.error!!
        val destinationResolved = resolveForWrite("move", rawDestination)
        val destination = destinationResolved.file ?: return destinationResolved.error!!
        if (sandbox.isProtectedRoot(source)) {
            return errorResult(
                "move",
                "protected_path",
                "Workspace roots cannot be moved.",
                "Move a child path instead."
            ) { put("source", sandbox.relative(source)) }
        }
        if (source.canonicalFile == destination.canonicalFile) {
            return errorResult("move", "same_path", "Source and destination are the same path.", "Choose a different destination.")
        }
        if (source.isDirectory && sandbox.isDescendant(destination, source)) {
            return errorResult(
                "move",
                "destination_inside_source",
                "A directory cannot be moved inside itself.",
                "Choose a destination outside the source directory."
            )
        }
        if (destination.exists() && args.overwrite != true) {
            return errorResult("move", "target_exists", "Destination already exists.", "Choose another destination or set overwrite=true.") {
                put("destination", sandbox.relative(destination))
            }
        }
        if (destination.exists() && destination.isDirectory) {
            return errorResult(
                "move",
                "target_is_directory",
                "Existing destination directories cannot be overwritten.",
                "Choose an empty destination path."
            )
        }
        if (destination.exists() && args.overwrite == true) {
            requestDestructiveConfirmation(
                action = "move",
                title = "Replace File",
                message = "Replace the existing destination file?\n${sandbox.relative(destination)}",
                confirmLabel = "Replace"
            )?.let { return it }
        }

        requestExternalWriteConfirmation("move", source)?.let { return it }
        if (destination.canonicalFile != source.canonicalFile) {
            requestExternalWriteConfirmation("move", destination)?.let { return it }
        }

        val parent = destination.parentFile
        if (parent != null && !parent.exists()) {
            if (args.createParent != true) {
                return errorResult(
                    "move",
                    "parent_not_found",
                    "Destination parent directory does not exist.",
                    "Set create_parent=true or create the parent directory first."
                )
            }
            if (!parent.mkdirs() && !parent.isDirectory) {
                return errorResult("move", "create_parent_failed", "Failed to create destination parent.", "Choose an existing parent directory.")
            }
        }

        var backupCleanupPath: String? = null
        val moveOperation = {
            val backup = destination.takeIf { it.exists() }?.let { existing ->
                File(
                    existing.parentFile,
                    ".${existing.name}.palmclaw-move-${System.nanoTime()}"
                ).also { backupFile ->
                    if (!fileRenamer(existing, backupFile)) {
                        throw IllegalStateException("Failed to prepare replacement for ${sandbox.relative(existing)}")
                    }
                }
            }
            try {
                val renamed = fileRenamer(source, destination) && !source.exists() && destination.exists()
                if (!renamed) {
                    if (source.isDirectory) {
                        throw DirectoryMoveException()
                    }
                    copyFileAndVerify(source, destination)
                    if (!source.delete() || source.exists()) {
                        destination.delete()
                        throw IllegalStateException("Copied destination but failed to remove source")
                    }
                }
                if (backup != null && !backup.delete()) {
                    backupCleanupPath = sandbox.relative(backup)
                }
            } catch (failure: Throwable) {
                val recoveryIssues = mutableListOf<String>()
                if (!source.exists() && destination.exists()) {
                    if (!fileRenamer(destination, source)) {
                        recoveryIssues += "source_restore_failed"
                    }
                }
                if (backup != null && backup.exists()) {
                    if (destination.exists()) {
                        recoveryIssues += "destination_occupied_before_backup_restore"
                    } else if (!fileRenamer(backup, destination)) {
                        recoveryIssues += "backup_restore_failed"
                    }
                }
                if (recoveryIssues.isNotEmpty()) {
                    throw MoveRecoveryException(
                        issues = recoveryIssues,
                        sourcePath = source.takeIf { it.exists() }?.let(sandbox::relative),
                        destinationPath = destination.takeIf { it.exists() }?.let(sandbox::relative),
                        backupPath = backup?.takeIf { it.exists() }?.let(sandbox::relative),
                        cause = failure
                    )
                }
                throw failure
            }
        }
        val failure = runCatching { moveOperation() }.exceptionOrNull()
        if (failure != null) {
            if (failure is MoveRecoveryException) {
                return errorResult(
                    "move",
                    "move_recovery_required",
                    "Move failed and automatic restoration was incomplete: ${failure.issues.joinToString(", ")}.",
                    "Review the reported surviving paths before retrying."
                ) {
                    failure.sourcePath?.let { put("source_path", it) }
                    failure.destinationPath?.let { put("destination_path", it) }
                    failure.backupPath?.let { put("backup_path", it) }
                }
            }
            if (failure is DirectoryMoveException) {
                return errorResult(
                    "move",
                    "directory_move_failed",
                    "Directory move failed. The storage volumes may differ, or storage may be read-only or temporarily unavailable.",
                    "Choose a destination on the same writable storage volume, or move files separately."
                )
            }
            val recoveredError = retryAfterPermissionFlow("move", args, failure, moveOperation)
            if (recoveredError != null) return recoveredError
        }
        return okResult("move", "move ok: ${sandbox.relative(destination)}") {
            put("source", rawSource)
            put("destination", sandbox.relative(destination))
            put("overwritten", args.overwrite == true)
            backupCleanupPath?.let {
                put("warning", "replaced_destination_backup_cleanup_failed")
                put("backup_path", it)
            }
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

    private suspend fun requestExternalWriteConfirmation(action: String, file: File): ToolResult? {
        if (!sandbox.isSharedExternalPath(file)) return null
        return when (
            confirmationRequester(
                "External File Write",
                "Allow PalmClaw to modify this external shared-storage file?\n${sandbox.relative(file)}",
                "Allow"
            )
        ) {
            true -> null
            false -> errorResult(
                action = action,
                code = "user_cancelled",
                message = "User cancelled external file write.",
                nextStep = "Choose a workspace path, or approve the external write."
            ) { put("path", sandbox.relative(file)) }
            null -> errorResult(
                action = action,
                code = "confirmation_unavailable",
                message = "User confirmation is required before writing external shared-storage files.",
                nextStep = "Open the app UI and retry, or write inside the session workspace."
            ) { put("path", sandbox.relative(file)) }
        }
    }

    private suspend fun requestDestructiveConfirmation(
        action: String,
        title: String,
        message: String,
        confirmLabel: String
    ): ToolResult? {
        return when (confirmationRequester(title, message, confirmLabel)) {
            true -> null
            false -> errorResult(
                action,
                "user_cancelled",
                "User cancelled the destructive file operation.",
                "Review the target and retry only if the change is intended."
            )
            null -> errorResult(
                action,
                "confirmation_unavailable",
                "User confirmation is required for this destructive file operation.",
                "Open the app UI and retry."
            )
        }
    }

    private fun copyFileAndVerify(source: File, destination: File) {
        try {
            fileCopier(source, destination)
            if (source.length() != destination.length() || !fileDigest(source).contentEquals(fileDigest(destination))) {
                throw IllegalStateException("Copied destination did not match source")
            }
        } catch (failure: Throwable) {
            destination.delete()
            throw failure
        }
    }

    private fun fileDigest(file: File): ByteArray {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }

    private fun openAppSettings(): ToolResult {
        return openAppSettingsAction()
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
        if (t is SecurityException && t.message.orEmpty().contains("all files access", ignoreCase = true)) {
            return errorResult(
                action = action,
                code = "all_files_access_required",
                message = "All files access is required for shared storage paths.",
                nextStep = "Grant 'All files access' in Android settings, then retry with the same path."
            ) { put("path", rawPath) }
        }
        val (code, nextStep) = when (t) {
            is SecurityException -> "path_outside_workspace" to
                "Use a relative path in the current session workspace, or an explicit shared:// path."
            is IllegalArgumentException -> "path_not_found" to "Check path and retry."
            else -> "path_invalid" to "Fix path and retry."
        }
        return errorResult(action, code, t.message ?: t.javaClass.simpleName, nextStep) { put("path", rawPath) }
    }

    @Serializable
    private data class Args(
        val action: String? = null,
        val path: String? = null,
        val source: String? = null,
        val destination: String? = null,
        @SerialName("path_base")
        val pathBase: String? = null,
        val pattern: String? = null,
        val query: String? = null,
        @SerialName("file_glob")
        val fileGlob: String? = null,
        val recursive: Boolean? = null,
        val overwrite: Boolean? = null,
        @SerialName("create_parent")
        val createParent: Boolean? = null,
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

    private class DirectoryMoveException : IllegalStateException()

    private class MoveRecoveryException(
        val issues: List<String>,
        val sourcePath: String?,
        val destinationPath: String?,
        val backupPath: String?,
        cause: Throwable
    ) : IllegalStateException(cause)
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

private class FileSandbox(
    private val pathResolver: WorkspacePathResolver
) {
    fun resolveExisting(rawPath: String): File {
        val resolved = pathResolver.resolveExisting(rawPath)
        if (!resolved.exists()) throw IllegalArgumentException("Path does not exist: $rawPath")
        return resolved
    }

    fun resolveForWrite(rawPath: String): File = pathResolver.resolveForWrite(rawPath)

    fun isSharedExternalPath(file: File): Boolean = pathResolver.isSharedExternalPath(file)

    fun isProtectedRoot(file: File): Boolean {
        val canonical = file.canonicalFile
        return canonical == pathResolver.currentWorkspaceRoot() ||
            canonical == pathResolver.sharedWorkspaceRoot() ||
            canonical == pathResolver.sharedExternalRoot()
    }

    fun isDescendant(file: File, possibleParent: File): Boolean {
        val childPath = file.canonicalFile.path
        val parentPath = possibleParent.canonicalFile.path + File.separator
        return childPath.startsWith(parentPath, ignoreCase = ignoreCase)
    }

    fun validateRecursiveTree(root: File): TreeIssue? {
        val rootCanonical = root.canonicalFile
        val pending = ArrayDeque<File>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            val normalized = current.absoluteFile.toPath().normalize().toFile()
            val canonical = current.canonicalFile
            if (!samePath(normalized, canonical)) {
                return TreeIssue("symbolic_link_not_allowed", "Recursive delete does not follow symbolic links or filesystem aliases.")
            }
            if (canonical != rootCanonical && !isDescendant(canonical, rootCanonical)) {
                return TreeIssue("path_outside_target", "Directory entry resolves outside the selected directory.")
            }
            if (current.isDirectory) {
                val children = current.listFiles()
                    ?: return TreeIssue("directory_unreadable", "Directory contents could not be inspected safely.")
                children.forEach(pending::add)
            }
        }
        return null
    }

    fun deleteValidatedTree(root: File): Boolean {
        if (validateRecursiveTree(root) != null) return false
        val entries = root.walkBottomUp().toList()
        return entries.all { it.delete() || !it.exists() }
    }

    fun relative(file: File): String {
        return pathResolver.displayPath(file)
    }

    fun relativeFrom(base: File, file: File): String {
        return base.canonicalFile.toPath().relativize(file.canonicalFile.toPath()).toString().replace('\\', '/').ifBlank { "." }
    }

    private val ignoreCase = System.getProperty("os.name")
        .orEmpty()
        .lowercase(Locale.US)
        .contains("win")

    private fun samePath(first: File, second: File): Boolean {
        return first.path.equals(second.path, ignoreCase = ignoreCase)
    }

    data class TreeIssue(val code: String, val message: String)
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
