package com.palmclaw.attachments

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.palmclaw.bus.MessageAttachment
import com.palmclaw.bus.MessageAttachmentKind
import com.palmclaw.bus.MessageAttachmentSource
import com.palmclaw.bus.MessageAttachmentTransferState
import com.palmclaw.bus.deriveMessageAttachmentLabel
import com.palmclaw.bus.inferMessageAttachmentKind
import com.palmclaw.bus.inferMessageAttachmentMimeType
import com.palmclaw.bus.isRemoteAttachmentReference
import com.palmclaw.bus.normalizeMessageAttachmentReference
import com.palmclaw.workspace.SessionWorkspaceManager
import com.palmclaw.workspace.SessionWorkspaceSnapshot
import com.palmclaw.workspace.WorkspacePathResolver
import java.io.File
import java.io.FileOutputStream
import java.net.URLConnection
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

class AttachmentTransferService(
    context: Context,
    private val workspaceManager: SessionWorkspaceManager,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val maxRemoteAttachmentBytes: Long = DEFAULT_MAX_REMOTE_ATTACHMENT_BYTES
) {
    private val appContext = context.applicationContext

    suspend fun importComposerDrafts(
        sessionId: String,
        sessionTitle: String,
        uriStrings: List<String>
    ): List<MessageAttachment> = withContext(Dispatchers.IO) {
        val snapshot = workspaceManager.ensureWorkspace(sessionId, sessionTitle)
        val destinationDir = File(snapshot.artifactsDir, "outgoing/user/${UUID.randomUUID()}").apply { mkdirs() }
        val imported = uriStrings.mapNotNull { raw ->
            importSingleAttachment(
                destinationDir = destinationDir,
                rawReference = raw,
                source = MessageAttachmentSource.Local,
                remoteBacked = false,
                metadata = emptyMap()
            )
        }
        if (imported.size != uriStrings.size) {
            throw IllegalStateException("Failed to import ${uriStrings.size - imported.size} attachment(s).")
        }
        imported
    }

    suspend fun prepareAssistantAttachments(
        sessionId: String,
        sessionTitle: String,
        messageId: Long,
        attachments: List<MessageAttachment>
    ): List<MessageAttachment> = withContext(Dispatchers.IO) {
        val snapshot = workspaceManager.ensureWorkspace(sessionId, sessionTitle)
        val destinationDir = File(snapshot.artifactsDir, "outgoing/assistant/$messageId").apply { mkdirs() }
        attachments.mapNotNull { attachment ->
            val reference = attachment.localWorkspacePath?.takeIf { it.isNotBlank() } ?: attachment.reference
            val workspaceFile = resolveWorkspaceAttachmentFile(reference, snapshot)
            if (workspaceFile != null) {
                attachment.copy(
                    reference = workspaceFile.absolutePath,
                    localWorkspacePath = workspaceFile.absolutePath,
                    transferState = MessageAttachmentTransferState.Ready
                )
            } else if (isRemoteAttachmentReference(reference)) {
                val locator = AttachmentRemoteLocatorJsonCodec.decode(
                    attachment.metadata[AttachmentRecordRepository.KEY_REMOTE_LOCATOR]
                )
                downloadRemoteAttachment(destinationDir, attachment.copy(reference = reference), locator)
            } else {
                importSingleAttachment(
                    destinationDir = destinationDir,
                    rawReference = reference,
                    source = attachment.source,
                    remoteBacked = attachment.isRemoteBacked,
                    metadata = attachment.metadata,
                    kindOverride = attachment.kind,
                    labelOverride = attachment.label,
                    mimeTypeOverride = attachment.mimeType,
                    sizeBytesOverride = attachment.sizeBytes,
                    workspaceSnapshot = snapshot
                ) ?: attachment.copy(
                    transferState = MessageAttachmentTransferState.Failed,
                    failureMessage = "Attachment file not found: $reference"
                )
            }
        }
    }

    suspend fun importInboundAttachments(
        sessionId: String,
        sessionTitle: String,
        channel: String,
        messageKey: String,
        attachments: List<MessageAttachment>
    ): List<MessageAttachment> = withContext(Dispatchers.IO) {
        if (attachments.isEmpty()) return@withContext emptyList()
        val snapshot = workspaceManager.ensureWorkspace(sessionId, sessionTitle)
        val destinationDir = File(snapshot.artifactsDir, "incoming/${channel.trim().ifBlank { "unknown" }}/$messageKey")
            .apply { mkdirs() }
        attachments.map { attachment ->
            importInboundAttachment(destinationDir, snapshot, attachment)
        }
    }

    private fun importInboundAttachment(
        destinationDir: File,
        snapshot: SessionWorkspaceSnapshot,
        attachment: MessageAttachment
    ): MessageAttachment {
        val locator = AttachmentRemoteLocatorJsonCodec.decode(
            attachment.metadata[AttachmentRecordRepository.KEY_REMOTE_LOCATOR]
        )
        return when {
            attachment.localWorkspacePath?.isNotBlank() == true &&
                File(attachment.localWorkspacePath).exists() &&
                isUnderRoot(File(attachment.localWorkspacePath), File(snapshot.workspaceRoot)) -> {
                attachment.copy(
                    reference = attachment.localWorkspacePath,
                    transferState = MessageAttachmentTransferState.Downloaded,
                    isRemoteBacked = attachment.isRemoteBacked || locator != null
                )
            }

            attachment.reference.startsWith("content://", ignoreCase = true) ||
                attachment.reference.startsWith("file://", ignoreCase = true) ||
                File(attachment.reference).exists() -> {
                importSingleAttachment(
                    destinationDir = destinationDir,
                    rawReference = attachment.reference,
                    source = attachment.source,
                    remoteBacked = attachment.isRemoteBacked,
                    metadata = attachment.metadata,
                    kindOverride = attachment.kind,
                    labelOverride = attachment.label,
                    mimeTypeOverride = attachment.mimeType,
                    sizeBytesOverride = attachment.sizeBytes
                )?.copy(transferState = MessageAttachmentTransferState.Downloaded)
                    ?: attachment.copy(
                        transferState = MessageAttachmentTransferState.Failed,
                        failureMessage = "Attachment import failed: ${attachment.label.ifBlank { attachment.reference }}"
                    )
            }

            else -> downloadRemoteAttachment(destinationDir, attachment, locator)
        }
    }

    private fun downloadRemoteAttachment(
        destinationDir: File,
        attachment: MessageAttachment,
        locator: AttachmentRemoteLocator?
    ): MessageAttachment {
        return runCatching {
            val request = buildRemoteAttachmentRequest(attachment, locator)
            httpClient.newCall(request).execute().use { response ->
                require(response.isSuccessful) { "HTTP ${response.code}" }
                val body = response.body ?: throw IllegalStateException("empty response body")
                if (body.contentLength() > maxRemoteAttachmentBytes) {
                    throw AttachmentTooLargeException(maxRemoteAttachmentBytes)
                }
                val target = buildDestinationFile(
                    destinationDir = destinationDir,
                    labelHint = attachment.label,
                    headerFilename = response.header("Content-Disposition").orEmpty(),
                    fallbackReference = attachment.reference
                )
                try {
                    body.byteStream().use { input ->
                        FileOutputStream(target).use { output ->
                            BoundedStreamCopy.copy(
                                input = input,
                                output = output,
                                maxBytes = maxRemoteAttachmentBytes
                            )
                        }
                    }
                } catch (t: Throwable) {
                    runCatching { target.delete() }
                    throw t
                }
                attachment.copy(
                    reference = target.absolutePath,
                    mimeType = attachment.mimeType ?: body.contentType()?.toString(),
                    sizeBytes = attachment.sizeBytes ?: target.length(),
                    source = MessageAttachmentSource.Remote,
                    transferState = MessageAttachmentTransferState.Downloaded,
                    localWorkspacePath = target.absolutePath,
                    isRemoteBacked = true
                )
            }
        }.getOrElse { t ->
            attachment.copy(
                transferState = MessageAttachmentTransferState.Failed,
                failureMessage = t.message ?: t.javaClass.simpleName,
                isRemoteBacked = true
            )
        }
    }

    private fun buildRemoteAttachmentRequest(
        attachment: MessageAttachment,
        locator: AttachmentRemoteLocator?
    ): Request {
        val url = when (locator) {
            is AttachmentRemoteLocator.BearerUrl -> locator.url
            is AttachmentRemoteLocator.PublicUrl -> locator.url
            is AttachmentRemoteLocator.TelegramBotFile -> locator.url
            is AttachmentRemoteLocator.FeishuFileKey -> locator.url
            is AttachmentRemoteLocator.SlackPrivateFile -> locator.url
            is AttachmentRemoteLocator.WeComEncryptedUrl -> locator.url
            else -> attachment.reference
        }
        if (!AttachmentDownloadPolicy.isAllowed(url)) {
            throw IllegalArgumentException("Remote attachment URL must use HTTP or HTTPS.")
        }
        val builder = Request.Builder().url(url).get()
        if (locator is AttachmentRemoteLocator.BearerUrl) {
            builder.header("Authorization", "Bearer ${locator.bearerToken}")
        }
        return builder.build()
    }

    private fun importSingleAttachment(
        destinationDir: File,
        rawReference: String,
        source: MessageAttachmentSource,
        remoteBacked: Boolean,
        metadata: Map<String, String>,
        kindOverride: MessageAttachmentKind? = null,
        labelOverride: String? = null,
        mimeTypeOverride: String? = null,
        sizeBytesOverride: Long? = null,
        workspaceSnapshot: SessionWorkspaceSnapshot? = null
    ): MessageAttachment? {
        val normalized = normalizeMessageAttachmentReference(rawReference) ?: return null
        val destination = buildDestinationFile(
            destinationDir = destinationDir,
            labelHint = labelOverride.orEmpty(),
            headerFilename = "",
            fallbackReference = normalized
        )
        val uri = normalized.toUriOrNull()
        val copied = when {
            uri != null && uri.scheme.equals("content", ignoreCase = true) -> {
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destination).use { output ->
                        BoundedStreamCopy.copy(
                            input = input,
                            output = output,
                            maxBytes = maxRemoteAttachmentBytes
                        )
                    }
                } != null
            }

            workspaceSnapshot != null -> {
                resolveWorkspaceAttachmentFile(normalized, workspaceSnapshot)
                    ?.let { copyFile(it, destination) }
                    ?: false
            }

            uri != null && uri.scheme.equals("file", ignoreCase = true) -> {
                copyFile(File(requireNotNull(uri.path)), destination)
            }

            File(normalized).exists() -> copyFile(File(normalized), destination)
            else -> false
        }
        if (!copied) return null
        val label = labelOverride?.trim()?.ifBlank { null }
            ?: queryDisplayName(uri)
            ?: destination.name
        val kind = kindOverride ?: inferMessageAttachmentKind(destination.absolutePath, explicitMimeType = mimeTypeOverride)
        val mimeType = mimeTypeOverride
            ?: queryMimeType(uri)
            ?: URLConnection.guessContentTypeFromName(destination.name)
            ?: inferMessageAttachmentMimeType(destination.absolutePath, kind)
        return MessageAttachment(
            kind = kind,
            reference = destination.absolutePath,
            label = label.ifBlank { deriveMessageAttachmentLabel(destination.absolutePath, kind) },
            mimeType = mimeType,
            sizeBytes = sizeBytesOverride ?: destination.length(),
            source = source,
            metadata = metadata,
            transferState = MessageAttachmentTransferState.Ready,
            localWorkspacePath = destination.absolutePath,
            isRemoteBacked = remoteBacked
        )
    }

    private fun buildDestinationFile(
        destinationDir: File,
        labelHint: String,
        headerFilename: String,
        fallbackReference: String
    ): File {
        val explicit = extractFilename(headerFilename)
            .ifBlank { labelHint.trim() }
            .ifBlank { fallbackReference.substringAfterLast('/') }
            .ifBlank { "attachment.bin" }
        val safeName = explicit.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "attachment.bin" }
        var target = File(destinationDir, safeName)
        if (!target.exists()) return target
        val name = safeName.substringBeforeLast('.', safeName)
        val ext = safeName.substringAfterLast('.', "")
        var index = 1
        while (target.exists()) {
            val candidate = if (ext.isBlank()) "$name-$index" else "$name-$index.$ext"
            target = File(destinationDir, candidate)
            index += 1
        }
        return target
    }

    private fun copyFile(from: File, to: File): Boolean {
        return runCatching {
            to.parentFile?.mkdirs()
            from.inputStream().use { input ->
                FileOutputStream(to).use { output ->
                    BoundedStreamCopy.copy(
                        input = input,
                        output = output,
                        maxBytes = maxRemoteAttachmentBytes
                    )
                }
            }
        }.isSuccess
    }

    private fun resolveWorkspaceAttachmentFile(
        rawReference: String,
        snapshot: SessionWorkspaceSnapshot
    ): File? {
        val currentRoot = File(snapshot.workspaceRoot).canonicalFile
        val sharedRoot = workspaceManager.sharedWorkspaceRoot()
        val sessionsRoot = workspaceManager.sessionsRoot()
        val uri = rawReference.toUriOrNull()
        val candidate = when {
            uri != null && uri.scheme.equals("file", ignoreCase = true) -> {
                File(uri.path ?: return null)
            }

            rawReference.startsWith(WorkspacePathResolver.SESSION_SCHEME, ignoreCase = true) -> {
                File(
                    currentRoot,
                    rawReference.substring(WorkspacePathResolver.SESSION_SCHEME.length).trimStart('/', '\\')
                )
            }

            rawReference.startsWith(WorkspacePathResolver.SHARED_SCHEME, ignoreCase = true) -> {
                File(
                    sharedRoot,
                    rawReference.substring(WorkspacePathResolver.SHARED_SCHEME.length).trimStart('/', '\\')
                )
            }

            File(rawReference).isAbsolute -> File(rawReference)
            else -> File(currentRoot, rawReference)
        }.canonicalFile

        if (!candidate.exists() || !candidate.isFile) return null
        if (isUnderRoot(candidate, currentRoot)) return candidate
        if (isUnderRoot(candidate, sessionsRoot) && !isUnderRoot(candidate, currentRoot)) return null
        if (isUnderRoot(candidate, sharedRoot)) return candidate
        return null
    }

    private fun isUnderRoot(file: File, root: File): Boolean {
        val path = file.canonicalFile.path
        val rootPath = root.canonicalFile.path
        return path == rootPath || path.startsWith(rootPath + File.separator)
    }

    private fun queryDisplayName(uri: Uri?): String? {
        if (uri == null || !uri.scheme.equals("content", ignoreCase = true)) return null
        return runCatching {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index < 0) return@use null
                cursor.getString(index)
            }
        }.getOrNull()
    }

    private fun queryMimeType(uri: Uri?): String? {
        if (uri == null || !uri.scheme.equals("content", ignoreCase = true)) return null
        return appContext.contentResolver.getType(uri)
    }

    private fun extractFilename(contentDisposition: String): String {
        val utf8 = Regex("filename\\*=UTF-8''([^;\\s]+)", RegexOption.IGNORE_CASE)
        utf8.find(contentDisposition)?.groupValues?.getOrNull(1)?.let { return Uri.decode(it) }
        val normal = Regex("filename=\"?([^\";\\s]+)\"?", RegexOption.IGNORE_CASE)
        return normal.find(contentDisposition)?.groupValues?.getOrNull(1).orEmpty()
    }

    private fun String.toUriOrNull(): Uri? {
        return runCatching { Uri.parse(this) }.getOrNull()
            ?.takeIf { it.scheme != null }
    }

    companion object {
        const val DEFAULT_MAX_REMOTE_ATTACHMENT_BYTES: Long = 50L * 1024L * 1024L

        private fun defaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .callTimeout(90, TimeUnit.SECONDS)
                .build()
        }
    }
}

internal object AttachmentDownloadPolicy {
    fun isAllowed(url: String): Boolean {
        val parsed = url.trim().toHttpUrlOrNull() ?: return false
        return parsed.scheme.equals("http", ignoreCase = true) ||
            parsed.scheme.equals("https", ignoreCase = true)
    }
}
