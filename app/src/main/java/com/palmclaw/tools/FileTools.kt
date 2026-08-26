package com.palmclaw.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.palmclaw.workspace.WorkspacePathResolver
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Path
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun createFileToolSet(context: Context, pathResolver: WorkspacePathResolver): List<Tool> {
    val appContext = context.applicationContext
    return createFileToolSet(
        context = appContext,
        pathResolver = pathResolver,
        confirmationRequester = { title, message, confirmLabel ->
            AndroidUserActionBridge.requestUserConfirmation(
                title = title,
                message = message,
                confirmLabel = confirmLabel,
                cancelLabel = "Cancel"
            )
        },
        openAppSettings = {
            val packageUri = Uri.parse("package:${appContext.packageName}")
            val intent = Intent(
                if (Build.VERSION.SDK_INT >= 30) {
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
                } else {
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                }
            ).apply {
                data = packageUri
            }
            val result = launchIntent(appContext, intent)
            if (!result.isError || Build.VERSION.SDK_INT < 30) {
                result
            } else {
                val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = packageUri
                }
                launchIntent(appContext, fallback)
            }
        }
    )
}

internal fun createFileToolSet(pathResolver: WorkspacePathResolver): List<Tool> =
    createFileToolSet(
        context = null,
        pathResolver = pathResolver,
        confirmationRequester = { _, _, _ -> true },
        openAppSettings = {
            ToolResult(
                toolCallId = "",
                content = """{"status":"error","operation":"open_settings","code":"ui_unavailable"}""",
                isError = true
            )
        }
    )

internal fun createFileToolSet(
    pathResolver: WorkspacePathResolver,
    confirmationRequester: suspend (title: String, message: String, confirmLabel: String) -> Boolean?,
    fileRenamer: ((source: File, destination: File) -> Boolean)? = null,
    fileCopier: ((source: File, destination: File) -> Unit)? = null,
    fileDeleter: ((target: File) -> Unit)? = null
): List<Tool> =
    createFileToolSet(
        context = null,
        pathResolver = pathResolver,
        confirmationRequester = confirmationRequester,
        openAppSettings = {
            ToolResult(
                toolCallId = "",
                content = """{"status":"error","operation":"open_settings","code":"ui_unavailable"}""",
                isError = true
            )
        },
        fileRenamer = fileRenamer,
        fileCopier = fileCopier,
        fileDeleter = fileDeleter
    )

private fun createFileToolSet(
    context: Context?,
    pathResolver: WorkspacePathResolver,
    confirmationRequester: suspend (title: String, message: String, confirmLabel: String) -> Boolean?,
    openAppSettings: () -> ToolResult,
    fileRenamer: ((source: File, destination: File) -> Boolean)? = null,
    fileCopier: ((source: File, destination: File) -> Unit)? = null,
    fileDeleter: ((target: File) -> Unit)? = null
): List<Tool> {
    val fileSystem = WorkspaceFileSystem(
        pathResolver = pathResolver,
        fileMover = fileRenamer?.let { rename -> { source, destination -> rename(source.toFile(), destination.toFile()) } },
        fileCopier = fileCopier?.let { copy -> { source, destination -> copy(source.toFile(), destination.toFile()) } },
        fileDeleter = fileDeleter?.let { delete -> { target -> delete(target.toFile()) } }
    )
    val module = FileToolModule(
        context = context,
        fileSystem = fileSystem,
        confirmationRequester = confirmationRequester,
        openAppSettingsAction = openAppSettings
    )
    return listOf(
        FileActionTool(
            name = "find",
            description = "Inspect one workspace path, list a directory, or find entries by glob. Returns structured metadata and never follows symbolic links.",
            jsonSchema = objectSchema(
                properties = """
                {
                  "path":{"type":"string"},
                  "pattern":{"type":"string"},
                  "max_depth":{"type":"integer","minimum":0,"maximum":20},
                  "kind":{"type":"string","enum":["any","file","directory","symlink"]},
                  "include_hidden":{"type":"boolean"},
                  "limit":{"type":"integer","minimum":1,"maximum":2000}
                }
                """,
                required = emptyList()
            ),
            runAction = { raw -> module.find(FILE_JSON.decodeFromString(raw)) }
        ),
        FileActionTool(
            name = "grep",
            description = "Search decoded text content in one file or a bounded workspace tree. Directory searches detect each file's encoding independently.",
            jsonSchema = objectSchema(
                properties = """
                {
                  "query":{"type":"string","minLength":1},
                  "path":{"type":"string"},
                  "regex":{"type":"boolean"},
                  "ignore_case":{"type":"boolean"},
                  "file_glob":{"type":"string"},
                  "max_depth":{"type":"integer","minimum":0,"maximum":20},
                  "max_files":{"type":"integer","minimum":1,"maximum":5000},
                  "max_file_bytes":{"type":"integer","minimum":1024,"maximum":5000000},
                  "max_total_bytes":{"type":"integer","minimum":1024,"maximum":100000000},
                  "limit":{"type":"integer","minimum":1,"maximum":2000},
                  "encoding":{"type":"string"}
                }
                """,
                required = listOf("query")
            ),
            runAction = { raw -> module.grep(FILE_JSON.decodeFromString(raw)) }
        ),
        FileActionTool(
            name = "read",
            description = "Read bounded text or extract text from supported PDF, Office, and ODT files. Returns structured source, range, revision, and encoding information.",
            jsonSchema = objectSchema(
                properties = """
                {
                  "path":{"type":"string"},
                  "encoding":{"type":"string"},
                  "start_line":{"type":"integer","minimum":1},
                  "start_column":{"type":"integer","minimum":1},
                  "max_lines":{"type":"integer","minimum":1,"maximum":5000},
                  "max_chars":{"type":"integer","minimum":128,"maximum":1800}
                }
                """,
                required = listOf("path")
            ),
            runAction = { raw -> module.read(FILE_JSON.decodeFromString(raw)) }
        ),
        FileActionTool(
            name = "write",
            description = "Create, overwrite, or append workspace text. mode is explicit; writes validate encoding and revision before safe publication.",
            jsonSchema = objectSchema(
                properties = """
                {
                  "path":{"type":"string"},
                  "text":{"type":"string","maxLength":500000},
                  "mode":{"type":"string","enum":["create","overwrite","append"]},
                  "encoding":{"type":"string"},
                  "create_parent":{"type":"boolean"},
                  "expected_revision":{"type":"string"}
                }
                """,
                required = listOf("path", "text", "mode")
            ),
            runAction = { raw -> module.write(FILE_JSON.decodeFromString(raw)) }
        ),
        FileActionTool(
            name = "edit",
            description = "Make one verified text replacement in an existing file. Unique matching is the safe default and publication is atomic.",
            jsonSchema = objectSchema(
                properties = """
                {
                  "path":{"type":"string"},
                  "find":{"type":"string","minLength":1},
                  "replace":{"type":"string"},
                  "match_mode":{"type":"string","enum":["literal","regex"]},
                  "occurrence":{"type":"string","enum":["unique","first","all"]},
                  "case_sensitive":{"type":"boolean"},
                  "encoding":{"type":"string"},
                  "expected_revision":{"type":"string"}
                }
                """,
                required = listOf("path", "find", "replace")
            ),
            runAction = { raw -> module.edit(FILE_JSON.decodeFromString(raw)) }
        ),
        FileActionTool(
            name = "mkdir",
            description = "Create a workspace directory, optionally including missing parents. Existing directories can be treated idempotently.",
            jsonSchema = objectSchema(
                properties = """
                {
                  "path":{"type":"string"},
                  "parents":{"type":"boolean"},
                  "exist_ok":{"type":"boolean"}
                }
                """,
                required = listOf("path")
            ),
            runAction = { raw -> module.mkdir(FILE_JSON.decodeFromString(raw)) }
        ),
        FileActionTool(
            name = "copy",
            description = "Copy a regular file or bounded directory tree while preserving the source. Existing targets are never overwritten implicitly.",
            jsonSchema = objectSchema(
                properties = """
                {
                  "source":{"type":"string"},
                  "destination":{"type":"string"},
                  "recursive":{"type":"boolean"},
                  "overwrite":{"type":"boolean"},
                  "create_parent":{"type":"boolean"}
                }
                """,
                required = listOf("source", "destination")
            ),
            runAction = { raw -> module.copy(FILE_JSON.decodeFromString(raw)) }
        ),
        FileActionTool(
            name = "move",
            description = "Move or rename a regular file or bounded directory tree. Cross-filesystem moves copy, verify, then remove the source.",
            jsonSchema = objectSchema(
                properties = """
                {
                  "source":{"type":"string"},
                  "destination":{"type":"string"},
                  "overwrite":{"type":"boolean"},
                  "create_parent":{"type":"boolean"}
                }
                """,
                required = listOf("source", "destination")
            ),
            runAction = { raw -> module.move(FILE_JSON.decodeFromString(raw)) }
        ),
        FileActionTool(
            name = "delete",
            description = "Delete one bounded workspace file or directory. Non-empty directories require recursive=true and user confirmation.",
            jsonSchema = objectSchema(
                properties = """
                {
                  "path":{"type":"string"},
                  "recursive":{"type":"boolean"}
                }
                """,
                required = listOf("path")
            ),
            runAction = { raw -> module.delete(FILE_JSON.decodeFromString(raw)) }
        )
    )
}

private class FileToolModule(
    private val context: Context?,
    private val fileSystem: WorkspaceFileSystem,
    private val confirmationRequester: suspend (title: String, message: String, confirmLabel: String) -> Boolean?,
    private val openAppSettingsAction: () -> ToolResult
) {
    fun find(request: FindRequest): ToolResult = toolResult("find") {
        val result = fileSystem.find(
            rawPath = request.path,
            pattern = request.pattern,
            maxDepth = request.maxDepth,
            kind = request.kind,
            includeHidden = request.includeHidden,
            limit = request.limit
        )
        val projectedEntries = mutableListOf<JsonObject>()
        var entryJsonChars = 0
        for (entry in result.entries) {
            val projected = entry.toJson()
            val serializedChars = projected.toString().length
            if (entryJsonChars + serializedChars > MAX_FIND_ENTRY_JSON_CHARS) break
            projectedEntries += projected
            entryJsonChars += serializedChars
        }
        val projectionTruncated = projectedEntries.size < result.entries.size
        success("find", omitFromMetadata = setOf("entries")) {
            put("path", result.base.path)
            put("base", result.base.toJson())
            put("entries", JsonArray(projectedEntries))
            put("returned_count", projectedEntries.size)
            put("scanned_entries", result.scannedEntries)
            put("truncated", result.truncated || projectionTruncated)
            when {
                projectionTruncated -> put("truncation_reason", "result_char_limit")
                result.truncationReason != null -> put("truncation_reason", result.truncationReason)
            }
            request.pattern?.let { put("pattern", it) }
        }
    }

    fun read(request: ReadRequest): ToolResult = toolResult("read") {
        val path = fileSystem.resolveReadableFile(request.path)
        val revisionBeforeRead = fileSystem.revision(path)
        val readResult = context?.let { LocalFileReadSupport.read(it, path.toFile(), request.encoding) }
            ?: LocalFileReadSupport.read(path.toFile(), request.encoding)
        val extracted = when (readResult) {
            is LocalFileReadResult.Success -> readResult
            is LocalFileReadResult.Unsupported -> throw WorkspaceFileException(
                code = readResult.code,
                message = readResult.message,
                nextStep = readResult.nextStep,
                details = encodingDetails(
                    readResult.encodingSource,
                    readResult.encodingConfidence,
                    readResult.encodingCandidates
                )
            )
            is LocalFileReadResult.Failure -> throw WorkspaceFileException(
                code = readResult.code,
                message = readResult.message,
                nextStep = readResult.nextStep
            )
        }
        val revisionAfterRead = fileSystem.revision(path)
        if (revisionAfterRead != revisionBeforeRead) {
            throw WorkspaceFileException(
                code = "file_changed",
                message = "The file changed while it was being read.",
                nextStep = "Read the file again."
            )
        }
        val lines = extracted.text.lines()
        val slice = sliceReadText(lines, request)
        success("read", omitFromMetadata = setOf("text")) {
            put("path", fileSystem.displayPath(path))
            put("source_type", extracted.sourceType)
            put("text", slice.text)
            put("start_line", slice.startLine)
            put("start_column", slice.startColumn)
            put("end_line", slice.endLine)
            put("returned_lines", slice.returnedLines)
            put("total_lines", lines.size)
            put("truncated", slice.truncated)
            slice.nextStartLine?.let { put("next_start_line", it) }
            slice.nextStartColumn?.let { put("next_start_column", it) }
            put("revision", revisionAfterRead)
            extracted.charset?.let { put("charset", it) }
            extracted.encodingSource?.let { put("encoding_source", it.metadataValue) }
            extracted.encodingConfidence?.let { put("encoding_confidence", it) }
            putEncodingCandidates(extracted.encodingCandidates)
            extracted.note?.let { put("note", it) }
        }
    }

    private fun sliceReadText(lines: List<String>, request: ReadRequest): ReadSlice {
        if (request.startLine > lines.size) {
            return ReadSlice(
                text = "",
                startLine = request.startLine,
                startColumn = request.startColumn,
                endLine = lines.size,
                returnedLines = 0,
                truncated = false
            )
        }
        var lineIndex = (request.startLine - 1).coerceAtLeast(0)
        var columnIndex = (request.startColumn - 1).coerceAtLeast(0)
        if (columnIndex > lines[lineIndex].length) {
            throw WorkspaceFileException(
                code = "column_out_of_range",
                message = "start_column is beyond the selected line.",
                nextStep = "Use a column between 1 and ${lines[lineIndex].length + 1}."
            )
        }
        val maxLines = request.maxLines.coerceIn(1, MAX_READ_LINES)
        val maxChars = request.maxChars.coerceIn(MIN_READ_CHARS, MAX_READ_CHARS)
        val output = StringBuilder()
        var returnedLines = 0
        var endLine = request.startLine - 1

        while (lineIndex < lines.size && returnedLines < maxLines) {
            val needsNewline = returnedLines > 0
            if (needsNewline) {
                if (output.length == maxChars) {
                    return ReadSlice(
                        text = output.toString(),
                        startLine = request.startLine,
                        startColumn = request.startColumn,
                        endLine = endLine,
                        returnedLines = returnedLines,
                        truncated = true,
                        nextStartLine = lineIndex + 1,
                        nextStartColumn = columnIndex + 1
                    )
                }
                output.append('\n')
            }
            val segment = lines[lineIndex].substring(columnIndex)
            val available = maxChars - output.length
            if (segment.length > available) {
                val taken = if (
                    available > 0 &&
                    available < segment.length &&
                    Character.isHighSurrogate(segment[available - 1]) &&
                    Character.isLowSurrogate(segment[available])
                ) {
                    available - 1
                } else {
                    available
                }
                output.append(segment, 0, taken)
                if (taken > 0) {
                    returnedLines += 1
                    endLine = lineIndex + 1
                }
                return ReadSlice(
                    text = output.toString(),
                    startLine = request.startLine,
                    startColumn = request.startColumn,
                    endLine = endLine,
                    returnedLines = returnedLines,
                    truncated = true,
                    nextStartLine = lineIndex + 1,
                    nextStartColumn = columnIndex + taken + 1
                )
            }
            output.append(segment)
            returnedLines += 1
            endLine = lineIndex + 1
            lineIndex += 1
            columnIndex = 0
        }
        val truncated = lineIndex < lines.size
        return ReadSlice(
            text = output.toString(),
            startLine = request.startLine,
            startColumn = request.startColumn,
            endLine = endLine,
            returnedLines = returnedLines,
            truncated = truncated,
            nextStartLine = (lineIndex + 1).takeIf { truncated },
            nextStartColumn = 1.takeIf { truncated }
        )
    }

    fun grep(request: GrepRequest): ToolResult = toolResult("grep") {
        if (request.query.isEmpty()) {
            throw WorkspaceFileException(
                code = "empty_query",
                message = "query must not be empty.",
                nextStep = "Provide text or a regular expression to search for."
            )
        }
        val base = fileSystem.find(request.path, null, 0, "any", false, 1).base
        if (base.type == WorkspaceEntryType.DIRECTORY && hasExplicitEncodingRequest(request.encoding)) {
            throw WorkspaceFileException(
                code = "encoding_hint_requires_file",
                message = "encoding can only be provided when path identifies one file.",
                nextStep = "Remove encoding for directory grep so every file is detected independently."
            )
        }
        val regex = if (request.regex) {
            val options = if (request.ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
            runCatching { Regex(request.query, options) }.getOrElse { failure ->
                throw WorkspaceFileException(
                    code = "invalid_regex",
                    message = "Invalid regular expression.",
                    nextStep = "Fix regex syntax and retry.",
                    cause = failure
                )
            }
        } else {
            null
        }
        val scan = fileSystem.scanFiles(
            rawPath = request.path,
            filePattern = request.fileGlob,
            maxDepth = request.maxDepth,
            maxFiles = request.maxFiles
        )
        val matches = mutableListOf<JsonObject>()
        val skipped = mutableListOf<JsonObject>()
        val charsets = linkedSetOf<String>()
        val sources = linkedSetOf<String>()
        val candidates = linkedMapOf<String, Int>()
        val confidences = mutableListOf<Int>()
        var filesScanned = 0
        var skippedCount = 0
        var matchJsonChars = 0
        var bytesScanned = 0L
        var truncated = scan.truncated
        var truncationReason = scan.truncationReason
        fun recordSkipped(path: Path, reason: String) {
            skippedCount += 1
            if (skipped.size < MAX_GREP_SKIPPED_DETAILS) {
                skipped += skippedFile(path, reason)
            }
        }
        for (path in scan.files) {
            val size = fileSystem.size(path)
            if (size > request.maxFileBytes) {
                if (base.type == WorkspaceEntryType.FILE) {
                    throw WorkspaceFileException(
                        code = "file_too_large",
                        message = "File exceeds max_file_bytes.",
                        nextStep = "Increase max_file_bytes within the supported limit or narrow the target."
                    )
                }
                recordSkipped(path, "file_too_large")
                continue
            }
            if (bytesScanned + size > request.maxTotalBytes) {
                truncated = true
                truncationReason = "total_byte_limit"
                break
            }
            val bytes = try {
                fileSystem.readBytesBounded(path, request.maxFileBytes)
            } catch (failure: WorkspaceFileException) {
                if (base.type == WorkspaceEntryType.FILE) throw failure
                recordSkipped(path, failure.code)
                continue
            } catch (failure: Throwable) {
                recordSkipped(path, failure.message ?: "read_failed")
                continue
            }
            if (bytesScanned + bytes.size > request.maxTotalBytes) {
                truncated = true
                truncationReason = "total_byte_limit"
                break
            }
            bytesScanned += bytes.size
            val decoded = when (
                val decode = WorkspaceTextCodec.default.decode(
                    bytes = bytes,
                    encodingHint = request.encoding,
                    accessMode = WorkspaceTextAccessMode.READ_ONLY
                )
            ) {
                is WorkspaceTextDecodeResult.Success -> decode
                is WorkspaceTextDecodeResult.Unsupported -> {
                    if (base.type == WorkspaceEntryType.FILE) {
                        throw WorkspaceFileException(
                            code = decode.code,
                            message = decode.message,
                            nextStep = decode.nextStep,
                            details = encodingDetails(decode.source, decode.confidence, decode.candidates)
                        )
                    }
                    recordSkipped(path, decode.code)
                    continue
                }
            }
            charsets += decoded.format.charsetName
            sources += decoded.source.metadataValue
            decoded.confidence?.let(confidences::add)
            filesScanned += 1
            decoded.candidates.forEach { candidate ->
                candidates[candidate.charset] = maxOf(candidates[candidate.charset] ?: 0, candidate.confidence)
            }
            for ((lineIndex, line) in decoded.text.lines().withIndex()) {
                val matchPosition = if (regex != null) {
                    regex.find(line)?.let { it.range.first to it.value }
                } else {
                    firstLiteralMatch(line, request.query, request.ignoreCase)
                } ?: continue
                val (column, matched) = matchPosition
                val match = buildJsonObject {
                    put("path", fileSystem.displayPath(path))
                    put("line_number", lineIndex + 1)
                    put("column", column + 1)
                    put("line_text", line.take(MAX_GREP_LINE_CHARS))
                    put("match_text", matched)
                }
                val serializedChars = match.toString().length
                if (matchJsonChars + serializedChars > MAX_GREP_MATCH_JSON_CHARS) {
                    truncated = true
                    truncationReason = "result_char_limit"
                    break
                }
                matches += match
                matchJsonChars += serializedChars
                if (matches.size >= request.limit) {
                    truncated = true
                    truncationReason = "result_limit"
                    break
                }
                if (matches.size >= request.limit || truncationReason == "result_char_limit") break
            }
            if (matches.size >= request.limit || truncationReason == "result_char_limit") break
        }
        success("grep", omitFromMetadata = setOf("matches", "files_skipped")) {
            put("query", request.query)
            put("path", base.path)
            put("matches", JsonArray(matches))
            put("match_count", matches.size)
            put("files_scanned", filesScanned)
            put("scanned_entries", scan.scannedEntries)
            put("bytes_scanned", bytesScanned)
            put("files_skipped", JsonArray(skipped))
            put("files_skipped_count", skippedCount)
            put("skipped_details_truncated", skippedCount > skipped.size)
            put("skipped_symbolic_links", scan.skippedSymbolicLinks)
            put("truncated", truncated)
            truncationReason?.let { put("truncation_reason", it) }
            if (charsets.isNotEmpty()) put("charsets", buildJsonArray { charsets.forEach(::add) })
            if (sources.size == 1) put("encoding_source", sources.first())
            if (base.type == WorkspaceEntryType.FILE && confidences.size == 1) {
                put("encoding_confidence", confidences.single())
            }
            if (candidates.isNotEmpty()) {
                put(
                    "encoding_candidates",
                    buildJsonArray {
                        candidates.entries.sortedByDescending { it.value }.take(3).forEach { (charset, confidence) ->
                            add(buildJsonObject {
                                put("charset", charset)
                                put("confidence", confidence)
                            })
                        }
                    }
                )
            }
        }
    }

    suspend fun write(request: WriteRequest): ToolResult = toolResultSuspend("write") {
        if (request.mode !in setOf("create", "overwrite", "append")) {
            throw WorkspaceFileException(
                code = "invalid_write_mode",
                message = "mode must be create, overwrite, or append.",
                nextStep = "Choose one supported write mode."
            )
        }
        if (request.text.length > MAX_WRITE_CHARS) {
            throw WorkspaceFileException(
                code = "text_too_large",
                message = "Text exceeds the maximum write size.",
                nextStep = "Split the content into smaller files."
            )
        }
        val (target, exists) = withPermissionRecovery("write") {
            val resolved = fileSystem.resolveWritablePath(request.path)
            resolved to fileSystem.exists(request.path)
        }
        if (exists && fileSystem.find(request.path, null, 0, "any", false, 1).base.type == WorkspaceEntryType.DIRECTORY) {
            throw WorkspaceFileException("path_is_directory", "Target path is a directory.", "Choose a file path.")
        }
        if (request.mode == "create" && exists) {
            throw WorkspaceFileException("target_exists", "Create mode requires a new path.", "Choose another path or use overwrite.")
        }
        val initialRevision = if (exists) fileSystem.revision(target) else null
        checkExpectedRevision(initialRevision, request.expectedRevision)
        confirmExternalMutation("write", listOf(target))

        val existingDecode = if (request.mode == "append" && exists && fileSystem.size(target) > 0L) {
            val bytes = fileSystem.readBytesBounded(target, MAX_TEXT_MUTATION_BYTES)
            when (
                val decoded = WorkspaceTextCodec.default.decode(
                    bytes,
                    request.encoding,
                    WorkspaceTextAccessMode.MUTATION
                )
            ) {
                is WorkspaceTextDecodeResult.Success -> decoded
                is WorkspaceTextDecodeResult.Unsupported -> throw codecException(decoded)
            }
        } else {
            null
        }
        if (existingDecode == null && !isCanonicalUtf8Request(request.encoding)) {
            throw WorkspaceFileException(
                code = "overwrite_requires_utf8",
                message = "New and overwritten workspace text uses canonical UTF-8.",
                nextStep = "Remove encoding or use UTF-8."
            )
        }
        val outputFormat = existingDecode?.format ?: WorkspaceTextFormat.UTF8
        val completeText = if (existingDecode != null) existingDecode.text + request.text else request.text
        val encoded = when (val output = WorkspaceTextCodec.default.encode(completeText, outputFormat)) {
            is WorkspaceTextEncodeResult.Success -> output
            is WorkspaceTextEncodeResult.Unsupported -> throw WorkspaceFileException(
                output.code,
                output.message,
                output.nextStep
            )
        }
        val summary = withPermissionRecovery("write") {
            fileSystem.atomicWrite(
                path = target,
                bytes = encoded.bytes,
                createParent = request.createParent,
                requireAbsent = request.mode == "create",
                expectedRevision = initialRevision,
                expectAbsent = !exists && request.mode != "create"
            )
        }
        success("write") {
            put("path", fileSystem.displayPath(target))
            put("mode", request.mode)
            put("created", !exists)
            put("bytes_written", encoded.bytes.size)
            put("final_size_bytes", summary.finalSizeBytes)
            put("charset", outputFormat.charsetName)
            put("encoding_source", existingDecode?.source?.metadataValue ?: "utf8")
            existingDecode?.confidence?.let { put("encoding_confidence", it) }
            putEncodingCandidates(existingDecode?.candidates.orEmpty())
            put("revision", "sha256:${summary.sha256}")
            put("atomic", summary.atomic)
            put("partial", false)
        }
    }

    suspend fun edit(request: EditRequest): ToolResult = toolResultSuspend("edit") {
        val path = withPermissionRecovery("edit") {
            fileSystem.resolveReadableFile(request.path)
        }
        val initialRevision = fileSystem.revision(path)
        checkExpectedRevision(initialRevision, request.expectedRevision)
        confirmExternalMutation("edit", listOf(path))
        val originalBytes = fileSystem.readBytesBounded(path, MAX_TEXT_MUTATION_BYTES)
        val decoded = when (
            val result = WorkspaceTextCodec.default.decode(
                originalBytes,
                request.encoding,
                WorkspaceTextAccessMode.MUTATION
            )
        ) {
            is WorkspaceTextDecodeResult.Success -> result
            is WorkspaceTextDecodeResult.Unsupported -> throw codecException(result)
        }
        val edit = applyEdit(decoded.text, request)
        val encoded = when (val output = WorkspaceTextCodec.default.encode(edit.updated, decoded.format)) {
            is WorkspaceTextEncodeResult.Success -> output
            is WorkspaceTextEncodeResult.Unsupported -> throw WorkspaceFileException(
                output.code,
                output.message,
                output.nextStep
            )
        }
        val summary = withPermissionRecovery("edit") {
            fileSystem.atomicWrite(
                path = path,
                bytes = encoded.bytes,
                createParent = false,
                expectedRevision = initialRevision
            )
        }
        success("edit") {
            put("path", fileSystem.displayPath(path))
            put("matched_count", edit.matchedCount)
            put("replaced_count", edit.replacedCount)
            put("match_mode", request.matchMode)
            put("occurrence", request.occurrence)
            put("charset", decoded.format.charsetName)
            put("encoding_source", decoded.source.metadataValue)
            decoded.confidence?.let { put("encoding_confidence", it) }
            putEncodingCandidates(decoded.candidates)
            put("before_revision", initialRevision)
            put("after_revision", "sha256:${summary.sha256}")
            put("atomic", summary.atomic)
            put("partial", false)
        }
    }

    suspend fun mkdir(request: MkdirRequest): ToolResult = toolResultSuspend("mkdir") {
        val target = withPermissionRecovery("mkdir") {
            fileSystem.resolveWritablePath(request.path)
        }
        confirmExternalMutation("mkdir", listOf(target))
        val (createdPath, created) = withPermissionRecovery("mkdir") {
            fileSystem.createDirectory(request.path, request.parents, request.existOk)
        }
        success("mkdir") {
            put("path", fileSystem.displayPath(createdPath))
            put("created", created)
            put("already_existed", !created)
            put("partial", false)
        }
    }

    suspend fun copy(request: CopyRequest): ToolResult = toolResultSuspend("copy") {
        val (sourceIdentity, destination, destinationIdentity) = withPermissionRecovery("copy") {
            fileSystem.find(request.source, null, 0, "any", false, 1)
            val resolved = fileSystem.resolveWritablePath(request.destination)
            Triple(
                fileSystem.requireMutationIdentity(request.source),
                resolved,
                fileSystem.mutationIdentity(request.destination)
            )
        }
        val destinationExists = destinationIdentity != null
        if (fileSystem.isProtectedRoot(destination)) {
            throw WorkspaceFileException(
                code = "protected_path",
                message = "Workspace and shared-storage roots cannot be replaced.",
                nextStep = "Choose a child destination path."
            )
        }
        val reasons = mutableListOf<String>()
        if (destinationExists && request.overwrite) reasons += "replace the existing destination"
        if (fileSystem.isExternal(destination)) reasons += "write external shared storage"
        if (reasons.isNotEmpty()) {
            confirm(
                title = "Copy File",
                message = "Allow PalmClaw to ${reasons.joinToString(" and ")}?\n${fileSystem.displayPath(destination)}",
                confirmLabel = "Copy"
            )
        }
        val summary = withPermissionRecovery("copy") {
            fileSystem.copy(
                rawSource = request.source,
                rawDestination = request.destination,
                recursive = request.recursive,
                overwrite = destinationExists && request.overwrite,
                createParent = request.createParent,
                expectedSourceIdentity = sourceIdentity,
                expectedDestinationIdentity = destinationIdentity
            )
        }
        mutationSuccess("copy", summary)
    }

    suspend fun move(request: MoveRequest): ToolResult = toolResultSuspend("move") {
        val (source, destination, sourceIdentity, destinationIdentity) = withPermissionRecovery("move") {
            fileSystem.find(request.source, null, 0, "any", false, 1)
            MovePreflight(
                fileSystem.resolveWritablePath(request.source),
                fileSystem.resolveWritablePath(request.destination),
                fileSystem.requireMutationIdentity(request.source),
                fileSystem.mutationIdentity(request.destination)
            )
        }
        val destinationExists = destinationIdentity != null
        if (fileSystem.isProtectedRoot(source) || fileSystem.isProtectedRoot(destination)) {
            throw WorkspaceFileException(
                code = "protected_path",
                message = "Workspace and shared-storage roots cannot be moved or replaced.",
                nextStep = "Choose child source and destination paths."
            )
        }
        val reasons = mutableListOf<String>()
        if (destinationExists && request.overwrite) reasons += "replace the existing destination"
        if (fileSystem.isExternal(source) || fileSystem.isExternal(destination)) {
            reasons += "modify external shared storage"
        }
        if (reasons.isNotEmpty()) {
            confirm(
                title = "Move File",
                message = "Allow PalmClaw to ${reasons.joinToString(" and ")}?\n${request.source} → ${request.destination}",
                confirmLabel = "Move"
            )
        }
        val summary = withPermissionRecovery("move") {
            fileSystem.move(
                rawSource = request.source,
                rawDestination = request.destination,
                overwrite = destinationExists && request.overwrite,
                createParent = request.createParent,
                expectedSourceIdentity = sourceIdentity,
                expectedDestinationIdentity = destinationIdentity
            )
        }
        mutationSuccess("move", summary)
    }

    suspend fun delete(request: DeleteRequest): ToolResult = toolResultSuspend("delete") {
        val plan = withPermissionRecovery("delete") {
            fileSystem.planDelete(request.path)
        }
        val nonEmptyDirectory = plan.type == WorkspaceEntryType.DIRECTORY && plan.entries.size > 1
        val requiresConfirmation = fileSystem.isExternal(plan.target) || (request.recursive && nonEmptyDirectory)
        if (requiresConfirmation) {
            confirm(
                title = "Delete File",
                message = buildString {
                    append("Delete ${fileSystem.displayPath(plan.target)}?")
                    append("\n${plan.files} files, ${plan.directories} directories, ${plan.bytes} bytes")
                    if (fileSystem.isExternal(plan.target)) append("\nThis path is in external shared storage.")
                },
                confirmLabel = "Delete"
            )
        }
        val summary = withPermissionRecovery("delete") {
            fileSystem.delete(plan, request.recursive)
        }
        success("delete") {
            put("path", fileSystem.displayPath(plan.target))
            put("type", summary.type.wireName)
            put("files_deleted", summary.filesProcessed)
            put("directories_deleted", summary.directoriesProcessed)
            put("bytes_deleted", summary.bytesProcessed)
            put("verified", summary.verified)
            put("partial", summary.partial)
        }
    }

    private fun mutationSuccess(operation: String, summary: WorkspaceMutationSummary): ToolResult =
        success(operation) {
            summary.source?.let { put("source", it) }
            summary.destination?.let { put("destination", it) }
            put("type", summary.type.wireName)
            put("files_processed", summary.filesProcessed)
            put("directories_processed", summary.directoriesProcessed)
            put("bytes_processed", summary.bytesProcessed)
            put("overwritten", summary.overwritten)
            put("atomic", summary.atomic)
            put("verified", summary.verified)
            put("partial", summary.partial)
        }

    private suspend fun confirmExternalMutation(operation: String, paths: List<Path>) {
        val external = paths.firstOrNull(fileSystem::isExternal) ?: return
        confirm(
            title = "External File Write",
            message = "Allow PalmClaw to $operation this external shared-storage path?\n${fileSystem.displayPath(external)}",
            confirmLabel = "Allow"
        )
    }

    private suspend fun confirm(title: String, message: String, confirmLabel: String) {
        when (confirmationRequester(title, message, confirmLabel)) {
            true -> Unit
            false -> throw WorkspaceFileException(
                code = "user_cancelled",
                message = "User cancelled the file operation.",
                nextStep = "Review the target and retry only if the change is intended."
            )
            null -> throw WorkspaceFileException(
                code = "confirmation_unavailable",
                message = "User confirmation is required for this file operation.",
                nextStep = "Open the app UI and retry."
            )
        }
    }

    private suspend fun <T> withPermissionRecovery(operation: String, block: () -> T): T {
        try {
            return block()
        } catch (failure: Throwable) {
            if (!isPermissionIssue(failure)) throw failure
            val openResult = openAppSettingsAction()
            if (openResult.isError) {
                throw WorkspaceFileException(
                    code = "permission_denied",
                    message = "Permission denied and app settings could not be opened.",
                    nextStep = "Grant storage permission manually and retry.",
                    cause = failure
                )
            }
            confirm(
                title = "Permission Required",
                message = "Grant storage permission in app settings, return, then continue $operation.",
                confirmLabel = "Continue"
            )
            return try {
                block()
            } catch (secondFailure: Throwable) {
                throw WorkspaceFileException(
                    code = "permission_still_denied",
                    message = secondFailure.message ?: "Permission is still denied.",
                    nextStep = "Check app permissions and retry.",
                    cause = secondFailure
                )
            }
        }
    }

    private fun checkExpectedRevision(actual: String?, expected: String?) {
        if (expected.isNullOrBlank()) return
        if (actual != expected.trim()) {
            throw WorkspaceFileException(
                code = "file_changed",
                message = "The file revision no longer matches the caller's expected revision.",
                nextStep = "Read the file again and retry against the new revision."
            )
        }
    }

    private fun applyEdit(source: String, request: EditRequest): AppliedEdit {
        if (request.find.isEmpty()) {
            throw WorkspaceFileException(
                code = "empty_find",
                message = "find must not be empty.",
                nextStep = "Provide a non-empty literal or regular expression."
            )
        }
        val occurrence = request.occurrence.lowercase(Locale.US)
        if (occurrence !in setOf("unique", "first", "all")) {
            throw WorkspaceFileException("invalid_occurrence", "occurrence must be unique, first, or all.")
        }
        val regexMode = request.matchMode.equals("regex", ignoreCase = true)
        if (!regexMode && !request.matchMode.equals("literal", ignoreCase = true)) {
            throw WorkspaceFileException("invalid_match_mode", "match_mode must be literal or regex.")
        }
        if (regexMode) {
            val options = if (request.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
            val pattern = runCatching { Regex(request.find, options) }.getOrElse { failure ->
                throw WorkspaceFileException("invalid_regex", "Invalid regular expression.", "Fix the pattern.", cause = failure)
            }
            val count = pattern.findAll(source).count()
            validateMatchCount(count, occurrence)
            val updated = when (occurrence) {
                "all" -> pattern.replace(source, request.replace)
                else -> pattern.replaceFirst(source, request.replace)
            }
            return AppliedEdit(updated, count, if (occurrence == "all") count else 1)
        }
        val ignoreCase = !request.caseSensitive
        val count = countOccurrences(source, request.find, ignoreCase)
        validateMatchCount(count, occurrence)
        val updated = when (occurrence) {
            "all" -> source.replace(request.find, request.replace, ignoreCase)
            else -> {
                val index = source.indexOf(request.find, 0, ignoreCase)
                source.replaceRange(index, index + request.find.length, request.replace)
            }
        }
        return AppliedEdit(updated, count, if (occurrence == "all") count else 1)
    }

    private fun validateMatchCount(count: Int, occurrence: String) {
        if (count == 0) {
            throw WorkspaceFileException("no_matches", "No matches were found.", "Adjust find and retry.")
        }
        if (occurrence == "unique" && count != 1) {
            throw WorkspaceFileException(
                "ambiguous_match",
                "The pattern matched $count locations.",
                "Use a unique snippet or explicitly choose first or all."
            )
        }
    }

    private fun countOccurrences(text: String, target: String, ignoreCase: Boolean): Int {
        var count = 0
        var start = 0
        while (start <= text.length - target.length) {
            val index = text.indexOf(target, start, ignoreCase)
            if (index < 0) break
            count += 1
            start = index + target.length.coerceAtLeast(1)
        }
        return count
    }

    private fun firstLiteralMatch(
        line: String,
        query: String,
        ignoreCase: Boolean
    ): Pair<Int, String>? {
        val index = line.indexOf(query, startIndex = 0, ignoreCase = ignoreCase)
        return index.takeIf { it >= 0 }?.let { it to line.substring(it, it + query.length) }
    }

    private fun skippedFile(path: Path, reason: String): JsonObject = buildJsonObject {
        put("path", fileSystem.displayPath(path))
        put("reason", reason)
    }

    private fun codecException(result: WorkspaceTextDecodeResult.Unsupported): WorkspaceFileException =
        WorkspaceFileException(
            code = result.code,
            message = result.message,
            nextStep = result.nextStep,
            details = encodingDetails(result.source, result.confidence, result.candidates)
        )

    private fun encodingDetails(
        source: WorkspaceTextEncodingSource?,
        confidence: Int?,
        candidates: List<WorkspaceTextEncodingCandidate>
    ): Map<String, String> = buildMap {
        source?.let { put("encoding_source", it.metadataValue) }
        confidence?.let { put("encoding_confidence", it.toString()) }
        if (candidates.isNotEmpty()) {
            put(
                "encoding_candidates",
                candidates.joinToString(",") { "${it.charset}:${it.confidence}" }
            )
        }
    }

    private fun isPermissionIssue(failure: Throwable): Boolean {
        var current: Throwable? = failure
        while (current != null) {
            if (current is WorkspaceFileException) {
                if (current.code in setOf("all_files_access_required", "permission_denied")) {
                    return true
                }
                if (current.code == "path_outside_workspace") {
                    return false
                }
            }
            if (current is SecurityException) return true
            val message = current.message.orEmpty()
            if (message.contains("permission", ignoreCase = true) ||
                message.contains("denied", ignoreCase = true)
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun isCanonicalUtf8Request(requestedEncoding: String?): Boolean {
        val normalized = requestedEncoding?.trim().orEmpty()
        if (normalized.isBlank() || normalized.equals("auto", ignoreCase = true)) return true
        return runCatching { Charset.forName(normalized) }
            .getOrNull()
            ?.name()
            .equals(Charsets.UTF_8.name(), ignoreCase = true)
    }

    private fun hasExplicitEncodingRequest(requestedEncoding: String?): Boolean {
        val normalized = requestedEncoding?.trim().orEmpty()
        return normalized.isNotBlank() && !normalized.equals("auto", ignoreCase = true)
    }

    private inline fun toolResult(operation: String, block: () -> ToolResult): ToolResult =
        try {
            block()
        } catch (failure: WorkspaceFileException) {
            error(operation, failure)
        } catch (failure: Throwable) {
            error(
                operation,
                WorkspaceFileException(
                    code = "io_error",
                    message = failure.message ?: failure.javaClass.simpleName,
                    nextStep = "Check the path and parameters, then retry.",
                    cause = failure
                )
            )
        }

    private suspend inline fun toolResultSuspend(
        operation: String,
        crossinline block: suspend () -> ToolResult
    ): ToolResult =
        try {
            block()
        } catch (failure: WorkspaceFileException) {
            error(operation, failure)
        } catch (failure: Throwable) {
            error(
                operation,
                WorkspaceFileException(
                    code = "io_error",
                    message = failure.message ?: failure.javaClass.simpleName,
                    nextStep = "Check the path and parameters, then retry.",
                    cause = failure
                )
            )
        }

    private fun success(
        operation: String,
        omitFromMetadata: Set<String> = emptySet(),
        extra: JsonObjectBuilder.() -> Unit
    ): ToolResult {
        val body = buildJsonObject {
            put("status", "ok")
            put("operation", operation)
            extra()
        }
        return ToolResult(
            toolCallId = "",
            content = body.toString(),
            isError = false,
            metadata = JsonObject(
                body.filterKeys { it !in omitFromMetadata } +
                    mapOf(
                        "tool" to JsonPrimitive(operation),
                        "action" to JsonPrimitive(operation)
                    )
            )
        )
    }

    private fun error(operation: String, failure: WorkspaceFileException): ToolResult {
        val body = buildJsonObject {
            put("status", "error")
            put("operation", operation)
            put("code", failure.code)
            put("message", failure.message)
            put("recoverable", !failure.nextStep.isNullOrBlank())
            failure.nextStep?.let { put("next_step", it) }
            put("partial", failure.partial)
            failure.details.forEach { (key, value) ->
                if (key == "encoding_confidence") {
                    value.toIntOrNull()?.let { put(key, it) }
                } else if (key == "encoding_candidates") {
                    put(
                        key,
                        buildJsonArray {
                            value.split(',').filter(String::isNotBlank).forEach { item ->
                                val separator = item.lastIndexOf(':')
                                if (separator > 0) {
                                    add(buildJsonObject {
                                        put("charset", item.substring(0, separator))
                                        put("confidence", item.substring(separator + 1).toIntOrNull() ?: 0)
                                    })
                                }
                            }
                        }
                    )
                } else {
                    put(key, value)
                }
            }
        }
        return ToolResult(
            toolCallId = "",
            content = body.toString(),
            isError = true,
            metadata = JsonObject(
                body + mapOf(
                    "tool" to JsonPrimitive(operation),
                    "action" to JsonPrimitive(operation),
                    "error" to JsonPrimitive(failure.code)
                )
            )
        )
    }

    private fun JsonObjectBuilder.putEncodingCandidates(
        candidates: List<WorkspaceTextEncodingCandidate>
    ) {
        if (candidates.isEmpty()) return
        put(
            "encoding_candidates",
            buildJsonArray {
                candidates.forEach { candidate ->
                    add(buildJsonObject {
                        put("charset", candidate.charset)
                        put("confidence", candidate.confidence)
                    })
                }
            }
        )
    }

    private fun WorkspaceFileEntry.toJson(): JsonObject = buildJsonObject {
        put("path", path)
        put("name", name)
        put("type", type.wireName)
        sizeBytes?.let { put("size_bytes", it) }
        put("modified_at_ms", modifiedAtMs)
        put("readable", readable)
        put("writable", writable)
        put("hidden", hidden)
    }

    private data class AppliedEdit(
        val updated: String,
        val matchedCount: Int,
        val replacedCount: Int
    )

    private data class ReadSlice(
        val text: String,
        val startLine: Int,
        val startColumn: Int,
        val endLine: Int,
        val returnedLines: Int,
        val truncated: Boolean,
        val nextStartLine: Int? = null,
        val nextStartColumn: Int? = null
    )
}

private class FileActionTool(
    override val name: String,
    override val description: String,
    override val jsonSchema: JsonObject,
    private val runAction: suspend (String) -> ToolResult
) : Tool, TimedTool {
    override val timeoutMs: Long = 180_000L

    override suspend fun run(argumentsJson: String): ToolResult = withContext(Dispatchers.IO) {
        runAction(argumentsJson)
    }
}

private fun objectSchema(properties: String, required: List<String>): JsonObject = buildJsonObject {
    put("type", "object")
    put("additionalProperties", false)
    if (required.isNotEmpty()) {
        put("required", buildJsonArray { required.forEach { field -> add(field) } })
    }
    put("properties", FILE_JSON.parseToJsonElement(properties.trimIndent()))
}

@Serializable
private data class FindRequest(
    val path: String = ".",
    val pattern: String? = null,
    @SerialName("max_depth")
    val maxDepth: Int = 1,
    val kind: String = "any",
    @SerialName("include_hidden")
    val includeHidden: Boolean = false,
    val limit: Int = DEFAULT_FIND_LIMIT
)

@Serializable
private data class ReadRequest(
    val path: String,
    val encoding: String? = null,
    @SerialName("start_line")
    val startLine: Int = 1,
    @SerialName("start_column")
    val startColumn: Int = 1,
    @SerialName("max_lines")
    val maxLines: Int = DEFAULT_READ_MAX_LINES,
    @SerialName("max_chars")
    val maxChars: Int = DEFAULT_READ_MAX_CHARS
)

@Serializable
private data class GrepRequest(
    val query: String,
    val path: String = ".",
    val regex: Boolean = false,
    @SerialName("ignore_case")
    val ignoreCase: Boolean = false,
    @SerialName("file_glob")
    val fileGlob: String? = null,
    @SerialName("max_depth")
    val maxDepth: Int = DEFAULT_GREP_DEPTH,
    @SerialName("max_files")
    val maxFiles: Int = DEFAULT_GREP_MAX_FILES,
    @SerialName("max_file_bytes")
    val maxFileBytes: Long = DEFAULT_GREP_MAX_FILE_BYTES,
    @SerialName("max_total_bytes")
    val maxTotalBytes: Long = DEFAULT_GREP_MAX_TOTAL_BYTES,
    val limit: Int = DEFAULT_GREP_LIMIT,
    val encoding: String? = null
)

@Serializable
private data class WriteRequest(
    val path: String,
    val text: String,
    val mode: String,
    val encoding: String? = null,
    @SerialName("create_parent")
    val createParent: Boolean = true,
    @SerialName("expected_revision")
    val expectedRevision: String? = null
)

@Serializable
private data class EditRequest(
    val path: String,
    val find: String,
    val replace: String,
    @SerialName("match_mode")
    val matchMode: String = "literal",
    val occurrence: String = "unique",
    @SerialName("case_sensitive")
    val caseSensitive: Boolean = true,
    val encoding: String? = null,
    @SerialName("expected_revision")
    val expectedRevision: String? = null
)

@Serializable
private data class MkdirRequest(
    val path: String,
    val parents: Boolean = true,
    @SerialName("exist_ok")
    val existOk: Boolean = true
)

@Serializable
private data class CopyRequest(
    val source: String,
    val destination: String,
    val recursive: Boolean = false,
    val overwrite: Boolean = false,
    @SerialName("create_parent")
    val createParent: Boolean = false
)

@Serializable
private data class MoveRequest(
    val source: String,
    val destination: String,
    val overwrite: Boolean = false,
    @SerialName("create_parent")
    val createParent: Boolean = false
)

@Serializable
private data class DeleteRequest(
    val path: String,
    val recursive: Boolean = false
)

private data class MovePreflight(
    val source: Path,
    val destination: Path,
    val sourceIdentity: String,
    val destinationIdentity: String?
)

private val FILE_JSON = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
}

private const val DEFAULT_FIND_LIMIT = 200
private const val MAX_FIND_ENTRY_JSON_CHARS = 2_500
private const val DEFAULT_READ_MAX_LINES = 400
private const val MAX_READ_LINES = 5_000
private const val MIN_READ_CHARS = 128
private const val DEFAULT_READ_MAX_CHARS = 1_800
private const val MAX_READ_CHARS = 1_800
private const val MAX_WRITE_CHARS = 500_000
private const val MAX_TEXT_MUTATION_BYTES = 5_000_000L
private const val DEFAULT_GREP_DEPTH = 12
private const val DEFAULT_GREP_MAX_FILES = 2_000
private const val DEFAULT_GREP_MAX_FILE_BYTES = 1_000_000L
private const val DEFAULT_GREP_MAX_TOTAL_BYTES = 20_000_000L
private const val DEFAULT_GREP_LIMIT = 200
private const val MAX_GREP_LINE_CHARS = 400
private const val MAX_GREP_MATCH_JSON_CHARS = 2_500
private const val MAX_GREP_SKIPPED_DETAILS = 20
