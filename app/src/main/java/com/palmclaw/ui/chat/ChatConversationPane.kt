package com.palmclaw.ui

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.delay

@Composable
internal fun ChatConversationPane(
    timelineState: ChatTimelineState,
    composerState: ChatComposerState,
    identityState: IdentityDisplayState,
    useChinese: Boolean,
    onInputChanged: (String) -> Unit,
    onPickAttachments: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onSendMessage: () -> Unit,
    onStopGeneration: () -> Unit,
    onLoadOlderMessages: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val openAttachmentTitle = uiLabel("Open attachment")
    val interactionState = rememberChatInteractionState()
    val bottomOverlayHeightPx = interactionState.inputBarSurfaceHeightPx + WindowInsets.ime.getBottom(density)

    DisposableEffect(interactionState) {
        onDispose { interactionState.releaseAudioPreview() }
    }

    LaunchedEffect(interactionState.previewAudioPlayer, interactionState.previewAudioRef) {
        val player = interactionState.previewAudioPlayer ?: return@LaunchedEffect
        while (interactionState.previewAudioPlayer === player) {
            val duration = runCatching { player.duration }.getOrDefault(0).coerceAtLeast(0)
            val position = runCatching { player.currentPosition }.getOrDefault(0).coerceAtLeast(0)
            interactionState.previewAudioDurationMs = duration
            interactionState.previewAudioPositionMs = position.coerceAtMost(duration)
            if (!runCatching { player.isPlaying }.getOrDefault(false)) {
                break
            }
            delay(250)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(start = 10.dp, end = 10.dp, bottom = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            ChatMessageListPane(
                state = timelineState,
                identity = identityState,
                useChinese = useChinese,
                bottomOverlayHeightPx = bottomOverlayHeightPx,
                previewAudioRef = interactionState.previewAudioRef,
                previewAudioDurationMs = interactionState.previewAudioDurationMs,
                previewAudioPositionMs = interactionState.previewAudioPositionMs,
                onOpenAttachment = { interactionState.openAttachment(context, it, openAttachmentTitle) },
                onToggleAudioPreview = { interactionState.toggleAudioPreview(context, it) },
                onLoadOlderMessages = onLoadOlderMessages,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .imePadding()
            ) {
                ChatComposerBar(
                    state = composerState,
                    onInputHeightChange = interactionState::onInputBarHeightChanged,
                    onInputChanged = onInputChanged,
                    onPickAttachments = onPickAttachments,
                    onRemoveAttachment = onRemoveAttachment,
                    onSendMessage = onSendMessage,
                    onStopGeneration = onStopGeneration
                )
            }
        }
    }
}

@Composable
internal fun rememberChatInteractionState(): ChatInteractionState = remember { ChatInteractionState() }

internal class ChatInteractionState {
    var previewAudioPlayer by mutableStateOf<MediaPlayer?>(null)
    var previewAudioRef by mutableStateOf<String?>(null)
    var previewAudioDurationMs by mutableStateOf(0)
    var previewAudioPositionMs by mutableStateOf(0)
    var inputBarSurfaceHeightPx by mutableStateOf(0)

    fun onInputBarHeightChanged(measuredHeight: Int) {
        if (abs(inputBarSurfaceHeightPx - measuredHeight) > 2) {
            inputBarSurfaceHeightPx = measuredHeight
        }
    }

    fun openAttachment(context: Context, attachment: UiAttachment, chooserTitle: String) {
        val uri = AttachmentOpenResolver.toUri(context, attachment.localWorkspacePath ?: attachment.reference)
            ?: return
        val mime = AttachmentOpenResolver.resolveMimeType(attachment)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(intent, chooserTitle)) }
    }

    fun toggleAudioPreview(context: Context, attachment: UiAttachment) {
        val sameRefPlaying = previewAudioRef == attachment.reference &&
            runCatching { previewAudioPlayer?.isPlaying == true }.getOrDefault(false)
        if (sameRefPlaying) {
            releaseAudioPreview()
            return
        }
        runCatching {
            releaseAudioPreview()
            val player = MediaPlayer()
            val raw = (attachment.localWorkspacePath ?: attachment.reference).trim()
            val uri = AttachmentOpenResolver.toUri(context, raw)
            if (uri != null && (
                    raw.startsWith("content://", true) ||
                        raw.startsWith("file://", true) ||
                        raw.startsWith("http://", true) ||
                        raw.startsWith("https://", true)
                    )
            ) {
                player.setDataSource(context, uri)
            } else {
                player.setDataSource(raw)
            }
            player.prepare()
            previewAudioDurationMs = runCatching { player.duration }.getOrDefault(0).coerceAtLeast(0)
            previewAudioPositionMs = 0
            player.setOnCompletionListener {
                runCatching { it.release() }
                previewAudioPositionMs = previewAudioDurationMs
                previewAudioPlayer = null
                previewAudioRef = null
            }
            player.start()
            previewAudioPlayer = player
            previewAudioRef = attachment.reference
        }.onFailure {
            releaseAudioPreview()
        }
    }

    fun releaseAudioPreview() {
        runCatching { previewAudioPlayer?.stop() }
        runCatching { previewAudioPlayer?.release() }
        previewAudioPlayer = null
        previewAudioRef = null
        previewAudioDurationMs = 0
        previewAudioPositionMs = 0
    }
}
