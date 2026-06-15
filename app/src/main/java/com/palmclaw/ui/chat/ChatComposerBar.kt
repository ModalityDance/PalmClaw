package com.palmclaw.ui

import androidx.compose.foundation.background
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * Composer bar for the main chat surface.
 */
@Composable
internal fun ChatComposerBar(
    state: ChatComposerState,
    onInputHeightChange: (Int) -> Unit,
    onInputChanged: (String) -> Unit,
    onPickAttachments: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onSendMessage: () -> Unit,
    onStopGeneration: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 6.dp)
            .onSizeChanged { onInputHeightChange(it.height) },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier
                .weight(1f)
                .align(Alignment.CenterVertically)
                .animateContentSize(),
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            tonalElevation = 2.dp,
            shadowElevation = 0.dp,
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
            ) {
                if (state.composerAttachments.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp),
                        contentPadding = PaddingValues(end = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(
                            items = state.composerAttachments,
                            key = { it.id }
                        ) { draft ->
                            ComposerAttachmentChip(
                                draft = draft,
                                enabled = !state.composerImporting,
                                onRemove = { onRemoveAttachment(draft.id) }
                            )
                        }
                    }
                }

                if (!state.composerAttachmentError.isNullOrBlank()) {
                    Text(
                        text = state.composerAttachmentError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 40.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onPickAttachments,
                        enabled = !state.isGenerating && !state.composerImporting,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = uiLabel("Add attachment"),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 6.dp, end = 6.dp),
                        contentAlignment = Alignment.TopStart
                    ) {
                        if (state.input.isBlank()) {
                            Text(
                                text = tr("Ask anything", ""),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 14.sp,
                                    lineHeight = 18.sp
                                ),
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.72f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        BasicTextField(
                            value = state.input,
                            onValueChange = onInputChanged,
                            singleLine = false,
                            maxLines = 6,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(
                                onSend = {
                                    if (state.canSend) {
                                        onSendMessage()
                                    }
                                }
                            ),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 14.sp,
                                lineHeight = 18.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    val isStopState = state.isGenerating
                    val canSend = state.canSend
                    Surface(
                        color = if (isStopState) {
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f)
                        } else {
                            if (canSend) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                            }
                        },
                        shape = CircleShape,
                        tonalElevation = if (canSend || isStopState) 2.dp else 0.dp,
                        shadowElevation = if (canSend || isStopState) 6.dp else 0.dp
                    ) {
                        IconButton(
                            onClick = {
                                if (state.isGenerating) {
                                    onStopGeneration()
                                } else {
                                    onSendMessage()
                                }
                            },
                            enabled = state.isGenerating || canSend,
                            modifier = Modifier.size(32.dp)
                        ) {
                            if (isStopState) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .background(
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                            shape = RoundedCornerShape(2.dp)
                                        )
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.KeyboardArrowUp,
                                    contentDescription = uiLabel("Send"),
                                    tint = if (canSend) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)
                                    },
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerAttachmentChip(
    draft: UiComposerAttachmentDraft,
    enabled: Boolean,
    onRemove: () -> Unit
) {
    val attachment = draft.attachment
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)),
        tonalElevation = 0.dp,
        modifier = Modifier.widthIn(max = 240.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 3.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.58f),
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ) {
                Icon(
                    imageVector = Icons.Rounded.AttachFile,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(5.dp)
                        .size(13.dp)
                )
            }
            Column(
                modifier = Modifier.widthIn(max = 164.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Text(
                    text = attachment.label,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                attachment.sizeBytes?.takeIf { it > 0L }?.let { sizeBytes ->
                    Text(
                        text = formatComposerAttachmentSize(sizeBytes),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(
                onClick = onRemove,
                enabled = enabled,
                modifier = Modifier.size(22.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = uiLabel("Remove attachment"),
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatComposerAttachmentSize(sizeBytes: Long): String {
    if (sizeBytes <= 0L) return ""
    val units = listOf("B", "KB", "MB", "GB")
    var value = sizeBytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    return if (unitIndex == 0) {
        "${sizeBytes} B"
    } else {
        String.format(Locale.US, "%.1f %s", value, units[unitIndex])
    }
}
