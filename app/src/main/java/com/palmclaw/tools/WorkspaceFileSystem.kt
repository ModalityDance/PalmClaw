package com.palmclaw.tools

import com.palmclaw.workspace.WorkspacePathResolver
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystems
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

internal enum class WorkspaceEntryType(val wireName: String) {
    FILE("file"),
    DIRECTORY("directory"),
    SYMBOLIC_LINK("symlink"),
    OTHER("other")
}

internal data class WorkspaceFileEntry(
    val path: String,
    val name: String,
    val type: WorkspaceEntryType,
    val sizeBytes: Long?,
    val modifiedAtMs: Long,
    val readable: Boolean,
    val writable: Boolean,
    val hidden: Boolean
)

internal data class WorkspaceFindResult(
    val base: WorkspaceFileEntry,
    val entries: List<WorkspaceFileEntry>,
    val scannedEntries: Int,
    val truncated: Boolean,
    val truncationReason: String?
)

internal data class WorkspaceFileScan(
    val files: List<Path>,
    val scannedEntries: Int,
    val skippedSymbolicLinks: Int,
    val truncated: Boolean,
    val truncationReason: String?
)

internal data class WorkspaceWriteSummary(
    val atomic: Boolean,
    val finalSizeBytes: Long,
    val sha256: String
)

internal data class WorkspaceMutationSummary(
    val source: String? = null,
    val destination: String? = null,
    val type: WorkspaceEntryType,
    val filesProcessed: Int,
    val directoriesProcessed: Int,
    val bytesProcessed: Long,
    val overwritten: Boolean = false,
    val atomic: Boolean = false,
    val verified: Boolean = true,
    val partial: Boolean = false
)

internal data class WorkspaceDeletePlan(
    val target: Path,
    val type: WorkspaceEntryType,
    val files: Int,
    val directories: Int,
    val bytes: Long,
    val entries: List<Path>,
    val identity: String
)

internal class WorkspaceFileException(
    val code: String,
    override val message: String,
    val nextStep: String? = null,
    val partial: Boolean = false,
    val details: Map<String, String> = emptyMap(),
    cause: Throwable? = null
) : IllegalStateException(message, cause)

/**
 * Deep workspace file module.
 *
 * Callers provide workspace-relative strings. This module owns lexical resolution, no-follow
 * validation, bounded traversal, atomic publication, copy verification, and mutation recovery.
 */
internal class WorkspaceFileSystem(
    private val pathResolver: WorkspacePathResolver,
    private val fileMover: ((Path, Path) -> Boolean)? = null,
    private val fileCopier: ((Path, Path) -> Unit)? = null,
    private val fileDeleter: ((Path) -> Unit)? = null
) {
    fun find(
        rawPath: String,
        pattern: String?,
        maxDepth: Int,
        kind: String,
        includeHidden: Boolean,
        limit: Int
    ): WorkspaceFindResult {
        val base = resolveExisting(rawPath, allowFinalSymbolicLink = true)
        val baseAttributes = readAttributes(base)
        val baseEntry = entry(base, baseAttributes)
        if (!baseAttributes.isDirectory) {
            return WorkspaceFindResult(
                base = baseEntry,
                entries = emptyList(),
                scannedEntries = 0,
                truncated = false,
                truncationReason = null
            )
        }

        val normalizedKind = kind.trim().lowercase()
        if (normalizedKind !in SUPPORTED_KINDS) {
            throw WorkspaceFileException(
                code = "invalid_kind",
                message = "kind must be any, file, directory, or symlink.",
                nextStep = "Use a supported kind filter."
            )
        }
        val matchers = pattern
            ?.takeIf { it.isNotBlank() }
            ?.let { globMatchers(it, "invalid_pattern", "pattern") }
            .orEmpty()
        val boundedDepth = maxDepth.coerceIn(0, MAX_TRAVERSAL_DEPTH)
        val boundedLimit = limit.coerceIn(1, MAX_FIND_RESULTS)
        val results = mutableListOf<WorkspaceFileEntry>()
        var scanned = 0
        var truncated = false
        var reason: String? = null

        Files.walkFileTree(
            base,
            emptySet<FileVisitOption>(),
            boundedDepth,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (dir == base) return FileVisitResult.CONTINUE
                    if (!includeHidden && isHiddenName(dir)) return FileVisitResult.SKIP_SUBTREE
                    return visitCandidate(dir, attrs)
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (file == base && attrs.isDirectory) return FileVisitResult.CONTINUE
                    if (!includeHidden && isHiddenName(file)) return FileVisitResult.CONTINUE
                    val result = visitCandidate(file, attrs)
                    if (result == FileVisitResult.CONTINUE && attrs.isDirectory) {
                        truncated = true
                        reason = "depth_limit"
                    }
                    return result
                }

                private fun visitCandidate(
                    path: Path,
                    attrs: BasicFileAttributes
                ): FileVisitResult {
                    scanned += 1
                    if (scanned > MAX_SCANNED_ENTRIES) {
                        truncated = true
                        reason = "scan_limit"
                        return FileVisitResult.TERMINATE
                    }
                    val relative = base.relativize(path)
                    val type = typeOf(attrs)
                    val kindMatches = normalizedKind == "any" || type.wireName == normalizedKind
                    val patternMatches = matchers.isEmpty() || matchers.any { it.matches(relative) }
                    if (kindMatches && patternMatches) {
                        if (results.size == boundedLimit) {
                            truncated = true
                            reason = "result_limit"
                            return FileVisitResult.TERMINATE
                        }
                        results += entry(path, attrs)
                    }
                    return FileVisitResult.CONTINUE
                }
            }
        )
        return WorkspaceFindResult(
            base = baseEntry,
            entries = results.sortedBy { it.path },
            scannedEntries = scanned.coerceAtMost(MAX_SCANNED_ENTRIES),
            truncated = truncated,
            truncationReason = reason
        )
    }

    fun scanFiles(
        rawPath: String,
        filePattern: String?,
        maxDepth: Int,
        maxFiles: Int
    ): WorkspaceFileScan {
        val target = resolveExisting(rawPath)
        val attrs = readAttributes(target)
        if (attrs.isRegularFile) {
            return WorkspaceFileScan(
                files = listOf(target),
                scannedEntries = 1,
                skippedSymbolicLinks = 0,
                truncated = false,
                truncationReason = null
            )
        }
        if (!attrs.isDirectory) {
            throw WorkspaceFileException(
                code = "not_file_or_directory",
                message = "Path is not a regular file or directory.",
                nextStep = "Choose a regular file or directory."
            )
        }
        val matchers = filePattern
            ?.takeIf { it.isNotBlank() }
            ?.let { globMatchers(it, "invalid_file_glob", "file_glob") }
            .orEmpty()
        val files = mutableListOf<Path>()
        var scanned = 0
        var skippedLinks = 0
        var truncated = false
        var reason: String? = null
        Files.walkFileTree(
            target,
            emptySet<FileVisitOption>(),
            maxDepth.coerceIn(0, MAX_TRAVERSAL_DEPTH),
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (dir != target && isHiddenName(dir)) return FileVisitResult.SKIP_SUBTREE
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isDirectory) {
                        if (file != target) {
                            truncated = true
                            reason = "depth_limit"
                        }
                        return FileVisitResult.CONTINUE
                    }
                    scanned += 1
                    if (scanned > MAX_SCANNED_ENTRIES) {
                        truncated = true
                        reason = "scan_limit"
                        return FileVisitResult.TERMINATE
                    }
                    if (attrs.isSymbolicLink) {
                        skippedLinks += 1
                        return FileVisitResult.CONTINUE
                    }
                    if (!attrs.isRegularFile || isHiddenName(file)) return FileVisitResult.CONTINUE
                    val relative = target.relativize(file)
                    if (matchers.isEmpty() || matchers.any { it.matches(relative) }) {
                        if (files.size == maxFiles.coerceIn(1, MAX_SCAN_FILES)) {
                            truncated = true
                            reason = "file_limit"
                            return FileVisitResult.TERMINATE
                        }
                        files.add(file)
                    }
                    return FileVisitResult.CONTINUE
                }
            }
        )
        return WorkspaceFileScan(
            files = files.sortedBy(::displayPath),
            scannedEntries = scanned.coerceAtMost(MAX_SCANNED_ENTRIES),
            skippedSymbolicLinks = skippedLinks,
            truncated = truncated,
            truncationReason = reason
        )
    }

    fun resolveReadableFile(rawPath: String): Path {
        val path = resolveExisting(rawPath)
        if (!readAttributes(path).isRegularFile) {
            throw WorkspaceFileException(
                code = "not_file",
                message = "Path is not a regular file.",
                nextStep = "Use find for directories."
            )
        }
        return path
    }

    fun resolveWritablePath(rawPath: String): Path = resolveForWrite(rawPath)

    fun displayPath(path: Path): String {
        val normalized = path.toAbsolutePath().normalize()
        val current = pathResolver.currentWorkspaceRoot().toPath().toAbsolutePath().normalize()
        val shared = pathResolver.sharedWorkspaceRoot().toPath().toAbsolutePath().normalize()
        val external = pathResolver.sharedExternalRoot()?.toPath()?.toAbsolutePath()?.normalize()
        return when {
            normalized == current -> "."
            normalized.startsWith(current) -> current.relativize(normalized).slashPath()
            normalized == shared -> "shared://"
            normalized.startsWith(shared) -> "shared://${shared.relativize(normalized).slashPath()}"
            external != null && normalized.startsWith(external) -> normalized.slashPath()
            else -> normalized.slashPath()
        }
    }

    fun isExternal(path: Path): Boolean = pathResolver.isSharedExternalPath(path.toFile())

    fun isProtectedRoot(path: Path): Boolean {
        val normalized = path.toAbsolutePath().normalize()
        val roots = listOfNotNull(
            pathResolver.currentWorkspaceRoot().toPath(),
            pathResolver.sharedWorkspaceRoot().toPath(),
            pathResolver.sharedExternalRoot()?.toPath()
        ).map { it.toAbsolutePath().normalize() }
        return roots.any { it == normalized }
    }

    fun exists(rawPath: String): Boolean {
        val path = resolveForWrite(rawPath)
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS)
    }

    fun size(path: Path): Long = Files.size(path)

    fun revision(path: Path): String = "sha256:${sha256(path)}"

    fun mutationIdentity(rawPath: String): String? {
        val path = resolveForWrite(rawPath)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        validateExistingComponents(path)
        return planTree(path).identity
    }

    fun requireMutationIdentity(rawPath: String): String =
        mutationIdentity(rawPath) ?: throw WorkspaceFileException(
            code = "path_not_found",
            message = "Path disappeared during mutation preflight: $rawPath",
            nextStep = "Inspect the path and retry."
        )

    fun readBytesBounded(path: Path, maxBytes: Long): ByteArray {
        if (Files.size(path) > maxBytes) {
            throw WorkspaceFileException(
                code = "file_too_large",
                message = "File exceeds the mutation byte limit.",
                nextStep = "Use overwrite for complete replacement or operate on a smaller file."
            )
        }
        val output = ByteArrayOutputStream()
        Files.newInputStream(path, StandardOpenOption.READ).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxBytes) {
                    throw WorkspaceFileException(
                        code = "file_too_large",
                        message = "File grew beyond the mutation byte limit while being read.",
                        nextStep = "Inspect the file and retry only after it is stable."
                    )
                }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    fun atomicWrite(
        path: Path,
        bytes: ByteArray,
        createParent: Boolean,
        requireAbsent: Boolean = false,
        expectedRevision: String? = null,
        expectAbsent: Boolean = false
    ): WorkspaceWriteSummary {
        val target = validateWritablePath(path, createParent)
        val parent = target.parent ?: throw WorkspaceFileException(
            code = "parent_not_found",
            message = "Target has no writable parent directory.",
            nextStep = "Choose a workspace file path."
        )
        val temp = Files.createTempFile(parent, ".palmclaw-write-", ".tmp")
        var published = false
        try {
            FileOutputStream(temp.toFile()).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            if (requireAbsent && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw WorkspaceFileException(
                    code = "target_exists",
                    message = "Create mode requires a new path.",
                    nextStep = "Choose another path or use overwrite."
                )
            }
            if (expectAbsent && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw WorkspaceFileException(
                    code = "file_changed",
                    message = "A file appeared at the target path before publication.",
                    nextStep = "Inspect the target and retry."
                )
            }
            if (expectedRevision != null) {
                val actual = if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    revision(target)
                } else {
                    null
                }
                if (actual != expectedRevision) {
                    throw WorkspaceFileException(
                        code = "file_changed",
                        message = "The file changed before the new content could be published.",
                        nextStep = "Read the file again and retry against the new revision."
                    )
                }
            }
            val atomic = try {
                publish(temp, target, replace = !(requireAbsent || expectAbsent))
            } catch (failure: FileAlreadyExistsException) {
                throw WorkspaceFileException(
                    code = if (requireAbsent) "target_exists" else "file_changed",
                    message = if (requireAbsent) {
                        "Create mode requires a new path."
                    } else {
                        "A file appeared at the target path before publication."
                    },
                    nextStep = if (requireAbsent) {
                        "Choose another path or use overwrite."
                    } else {
                        "Inspect the target and retry."
                    },
                    cause = failure
                )
            }
            published = true
            return WorkspaceWriteSummary(
                atomic = atomic,
                finalSizeBytes = Files.size(target),
                sha256 = sha256(target)
            )
        } finally {
            if (!published) Files.deleteIfExists(temp)
        }
    }

    fun createDirectory(rawPath: String, parents: Boolean, existOk: Boolean): Pair<Path, Boolean> {
        val target = resolveForWrite(rawPath)
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            val attrs = readAttributes(target)
            if (!attrs.isDirectory) {
                throw WorkspaceFileException(
                    code = "path_exists_as_file",
                    message = "Path already exists and is not a directory.",
                    nextStep = "Choose another directory path."
                )
            }
            if (!existOk) {
                throw WorkspaceFileException(
                    code = "directory_exists",
                    message = "Directory already exists.",
                    nextStep = "Set exist_ok=true or choose another path."
                )
            }
            return target to false
        }
        validateExistingComponents(target.parent ?: target)
        try {
            if (parents) Files.createDirectories(target) else Files.createDirectory(target)
        } catch (failure: FileAlreadyExistsException) {
            val attrs = readAttributes(target)
            if (!attrs.isDirectory) {
                throw WorkspaceFileException(
                    code = "path_exists_as_file",
                    message = "Path appeared during creation and is not a directory.",
                    nextStep = "Choose another directory path.",
                    cause = failure
                )
            }
            if (!existOk) {
                throw WorkspaceFileException(
                    code = "directory_exists",
                    message = "Directory appeared during creation.",
                    nextStep = "Set exist_ok=true or choose another path.",
                    cause = failure
                )
            }
            validateExistingComponents(target)
            return target to false
        }
        validateExistingComponents(target)
        return target to true
    }

    fun copy(
        rawSource: String,
        rawDestination: String,
        recursive: Boolean,
        overwrite: Boolean,
        createParent: Boolean,
        expectedSourceIdentity: String,
        expectedDestinationIdentity: String?
    ): WorkspaceMutationSummary {
        val source = resolveExisting(rawSource)
        val destination = resolveForWrite(rawDestination)
        val sourceAttrs = readAttributes(source)
        if (sourceAttrs.isDirectory && !recursive) {
            throw WorkspaceFileException(
                code = "recursive_required",
                message = "Directory copy requires recursive=true.",
                nextStep = "Inspect the directory, then set recursive=true."
            )
        }
        if (source == destination) {
            throw WorkspaceFileException(
                code = "same_path",
                message = "Source and destination are the same path.",
                nextStep = "Choose a different destination."
            )
        }
        if (isProtectedRoot(destination)) {
            throw WorkspaceFileException(
                code = "protected_path",
                message = "Workspace and shared-storage roots cannot be replaced.",
                nextStep = "Choose a child destination path."
            )
        }
        if (source.startsWith(destination)) {
            throw WorkspaceFileException(
                code = "destination_contains_source",
                message = "A source cannot replace one of its parent directories.",
                nextStep = "Choose a destination outside the source's parent chain."
            )
        }
        if (sourceAttrs.isDirectory && destination.startsWith(source)) {
            throw WorkspaceFileException(
                code = "destination_inside_source",
                message = "A directory cannot be copied inside itself.",
                nextStep = "Choose a destination outside the source directory."
            )
        }
        val destinationExists = Files.exists(destination, LinkOption.NOFOLLOW_LINKS)
        if (destinationExists != (expectedDestinationIdentity != null)) {
            throw WorkspaceFileException(
                code = "file_changed",
                message = "The destination changed after preflight.",
                nextStep = "Inspect the destination and retry."
            )
        }
        if (destinationExists && !overwrite) {
            throw WorkspaceFileException(
                code = "target_exists",
                message = "Destination already exists.",
                nextStep = "Choose another destination or explicitly allow overwrite."
            )
        }
        val plan = planTree(source)
        if (plan.identity != expectedSourceIdentity) {
            throw WorkspaceFileException(
                code = "file_changed",
                message = "The source changed after preflight.",
                nextStep = "Inspect the source and retry."
            )
        }
        val destinationPlan = destination.takeIf { destinationExists }?.let(::planTree)
        if (destinationPlan != null && destinationPlan.identity != expectedDestinationIdentity) {
            throw WorkspaceFileException(
                code = "file_changed",
                message = "The destination changed after preflight.",
                nextStep = "Inspect the destination and retry."
            )
        }
        validateWritablePath(destination, createParent)
        val parent = destination.parent ?: throw WorkspaceFileException(
            code = "parent_not_found",
            message = "Destination has no parent directory.",
            nextStep = "Choose another destination."
        )
        val stage = parent.resolve(".palmclaw-copy-${UUID.randomUUID()}")
        try {
            if (sourceAttrs.isDirectory) {
                copyDirectoryToStage(source, stage, plan)
            } else {
                copyOneFile(source, stage)
            }
            verifyCopy(source, stage, plan)
            val stagedPlan = planTree(stage)
            val stagedContentIdentity = treeContentIdentity(stage, stagedPlan.entries)
            val atomic = replaceWithStage(
                stage,
                destination,
                destinationPlan,
                stagedContentIdentity
            )
            return WorkspaceMutationSummary(
                source = displayPath(source),
                destination = displayPath(destination),
                type = typeOf(sourceAttrs),
                filesProcessed = plan.files,
                directoriesProcessed = plan.directories,
                bytesProcessed = plan.bytes,
                overwritten = destinationExists,
                atomic = atomic,
                verified = true
            )
        } catch (failure: WorkspaceFileException) {
            deleteQuietly(stage)
            throw failure
        } catch (failure: FileAlreadyExistsException) {
            deleteQuietly(stage)
            throw WorkspaceFileException(
                code = "target_exists",
                message = "Destination appeared before the copy could be published.",
                nextStep = "Inspect the destination and retry only if replacement is intended.",
                cause = failure
            )
        } catch (failure: Throwable) {
            deleteQuietly(stage)
            throw WorkspaceFileException(
                code = "copy_failed",
                message = failure.message ?: "Copy failed.",
                nextStep = "Check source, destination, free space, and permissions.",
                cause = failure
            )
        }
    }

    fun move(
        rawSource: String,
        rawDestination: String,
        overwrite: Boolean,
        createParent: Boolean,
        expectedSourceIdentity: String,
        expectedDestinationIdentity: String?
    ): WorkspaceMutationSummary {
        val source = resolveExisting(rawSource)
        val destination = resolveForWrite(rawDestination)
        val sourceAttrs = readAttributes(source)
        if (source == destination) {
            throw WorkspaceFileException(
                code = "same_path",
                message = "Source and destination are the same path.",
                nextStep = "Choose a different destination."
            )
        }
        if (isProtectedRoot(source) || isProtectedRoot(destination)) {
            throw WorkspaceFileException(
                code = "protected_path",
                message = "Workspace and shared-storage roots cannot be moved or replaced.",
                nextStep = "Choose child source and destination paths."
            )
        }
        if (source.startsWith(destination)) {
            throw WorkspaceFileException(
                code = "destination_contains_source",
                message = "A source cannot replace one of its parent directories.",
                nextStep = "Choose a destination outside the source's parent chain."
            )
        }
        if (sourceAttrs.isDirectory && destination.startsWith(source)) {
            throw WorkspaceFileException(
                code = "destination_inside_source",
                message = "A directory cannot be moved inside itself.",
                nextStep = "Choose a destination outside the source directory."
            )
        }
        val destinationExists = Files.exists(destination, LinkOption.NOFOLLOW_LINKS)
        if (destinationExists != (expectedDestinationIdentity != null)) {
            throw WorkspaceFileException(
                code = "file_changed",
                message = "The destination changed after preflight.",
                nextStep = "Inspect the destination and retry."
            )
        }
        if (destinationExists && !overwrite) {
            throw WorkspaceFileException(
                code = "target_exists",
                message = "Destination already exists.",
                nextStep = "Choose another destination or explicitly allow overwrite."
            )
        }
        val plan = planTree(source)
        if (plan.identity != expectedSourceIdentity) {
            throw WorkspaceFileException(
                code = "file_changed",
                message = "The source changed after preflight.",
                nextStep = "Inspect the source and retry."
            )
        }
        val destinationPlan = destination.takeIf { destinationExists }?.let(::planTree)
        if (destinationPlan != null && destinationPlan.identity != expectedDestinationIdentity) {
            throw WorkspaceFileException(
                code = "file_changed",
                message = "The destination changed after preflight.",
                nextStep = "Inspect the destination and retry."
            )
        }
        validateWritablePath(destination, createParent)
        val backup = if (destinationExists) {
            destination.parent.resolve(".palmclaw-move-backup-${UUID.randomUUID()}")
        } else {
            null
        }
        if (backup != null) {
            try {
                secureDestinationBackup(destination, backup, destinationPlan!!)
            } catch (failure: WorkspaceFileException) {
                throw failure
            } catch (failure: Throwable) {
                throw WorkspaceFileException(
                    code = "destination_backup_failed",
                    message = "The existing destination could not be moved aside safely.",
                    nextStep = "Check destination permissions and retry.",
                    cause = failure
                )
            }
        }
        val movedDirectly = try {
            moveDirect(source, destination)
        } catch (_: Throwable) {
            false
        }
        if (movedDirectly) {
            backup?.let { deleteBackupOrThrow(it, "move", destination) }
            return WorkspaceMutationSummary(
                source = displayPath(source),
                destination = displayPath(destination),
                type = typeOf(sourceAttrs),
                filesProcessed = plan.files,
                directoriesProcessed = plan.directories,
                bytesProcessed = plan.bytes,
                overwritten = destinationExists,
                atomic = false,
                verified = true
            )
        }
        val copied = try {
            copy(
                rawSource = rawSource,
                rawDestination = rawDestination,
                recursive = sourceAttrs.isDirectory,
                overwrite = false,
                createParent = createParent,
                expectedSourceIdentity = plan.identity,
                expectedDestinationIdentity = null
            )
        } catch (failure: Throwable) {
            val restored = backup
                ?.takeIf { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
                ?.let { saved ->
                    runCatching {
                        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                            false
                        } else {
                            relocateExisting(saved, destination)
                            true
                        }
                    }.getOrDefault(false)
                } == true
            if (failure is WorkspaceFileException && (backup == null || restored)) {
                throw failure
            }
            throw WorkspaceFileException(
                code = if (backup != null && !restored) "move_recovery_required" else "move_failed",
                message = if (backup != null && !restored) {
                    "Move failed before publication; the original destination remains in a backup path."
                } else {
                    failure.message ?: "Move failed."
                },
                nextStep = if (backup != null && !restored) {
                    "Review the source and backup paths before retrying."
                } else {
                    "Check source, destination, free space, and permissions."
                },
                details = buildMap {
                    put("source", displayPath(source))
                    put("destination", displayPath(destination))
                    backup?.takeIf { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }?.let {
                        put("backup_path", displayPath(it))
                    }
                },
                cause = failure
            )
        }
        val sourceQuarantine = source.parent.resolve(".palmclaw-move-source-${UUID.randomUUID()}")
        try {
            Files.move(source, sourceQuarantine)
        } catch (failure: Throwable) {
            val destinationRolledBack = rollbackPublishedDestination(destination, backup)
            throw WorkspaceFileException(
                code = "move_source_commit_failed",
                message = "Destination was verified, but the source could not be secured for removal.",
                nextStep = "Inspect the source and destination before retrying.",
                partial = !destinationRolledBack,
                details = buildMap {
                    put("source", displayPath(source))
                    put("destination", displayPath(destination))
                    backup
                        ?.takeIf { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
                        ?.let { put("backup_path", displayPath(it)) }
                },
                cause = failure
            )
        }
        val quarantinedPlan = try {
            planTree(sourceQuarantine)
        } catch (failure: Throwable) {
            val sourceRestored = restoreQuarantinedSource(sourceQuarantine, source)
            val destinationRolledBack = rollbackPublishedDestination(destination, backup)
            throw WorkspaceFileException(
                code = "move_source_changed",
                message = "Source changed before the move could be committed.",
                nextStep = "Inspect all reported paths before retrying.",
                partial = !(sourceRestored && destinationRolledBack),
                details = moveRecoveryDetails(source, destination, sourceQuarantine, backup),
                cause = failure
            )
        }
        try {
            verifyCopy(sourceQuarantine, destination, quarantinedPlan)
        } catch (failure: Throwable) {
            val sourceRestored = restoreQuarantinedSource(sourceQuarantine, source)
            val destinationRolledBack = rollbackPublishedDestination(destination, backup)
            throw WorkspaceFileException(
                code = "move_source_changed",
                message = "Source changed after destination verification.",
                nextStep = "Inspect all reported paths before retrying.",
                partial = !(sourceRestored && destinationRolledBack),
                details = moveRecoveryDetails(source, destination, sourceQuarantine, backup),
                cause = failure
            )
        }
        try {
            deletePlanned(quarantinedPlan)
        } catch (failure: Throwable) {
            throw WorkspaceFileException(
                code = "move_source_cleanup_failed",
                message = "Destination was verified, but the secured source could not be removed.",
                nextStep = "Review the destination and source backup before removing anything.",
                partial = true,
                details = moveRecoveryDetails(source, destination, sourceQuarantine, backup),
                cause = failure
            )
        }
        backup?.let { deleteBackupOrThrow(it, "move", destination) }
        return copied.copy(
            overwritten = destinationExists,
            atomic = false
        )
    }

    fun planDelete(rawPath: String): WorkspaceDeletePlan {
        val target = resolveExisting(rawPath)
        if (isProtectedRoot(target)) {
            throw WorkspaceFileException(
                code = "protected_path",
                message = "Workspace and shared-storage roots cannot be deleted.",
                nextStep = "Choose a child path."
            )
        }
        val plan = planTree(target)
        return WorkspaceDeletePlan(
            target = target,
            type = plan.type,
            files = plan.files,
            directories = plan.directories,
            bytes = plan.bytes,
            entries = plan.entries,
            identity = plan.identity
        )
    }

    fun delete(plan: WorkspaceDeletePlan, recursive: Boolean): WorkspaceMutationSummary {
        if (plan.type == WorkspaceEntryType.DIRECTORY && plan.entries.size > 1 && !recursive) {
            throw WorkspaceFileException(
                code = "directory_not_empty",
                message = "Directory is not empty.",
                nextStep = "Inspect it, then set recursive=true only if all contents should be deleted."
            )
        }
        if (!Files.exists(plan.target, LinkOption.NOFOLLOW_LINKS)) {
            throw WorkspaceFileException(
                code = "path_not_found",
                message = "Delete target no longer exists.",
                nextStep = "Inspect the path again."
            )
        }
        val currentPlan = planTree(plan.target)
        if (currentPlan.identity != plan.identity) {
            throw WorkspaceFileException(
                code = "file_changed",
                message = "Delete target changed after preflight.",
                nextStep = "Inspect the target again before deleting it."
            )
        }
        val deletionOrder = if (plan.type == WorkspaceEntryType.DIRECTORY && recursive) {
            plan.entries.asReversed()
        } else {
            listOf(plan.target)
        }
        var deletedEntries = 0
        try {
            for (path in deletionOrder) {
                deleteOne(path)
                deletedEntries += 1
            }
        } catch (failure: DirectoryNotEmptyException) {
            throw WorkspaceFileException(
                code = if (deletedEntries > 0) "delete_partial" else "directory_not_empty",
                message = if (deletedEntries > 0) {
                    "Deletion stopped after removing $deletedEntries entries because the directory changed."
                } else {
                    "Directory is not empty."
                },
                nextStep = "Inspect the target again before deciding whether to retry.",
                partial = deletedEntries > 0,
                details = mapOf(
                    "path" to displayPath(plan.target),
                    "deleted_entries" to deletedEntries.toString(),
                    "planned_entries" to deletionOrder.size.toString()
                ),
                cause = failure
            )
        } catch (failure: Throwable) {
            throw WorkspaceFileException(
                code = if (deletedEntries > 0) "delete_partial" else "delete_failed",
                message = if (deletedEntries > 0) {
                    "Deletion stopped after removing $deletedEntries entries."
                } else {
                    failure.message ?: "Deletion failed."
                },
                nextStep = "Inspect the target again before deciding whether to retry.",
                partial = deletedEntries > 0,
                details = mapOf(
                    "path" to displayPath(plan.target),
                    "deleted_entries" to deletedEntries.toString(),
                    "planned_entries" to deletionOrder.size.toString()
                ),
                cause = failure
            )
        }
        return WorkspaceMutationSummary(
            type = plan.type,
            filesProcessed = plan.files,
            directoriesProcessed = plan.directories,
            bytesProcessed = plan.bytes,
            verified = !Files.exists(plan.target, LinkOption.NOFOLLOW_LINKS)
        )
    }

    private fun resolveExisting(
        rawPath: String,
        allowFinalSymbolicLink: Boolean = false
    ): Path {
        val path = resolveForWrite(rawPath)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw WorkspaceFileException(
                code = "path_not_found",
                message = "Path does not exist: $rawPath",
                nextStep = "Check the path and retry."
            )
        }
        validateExistingComponents(path, allowFinalSymbolicLink)
        return path
    }

    private fun resolveForWrite(rawPath: String): Path {
        val file = try {
            pathResolver.resolveLexical(rawPath)
        } catch (failure: SecurityException) {
            val allFiles = failure.message.orEmpty().contains("all files access", ignoreCase = true)
            throw WorkspaceFileException(
                code = if (allFiles) "all_files_access_required" else "path_outside_workspace",
                message = failure.message ?: "Path is outside the workspace.",
                nextStep = if (allFiles) {
                    "Grant All files access in Android settings and retry."
                } else {
                    "Use a path in the current workspace or shared://."
                },
                cause = failure
            )
        }
        return file.toPath().toAbsolutePath().normalize()
    }

    private fun validateWritablePath(path: Path, createParent: Boolean): Path {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            validateExistingComponents(path)
        } else {
            val parent = path.parent ?: throw WorkspaceFileException(
                code = "parent_not_found",
                message = "Target has no parent directory.",
                nextStep = "Choose another target path."
            )
            if (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
                if (!createParent) {
                    throw WorkspaceFileException(
                        code = "parent_not_found",
                        message = "Destination parent directory does not exist.",
                        nextStep = "Set create_parent=true or create it with mkdir."
                    )
                }
                validateExistingComponents(nearestExistingParent(parent))
                Files.createDirectories(parent)
            }
            validateExistingComponents(parent)
        }
        return path
    }

    private fun validateExistingComponents(
        path: Path,
        allowFinalSymbolicLink: Boolean = false
    ) {
        val normalized = path.toAbsolutePath().normalize()
        val root = allowedRoot(normalized) ?: throw WorkspaceFileException(
            code = "path_outside_workspace",
            message = "Path is outside the workspace.",
            nextStep = "Use a workspace or shared:// path."
        )
        var current = root
        for (part in root.relativize(normalized)) {
            current = current.resolve(part)
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) break
            val attrs = readAttributes(current)
            if (attrs.isSymbolicLink && !(allowFinalSymbolicLink && current == normalized)) {
                throw WorkspaceFileException(
                    code = "symbolic_link_not_allowed",
                    message = "Workspace file operations do not follow symbolic links.",
                    nextStep = "Use the real workspace path instead of a symbolic link."
                )
            }
        }
    }

    private fun nearestExistingParent(path: Path): Path {
        var current: Path? = path
        while (current != null && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            current = current.parent
        }
        return current ?: throw WorkspaceFileException(
            code = "parent_not_found",
            message = "No existing workspace parent was found.",
            nextStep = "Choose a valid workspace path."
        )
    }

    private fun allowedRoot(path: Path): Path? {
        val roots = listOfNotNull(
            pathResolver.currentWorkspaceRoot().toPath(),
            pathResolver.sharedWorkspaceRoot().toPath(),
            pathResolver.sharedExternalRoot()?.toPath()
        ).map { it.toAbsolutePath().normalize() }
        return roots.filter { path.startsWith(it) }.maxByOrNull { it.nameCount }
    }

    private fun planTree(root: Path): TreePlan {
        val rootAttrs = readAttributes(root)
        if (rootAttrs.isSymbolicLink) {
            throw WorkspaceFileException(
                code = "symbolic_link_not_allowed",
                message = "Workspace file operations do not follow symbolic links.",
                nextStep = "Use the real workspace path."
            )
        }
        if (!rootAttrs.isDirectory && !rootAttrs.isRegularFile) {
            throw WorkspaceFileException(
                code = "unsupported_file_type",
                message = "Mutation target is not a regular file or directory.",
                nextStep = "Choose a regular file or directory."
            )
        }
        if (!Files.isReadable(root)) {
            throw WorkspaceFileException(
                code = "permission_denied",
                message = "Mutation target is not readable.",
                nextStep = "Grant storage access or choose another path."
            )
        }
        if (!rootAttrs.isDirectory) {
            val bytes = rootAttrs.size()
            enforceMutationLimits(1, bytes)
            val entries = listOf(root)
            return TreePlan(
                root = root,
                type = typeOf(rootAttrs),
                files = 1,
                directories = 0,
                bytes = bytes,
                entries = entries,
                identity = treeIdentity(root, entries)
            )
        }
        val entries = mutableListOf<Path>()
        var files = 0
        var directories = 0
        var bytes = 0L
        Files.walkFileTree(
            root,
            emptySet<FileVisitOption>(),
            MAX_MUTATION_DEPTH,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!Files.isReadable(dir)) {
                        throw WorkspaceFileException(
                            code = "permission_denied",
                            message = "Recursive mutation encountered an unreadable directory.",
                            nextStep = "Grant storage access or choose another path."
                        )
                    }
                    directories += 1
                    entries.add(dir)
                    enforceMutationLimits(entries.size, bytes)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isDirectory) {
                        throw WorkspaceFileException(
                            code = "operation_limit_exceeded",
                            message = "Mutation exceeds the maximum directory depth.",
                            nextStep = "Split the operation into shallower directory trees."
                        )
                    }
                    if (attrs.isSymbolicLink) {
                        throw WorkspaceFileException(
                            code = "symbolic_link_not_allowed",
                            message = "Recursive mutations do not follow symbolic links.",
                            nextStep = "Remove or replace the symbolic link before retrying."
                        )
                    }
                    if (!attrs.isRegularFile) {
                        throw WorkspaceFileException(
                            code = "unsupported_file_type",
                            message = "Recursive mutation encountered an unsupported file type.",
                            nextStep = "Operate only on regular files and directories."
                        )
                    }
                    if (!Files.isReadable(file)) {
                        throw WorkspaceFileException(
                            code = "permission_denied",
                            message = "Recursive mutation encountered an unreadable file.",
                            nextStep = "Grant storage access or choose another path."
                        )
                    }
                    files += 1
                    bytes += attrs.size()
                    entries.add(file)
                    enforceMutationLimits(entries.size, bytes)
                    return FileVisitResult.CONTINUE
                }
            }
        )
        return TreePlan(
            root = root,
            type = WorkspaceEntryType.DIRECTORY,
            files = files,
            directories = directories,
            bytes = bytes,
            entries = entries,
            identity = treeIdentity(root, entries)
        )
    }

    private fun enforceMutationLimits(entries: Int, bytes: Long) {
        if (entries > MAX_MUTATION_ENTRIES) {
            throw WorkspaceFileException(
                code = "operation_limit_exceeded",
                message = "Mutation exceeds the maximum entry count.",
                nextStep = "Split the operation into smaller directories."
            )
        }
        if (bytes > MAX_MUTATION_BYTES) {
            throw WorkspaceFileException(
                code = "operation_limit_exceeded",
                message = "Mutation exceeds the maximum total byte count.",
                nextStep = "Split the operation into smaller parts."
            )
        }
    }

    private fun copyDirectoryToStage(source: Path, stage: Path, plan: TreePlan) {
        Files.createDirectory(stage)
        for (entry in plan.entries.drop(1)) {
            validateExistingComponents(entry)
            val relative = source.relativize(entry)
            val target = stage.resolve(relative)
            val attrs = readAttributes(entry)
            if (attrs.isDirectory) {
                Files.createDirectory(target)
            } else {
                copyOneFile(entry, target)
            }
        }
    }

    private fun copyOneFile(source: Path, destination: Path) {
        validateExistingComponents(source)
        if (!readAttributes(source).isRegularFile) {
            throw WorkspaceFileException(
                code = "source_changed",
                message = "Copy source changed after preflight.",
                nextStep = "Inspect the source and retry."
            )
        }
        destination.parent?.let { Files.createDirectories(it) }
        val hook = fileCopier
        if (hook != null) {
            hook(source, destination)
        } else {
            try {
                Files.copy(
                    source,
                    destination,
                    StandardCopyOption.COPY_ATTRIBUTES,
                    StandardCopyOption.REPLACE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS
                )
            } catch (_: UnsupportedOperationException) {
                Files.copy(
                    source,
                    destination,
                    StandardCopyOption.REPLACE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS
                )
            }
        }
    }

    private fun verifyCopy(source: Path, destination: Path, plan: TreePlan) {
        if (plan.type != WorkspaceEntryType.DIRECTORY) {
            if (sha256(source) != sha256(destination)) {
                throw WorkspaceFileException("verification_failed", "Copied file verification failed.")
            }
            return
        }
        for (entry in plan.entries) {
            validateExistingComponents(entry)
            val relative = source.relativize(entry)
            val copied = destination.resolve(relative)
            val attrs = readAttributes(entry)
            val copiedAttrs = readAttributes(copied)
            if (typeOf(attrs) != typeOf(copiedAttrs)) {
                throw WorkspaceFileException("verification_failed", "Copied tree type verification failed.")
            }
            if (attrs.isRegularFile && (attrs.size() != copiedAttrs.size() || sha256(entry) != sha256(copied))) {
                throw WorkspaceFileException("verification_failed", "Copied tree content verification failed.")
            }
        }
    }

    private fun replaceWithStage(
        stage: Path,
        destination: Path,
        expectedDestination: TreePlan?,
        stagedContentIdentity: String
    ): Boolean {
        val destinationExists = Files.exists(destination, LinkOption.NOFOLLOW_LINKS)
        if (expectedDestination == null) {
            if (destinationExists) {
                throw FileAlreadyExistsException(destination.toString())
            }
            val atomic = publish(stage, destination, replace = false)
            verifyPublishedIdentity(destination, stagedContentIdentity, backup = null)
            return atomic
        }
        if (!destinationExists) {
            throw WorkspaceFileException(
                code = "file_changed",
                message = "The destination changed before replacement.",
                nextStep = "Inspect the destination and retry."
            )
        }
        val backup = destination.parent.resolve(".palmclaw-backup-${UUID.randomUUID()}")
        secureDestinationBackup(destination, backup, expectedDestination)
        val atomic = try {
            publish(stage, destination, replace = false).also {
                verifyPublishedIdentity(destination, stagedContentIdentity, backup)
            }
        } catch (failure: Throwable) {
            val restored = if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                false
            } else {
                runCatching {
                    relocateExisting(backup, destination)
                    true
                }.getOrDefault(false)
            }
            if (!restored) {
                throw WorkspaceFileException(
                    code = "copy_recovery_required",
                    message = "Copy publication or verification failed and the original destination remains in a backup path.",
                    nextStep = "Review the destination and backup paths before retrying.",
                    partial = true,
                    details = mapOf(
                        "destination" to displayPath(destination),
                        "backup_path" to displayPath(backup)
                    ),
                    cause = failure
                )
            }
            throw failure
        }
        deleteBackupOrThrow(backup, "copy", destination)
        return atomic
    }

    private fun verifyPublishedIdentity(
        destination: Path,
        stagedContentIdentity: String,
        backup: Path?
    ) {
        val published = planTree(destination)
        if (treeContentIdentity(destination, published.entries) == stagedContentIdentity) return
        throw WorkspaceFileException(
            code = if (backup == null) "verification_failed" else "copy_recovery_required",
            message = "Published destination verification failed.",
            nextStep = "Inspect the reported paths before retrying.",
            partial = true,
            details = buildMap {
                put("destination", displayPath(destination))
                backup
                    ?.takeIf { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
                    ?.let { put("backup_path", displayPath(it)) }
            }
        )
    }

    private fun secureDestinationBackup(
        destination: Path,
        backup: Path,
        expectedDestination: TreePlan
    ) {
        val current = planTree(destination)
        if (current.identity != expectedDestination.identity) {
            throw WorkspaceFileException(
                code = "file_changed",
                message = "The destination changed before replacement.",
                nextStep = "Inspect the destination and retry."
            )
        }
        relocateExisting(destination, backup)
        val secured = try {
            planTree(backup)
        } catch (failure: Throwable) {
            runCatching { relocateExisting(backup, destination) }
            throw failure
        }
        if (secured.identity != expectedDestination.identity) {
            val restored = runCatching {
                relocateExisting(backup, destination)
                true
            }.getOrDefault(false)
            throw WorkspaceFileException(
                code = if (restored) "file_changed" else "copy_recovery_required",
                message = if (restored) {
                    "The destination changed while replacement was being prepared."
                } else {
                    "The destination changed and remains in a backup path."
                },
                nextStep = "Inspect the destination and backup paths before retrying.",
                partial = !restored,
                details = buildMap {
                    put("destination", displayPath(destination))
                    if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
                        put("backup_path", displayPath(backup))
                    }
                }
            )
        }
    }

    private fun publish(stage: Path, destination: Path, replace: Boolean): Boolean {
        if (!replace) {
            Files.move(stage, destination)
            return false
        }
        return try {
            Files.move(
                stage,
                destination,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
            true
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(stage, destination, StandardCopyOption.REPLACE_EXISTING)
            false
        }
    }

    private fun moveDirect(source: Path, destination: Path): Boolean {
        val hook = fileMover
        if (hook != null) return hook(source, destination)
        return try {
            val destinationParent = destination.parent ?: return false
            if (Files.getFileStore(source) != Files.getFileStore(destinationParent)) return false
            Files.move(source, destination)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Moves an existing path and either completes or throws. Unlike [moveDirect], this helper may
     * use a non-atomic move because it is used only for local backup and restoration steps.
     */
    private fun relocateExisting(source: Path, destination: Path) {
        val hook = fileMover
        if (hook != null) {
            if (!hook(source, destination)) {
                throw IllegalStateException("Injected move failed.")
            }
            return
        }
        Files.move(source, destination)
    }

    private fun deletePlanned(plan: TreePlan) {
        for (path in plan.entries.asReversed()) {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                deleteOne(path)
            }
        }
    }

    private fun deleteOne(path: Path) {
        val hook = fileDeleter
        if (hook != null) {
            hook(path)
        } else {
            Files.delete(path)
        }
    }

    private fun deleteQuietly(path: Path): Boolean {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return true
        return runCatching {
            if (readAttributes(path).isSymbolicLink) {
                Files.deleteIfExists(path)
            } else {
                val plan = planTree(path)
                deletePlanned(plan)
            }
            !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
        }.getOrDefault(false)
    }

    private fun rollbackPublishedDestination(destination: Path, backup: Path?): Boolean {
        if (!deleteQuietly(destination)) return false
        if (backup == null) return true
        if (!Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) return false
        return runCatching {
            relocateExisting(backup, destination)
            true
        }.getOrDefault(false)
    }

    private fun restoreQuarantinedSource(quarantine: Path, source: Path): Boolean {
        if (!Files.exists(quarantine, LinkOption.NOFOLLOW_LINKS)) return false
        if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) return false
        return runCatching {
            Files.move(quarantine, source)
            true
        }.getOrDefault(false)
    }

    private fun moveRecoveryDetails(
        source: Path,
        destination: Path,
        sourceQuarantine: Path,
        destinationBackup: Path?
    ): Map<String, String> = buildMap {
        put("source", displayPath(source))
        put("destination", displayPath(destination))
        if (Files.exists(sourceQuarantine, LinkOption.NOFOLLOW_LINKS)) {
            put("source_backup_path", displayPath(sourceQuarantine))
        }
        destinationBackup
            ?.takeIf { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
            ?.let { put("backup_path", displayPath(it)) }
    }

    private fun deleteBackupOrThrow(backup: Path, operation: String, destination: Path) {
        try {
            val plan = planTree(backup)
            deletePlanned(plan)
        } catch (failure: Throwable) {
            throw WorkspaceFileException(
                code = "backup_cleanup_failed",
                message = "The $operation completed, but its destination backup could not be removed.",
                nextStep = "Verify the destination, then remove the reported backup path.",
                partial = true,
                details = mapOf(
                    "destination" to displayPath(destination),
                    "backup_path" to displayPath(backup)
                ),
                cause = failure
            )
        }
    }

    private fun entry(path: Path, attrs: BasicFileAttributes): WorkspaceFileEntry {
        val symbolicLink = attrs.isSymbolicLink
        return WorkspaceFileEntry(
            path = displayPath(path),
            name = path.fileName?.toString().orEmpty(),
            type = typeOf(attrs),
            sizeBytes = attrs.size().takeIf { attrs.isRegularFile },
            modifiedAtMs = attrs.lastModifiedTime().toMillis(),
            readable = !symbolicLink && Files.isReadable(path),
            writable = !symbolicLink && Files.isWritable(path),
            hidden = isHiddenName(path)
        )
    }

    private fun readAttributes(path: Path): BasicFileAttributes {
        return try {
            Files.readAttributes(
                path,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS
            )
        } catch (failure: Throwable) {
            throw WorkspaceFileException(
                code = "io_error",
                message = failure.message ?: "Failed to read file attributes.",
                nextStep = "Check the path and permissions, then retry.",
                cause = failure
            )
        }
    }

    private fun globMatchers(
        pattern: String,
        errorCode: String,
        parameterName: String
    ): List<PathMatcher> {
        val patterns = buildList {
            add(pattern)
            if (pattern.startsWith("**/") && pattern.length > 3) {
                add(pattern.removePrefix("**/"))
            }
        }
        return patterns.map { candidate ->
            runCatching {
                FileSystems.getDefault().getPathMatcher("glob:$candidate")
            }.getOrElse { failure ->
                throw WorkspaceFileException(
                    code = errorCode,
                    message = "Invalid $parameterName glob pattern.",
                    nextStep = "Fix $parameterName syntax and retry.",
                    cause = failure
                )
            }
        }
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path, StandardOpenOption.READ).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private fun treeIdentity(root: Path, entries: List<Path>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        entries.sortedBy { root.relativize(it).slashPath() }.forEach { path ->
            val attrs = readAttributes(path)
            val record = listOf(
                root.relativize(path).slashPath(),
                typeOf(attrs).wireName,
                attrs.size().toString(),
                attrs.lastModifiedTime().toMillis().toString(),
                attrs.fileKey()?.toString().orEmpty()
            ).joinToString("\u0000")
            digest.update(record.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
        }
        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private fun treeContentIdentity(root: Path, entries: List<Path>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        entries.sortedBy { root.relativize(it).slashPath() }.forEach { path ->
            val attrs = readAttributes(path)
            val record = listOf(
                root.relativize(path).slashPath(),
                typeOf(attrs).wireName,
                attrs.size().toString(),
                if (attrs.isRegularFile) sha256(path) else ""
            ).joinToString("\u0000")
            digest.update(record.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
        }
        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private fun typeOf(attrs: BasicFileAttributes): WorkspaceEntryType = when {
        attrs.isSymbolicLink -> WorkspaceEntryType.SYMBOLIC_LINK
        attrs.isRegularFile -> WorkspaceEntryType.FILE
        attrs.isDirectory -> WorkspaceEntryType.DIRECTORY
        else -> WorkspaceEntryType.OTHER
    }

    private fun isHiddenName(path: Path): Boolean =
        path.fileName?.toString()?.startsWith(".") == true

    private fun Path.slashPath(): String = toString().replace('\\', '/')

    private data class TreePlan(
        val root: Path,
        val type: WorkspaceEntryType,
        val files: Int,
        val directories: Int,
        val bytes: Long,
        val entries: List<Path>,
        val identity: String
    )

    private companion object {
        val SUPPORTED_KINDS = setOf("any", "file", "directory", "symlink")
        const val MAX_TRAVERSAL_DEPTH = 20
        const val MAX_SCANNED_ENTRIES = 20_000
        const val MAX_FIND_RESULTS = 2_000
        const val MAX_SCAN_FILES = 5_000
        const val MAX_MUTATION_DEPTH = 20
        const val MAX_MUTATION_ENTRIES = 10_000
        const val MAX_MUTATION_BYTES = 512L * 1024L * 1024L
    }
}
