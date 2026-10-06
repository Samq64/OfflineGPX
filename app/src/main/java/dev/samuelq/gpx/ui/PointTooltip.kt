package dev.samuelq.gpx.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * A bubble beside a box that moves: a scrubbed chart point, a tapped map pin. [anchorAt] is
 * the box's top-left in the parent's coordinates. Placed during layout in the app's own
 * window rather than as a popup, which trails a drag by a frame or more. Fills its parent,
 * flips sides at its edges, and may overhang it vertically.
 */
@Composable
fun PointTooltip(
    anchorAt: () -> IntOffset,
    anchorSize: DpSize,
    /** Kept within these rows if it fits, its caret still on the anchor; else it may overhang. */
    within: IntRange? = null,
    content: @Composable () -> Unit,
) {
    // Surface-toned, not Material's inverse: that is a near-white block over a dark map.
    val fill = MaterialTheme.colorScheme.surfaceContainerHigh
    val border = MaterialTheme.colorScheme.outlineVariant
    // Written at placement, read at draw: moving sides redraws the caret without recomposing.
    val caretOnLeft = remember { mutableStateOf(true) }
    val caretY = remember { mutableStateOf<Float?>(null) }

    Layout(
        modifier = Modifier.fillMaxSize(),
        content = {
            Box(
                Modifier
                    .drawWithCache {
                        val path = bubblePath(size, caretOnLeft.value, caretY.value)
                        val stroke = Stroke(BorderWidth.toPx())
                        onDrawBehind {
                            drawPath(path, fill)
                            drawPath(path, border, style = stroke)
                        }
                    }
                    .padding(horizontal = CaretDepth)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                CompositionLocalProvider(
                    LocalContentColor provides MaterialTheme.colorScheme.onSurface,
                    LocalTextStyle provides MaterialTheme.typography.bodySmall,
                    content = content,
                )
            }
        },
    ) { measurables, constraints ->
        val bubble = measurables.single().measure(
            Constraints(maxWidth = minOf(constraints.maxWidth, MaxWidth.roundToPx())),
        )
        layout(constraints.maxWidth, constraints.maxHeight) {
            val at = anchorAt()
            val width = anchorSize.width.roundToPx()
            val height = anchorSize.height.roundToPx()
            val centre = Offset(at.x + width / 2f, at.y + height / 2f)
            // Off the parent is off screen, or under whatever covers it.
            if (centre.x !in 0f..constraints.maxWidth.toFloat() ||
                centre.y !in 0f..constraints.maxHeight.toFloat()
            ) {
                return@layout
            }
            val gap = Gap.roundToPx()
            val right = at.x + width + gap
            val onRight = right + bubble.width <= constraints.maxWidth || constraints.maxWidth - right >= at.x
            caretOnLeft.value = onRight
            val x = if (onRight) right else at.x - gap - bubble.width
            val centred = (centre.y - bubble.height / 2f).toInt()
            val y = within?.takeIf { it.last - it.first >= bubble.height }
                ?.let { centred.coerceIn(it.first, it.last - bubble.height) } ?: centred
            caretY.value = (centre.y - y).takeIf { y != centred }
            bubble.place(x, y)
        }
    }
}

/** One outline, so the border has no seam where the caret meets the bubble. */
/** The caret at [caretY] from the top, kept off the corners; mid-height if null. */
private fun CacheDrawScope.bubblePath(size: Size, caretOnLeft: Boolean, caretY: Float?): Path {
    val depth = CaretDepth.toPx()
    val half = CaretHalfWidth.toPx()
    val inner = Corner.toPx() + half
    val y = caretY?.takeIf { size.height >= 2 * inner }?.coerceIn(inner, size.height - inner) ?: (size.height / 2)
    val bubble = Path().apply {
        addRoundRect(RoundRect(depth, 0f, size.width - depth, size.height, CornerRadius(Corner.toPx())))
    }
    val (base, tip) = if (caretOnLeft) depth to 0f else size.width - depth to size.width
    val caret = Path().apply {
        // Overlapping the bubble a little, so the union leaves no hairline gap.
        val inset = if (caretOnLeft) 1f else -1f
        moveTo(base + inset, y - half)
        lineTo(tip, y)
        lineTo(base + inset, y + half)
        close()
    }
    return Path.combine(PathOperation.Union, bubble, caret)
}

private val Gap = 2.dp
private val Corner = 4.dp
private val BorderWidth = 1.dp
private val CaretDepth = 6.dp
private val CaretHalfWidth = 6.dp
private val MaxWidth = 200.dp
