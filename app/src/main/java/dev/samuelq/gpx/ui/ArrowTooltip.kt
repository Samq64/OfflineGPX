package dev.samuelq.gpx.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

/** Which edge of the bubble the arrow sits on - the side nearer whatever it points at. */
enum class TooltipArrow { Left, Right }

/**
 * A rounded speech-bubble with a small triangular arrow on one edge, pointing at [anchor] -
 * a scrubbed position on a chart, a tapped marker on the map. One shape shared by both, so
 * a note about a moment always reads as the same kind of thing wherever it shows up.
 *
 * Beside [anchor] rather than above it, so it never covers the thing it points at or a
 * chart's vertical scrub line: to the right when there's room, the left otherwise. Centred
 * on it vertically until that would run off an edge or rise more than [maxRise] above it,
 * then the bubble slides and the arrow moves within it to keep pointing at the same spot.
 * The arrow's tip stops [gap] short of [anchor], clear of whatever marker is drawn there.
 *
 * Needs bounded constraints from [modifier] (typically `Modifier.fillMaxSize()` or a fixed
 * `size()`), since that's what "off the edge" is measured against.
 */
@Composable
fun ArrowTooltip(
    // Read during layout, so a moving anchor - a panned map - re-places without recomposing.
    anchor: () -> Offset,
    modifier: Modifier = Modifier,
    gap: Dp = 0.dp,
    /** How far the bubble may extend above [anchor], for a marker that stands above it. */
    maxRise: Dp = Dp.Infinity,
    fill: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    border: Color = MaterialTheme.colorScheme.outlineVariant,
    content: @Composable () -> Unit,
) {
    // Written during placement, read during the child's own draw - a redraw, not a
    // recomposition, so dragging a chart's scrubber doesn't recompose the tooltip every
    // pixel of the drag, only repaint the one thing that actually depends on where it landed.
    val arrowSide = remember { mutableStateOf(TooltipArrow.Left) }
    val arrowTipY = remember { mutableFloatStateOf(0f) }

    Layout(
        modifier = modifier,
        content = {
            Box(
                Modifier
                    // Drawn over the arrow's room on both edges, so the arrow's tip is the
                    // placeable's own edge.
                    .drawBehind {
                        val bubble = Rect(ArrowHeight.toPx(), 0f, size.width - ArrowHeight.toPx(), size.height)
                        val path = tooltipPath(
                            bubble = bubble,
                            cornerRadius = CornerRadius.toPx(),
                            arrowSide = arrowSide.value,
                            arrowTipY = arrowTipY.floatValue,
                            halfArrowWidth = ArrowHalfWidth.toPx(),
                            arrowHeight = ArrowHeight.toPx(),
                        )
                        drawPath(path, fill)
                        drawPath(path, border, style = Stroke(BorderWidth.toPx()))
                    }
                    .padding(horizontal = ArrowHeight)
                    .padding(horizontal = ContentPaddingHorizontal, vertical = ContentPaddingVertical),
            ) { content() }
        },
    ) { measurables, constraints ->
        val placeable = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val anchor = anchor()
        val gapPx = gap.toPx()

        val rightX = anchor.x + gapPx
        val leftX = anchor.x - gapPx - placeable.width
        // The left only when the right doesn't fit and the left has more room.
        val side = if (rightX + placeable.width <= constraints.maxWidth ||
            constraints.maxWidth - rightX >= anchor.x
        ) {
            TooltipArrow.Left
        } else {
            TooltipArrow.Right
        }
        val x = if (side == TooltipArrow.Left) rightX else leftX

        val cornerPx = CornerRadius.toPx()
        val halfArrowPx = ArrowHalfWidth.toPx()
        // Never less than the arrow needs above it to still point at the anchor.
        val risePx = if (maxRise == Dp.Infinity) Float.MAX_VALUE else maxOf(maxRise.toPx(), cornerPx + halfArrowPx)
        val y = maxOf(anchor.y - placeable.height / 2f, anchor.y - risePx)
            .coerceIn(0f, (constraints.maxHeight - placeable.height).coerceAtLeast(0).toFloat())

        arrowSide.value = side
        arrowTipY.floatValue = if (placeable.height > 2 * (cornerPx + halfArrowPx)) {
            (anchor.y - y).coerceIn(cornerPx + halfArrowPx, placeable.height - cornerPx - halfArrowPx)
        } else {
            placeable.height / 2f
        }

        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.place(x.toInt(), y.toInt())
        }
    }
}

/**
 * A rounded rectangle with a triangular bump on one edge, as one continuous outline rather
 * than a rectangle and a triangle overlaid - so a stroked border reads as a single bubble
 * with a tail, not two shapes with a seam where they meet.
 */
private fun tooltipPath(
    bubble: Rect,
    cornerRadius: Float,
    arrowSide: TooltipArrow,
    arrowTipY: Float,
    halfArrowWidth: Float,
    arrowHeight: Float,
): Path {
    val r = min(cornerRadius, min(bubble.width, bubble.height) / 2f)
    val left = bubble.left
    val top = bubble.top
    val right = bubble.right
    val bottom = bubble.bottom
    val tipY = arrowTipY.coerceIn(top + r + halfArrowWidth, bottom - r - halfArrowWidth)

    return Path().apply {
        moveTo(left + r, top)
        lineTo(right - r, top)
        arcTo(Rect(right - 2 * r, top, right, top + 2 * r), -90f, 90f, false)
        if (arrowSide == TooltipArrow.Right) {
            lineTo(right, tipY - halfArrowWidth)
            lineTo(right + arrowHeight, tipY)
            lineTo(right, tipY + halfArrowWidth)
        }
        lineTo(right, bottom - r)
        arcTo(Rect(right - 2 * r, bottom - 2 * r, right, bottom), 0f, 90f, false)
        lineTo(left + r, bottom)
        arcTo(Rect(left, bottom - 2 * r, left + 2 * r, bottom), 90f, 90f, false)
        if (arrowSide == TooltipArrow.Left) {
            lineTo(left, tipY + halfArrowWidth)
            lineTo(left - arrowHeight, tipY)
            lineTo(left, tipY - halfArrowWidth)
        }
        lineTo(left, top + r)
        arcTo(Rect(left, top, left + 2 * r, top + 2 * r), 180f, 90f, false)
        close()
    }
}

private val ArrowHeight = 6.dp
private val ArrowHalfWidth = 6.dp
private val CornerRadius = 6.dp
private val BorderWidth = 1.dp
private val ContentPaddingHorizontal = 10.dp
private val ContentPaddingVertical = 6.dp
