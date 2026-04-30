package com.palmclaw.bus

import java.io.File
import java.net.URLConnection
import java.util.LinkedHashSet
import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class MessageAttachmentKind {
    @SerialName("image")
    Image,

    @SerialName("video")
    Video,

    @SerialName("audio")
    Audio,

    @SerialName("file")
    File
}

@Serializable
enum class MessageAttachmentSource {
    @SerialName("local")
    Local,

    @SerialName("remote")
    Remote,

    @SerialName("unknown")
    Unknown
}

@Serializable
enum class MessageAttachmentTransferState {
    @SerialName("draft")
    Draft,

    @SerialName("importing")
    Importing,

    @SerialName("ready")
    Ready,

    @SerialName("uploading")
    Uploading,

    @SerialName("uploaded")
    Uploaded,

    @SerialName("downloading")
    Downloading,

    @SerialName("downloaded")
    Downloaded,

    @SerialName("failed")
    Failed
}

@Serializable
data class MessageAttachment(
    val kind: MessageAttachmentKind = MessageAttachmentKind.File,
    val reference: String,
    val label: String = "",
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val source: MessageAttachmentSource = MessageAttachmentSource.Unknown,
    val metadata: Map<String, String> = emptyMap(),
    val transferState: MessageAttachmentTransferState = MessageAttachmentTransferState.Ready,
    val failureMessage: String? = null,
    val localWorkspacePath: String? = null,
    val isRemoteBacked: Boolean = false
)

object MessageAttachmentJsonCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun encode(attachments: List<MessageAttachment>): String? {
        val normalized = normalizeMessageAttachments(attachments = attachments)
        if (normalized.isEmpty()) return null
        return json.encodeToString(normalized)
    }

    fun decode(raw: String?): List<MessageAttachment> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<MessageAttachment>>(raw)
        }.getOrDefault(emptyList()).let { normalizeMessageAttachments(it) }
    }
}

fun normalizeMessageAttachments(
    attachments: List<MessageAttachment> = emptyList(),
    legacyMedia: List<String> = emptyList()
): List<MessageAttachment> {
    val preferred = attachments.mapNotNull(::normalizeMessageAttachment)
    if (preferred.isNotEmpty()) {
        return dedupeMessageAttachments(preferred)
    }
    return dedupeMessageAttachments(legacyMedia.mapNotNull(::legacyMediaToAttachment))
}

fun normalizeMessageAttachment(attachment: MessageAttachment): MessageAttachment? {
    val reference = normalizeMessageAttachmentReference(attachment.reference) ?: return null
    val kind = inferMessageAttachmentKind(
        reference = reference,
        explicitMimeType = attachment.mimeType,
        fallbackKind = attachment.kind
    )
    val mimeType = inferMessageAttachmentMimeType(
        reference = reference,
        kind = kind,
        explicitMimeType = attachment.mimeType
    )
    return attachment.copy(
        kind = kind,
        reference = reference,
        label = attachment.label.trim().ifBlank { deriveMessageAttachmentLabel(reference, kind) },
        mimeType = mimeType,
        source = normalizeMessageAttachmentSource(attachment.source, reference),
        transferState = attachment.transferState,
        failureMessage = attachment.failureMessage?.trim()?.ifBlank { null },
        localWorkspacePath = attachment.localWorkspacePath
            ?.trim()
            ?.ifBlank { null }
            ?: reference.takeUnless(::isRemoteAttachmentReference),
        isRemoteBacked = attachment.isRemoteBacked || isRemoteAttachmentReference(reference),
        metadata = attachment.metadata
            .mapNotNull { (key, value) ->
                key.trim().takeIf { it.isNotBlank() }?.let { normalizedKey ->
                    normalizedKey to value
                }
            }
            .toMap()
    )
}

fun normalizeMessageAttachmentReference(raw: String): String? {
    return raw.trim()
        .trim('"', '\'')
        .trimEnd(',', ';', ')', ']', '}')
        .takeIf { it.isNotBlank() }
}

fun legacyMediaToAttachment(reference: String): MessageAttachment? {
    val normalizedReference = normalizeMessageAttachmentReference(reference) ?: return null
    val kind = inferMessageAttachmentKind(normalizedReference)
    return normalizeMessageAttachment(
        MessageAttachment(
            kind = kind,
            reference = normalizedReference,
            source = inferMessageAttachmentSource(normalizedReference)
        )
    )
}

fun inferMessageAttachmentKind(
    reference: String,
    explicitMimeType: String? = null,
    fallbackKind: MessageAttachmentKind = MessageAttachmentKind.File
): MessageAttachmentKind {
    val mimeType = explicitMimeType?.trim()?.lowercase(Locale.US).orEmpty()
    when {
        mimeType.startsWith("image/") -> return MessageAttachmentKind.Image
        mimeType.startsWith("video/") -> return MessageAttachmentKind.Video
        mimeType.startsWith("audio/") -> return MessageAttachmentKind.Audio
        mimeType.isNotBlank() -> return MessageAttachmentKind.File
    }

    val lower = reference.lowercase(Locale.US)
    if (lower.contains("/images/")) return MessageAttachmentKind.Image
    if (lower.contains("/video/")) return MessageAttachmentKind.Video
    if (lower.contains("/audio/")) return MessageAttachmentKind.Audio
    if (IMAGE_EXTENSIONS.any(lower::contains)) return MessageAttachmentKind.Image
    if (VIDEO_EXTENSIONS.any(lower::contains)) return MessageAttachmentKind.Video
    if (AUDIO_EXTENSIONS.any(lower::contains)) return MessageAttachmentKind.Audio
    return fallbackKind
}

fun inferMessageAttachmentMimeType(
    reference: String,
    kind: MessageAttachmentKind,
    explicitMimeType: String? = null
): String {
    explicitMimeType?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
    URLConnection.guessContentTypeFromName(reference.substringBefore('?').substringBefore('#'))
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }
    return defaultMimeTypeForKind(kind)
}

fun defaultMimeTypeForKind(kind: MessageAttachmentKind): String {
    return when (kind) {
        MessageAttachmentKind.Image -> "image/*"
        MessageAttachmentKind.Video -> "video/*"
        MessageAttachmentKind.Audio -> "audio/*"
        MessageAttachmentKind.File -> "*/*"
    }
}

fun deriveMessageAttachmentLabel(reference: String, kind: MessageAttachmentKind): String {
    val name = runCatching {
        if (isRemoteAttachmentReference(reference) ||
            reference.startsWith("content://", ignoreCase = true) ||
            reference.startsWith("file://", ignoreCase = true)
        ) {
            reference.substringAfterLast('/').substringBefore('?').substringBefore('#')
        } else {
            File(reference).name
        }
    }.getOrDefault("")
    val fallback = when (kind) {
        MessageAttachmentKind.Image -> "Image"
        MessageAttachmentKind.Video -> "Video"
        MessageAttachmentKind.Audio -> "Audio"
        MessageAttachmentKind.File -> "File"
    }
    return name.takeIf { it.isNotBlank() } ?: fallback
}

fun inferMessageAttachmentSource(reference: String): MessageAttachmentSource {
    return if (isRemoteAttachmentReference(reference)) {
        MessageAttachmentSource.Remote
    } else {
        MessageAttachmentSource.Local
    }
}

fun isRemoteAttachmentReference(reference: String): Boolean {
    return reference.startsWith("http://", ignoreCase = true) ||
        reference.startsWith("https://", ignoreCase = true)
}

private fun dedupeMessageAttachments(attachments: List<MessageAttachment>): List<MessageAttachment> {
    val seen = LinkedHashSet<String>()
    return attachments.filter { attachment ->
        seen.add("${attachment.kind.name}:${attachment.reference}")
    }
}

private fun normalizeMessageAttachmentSource(
    source: MessageAttachmentSource,
    reference: String
): MessageAttachmentSource {
    return if (source == MessageAttachmentSource.Unknown) {
        inferMessageAttachmentSource(reference)
    } else {
        source
    }
}

private val IMAGE_EXTENSIONS = listOf(".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp", ".heic", ".heif")
private val VIDEO_EXTENSIONS = listOf(".mp4", ".m4v", ".mov", ".webm", ".mkv", ".avi", ".3gp")
private val AUDIO_EXTENSIONS = listOf(".mp3", ".wav", ".m4a", ".aac", ".ogg", ".opus", ".flac")
