package com.palmclaw.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Scroll affordances layered above the chat transcript.
 */
@Composable
internal fun BoxScope.ChatScrollOverlay(
    listState: LazyListState,
    scrollIndicator: ScrollIndicatorUi?,
    showScrollToLatestButton: Boolean,
    chatInputBarClearance: Dp,
    onScrollToLatest: () -> Unit
) {
    if (listState.isScrollInProgress) {
        scrollIndicator?.let { indicator ->
            val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
            val thumbColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
            Canvas(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = 8.dp)
                    .fillMaxHeight()
                    .padding(vertical = 12.dp)
                    .width(4.dp)
            ) {
                drawRoundRect(
                    color = trackColor,
                    cornerRadius = CornerRadius(size.width / 2f, size.width / 2f)
                )
                val thumbHeight = size.height * indicator.thumbFraction
                val maxTop = (size.height - thumbHeight).coerceAtLeast(0f)
                val top = maxTop * indicator.progress
                drawRoundRect(
                    color = thumbColor,
                    topLeft = Offset(0f, top),
                    size = Size(size.width, thumbHeight),
                    cornerRadius = CornerRadius(size.width / 2f, size.width / 2f)
                )
            }
        }
    }

    if (showScrollToLatestButton) {
        SmallFloatingActionButton(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = chatInputBarClearance - 8.dp)
                .size(34.dp),
            onClick = onScrollToLatest
        ) {
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = uiLabel("Scroll to latest"),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
