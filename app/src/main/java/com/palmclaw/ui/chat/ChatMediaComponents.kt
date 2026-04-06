package com.palmclaw.ui

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.DownloadManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.text.method.LinkMovementMethod
import android.view.WindowManager
import android.widget.Toast
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.palmclaw.R
import com.palmclaw.config.AppLimits
import com.palmclaw.config.AppSession
import com.palmclaw.providers.ProviderCatalog
import com.palmclaw.tools.AndroidUserActionBridge
import com.palmclaw.tools.AndroidUserActionRequester
import com.palmclaw.tools.hasAllFilesAccess
import com.palmclaw.tools.hasPermission
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock


/**
 * Chat media attachment components and media preview helpers used by the transcript UI.
 */
@Composable
internal fun MediaAttachmentList(
    attachments: List<UiMediaAttachment>,
    currentPreviewAudioRef: String?,
    currentPreviewAudioDurationMs: Int,
    currentPreviewAudioPositionMs: Int,
    onOpenAttachment: (UiMediaAttachment) -> Unit,
    onToggleAudioPreview: (UiMediaAttachment) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        attachments.forEach { attachment ->
            when (attachment.kind) {
                UiMediaKind.Image -> {
                    ImageAttachmentCard(
                        attachment = attachment,
                        onOpenAttachment = onOpenAttachment
                    )
                }
                UiMediaKind.Video -> {
                    VideoAttachmentCard(
                        attachment = attachment,
                        onOpenAttachment = onOpenAttachment
                    )
                }
                UiMediaKind.Audio -> {
                    val isPlaying = currentPreviewAudioRef == attachment.reference
                    AudioAttachmentCard(
                        attachment = attachment,
                        isPlaying = isPlaying,
                        durationMs = if (isPlaying) currentPreviewAudioDurationMs else 0,
                        positionMs = if (isPlaying) currentPreviewAudioPositionMs else 0,
                        onTogglePlayback = { onToggleAudioPreview(attachment) }
                    )
                }
            }
        }
    }
}

@Composable
internal fun ImageAttachmentCard(
    attachment: UiMediaAttachment,
    onOpenAttachment: (UiMediaAttachment) -> Unit
) {
    val uri = remember(attachment.reference) { toAttachmentUri(attachment.reference) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 220.dp)
                    .clickable { onOpenAttachment(attachment) }
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth(),
                    factory = { ctx ->
                        ImageView(ctx).apply {
                            adjustViewBounds = true
                            scaleType = ImageView.ScaleType.CENTER_CROP
                        }
                    },
                    update = { imageView ->
                        if (uri == null) {
                            imageView.setImageResource(android.R.drawable.ic_menu_report_image)
                        } else {
                            runCatching { imageView.setImageURI(uri) }
                                .onFailure {
                                    imageView.setImageResource(android.R.drawable.ic_menu_report_image)
                                }
                        }
                    }
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = attachment.label,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { onOpenAttachment(attachment) }) {
                    Text(uiLabel("Preview"))
                }
            }
        }
    }
}

@Composable
internal fun VideoAttachmentCard(
    attachment: UiMediaAttachment,
    onOpenAttachment: (UiMediaAttachment) -> Unit
) {
    val context = LocalContext.current
    val uri = remember(attachment.reference) { toAttachmentUri(attachment.reference) }
    val videoBackgroundArgb = MaterialTheme.colorScheme.surfaceVariant.toArgb()
    val exoPlayer = remember(attachment.reference, uri) {
        uri?.let { mediaUri ->
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(mediaUri))
                prepare()
                playWhenReady = false
            }
        }
    }
    var isPlaying by rememberSaveable(attachment.reference) { mutableStateOf(false) }
    var durationMs by rememberSaveable(attachment.reference) { mutableStateOf(0) }
    var positionMs by rememberSaveable(attachment.reference) { mutableStateOf(0) }

    DisposableEffect(exoPlayer) {
        val player = exoPlayer
        if (player == null) {
            onDispose {}
        } else {
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        durationMs = player.duration.coerceAtLeast(0L).toInt()
                    }
                    if (state == Player.STATE_ENDED) {
                        positionMs = durationMs
                        isPlaying = false
                    }
                }
            }
            player.addListener(listener)
            onDispose {
                player.removeListener(listener)
                runCatching { player.release() }
                isPlaying = false
            }
        }
    }

    LaunchedEffect(exoPlayer, isPlaying) {
        val player = exoPlayer ?: return@LaunchedEffect
        if (!isPlaying) return@LaunchedEffect
        while (isPlaying) {
            val duration = player.duration.coerceAtLeast(0L).toInt()
            val position = player.currentPosition.coerceAtLeast(0L).toInt()
            durationMs = duration
            positionMs = position.coerceAtMost(duration)
            delay(250)
        }
    }

    val toggleVideoPlayback: () -> Unit = toggle@{
        val player = exoPlayer ?: return@toggle
        if (isPlaying) {
            positionMs = player.currentPosition.coerceAtLeast(0L).toInt()
            player.pause()
            isPlaying = false
        } else {
            runCatching {
                if (positionMs > 0) {
                    player.seekTo(positionMs.toLong().coerceAtMost(player.duration.coerceAtLeast(0L)))
                }
                player.play()
                isPlaying = true
            }.onFailure {
                player.pause()
                isPlaying = false
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp, max = 220.dp)
            ) {
                if (exoPlayer == null) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = uiLabel("Video unavailable"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = false
                                player = exoPlayer
                                setShutterBackgroundColor(videoBackgroundArgb)
                                setBackgroundColor(videoBackgroundArgb)
                            }
                        },
                        update = { view ->
                            if (view.player !== exoPlayer) {
                                view.player = exoPlayer
                            }
                        }
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = uiLabel("Video"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = attachment.label,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (durationMs > 0) {
                val progress = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                SimpleProgressBar(progress = progress)
                Text(
                    text = "${formatDuration(positionMs)} / ${formatDuration(durationMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = toggleVideoPlayback) {
                    Text(uiLabel(if (isPlaying) "Pause" else "Play"))
                }
                TextButton(onClick = { onOpenAttachment(attachment) }) {
                    Text(uiLabel("Open"))
                }
            }
        }
    }
}

@Composable
internal fun AudioAttachmentCard(
    attachment: UiMediaAttachment,
    isPlaying: Boolean,
    durationMs: Int,
    positionMs: Int,
    onTogglePlayback: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = uiLabel("Audio"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = attachment.label,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (durationMs > 0) {
                val progress = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                SimpleProgressBar(progress = progress)
                Text(
                    text = "${formatDuration(positionMs)} / ${formatDuration(durationMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onTogglePlayback) {
                    Text(uiLabel(if (isPlaying) "Stop" else "Play"))
                }
            }
        }
    }
}

@Composable
internal fun SimpleProgressBar(progress: Float) {
    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
    val fill = MaterialTheme.colorScheme.primary
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
    ) {
        drawRoundRect(
            color = track,
            cornerRadius = CornerRadius(size.height / 2f, size.height / 2f)
        )
        drawRoundRect(
            color = fill,
            size = Size(size.width * progress.coerceIn(0f, 1f), size.height),
            cornerRadius = CornerRadius(size.height / 2f, size.height / 2f)
        )
    }
}

internal fun formatDuration(valueMs: Int): String {
    val totalSec = (valueMs.coerceAtLeast(0) / 1000)
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}

internal fun toAttachmentUri(reference: String): Uri? {
    val raw = reference.trim()
    if (raw.isBlank()) return null
    return when {
        raw.startsWith("content://", true) ||
            raw.startsWith("file://", true) ||
            raw.startsWith("http://", true) ||
            raw.startsWith("https://", true) -> Uri.parse(raw)
        else -> Uri.fromFile(File(raw))
    }
}

internal fun mediaMimeTypeForKind(kind: UiMediaKind): String {
    return when (kind) {
        UiMediaKind.Image -> "image/*"
        UiMediaKind.Video -> "video/*"
        UiMediaKind.Audio -> "audio/*"
    }
}
