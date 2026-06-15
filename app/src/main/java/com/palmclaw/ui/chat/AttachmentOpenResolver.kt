package com.palmclaw.ui

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.palmclaw.bus.MessageAttachmentKind
import com.palmclaw.bus.inferMessageAttachmentMimeType
import java.io.File

internal object AttachmentOpenResolver {
    fun toUri(context: Context, reference: String): Uri? {
        val raw = reference.trim()
        if (raw.isBlank()) return null
        return when {
            raw.startsWith("content://", ignoreCase = true) ||
                raw.startsWith("file://", ignoreCase = true) ||
                raw.startsWith("http://", ignoreCase = true) ||
                raw.startsWith("https://", ignoreCase = true) -> Uri.parse(raw)
            else -> {
                val file = File(raw)
                if (!file.exists()) {
                    Uri.fromFile(file)
                } else {
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                }
            }
        }
    }

    fun resolveMimeType(attachment: UiAttachment): String {
        return inferMessageAttachmentMimeType(
            reference = attachment.localWorkspacePath ?: attachment.reference,
            kind = attachment.kind.toMessageAttachmentKind(),
            explicitMimeType = attachment.mimeType
        )
    }

    private fun UiAttachmentKind.toMessageAttachmentKind(): MessageAttachmentKind {
        return when (this) {
            UiAttachmentKind.Image -> MessageAttachmentKind.Image
            UiAttachmentKind.Video -> MessageAttachmentKind.Video
            UiAttachmentKind.Audio -> MessageAttachmentKind.Audio
            UiAttachmentKind.File -> MessageAttachmentKind.File
        }
    }
}
