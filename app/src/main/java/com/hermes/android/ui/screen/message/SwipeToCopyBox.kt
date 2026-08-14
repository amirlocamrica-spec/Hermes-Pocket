package com.hermes.android.ui.screen

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Wraps a chat bubble so a quick horizontal swipe copies its text.
 *
 * The bubble follows the finger up to a threshold; on release past the
 * threshold [onCopy] fires and the bubble springs back. A swipe that
 * doesn't cross the threshold just settles back — no accidental copies.
 *
 * Implemented with a raw pointer + graphicsLayer offset instead of
 * SwipeToDismissBox: the Material swipe-dismiss API is built for list-item
 * deletion and fights the LazyColumn's vertical scroll far more than this
 * lightweight version, and it only needs one direction.
 */
@Composable
internal fun SwipeToCopyBox(
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val thresholdPx = with(LocalDensity.current) { 72.dp.toPx() }
    var offsetX by remember { mutableFloatStateOf(0f) }

    Box(modifier = modifier.fillMaxWidth()) {
        // The icon revealed behind the bubble as it slides away.
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { alpha = (abs(offsetX) / thresholdPx).coerceAtMost(1f) * 0.9f },
            contentAlignment = if (offsetX >= 0f) Alignment.CenterStart else Alignment.CenterEnd,
        ) {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (abs(offsetX) >= thresholdPx) {
                                onCopy()
                            }
                            offsetX = 0f
                        },
                        onDragCancel = { offsetX = 0f },
                        onHorizontalDrag = { change, dragAmount ->
                            // Only hijack the gesture once it's clearly
                            // horizontal — vertical drags belong to the list.
                            val horizontal = abs(dragAmount) > 0f
                            if (horizontal || abs(offsetX) > 0f) {
                                change.consume()
                                offsetX = (offsetX + dragAmount).coerceIn(-thresholdPx * 2f, thresholdPx * 2f)
                            }
                        },
                    )
                },
        ) {
            content()
        }
    }
}