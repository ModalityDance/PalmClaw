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
import com.palmclaw.bus.normalizeMessageAttachmentReference
import com.palmclaw.workspace.SessionWorkspaceManager
import java.io.File
import java.io.FileOutputStream
import java.net.URLConnection
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class AttachmentTransferService(
    context: Context,
    private val workspaceManager: SessionWorkspaceManager,
    private val httpClient: OkHttpClient = OkHttpClient()
) {
    private val appContext = context.applicationContext

    suspend fun importComposerDrafts(
        sessionId: String,
        sessionTitle: String,
        uriStrings: List<String>
    ): List<MessageAttachment> = withContext(Dispatchers.IO) {
        val snapshot = workspaceManager.ensureWorkspace(sessionId, sessionTitle)
        val destinationDir = File(snapshot.artifactsDir, "outgoing/user/${UUID.randomUUID()}").apply { mkdirs() }
        uriStrings.mapNotNull { raw ->
            importSingleAttachment(
                destinationDir = destinationDir,
                rawReference = raw,
                source = MessageAttachmentSource.Local,
                remoteBacked = false,
                metadata = emptyMap()
            )
        }
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
            if (reference.startsWith(snapshot.workspaceRoot, ignoreCase = true)) {
                attachment.copy(
                    reference = reference,
                    localWorkspacePath = reference,
                    transferState = MessageAttachmentTransferState.Ready
                )
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
                    sizeBytesOverride = attachment.sizeBytes
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
            importInboundAttachment(destinationDir, attachment)
        }
    }

    private fun importInboundAttachment(
        destinationDir: File,
        attachment: MessageAttachment
    ): MessageAttachment {
        val locator = AttachmentRemoteLocatorJsonCodec.decode(
            attachment.metadata[AttachmentRecordRepository.KEY_REMOTE_LOCATOR]
        )
        return when {
            attachment.localWorkspacePath?.isNotBlank() == true &&
                File(attachment.localWorkspacePath).exists() -> {
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
                    ?: attachment.copy(transferState = MessageAttachmentTransferState.Failed)
            }

            else -> downloadRemoteAttachment(destinationDir, attachment, locator)
        }
    }

    private fun downloadRemoteAttachment(
        destinationDir: File,
        attachment: MessageAttachment,
        locator: AttachmentRemoteLocator?
    ): MessageAttachment {
        val request = when (locator) {
            is AttachmentRemoteLocator.BearerUrl -> {
                Request.Builder()
                    .url(locator.url)
                    .header("Authorization", "Bearer ${locator.bearerToken}")
                    .get()
                    .build()
            }

            else -> {
                val url = when (locator) {
                    is AttachmentRemoteLocator.PublicUrl -> locator.url
                    is AttachmentRemoteLocator.TelegramBotFile -> locator.url
                    is AttachmentRemoteLocator.FeishuFileKey -> locator.url
                    is AttachmentRemoteLocator.SlackPrivateFile -> locator.url
                    is AttachmentRemoteLocator.WeComEncryptedUrl -> locator.url
                    else -> attachment.reference
                }
                Request.Builder().url(url).get().build()
            }
        }
        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                require(response.isSuccessful) { "HTTP ${response.code}" }
                val target = buildDestinationFile(
                    destinationDir = destinationDir,
                    labelHint = attachment.label,
                    headerFilename = response.header("Content-Disposition").orEmpty(),
                    fallbackReference = attachment.reference
                )
                response.body?.byteStream()?.use { input ->
                    FileOutputStream(target).use { output ->
                        input.copyTo(output)
                    }
                }
                attachment.copy(
                    reference = target.absolutePath,
                    mimeType = attachment.mimeType ?: response.body?.contentType()?.toString(),
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

    private fun importSingleAttachment(
        destinationDir: File,
        rawReference: String,
        source: MessageAttachmentSource,
        remoteBacked: Boolean,
        metadata: Map<String, String>,
        kindOverride: MessageAttachmentKind? = null,
        labelOverride: String? = null,
        mimeTypeOverride: String? = null,
        sizeBytesOverride: Long? = null
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
                    FileOutputStream(destination).use { output -> input.copyTo(output) }
                } != null
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
                FileOutputStream(to).use { output -> input.copyTo(output) }
            }
        }.isSuccess
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
}
