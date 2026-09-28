package dev.samuelq.gpx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TooltipDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * Material's plain tooltip, beside a box that moves: a scrubbed chart point, a tapped map
 * pin. [anchorAt] is the box's top-left in the parent's coordinates. Placed during layout
 * in the app's own window rather than as a popup, which trails a drag by a frame or more.
 * Fills its parent, flips sides at its edges, and may overhang it vertically.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PointTooltip(
    anchorAt: () -> IntOffset,
    anchorSize: DpSize,
    content: @Composable () -> Unit,
) {
    val color = TooltipDefaults.plainTooltipContainerColor
    // Written at placement, read at draw: moving sides redraws the caret without recomposing.
    val caretOnLeft = remember { mutableStateOf(true) }

    Layout(
        modifier = Modifier.fillMaxSize(),
        content = {
            Box(
                Modifier
                    .drawBehind {
                        val depth = CaretDepth.toPx()
                        val half = CaretHalfWidth.toPx()
                        val y = size.height / 2
                        val (base, tip) = if (caretOnLeft.value) depth to 0f else size.width - depth to size.width
                        drawPath(
                            Path().apply {
                                moveTo(base, y - half)
                                lineTo(tip, y)
                                lineTo(base, y + half)
                                close()
                            },
                            color,
                        )
                    }
                    .padding(horizontal = CaretDepth)
                    .background(color, TooltipDefaults.plainTooltipContainerShape)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                CompositionLocalProvider(
                    LocalContentColor provides TooltipDefaults.plainTooltipContentColor,
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
            bubble.place(x, (centre.y - bubble.height / 2f).toInt())
        }
    }
}

private val Gap = 2.dp
private val CaretDepth = 6.dp
private val CaretHalfWidth = 6.dp
private val MaxWidth = 200.dp
