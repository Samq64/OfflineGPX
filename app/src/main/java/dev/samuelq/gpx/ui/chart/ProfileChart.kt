package dev.samuelq.gpx.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import dev.samuelq.gpx.R
import dev.samuelq.gpx.ui.PointTooltip
import dev.samuelq.gpx.ui.TooltipSecondary
import dev.samuelq.gpx.ui.format.tabularFigures
import dev.samuelq.gpx.ui.theme.ChartColors
import dev.samuelq.gpx.ui.theme.LocalChartColors
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private val LineWidth = 2.dp
private val GridWidth = 1.dp
private val MarkerRadius = 4.dp

/** Enough to read as cut while the line beneath still shows. */
private const val CutScrimAlpha = 0.75f
private val SurfaceRing = 2.dp
private val LabelGap = 6.dp
private val RightPad = 10.dp
private val AxisBand = 18.dp
private val PlotHeight = 100.dp
private const val AreaFillAlpha = 0.10f
private const val BreakWashAlpha = 0.10f

/** A trim end's grip, outside what's kept, as on a video editor's timeline; and the frame between. */
private val HandleWidth = 14.dp
private val HandleCorner = 6.dp
private val GripLine = DpSize(2.dp, 12.dp)
private val FrameWidth = 1.5.dp

/** How near an end a drag takes it rather than scrubbing. */
private val HandleReach = 24.dp

/** Where a scrub nears a plot edge and scrolls a zoomed chart. */
private val EdgeZone = 32.dp

/** Plot widths per second, with the finger at or past the edge. */
private const val EdgeScrollSpeed = 1f

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
    /** Shows and speaks gaps by their width in x units; null leaves them unlabelled. */
    breakLabel: BreakLabel?,
    /** Tooltip value with its unit; [formatY] labels bare axis ticks. */
    formatValue: (Float) -> String,
    /** Where the point at an index is along the track. */
    formatPosition: (Int) -> String,
    /** A pinch as (anchor, zoom), the anchor a fraction of plot width. */
    onZoom: (Float, Float) -> Unit,
    /** Shows a reset in the corner, and a double tap resets too. */
    zoomed: Boolean,
    /** Scrolls a zoomed view by a fraction of its span, while a scrub holds an edge. */
    onScroll: (Float) -> Unit,
    onResetZoom: () -> Unit,
    /** Shared by stacked charts so their plots line up. */
    axisGroup: ChartAxisGroup,
    modifier: Modifier = Modifier,
    /** While trimming: the points kept, the rest greyed out. */
    keptRange: IntRange? = null,
    /** Makes [keptRange]'s ends draggable, each drag also selecting the end it moves. */
    onKeptRangeChange: ((IntRange) -> Unit)? = null,
) {
    val chartColors = LocalChartColors.current
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    val labelStyle = MaterialTheme.typography.labelSmall.tabularFigures().copy(
        color = chartColors.label,
    )

    // Measured in composition: label width sets the plot rect the pointer handler also needs.
    val render = remember(
        series,
        xScale,
        yScale,
        formatX,
        formatY,
        labelStyle,
        breakLabel,
    ) {
        ChartRender(
            series = series,
            xScale = xScale,
            yScale = yScale,
            yTicks = yScale.ticks.map { textMeasurer.measure(formatY(it), labelStyle) },
            xTicks = xScale.ticks.map { textMeasurer.measure(formatX(it), labelStyle) },
            breaks = series.breaks().map { gap ->
                ChartBreak(
                    from = gap.start,
                    to = gap.endInclusive,
                    layout = breakLabel?.let {
                        textMeasurer.measure(it.shown(gap.endInclusive - gap.start), labelStyle)
                    },
                )
            },
        )
    }

    val ownGutter = render.yTicks.maxOfOrNull { it.size.width }?.toFloat() ?: 0f
    SideEffect { if (ownGutter > axisGroup.gutterPx) axisGroup.gutterPx = ownGutter }
    val gutter = maxOf(ownGutter, axisGroup.gutterPx)
    // Sized to the labels, which grow with the font; a pinch doesn't change their height.
    val xLabelHeight = render.xTicks.maxOfOrNull { it.size.height } ?: 0
    val bandPx = with(density) { maxOf(AxisBand.toPx(), xLabelHeight + LabelGap.toPx()) }
    val plotHeight = PlotHeight * textGrowth(density.fontScale)
    // Inset while trimming, so a grip at either end sits beside the data rather than over it.
    val handles = keptRange != null && onKeptRangeChange != null
    // Not keyed on render: a pinch makes a new one every frame and would restart the gesture.
    val geometry = remember(gutter, density, bandPx, handles) {
        with(density) {
            val inset = if (handles) HandleWidth.toPx() else 0f
            ChartGeometry(
                gutterPx = gutter,
                plotLeft = gutter + LabelGap.toPx() + inset,
                // Room for half a tick label, should one land on the top edge.
                topPad = 8.dp.toPx(),
                bottomBand = bandPx,
                rightPad = maxOf(RightPad.toPx(), inset),
                labelGap = LabelGap.toPx(),
            )
        }
    }

    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    // Adjustable for a screen reader: each step picks the nearest point, as a tap would.
    val range = remember(series) { series.yRange() }
    val described = range?.let {
        stringResource(R.string.chart_summary, contentDescription, formatValue(it.start), formatValue(it.endInclusive))
    } ?: contentDescription
    // Drawn gaps only, so the summary says what the chart shows.
    val breaks = render.breaks
    val gaps = breakLabel?.takeIf { breaks.isNotEmpty() }?.let { label ->
        val total = breaks.sumOf { (it.to - it.from).toDouble() }.toFloat()
        pluralStringResource(R.plurals.chart_gaps, breaks.size, breaks.size, label.spoken(total))
    }
    val summary = gaps?.let { stringResource(R.string.chart_summary_gaps, described, it) } ?: described
    val selectedX = selectedIndex?.let { series.x.getOrNull(it) }
    val state = if (selectedIndex != null && selectedX != null) {
        stringResource(
            R.string.chart_point,
            formatPosition(selectedIndex),
            series.y.getOrNull(selectedIndex)?.let(formatValue) ?: formatValue(Float.NaN),
        )
    } else {
        stringResource(R.string.chart_no_point)
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(plotHeight + with(density) { bandPx.toDp() })
            .onSizeChanged { boxSize = it }
            .semantics {
                this.contentDescription = summary
                stateDescription = state
                if (series.size > 0 && xScale.max > xScale.min) {
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        current = (selectedX ?: xScale.min).coerceIn(xScale.min, xScale.max),
                        range = xScale.min..xScale.max,
                    )
                    setProgress { target ->
                        val index = nearestIndex(series.x, target)
                        if (index >= 0) onSelectedIndexChange(index)
                        index >= 0
                    }
                }
            },
    ) {
        StaticLayer(render, geometry, chartColors)
        keptRange?.let { KeptLayer(render, geometry, it, handles) }
        ScrubberLayer(
            render = render,
            geometry = geometry,
            chartColors = chartColors,
            selectedIndex = selectedIndex,
            onSelectedIndexChange = onSelectedIndexChange,
            keptRange = keptRange,
            onKeptRangeChange = onKeptRangeChange,
            onZoom = onZoom,
            scrollable = zoomed,
            onScroll = onScroll,
            onDoubleTap = onResetZoom,
        )
        ChartTooltip(
            render = render,
            geometry = geometry,
            boxSize = boxSize,
            selectedIndex = selectedIndex,
            formatValue = formatValue,
            formatPosition = formatPosition,
        )
        if (zoomed) {
            IconButton(onClick = onResetZoom, modifier = Modifier.align(Alignment.TopEnd)) {
                Icon(
                    painterResource(R.drawable.ic_fit),
                    contentDescription = stringResource(R.string.chart_zoom_reset),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Stable params only, so Compose skips it while the scrubber moves. */
@Composable
private fun StaticLayer(render: ChartRender, geometry: ChartGeometry, chartColors: ChartColors) {
    Box(
        Modifier
            .fillMaxSize()
            // Its own layer, so redrawing the scrubber above doesn't re-record this.
            .graphicsLayer()
            .drawWithCache {
                val plot = geometry.plotRect(size)
                val linePath = Path()
                val areaPath = Path()
                buildPaths(render.series, render.xScale, render.yScale, plot, linePath, areaPath)
                val stroke = Stroke(width = LineWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                val gapLabels = breakLabelRects(render, plot)
                // Grid lines would strike through gap labels.
                val gridKnockout = Path().apply {
                    gapLabels.forEach { it?.let { rect -> addRect(rect.inflate(LabelGap.toPx() / 2f)) } }
                }

                onDrawBehind {
                    clipPath(gridKnockout, ClipOp.Difference) {
                        drawGrid(render.yScale, plot, chartColors.grid, GridWidth.toPx())
                    }
                    // Bleed above so a peak at the top isn't shaved flat.
                    val bleed = LineWidth.toPx()
                    clipRect(plot.left, plot.top - bleed, plot.right, plot.bottom) {
                        drawBreaks(render, plot, chartColors.label, gapLabels)
                        drawPath(areaPath, render.series.color.copy(alpha = AreaFillAlpha))
                        drawPath(linePath, render.series.color, style = stroke)
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

/** Scrims the plot either side of [kept], as a trim would cut it, with [handles] on its ends. */
@Composable
private fun KeptLayer(render: ChartRender, geometry: ChartGeometry, kept: IntRange, handles: Boolean) {
    val series = render.series
    val scrim = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = CutScrimAlpha)
    val handleColor = MaterialTheme.colorScheme.primary
    val gripColor = MaterialTheme.colorScheme.onPrimary
    Canvas(Modifier.fillMaxSize()) {
        if (series.size == 0) return@Canvas
        val plot = geometry.plotRect(size)
        fun xAt(i: Int) = plot.xFor(series.x[i.coerceIn(0, series.size - 1)], render.xScale)
        val start = xAt(kept.first)
        val end = xAt(kept.last)
        val left = start.coerceIn(plot.left, plot.right)
        val right = end.coerceIn(plot.left, plot.right)
        drawRect(scrim, Offset(plot.left, plot.top), Size(left - plot.left, plot.height))
        drawRect(scrim, Offset(right, plot.top), Size(plot.right - right, plot.height))
        if (!handles) return@Canvas
        val width = HandleWidth.toPx()
        val frame = FrameWidth.toPx()
        // The frame along what's kept, over the plot's edges.
        drawRect(handleColor, Offset(left, plot.top - frame), Size(right - left, frame))
        drawRect(handleColor, Offset(left, plot.bottom), Size(right - left, frame))
        // Not at the plot's edge for an end zoomed out of view: that would look movable there.
        val corner = HandleCorner.toPx()
        val grip = GripLine.toSize()
        for ((x, outward) in listOf(start to -1f, end to 1f)) {
            if (x < plot.left - 0.5f || x > plot.right + 0.5f) continue
            // Outside the end, in the inset at the plot's edges.
            val handleLeft = if (outward < 0) x - width else x
            val rect = Rect(handleLeft, plot.top - frame, handleLeft + width, plot.bottom + frame)
            val radii = CornerRadius(corner)
            drawPath(
                Path().apply {
                    addRoundRect(
                        if (outward < 0) {
                            RoundRect(rect, topLeft = radii, bottomLeft = radii)
                        } else {
                            RoundRect(rect, topRight = radii, bottomRight = radii)
                        },
                    )
                },
                handleColor,
            )
            drawRoundRect(
                color = gripColor,
                topLeft = Offset(rect.center.x - grip.width / 2, rect.center.y - grip.height / 2),
                size = grip,
                cornerRadius = CornerRadius(grip.width / 2),
            )
        }
    }
}

/** Hairline and dot at the scrubbed position; the text is [ChartTooltip]. */
@Composable
private fun ScrubberLayer(
    render: ChartRender,
    geometry: ChartGeometry,
    chartColors: ChartColors,
    selectedIndex: Int?,
    onSelectedIndexChange: (Int?) -> Unit,
    keptRange: IntRange?,
    onKeptRangeChange: ((IntRange) -> Unit)?,
    onZoom: (Float, Float) -> Unit,
    scrollable: Boolean,
    onScroll: (Float) -> Unit,
    onDoubleTap: () -> Unit,
) {
    val series = render.series
    val ringColor = MaterialTheme.colorScheme.surfaceContainer
    // Read through state, not keyed on, or each pinch frame would restart the gesture.
    val currentRender by rememberUpdatedState(render)
    val currentOnZoom by rememberUpdatedState(onZoom)
    val currentScrollable by rememberUpdatedState(scrollable)
    val currentOnScroll by rememberUpdatedState(onScroll)
    val currentOnDoubleTap by rememberUpdatedState(onDoubleTap)
    val currentKept by rememberUpdatedState(keptRange)
    val currentOnKeptChange by rememberUpdatedState(onKeptRangeChange)

    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(geometry) {
                fun select(x: Float) = onSelectedIndexChange(geometry.indexAt(x, size.toSize(), currentRender))

                // The trim end a drag holds, if it started on one.
                var handle: TrimEnd? = null

                // Moves the held end, keeping two points, or scrubs.
                fun drag(x: Float) {
                    val end = handle
                    val kept = currentKept
                    val onKept = currentOnKeptChange
                    if (end == null || kept == null || onKept == null) return select(x)
                    val index = geometry.indexAt(x, size.toSize(), currentRender)
                    val moved = when (end) {
                        TrimEnd.Start -> index.coerceAtMost(kept.last - 1)..kept.last
                        TrimEnd.End -> kept.first..index.coerceAtLeast(kept.first + 1)
                    }
                    onKept(moved)
                    onSelectedIndexChange(if (end == TrimEnd.Start) moved.first else moved.last)
                }

                // The nearer end within reach of [x], when one shows.
                fun handleAt(x: Float): TrimEnd? {
                    val kept = currentKept ?: return null
                    if (currentOnKeptChange == null) return null
                    val render = currentRender
                    val series = render.series
                    if (series.size == 0) return null
                    val plot = geometry.plotRect(size.toSize())
                    val reach = HandleReach.toPx()
                    fun distance(i: Int): Float {
                        val at = plot.xFor(series.x[i.coerceIn(0, series.size - 1)], render.xScale)
                        return if (at < plot.left - 0.5f || at > plot.right + 0.5f) Float.MAX_VALUE else abs(at - x)
                    }
                    val start = distance(kept.first)
                    val end = distance(kept.last)
                    return when {
                        minOf(start, end) > reach -> null
                        // Ends drawn together: past the end, only the end can go; before it, the start.
                        start == end -> {
                            val at = plot.xFor(series.x[kept.last], render.xScale)
                            if (x > at) TrimEnd.End else TrimEnd.Start
                        }
                        start < end -> TrimEnd.Start
                        else -> TrimEnd.End
                    }
                }

                // The first tap still selects at once, so a single tap never waits for a second.
                var lastTapAt = Long.MIN_VALUE / 2
                var lastTapAtX = 0f
                val edgeZone = EdgeZone.toPx()

                coroutineScope {
                    // Scrolls each frame the scrub holds an edge, even with the finger still.
                    var scrubX = 0f
                    var autoscroll: Job? = null
                    fun startAutoscroll() = launch {
                        var last = withFrameNanos { it }
                        while (true) {
                            val now = withFrameNanos { it }
                            val plot = geometry.plotRect(size.toSize())
                            val speed = edgeScroll(scrubX, plot.left, plot.right, edgeZone)
                            if (speed != 0f && currentScrollable) {
                                currentOnScroll(speed * EdgeScrollSpeed * (now - last) / 1e9f)
                                drag(scrubX)
                            }
                            last = now
                        }
                    }

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        handle = handleAt(down.position.x)
                        var drift = Offset.Zero
                        var scrubbing = false
                        var zooming = false
                        var lift: List<PointerInputChange> = emptyList()
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) {
                                    lift = event.changes
                                    break
                                }
                                if (pressed.size >= 2) {
                                    zooming = true
                                    autoscroll?.cancel()
                                    autoscroll = null
                                    val plot = geometry.plotRect(size.toSize())
                                    val scale = event.calculateZoom()
                                    if (scale != 1f) {
                                        val anchor = (event.calculateCentroid().x - plot.left) / plot.width
                                        currentOnZoom(anchor, scale)
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
                                        scrubX = change.position.x
                                        drag(scrubX)
                                        if (autoscroll == null) autoscroll = startAutoscroll()
                                    }
                                }
                            }
                        } finally {
                            autoscroll?.cancel()
                            autoscroll = null
                        }
                        if (!scrubbing && !zooming) {
                            // Taken, or the sheet's tap-to-clear would undo it.
                            lift.forEach { it.consume() }
                            val second = down.uptimeMillis - lastTapAt <= viewConfiguration.doubleTapTimeoutMillis &&
                                abs(down.position.x - lastTapAtX) <= viewConfiguration.touchSlop * 2
                            if (second) {
                                lastTapAt = Long.MIN_VALUE / 2
                                currentOnDoubleTap()
                            } else {
                                lastTapAt = down.uptimeMillis
                                lastTapAtX = down.position.x
                                select(down.position.x)
                            }
                        }
                    }
                }
            },
    ) {
        val plot = geometry.plotRect(size)
        val point = render.scrubberAt(selectedIndex, plot) ?: return@Canvas

        drawLine(
            color = chartColors.axis,
            start = Offset(point.x, plot.top),
            end = Offset(point.x, plot.bottom),
            strokeWidth = GridWidth.toPx(),
        )
        if (point.y.isNaN()) return@Canvas

        // Surface ring keeps the dot legible on the line.
        drawCircle(ringColor, MarkerRadius.toPx() + SurfaceRing.toPx(), point)
        drawCircle(series.color, MarkerRadius.toPx(), point)
    }
}

@Composable
private fun ChartTooltip(
    render: ChartRender,
    geometry: ChartGeometry,
    boxSize: IntSize,
    selectedIndex: Int?,
    formatValue: (Float) -> String,
    formatPosition: (Int) -> String,
) {
    val style = MaterialTheme.typography.labelSmall.tabularFigures()
    val series = render.series
    val index = selectedIndex ?: return
    if (boxSize.width <= 0 || boxSize.height <= 0) return

    val plot = geometry.plotRect(boxSize.toSize())
    val (x, y) = render.scrubberAt(index, plot)?.takeUnless { it.y.isNaN() } ?: return
    val value = series.y[index]

    val density = LocalDensity.current
    val half = with(density) { (MarkerRadius + SurfaceRing).toPx() }
    PointTooltip(
        anchorAt = { IntOffset((x - half).roundToInt(), (y - half).roundToInt()) },
        anchorSize = DpSize((MarkerRadius + SurfaceRing) * 2, (MarkerRadius + SurfaceRing) * 2),
        // Off the x-axis labels, which it would cover near the bottom.
        within = plot.top.roundToInt()..plot.bottom.roundToInt(),
    ) {
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = TooltipSecondary)) {
                    append(formatPosition(index))
                }
                append('\n')
                append(formatValue(value))
            },
            style = style,
        )
    }
}

/** A gap's width in x units, as drawn in it and as spoken in the chart's summary. */
@Immutable
class BreakLabel(val shown: (Float) -> String, val spoken: (Float) -> String)

/** Widest y-axis label gutter across a stack of charts. */
@Stable
class ChartAxisGroup {
    internal var gutterPx by mutableFloatStateOf(0f)
}

private enum class TrimEnd { Start, End }

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

/** Where the scrubber at [index] meets the line, null outside the plot; y is NaN in a gap. */
private fun ChartRender.scrubberAt(index: Int?, plot: Rect): Offset? = series.pointAt(index, xScale, yScale, plot)

/** A wash over each gap, not a drop to zero, which would invent unmeasured decelerations. */
private fun DrawScope.drawBreaks(render: ChartRender, plot: Rect, color: Color, labels: List<Rect?>) {
    render.breaks.forEachIndexed { index, gap ->
        val left = plot.xFor(gap.from, render.xScale)
        val right = plot.xFor(gap.to, render.xScale)
        // A sub-pixel band would read as a grid line.
        if (right - left < 1f) return@forEachIndexed

        drawRect(
            color = color.copy(alpha = BreakWashAlpha),
            topLeft = Offset(left, plot.top),
            size = Size(right - left, plot.height),
        )
        val layout = gap.layout ?: return@forEachIndexed
        labels[index]?.let { drawText(layout, topLeft = it.topLeft) }
    }
}

/** Each gap's label centred in its wash, or null where it has none or wouldn't fit. */
private fun Density.breakLabelRects(render: ChartRender, plot: Rect): List<Rect?> = render.breaks.map { gap ->
    val layout = gap.layout ?: return@map null
    val left = plot.xFor(gap.from, render.xScale)
    val right = plot.xFor(gap.to, render.xScale)
    centredLabel(left, right, plot.center.y, layout.size.toSize(), LabelGap.toPx())
}

/** A chart's footprint and bare grid with [message] over it, for a series with nothing to plot. */
@Composable
fun EmptyChart(message: String, modifier: Modifier = Modifier) {
    val chartColors = LocalChartColors.current
    val growth = textGrowth(LocalDensity.current.fontScale)
    Box(
        modifier
            .fillMaxWidth()
            .height((PlotHeight + AxisBand) * growth)
            .drawWithCache {
                val width = GridWidth.toPx()
                val plotHeight = (PlotHeight * growth).toPx()
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

/** How much taller a plot gets for large text, so its tick labels don't crowd. */
private fun textGrowth(fontScale: Float) = fontScale.coerceIn(1f, 2f)

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
