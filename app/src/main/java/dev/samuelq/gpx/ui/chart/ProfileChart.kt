package dev.samuelq.gpx.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.DpSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import dev.samuelq.gpx.ui.PointTooltip
import dev.samuelq.gpx.ui.format.tabularFigures
import dev.samuelq.gpx.ui.theme.ChartColors
import dev.samuelq.gpx.ui.theme.LocalChartColors
import kotlin.math.abs
import kotlin.math.roundToInt

private val LineWidth = 2.dp
private val GridWidth = 1.dp
private val MarkerRadius = 4.dp
private val SurfaceRing = 2.dp
private val LabelGap = 6.dp
private val RightPad = 10.dp
private val AxisBand = 18.dp
private val PlotHeight = 100.dp
private const val AreaFillAlpha = 0.10f
private const val BreakWashAlpha = 0.10f

/**
 * A single-series line chart with an area wash and a scrubber. [xScale] is passed in so
 * stacked charts share one domain and the scrubber points at the same moment in each.
 */
@Composable
fun ProfileChart(
    series: ChartSeries,
    xScale: Scale,
    yScale: Scale,
    formatX: (Float) -> String,
    formatY: (Float) -> String,
    selectedIndex: Int?,
    onSelectedIndexChange: (Int?) -> Unit,
    contentDescription: String,
    /** Labels a data gap by its width in x units; null leaves gaps unlabelled. */
    breakLabel: ((Float) -> String)?,
    /** Tooltip value with its unit; [formatY] labels bare axis ticks. */
    formatValue: (Float) -> String,
    formatPosition: (Float) -> String,
    /** Pinch or pan as (anchor, zoom, pan), anchor and pan as fractions of plot width. */
    onZoom: (Float, Float, Float) -> Unit,
    /** Shared by stacked charts so their plots line up. */
    axisGroup: ChartAxisGroup,
    modifier: Modifier = Modifier,
) {
    val chartColors = LocalChartColors.current
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    val labelStyle = MaterialTheme.typography.labelSmall.tabularFigures().copy(
        color = chartColors.label,
    )

    // Measured in composition: label width sets the plot rect the pointer handler also needs.
    val render = remember(
        series, xScale, yScale, formatX, formatY, labelStyle, breakLabel,
    ) {
        ChartRender(
            series = series,
            xScale = xScale,
            yScale = yScale,
            yTicks = yScale.ticks.map { textMeasurer.measure(formatY(it), labelStyle) },
            xTicks = xScale.ticks.map { textMeasurer.measure(formatX(it), labelStyle) },
            breaks = series.breaks().map { (from, to) ->
                ChartBreak(
                    from = from,
                    to = to,
                    layout = breakLabel?.let { textMeasurer.measure(it(to - from), labelStyle) },
                )
            },
        )
    }

    val ownGutter = render.yTicks.maxOfOrNull { it.size.width }?.toFloat() ?: 0f
    SideEffect { if (ownGutter > axisGroup.gutterPx) axisGroup.gutterPx = ownGutter }
    val gutter = maxOf(ownGutter, axisGroup.gutterPx)
    // Not keyed on render: a pinch makes a new one every frame and would restart the gesture.
    val geometry = remember(gutter, density) {
        with(density) {
            ChartGeometry(
                gutterPx = gutter,
                plotLeft = gutter + LabelGap.toPx(),
                // Room for half a tick label, should one land on the top edge.
                topPad = 8.dp.toPx(),
                bottomBand = AxisBand.toPx(),
                rightPad = RightPad.toPx(),
                labelGap = LabelGap.toPx(),
            )
        }
    }

    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier
            .fillMaxWidth()
            .height(PlotHeight + AxisBand)
            .onSizeChanged { boxSize = it }
            .semantics { this.contentDescription = contentDescription },
    ) {
        StaticLayer(render, geometry, chartColors)
        ScrubberLayer(
            render = render,
            geometry = geometry,
            chartColors = chartColors,
            selectedIndex = selectedIndex,
            onSelectedIndexChange = onSelectedIndexChange,
            onZoom = onZoom,
        )
        ChartTooltip(
            render = render,
            geometry = geometry,
            boxSize = boxSize,
            selectedIndex = selectedIndex,
            style = MaterialTheme.typography.labelSmall.tabularFigures(),
            formatValue = formatValue,
            formatPosition = formatPosition,
        )
    }
}

/** Stable params only, so Compose skips it while the scrubber moves. */
@Composable
private fun StaticLayer(
    render: ChartRender,
    geometry: ChartGeometry,
    chartColors: ChartColors,
) {
    Box(
        Modifier
            .fillMaxSize()
            .drawWithCache {
                val plot = geometry.plotRect(size)
                val linePath = Path()
                val areaPath = Path()
                buildPaths(render.series, render.xScale, render.yScale, plot, linePath, areaPath)

                onDrawBehind {
                    drawGrid(render.yScale, plot, chartColors.grid, GridWidth.toPx())
                    // Bleed above so a peak at the top isn't shaved flat.
                    val bleed = LineWidth.toPx()
                    clipRect(plot.left, plot.top - bleed, plot.right, plot.bottom) {
                        drawBreaks(render, plot, chartColors.label)
                        drawPath(areaPath, render.series.color.copy(alpha = AreaFillAlpha))
                        drawPath(
                            path = linePath,
                            color = render.series.color,
                            style = Stroke(
                                width = LineWidth.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round,
                            ),
                        )
                    }

                    drawLine(
                        color = chartColors.axis,
                        start = Offset(plot.left, plot.bottom),
                        end = Offset(plot.right, plot.bottom),
                        strokeWidth = GridWidth.toPx(),
                    )

                    drawAxisLabels(render, plot, geometry)
                }
            },
    )
}

/** Hairline and dot at the scrubbed position; the text is [ChartTooltip]. */
@Composable
private fun ScrubberLayer(
    render: ChartRender,
    geometry: ChartGeometry,
    chartColors: ChartColors,
    selectedIndex: Int?,
    onSelectedIndexChange: (Int?) -> Unit,
    onZoom: (Float, Float, Float) -> Unit,
) {
    val series = render.series
    val ringColor = MaterialTheme.colorScheme.surfaceContainer
    // Read through state, not keyed on, or each pinch frame would restart the gesture.
    val currentRender by rememberUpdatedState(render)
    val currentOnZoom by rememberUpdatedState(onZoom)

    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(geometry) {
                fun select(x: Float) =
                    onSelectedIndexChange(geometry.indexAt(x, size.toSize(), currentRender))

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var drift = Offset.Zero
                    var scrubbing = false
                    var zooming = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            zooming = true
                            val plot = geometry.plotRect(size.toSize())
                            val scale = event.calculateZoom()
                            val pan = event.calculatePan().x
                            if (scale != 1f || pan != 0f) {
                                val anchor = (event.calculateCentroid().x - plot.left) / plot.width
                                currentOnZoom(anchor, scale, pan / plot.width)
                            }
                            event.changes.forEach { it.consume() }
                        } else if (!zooming) {
                            // After a pinch the finger left behind neither scrubs nor taps.
                            val change = pressed.firstOrNull { it.id == down.id } ?: break
                            if (change.isConsumed) return@awaitEachGesture
                            if (!scrubbing) {
                                drift += change.positionChange()
                                // Leave vertical drags to the page scroll.
                                if (abs(drift.y) > viewConfiguration.touchSlop && abs(drift.y) > abs(drift.x)) {
                                    return@awaitEachGesture
                                }
                                scrubbing = abs(drift.x) > viewConfiguration.touchSlop
                            }
                            if (scrubbing) {
                                change.consume()
                                select(change.position.x)
                            }
                        }
                    }
                    if (!scrubbing && !zooming) select(down.position.x)
                }
            },
    ) {
        val index = selectedIndex ?: return@Canvas
        if (index !in 0 until series.size) return@Canvas

        val plot = geometry.plotRect(size)
        val x = plot.xFor(series.x[index], render.xScale)
        if (x < plot.left - 1f || x > plot.right + 1f) return@Canvas

        drawLine(
            color = chartColors.axis,
            start = Offset(x, plot.top),
            end = Offset(x, plot.bottom),
            strokeWidth = GridWidth.toPx(),
        )

        val value = series.y[index]
        if (value.isNaN()) return@Canvas

        val y = plot.yFor(value, render.yScale)
        // Surface ring keeps the dot legible on the line.
        drawCircle(ringColor, MarkerRadius.toPx() + SurfaceRing.toPx(), Offset(x, y))
        drawCircle(series.color, MarkerRadius.toPx(), Offset(x, y))
    }
}

@Composable
private fun ChartTooltip(
    render: ChartRender,
    geometry: ChartGeometry,
    boxSize: IntSize,
    selectedIndex: Int?,
    style: TextStyle,
    formatValue: (Float) -> String,
    formatPosition: (Float) -> String,
) {
    val series = render.series
    val index = selectedIndex ?: return
    if (index !in 0 until series.size) return
    val value = series.y[index]
    if (value.isNaN()) return
    if (boxSize.width <= 0 || boxSize.height <= 0) return

    val plot = remember(geometry, boxSize) { geometry.plotRect(boxSize.toSize()) }
    val x = plot.xFor(series.x[index], render.xScale)
    if (x < plot.left - 1f || x > plot.right + 1f) return
    val y = plot.yFor(value, render.yScale)

    val density = LocalDensity.current
    val half = with(density) { (MarkerRadius + SurfaceRing).toPx() }
    PointTooltip(
        anchorAt = { IntOffset((x - half).roundToInt(), (y - half).roundToInt()) },
        anchorSize = DpSize((MarkerRadius + SurfaceRing) * 2, (MarkerRadius + SurfaceRing) * 2),
    ) {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = LocalContentColor.current.copy(alpha = 0.7f))) {
                    append(formatPosition(series.x[index]))
                }
                append('\n')
                append(formatValue(value))
            },
            style = style,
        )
    }
}

/** Widest y-axis label gutter across a stack of charts. */
@Stable
class ChartAxisGroup {
    internal var gutterPx by mutableFloatStateOf(0f)
}

@Immutable
private class ChartRender(
    val series: ChartSeries,
    val xScale: Scale,
    val yScale: Scale,
    val yTicks: List<TextLayoutResult>,
    val xTicks: List<TextLayoutResult>,
    val breaks: List<ChartBreak>,
)

@Immutable
private class ChartBreak(val from: Float, val to: Float, val layout: TextLayoutResult?)

/** The x spans between segments (signal loss or long stops). */
private fun ChartSeries.breaks(): List<Pair<Float, Float>> {
    if (segmentStartIndices.size < 2) return emptyList()
    return buildList {
        for (segment in 1 until segmentStartIndices.size) {
            val index = segmentStartIndices[segment]
            if (index <= 0 || index >= size) continue
            val from = x[index - 1]
            val to = x[index]
            if (to > from) add(from to to)
        }
    }
}

@Immutable
private class ChartGeometry(
    val gutterPx: Float,
    val plotLeft: Float,
    val topPad: Float,
    val bottomBand: Float,
    val rightPad: Float,
    val labelGap: Float,
) {
    fun plotRect(size: Size) = Rect(
        left = plotLeft,
        top = topPad,
        right = (size.width - rightPad).coerceAtLeast(plotLeft + 1f),
        bottom = (size.height - bottomBand).coerceAtLeast(topPad + 1f),
    )

    fun indexAt(pointerX: Float, size: Size, render: ChartRender): Int {
        val plot = plotRect(size)
        val target = plot.valueForX(pointerX.coerceIn(plot.left, plot.right), render.xScale)
        return nearestIndex(render.series.x, target)
    }
}

private fun Rect.xFor(value: Float, scale: Scale): Float =
    left + ((value - scale.min) / scale.span) * width

private fun Rect.yFor(value: Float, scale: Scale): Float =
    bottom - ((value - scale.min) / scale.span) * height

private fun Rect.valueForX(px: Float, scale: Scale): Float =
    scale.min + ((px - left) / width) * scale.span

private fun buildPaths(
    series: ChartSeries,
    xScale: Scale,
    yScale: Scale,
    plot: Rect,
    line: Path,
    area: Path,
) {
    val starts = series.segmentStartIndices
    val builder = PolylineBuilder(line, area, plot.bottom)
    // Visible range plus one either side, so the line runs off both edges.
    val first = (nearestIndex(series.x, xScale.min) - 1).coerceAtLeast(0)
    val last = (nearestIndex(series.x, xScale.max) + 2).coerceAtMost(series.size)

    for (segment in starts.indices) {
        val from = maxOf(starts[segment], first)
        val to = minOf(if (segment + 1 < starts.size) starts[segment + 1] else series.size, last)
        for (i in from until to) {
            val value = series.y[i]
            if (value.isNaN()) {
                builder.breakLine()
                continue
            }
            builder.add(plot.xFor(series.x[i], xScale), plot.yFor(value, yScale))
        }
        builder.breakLine()
    }
    builder.finish()
}

/** A wash over each gap, not a drop to zero, which would invent unmeasured decelerations. */
private fun DrawScope.drawBreaks(render: ChartRender, plot: Rect, color: Color) {
    for (gap in render.breaks) {
        val left = plot.xFor(gap.from, render.xScale)
        val right = plot.xFor(gap.to, render.xScale)
        val width = right - left
        // A sub-pixel band would read as a grid line.
        if (width < 1f) continue

        drawRect(
            color = color.copy(alpha = BreakWashAlpha),
            topLeft = Offset(left, plot.top),
            size = Size(width, plot.height),
        )

        val layout = gap.layout ?: continue
        if (layout.size.width + 2 * LabelGap.toPx() > width) continue
        drawText(
            textLayoutResult = layout,
            topLeft = Offset(left + (width - layout.size.width) / 2f, plot.top + 2f),
        )
    }
}

/** A chart's footprint and bare grid with [message] over it, for a series with nothing to plot. */
@Composable
fun EmptyChart(message: String, modifier: Modifier = Modifier) {
    val chartColors = LocalChartColors.current
    Box(
        modifier
            .fillMaxWidth()
            .height(PlotHeight + AxisBand)
            .drawWithCache {
                val width = GridWidth.toPx()
                val plotHeight = PlotHeight.toPx()
                onDrawBehind {
                    for (i in 0..EmptyGridLines) {
                        val y = plotHeight * i / EmptyGridLines
                        drawLine(chartColors.grid, Offset(0f, y), Offset(size.width, y), width)
                    }
                }
            },
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.Center)
                // Masks the grid behind it; the sheet's colour.
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

private const val EmptyGridLines = 4

private fun DrawScope.drawGrid(yScale: Scale, plot: Rect, color: Color, width: Float) {
    for (tick in yScale.ticks) {
        val y = plot.yFor(tick, yScale)
        if (y < plot.top - 1f || y > plot.bottom + 1f) continue
        drawLine(color, Offset(plot.left, y), Offset(plot.right, y), width)
    }
}

private fun DrawScope.drawAxisLabels(render: ChartRender, plot: Rect, geometry: ChartGeometry) {
    render.yScale.ticks.forEachIndexed { index, tick ->
        val layout = render.yTicks.getOrNull(index) ?: return@forEachIndexed
        val y = plot.yFor(tick, render.yScale) - layout.size.height / 2f
        if (y < -layout.size.height || y > plot.bottom) return@forEachIndexed
        drawText(layout, topLeft = Offset(geometry.gutterPx - layout.size.width, y))
    }

    render.xScale.ticks.forEachIndexed { index, tick ->
        val layout = render.xTicks.getOrNull(index) ?: return@forEachIndexed
        val centered = plot.xFor(tick, render.xScale) - layout.size.width / 2f
        // Clamped, not clipped, so end ticks stay readable.
        val x = centered.coerceIn(0f, (plot.right - layout.size.width).coerceAtLeast(0f))
        drawText(layout, topLeft = Offset(x, plot.bottom + geometry.labelGap))
    }
}
