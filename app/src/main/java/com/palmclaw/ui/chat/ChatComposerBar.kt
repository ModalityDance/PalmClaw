package com.palmclaw.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
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

/**
 * Composer bar for the main chat surface.
 */
@Composable
internal fun ChatComposerBar(
    state: ChatUiState,
    onInputHeightChange: (Int) -> Unit,
    onInputChanged: (String) -> Unit,
    onSendMessage: () -> Unit,
    onStopGeneration: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier
                .weight(1f)
                .align(Alignment.CenterVertically)
                .onSizeChanged { onInputHeightChange(it.height) },
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            tonalElevation = 2.dp,
            shadowElevation = 6.dp,
            shape = RoundedCornerShape(24.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 46.dp)
                    .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 6.dp),
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
                                if (!state.isGenerating && state.input.isNotBlank()) {
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
                val canSend = state.input.isNotBlank() && !state.isGenerating
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
                        enabled = state.isGenerating || state.input.isNotBlank(),
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
