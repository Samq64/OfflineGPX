package dev.samuelq.gpx.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import dev.samuelq.gpx.ui.ArrowTooltip
import dev.samuelq.gpx.ui.format.tabularFigures
import dev.samuelq.gpx.ui.theme.ChartColors
import dev.samuelq.gpx.ui.theme.LocalChartColors
import kotlin.math.abs
import kotlin.math.roundToInt

// Mark specs. Thin marks, hairline chrome, generous air - the data is the only loud thing.
private val LineWidth = 2.dp
private val GridWidth = 1.dp
private val MarkerRadius = 4.dp // an 8dp mark
private val SurfaceRing = 2.dp
private val LabelGap = 6.dp
private val RightPad = 10.dp
private val AxisBand = 18.dp
private val PlotHeight = 100.dp
private const val AreaFillAlpha = 0.10f
private const val BreakWashAlpha = 0.10f

/**
 * A single-series line chart with an area wash and a shared scrubber. No legend: one
 * series, and the section title names it.
 *
 * [xScale] is passed in rather than derived, so every chart shares one domain - which is
 * what makes the scrubber meaningful, the same pixel column being the same moment in both.
 */
@Composable
fun ProfileChart(
    series: ChartSeries,
    xScale: Scale,
    /** From [yScale]. Passed in so the caller can label it to the precision of its step. */
    yScale: Scale,
    formatX: (Float) -> String,
    formatY: (Float) -> String,
    selectedIndex: Int?,
    onSelectedIndexChange: (Int?) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    /**
     * Names a gap in the data, given its width in x units, or null to leave it unnamed. An
     * unexplained hole otherwise reads as a rendering fault rather than a fact about the ride.
     */
    breakLabel: ((Float) -> String)? = null,
    /**
     * The scrubbed value *with* its unit, for the tooltip. Distinct from [formatY], which
     * labels bare axis ticks read in a column, not on their own.
     */
    formatValue: ((Float) -> String)? = null,
    /** The scrubbed position with its unit, shown in the same tooltip ahead of the value. */
    formatPosition: ((Float) -> String)? = null,
    /**
     * A two-finger pinch or pan, as (anchor, zoom, pan) - anchor and pan as fractions of
     * the plot's width, for [zoomView]. Null to leave the chart unzoomable.
     */
    onZoom: ((Float, Float, Float) -> Unit)? = null,
    /** Shared by charts stacked on one x domain, so their plots line up column for column. */
    axisGroup: ChartAxisGroup? = null,
) {
    val chartColors = LocalChartColors.current
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    val labelStyle = MaterialTheme.typography.labelSmall.tabularFigures().copy(
        color = chartColors.label,
    )

    // Labels are measured during composition, not while drawing: their width decides the
    // gutter, the gutter decides the plot rect, and the pointer handler needs that same
    // rect to map a touch back to a sample.
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
    if (axisGroup != null) {
        SideEffect { if (ownGutter > axisGroup.gutterPx) axisGroup.gutterPx = ownGutter }
    }
    val gutter = maxOf(ownGutter, axisGroup?.gutterPx ?: 0f)
    // Keyed on what it's measured from, not on [render]: a pinch makes a new render every
    // frame, and a new geometry would restart the gesture reading it.
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
            style = labelStyle.copy(color = MaterialTheme.colorScheme.onSurface),
            labelColor = chartColors.label,
            formatValue = formatValue,
            formatPosition = formatPosition,
        )
    }
}

/**
 * Everything that depends only on the data. Split out with exclusively stable parameters
 * so Compose skips it while the scrubber moves, rather than rebuilding the path every frame.
 */
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
                    // Zoomed in, the data runs on past both edges. Widened by the line's
                    // own width, so a peak at the top isn't shaved flat.
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

/**
 * A hairline and a dot at the scrubbed position - what it's pointing at is [ChartTooltip]'s
 * business, drawn as a separate composable overlay rather than here, so its text can be a
 * real `Text` instead of a `TextLayoutResult` measured and drawn by hand.
 */
@Composable
private fun ScrubberLayer(
    render: ChartRender,
    geometry: ChartGeometry,
    chartColors: ChartColors,
    selectedIndex: Int?,
    onSelectedIndexChange: (Int?) -> Unit,
    onZoom: ((Float, Float, Float) -> Unit)?,
) {
    val series = render.series
    // Read through state, not keyed on: a pinch changes the scale every frame, and
    // restarting the gesture each time would drop it after the first.
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
                        val zoom = currentOnZoom
                        if (pressed.size >= 2 && zoom != null) {
                            zooming = true
                            val plot = geometry.plotRect(size.toSize())
                            val scale = event.calculateZoom()
                            val pan = event.calculatePan().x
                            if (scale != 1f || pan != 0f) {
                                val anchor = (event.calculateCentroid().x - plot.left) / plot.width
                                zoom(anchor, scale, pan / plot.width)
                            }
                            event.changes.forEach { it.consume() }
                        } else if (!zooming) {
                            // After a pinch the finger left behind neither scrubs nor taps.
                            val change = pressed.firstOrNull { it.id == down.id } ?: break
                            if (change.isConsumed) return@awaitEachGesture
                            if (!scrubbing) {
                                drift += change.positionChange()
                                // Horizontal-only, so dragging across the chart never
                                // steals the vertical scroll of the page it sits on.
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
        // Zoomed away from it: nothing to point at in this view.
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
        // Surface ring first, so the dot stays legible where it sits on the line.
        drawCircle(chartColors.surface, MarkerRadius.toPx() + SurfaceRing.toPx(), Offset(x, y))
        drawCircle(series.color, MarkerRadius.toPx(), Offset(x, y))
    }
}

/**
 * Where you are and what is there, anchored to the scrubbed dot - the same [ArrowTooltip]
 * a tapped waypoint on the map uses, clamped to the plot rect rather than the whole chart
 * so it never covers the y-axis labels in the gutter beside it.
 */
@Composable
private fun ChartTooltip(
    render: ChartRender,
    geometry: ChartGeometry,
    boxSize: IntSize,
    selectedIndex: Int?,
    style: TextStyle,
    labelColor: Color,
    formatValue: ((Float) -> String)?,
    formatPosition: ((Float) -> String)?,
) {
    val series = render.series
    val index = selectedIndex ?: return
    if (index !in 0 until series.size) return
    val value = series.y[index]
    if (value.isNaN()) return
    val format = formatValue ?: return
    if (boxSize.width <= 0 || boxSize.height <= 0) return

    val density = LocalDensity.current
    val plot = remember(geometry, boxSize) { geometry.plotRect(boxSize.toSize()) }

    Box(
        Modifier
            .offset { IntOffset(plot.left.roundToInt(), plot.top.roundToInt()) }
            .size(with(density) { plot.width.toDp() }, with(density) { plot.height.toDp() }),
    ) {
        val x = plot.xFor(series.x[index], render.xScale) - plot.left
        val y = plot.yFor(value, render.yScale) - plot.top
        if (x < -1f || x > plot.width + 1f) return@Box

        ArrowTooltip(
            anchor = { Offset(x, y) },
            gap = MarkerRadius + SurfaceRing + 2.dp,
            modifier = Modifier.matchParentSize(),
        ) {
            Text(
                text = buildAnnotatedString {
                    formatPosition?.let { position ->
                        withStyle(SpanStyle(color = labelColor)) {
                            append(position(series.x[index]))
                        }
                        append('\n')
                    }
                    append(format(value))
                },
                style = style,
            )
        }
    }
}

/** The widest y-axis labels among a stack of charts, which all of them then make room for. */
@Stable
class ChartAxisGroup {
    internal var gutterPx by mutableFloatStateOf(0f)
}

/** Everything derived from the data, measured once. */
@Immutable
private class ChartRender(
    val series: ChartSeries,
    val xScale: Scale,
    val yScale: Scale,
    val yTicks: List<TextLayoutResult>,
    val xTicks: List<TextLayoutResult>,
    val breaks: List<ChartBreak>,
)

/** A stretch of x where the recorder said nothing. */
@Immutable
private class ChartBreak(val from: Float, val to: Float, val layout: TextLayoutResult?)

/**
 * The spans between segments, in x units.
 *
 * A segment boundary means the two points either side of it are not connected - signal
 * loss, or a stop long enough that the analyser cut the track there. The line already
 * stops; this is what lets the chart say why.
 */
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

/** Insets in pixels, resolved once from the measured labels. */
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
    // Only what's in view, plus one either side so the line runs on off both edges.
    val first = (nearestIndex(series.x, xScale.min) - 1).coerceAtLeast(0)
    val last = (nearestIndex(series.x, xScale.max) + 2).coerceAtMost(series.size)

    for (segment in starts.indices) {
        val from = maxOf(starts[segment], first)
        val to = minOf(if (segment + 1 < starts.size) starts[segment + 1] else series.size, last)
        for (i in from until to) {
            val value = series.y[i]
            if (value.isNaN()) {
                // A missing sample is a hole, not a straight line drawn across the hole.
                builder.breakLine()
                continue
            }
            builder.add(plot.xFor(series.x[i], xScale), plot.yFor(value, yScale))
        }
        builder.breakLine()
    }
    builder.finish()
}

/**
 * A wash over each gap, labelled where there's room. Deliberately not a line drawn down to
 * zero and back, which would invent decelerations that were never measured.
 */
private fun DrawScope.drawBreaks(render: ChartRender, plot: Rect, color: Color) {
    for (gap in render.breaks) {
        val left = plot.xFor(gap.from, render.xScale)
        val right = plot.xFor(gap.to, render.xScale)
        val width = right - left
        // Under a pixel it is not a gap the reader can see, and a hairline band there
        // would read as a grid line. On a distance axis every stop lands here.
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

private fun DrawScope.drawGrid(yScale: Scale, plot: Rect, color: Color, width: Float) {
    for (tick in yScale.ticks) {
        val y = plot.yFor(tick, yScale)
        if (y < plot.top - 1f || y > plot.bottom + 1f) continue
        // Solid hairlines. Dashes would read as a threshold or a projection.
        drawLine(color, Offset(plot.left, y), Offset(plot.right, y), width)
    }
}

private fun DrawScope.drawAxisLabels(render: ChartRender, plot: Rect, geometry: ChartGeometry) {
    render.yScale.ticks.forEachIndexed { index, tick ->
        val layout = render.yTicks.getOrNull(index) ?: return@forEachIndexed
        val y = plot.yFor(tick, render.yScale) - layout.size.height / 2f
        if (y < -layout.size.height || y > plot.bottom) return@forEachIndexed
        // Right-aligned against the plot edge, so the digits form a clean column.
        drawText(layout, topLeft = Offset(geometry.gutterPx - layout.size.width, y))
    }

    render.xScale.ticks.forEachIndexed { index, tick ->
        val layout = render.xTicks.getOrNull(index) ?: return@forEachIndexed
        val centered = plot.xFor(tick, render.xScale) - layout.size.width / 2f
        // Clamped rather than clipped: a tick at either end stays fully readable.
        val x = centered.coerceIn(0f, (plot.right - layout.size.width).coerceAtLeast(0f))
        drawText(layout, topLeft = Offset(x, plot.bottom + geometry.labelGap))
    }
}
