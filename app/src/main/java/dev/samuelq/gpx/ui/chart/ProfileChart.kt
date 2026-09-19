package dev.samuelq.gpx.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import dev.samuelq.gpx.ui.format.tabularFigures
import dev.samuelq.gpx.ui.theme.ChartColors
import dev.samuelq.gpx.ui.theme.LocalChartColors

// Mark specs. Thin marks, hairline chrome, generous air - the data is the only loud thing.
private val LineWidth = 2.dp
private val GridWidth = 1.dp
private val MarkerRadius = 4.dp // an 8dp mark
private val SurfaceRing = 2.dp
private val LabelGap = 6.dp
private val RightPad = 10.dp
private val AxisBand = 18.dp
private val LabelPad = 3.dp
private val HighlightBand = 16.dp
private const val AreaFillAlpha = 0.10f
private const val BreakWashAlpha = 0.10f

/**
 * A single-series line chart with an area wash and a shared scrubber. No legend: one
 * series, and the section title already names it.
 *
 * [xScale] is passed in rather than derived, so every chart shares one domain. That is
 * what makes the scrubber meaningful - the same pixel column is the same moment in both
 * charts. The height covers the plot *and* the axis band, so ticks are never clipped.
 */
@Composable
fun ProfileChart(
    series: ChartSeries,
    xScale: Scale,
    formatX: (Float) -> String,
    formatY: (Float) -> String,
    selectedIndex: Int?,
    onSelectedIndexChange: (Int?) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    /** Index of the one extreme worth direct-labelling, or -1. */
    highlightIndex: Int = -1,
    highlightLabel: String? = null,
    /**
     * Names a gap in the data, given its width in x units, or null to leave it unnamed.
     *
     * A break in the line is the honest thing to draw - nothing was recorded, so nothing
     * is drawn - but an unexplained hole reads as a rendering fault rather than as a fact
     * about the ride. On a distance axis a stop is zero wide and there is nothing to say;
     * on a time axis it is ten minutes of empty chart and needs a word.
     */
    breakLabel: ((Float) -> String)? = null,
    /**
     * The scrubbed value *with* its unit, for the tooltip. Distinct from [formatY], which
     * labels bare axis ticks - a tick reads in the column of ticks above and below it, a
     * tooltip is read on its own and has to say what it is.
     */
    formatValue: ((Float) -> String)? = null,
    /**
     * The scrubbed position with its unit. Shown in the same tooltip as the value, ahead
     * of it and in the label colour: where you are is the context for what is there, and
     * a reader following a line should not have to look down at the axis to have both.
     */
    formatPosition: ((Float) -> String)? = null,
    plotHeight: Dp = 164.dp,
) {
    val chartColors = LocalChartColors.current
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    val labelStyle = MaterialTheme.typography.labelSmall.tabularFigures().copy(
        color = chartColors.label,
    )

    val yScale = remember(series) { series.yScale() }

    // Labels are measured during composition, not while drawing: their width decides the
    // gutter, the gutter decides the plot rect, and the pointer handler needs that same
    // rect to map a touch back to a sample.
    val render = remember(
        series, xScale, yScale, formatX, formatY, labelStyle, highlightIndex, highlightLabel,
        breakLabel,
    ) {
        ChartRender(
            series = series,
            xScale = xScale,
            yScale = yScale,
            yTicks = yScale.ticks.map { textMeasurer.measure(formatY(it), labelStyle) },
            xTicks = xScale.ticks.map { textMeasurer.measure(formatX(it), labelStyle) },
            highlightIndex = highlightIndex,
            highlightLayout = highlightLabel?.let { textMeasurer.measure(it, labelStyle) },
            breaks = series.breaks().map { (from, to) ->
                ChartBreak(
                    from = from,
                    to = to,
                    layout = breakLabel?.let { textMeasurer.measure(it(to - from), labelStyle) },
                )
            },
        )
    }

    val geometry = remember(render, density) {
        val gutter = render.yTicks.maxOfOrNull { it.size.width }?.toFloat() ?: 0f
        with(density) {
            ChartGeometry(
                gutterPx = gutter,
                plotLeft = gutter + LabelGap.toPx(),
                topPad = if (render.highlightLayout != null) HighlightBand.toPx() else 4.dp.toPx(),
                bottomBand = AxisBand.toPx(),
                rightPad = RightPad.toPx(),
                labelGap = LabelGap.toPx(),
            )
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(plotHeight + AxisBand)
            .semantics { this.contentDescription = contentDescription },
    ) {
        StaticLayer(render, geometry, chartColors)
        ScrubberLayer(
            render = render,
            geometry = geometry,
            chartColors = chartColors,
            selectedIndex = selectedIndex,
            onSelectedIndexChange = onSelectedIndexChange,
            measurer = textMeasurer,
            style = labelStyle.copy(color = MaterialTheme.colorScheme.onSurface),
            formatValue = formatValue,
            formatPosition = formatPosition,
        )
    }
}

/**
 * Everything that depends only on the data.
 *
 * Split into its own composable with exclusively stable parameters so that Compose skips
 * it while the scrubber moves. Without that boundary, each frame of a drag would recreate
 * the `drawWithCache` lambda and rebuild a path of tens of thousands of points.
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

                    drawLine(
                        color = chartColors.axis,
                        start = Offset(plot.left, plot.bottom),
                        end = Offset(plot.right, plot.bottom),
                        strokeWidth = GridWidth.toPx(),
                    )

                    drawAxisLabels(render, plot, geometry)

                    if (render.highlightIndex in 0 until render.series.size && render.highlightLayout != null) {
                        drawHighlight(
                            render = render,
                            plot = plot,
                            surface = chartColors.surface,
                            markerRadius = MarkerRadius.toPx(),
                            ring = SurfaceRing.toPx(),
                        )
                    }
                }
            },
    )
}

/**
 * A hairline, a dot, and what they are pointing at - cheap enough to redraw on every
 * pointer move.
 *
 * The readings used to live in a row at the top of the sheet, which meant reading a value
 * off a chart involved looking somewhere else entirely, and put the number for the chart
 * you were *not* touching next to the one you were. On the chart, beside the mark, there
 * is no question which series a figure belongs to.
 */
@Composable
private fun ScrubberLayer(
    render: ChartRender,
    geometry: ChartGeometry,
    chartColors: ChartColors,
    selectedIndex: Int?,
    onSelectedIndexChange: (Int?) -> Unit,
    measurer: TextMeasurer,
    style: TextStyle,
    formatValue: ((Float) -> String)?,
    formatPosition: ((Float) -> String)?,
) {
    val series = render.series

    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(render, geometry) {
                // Horizontal-only, so dragging across the chart never steals the vertical
                // scroll of the page it sits on.
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        onSelectedIndexChange(geometry.indexAt(offset.x, size.toSize(), render))
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        onSelectedIndexChange(geometry.indexAt(change.position.x, size.toSize(), render))
                    },
                )
            }
            .pointerInput(render, geometry) {
                detectTapGestures { offset ->
                    onSelectedIndexChange(geometry.indexAt(offset.x, size.toSize(), render))
                }
            },
    ) {
        val index = selectedIndex ?: return@Canvas
        if (index !in 0 until series.size) return@Canvas

        val plot = geometry.plotRect(size)
        val x = plot.xFor(series.x[index], render.xScale)

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

        formatValue?.let { format ->
            drawTooltip(
                text = buildAnnotatedString {
                    formatPosition?.let { position ->
                        withStyle(SpanStyle(color = chartColors.label)) {
                            append(position(series.x[index]))
                        }
                        append('\n')
                    }
                    append(format(value))
                },
                at = Offset(x, y),
                plot = plot,
                measurer = measurer,
                style = style,
                fill = chartColors.surface,
                border = chartColors.axis,
            )
        }
    }
}

/**
 * Where you are and what is there, boxed above the mark and flipped below it rather than
 * allowed off the top.
 *
 * One box rather than two readings in two places: the position used to sit in the axis
 * band under the crosshair, which is where an x label belongs but is not where the eye is
 * when it is following a line. Stacked rather than run together: two figures on one line
 * have to be read apart before either can be read, and the box is narrow enough at this
 * width to stay out of the way. Opaque, because it sits over the line it is describing and
 * a wash would leave the digits competing with a stroke running through them.
 */
private fun DrawScope.drawTooltip(
    text: AnnotatedString,
    at: Offset,
    plot: Rect,
    measurer: TextMeasurer,
    style: TextStyle,
    fill: Color,
    border: Color,
) {
    val layout = measurer.measure(text, style)
    val padX = LabelPad.toPx() * 2
    val padY = LabelPad.toPx()
    val width = layout.size.width + 2 * padX
    val height = layout.size.height + 2 * padY
    val corner = CornerRadius(4.dp.toPx())

    val left = (at.x - width / 2f)
        .coerceIn(plot.left, (plot.right - width).coerceAtLeast(plot.left))
    val clear = MarkerRadius.toPx() + SurfaceRing.toPx() + LabelPad.toPx()
    val above = at.y - clear - height
    val top = if (above >= plot.top) above else at.y + clear

    drawRoundRect(fill, Offset(left, top), Size(width, height), corner)
    drawRoundRect(
        color = border,
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = corner,
        style = Stroke(GridWidth.toPx()),
    )
    drawText(layout, topLeft = Offset(left + padX, top + padY))
}

/** Everything derived from the data, measured once. */
@Immutable
private class ChartRender(
    val series: ChartSeries,
    val xScale: Scale,
    val yScale: Scale,
    val yTicks: List<TextLayoutResult>,
    val xTicks: List<TextLayoutResult>,
    val highlightIndex: Int,
    val highlightLayout: TextLayoutResult?,
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

private fun ChartSeries.yScale(): Scale {
    var min = Float.POSITIVE_INFINITY
    var max = Float.NEGATIVE_INFINITY
    for (value in y) {
        if (value.isNaN()) continue
        if (value < min) min = value
        if (value > max) max = value
    }
    if (!min.isFinite() || !max.isFinite()) return Scale(0f, 1f, floatArrayOf(0f, 1f))
    return niceScale(min, max, zeroBased)
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

    for (segment in starts.indices) {
        val from = starts[segment]
        val to = if (segment + 1 < starts.size) starts[segment + 1] else series.size
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
 * A wash over each gap, labelled where there is room for it.
 *
 * Deliberately not a line drawn down to zero and back: that would invent two decelerations
 * and put timings on them that were never measured. The band says the one true thing -
 * there is no data here - and the label says how much of it there is not.
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

/**
 * Direct-labels exactly one point, the extreme. Labels work because they are sparing; a
 * number beside every point of a 30k-point line is noise, and the scrubber reaches the
 * rest. The label wears a text token rather than the series colour - the mark beside it
 * carries the identity, and a light hue is illegible as type.
 */
private fun DrawScope.drawHighlight(
    render: ChartRender,
    plot: Rect,
    surface: Color,
    markerRadius: Float,
    ring: Float,
) {
    val series = render.series
    val index = render.highlightIndex
    val layout = render.highlightLayout ?: return
    val value = series.y[index]
    if (value.isNaN()) return

    val x = plot.xFor(series.x[index], render.xScale)
    val y = plot.yFor(value, render.yScale)

    drawCircle(surface, markerRadius + ring, Offset(x, y))
    drawCircle(series.color, markerRadius, Offset(x, y))

    val labelX = (x - layout.size.width / 2f)
        .coerceIn(0f, (plot.right - layout.size.width).coerceAtLeast(0f))
    val above = y - markerRadius - ring - layout.size.height - 2f
    // Flip below the marker rather than let the label run off the top of the plot.
    val labelY = if (above >= 0f) above else y + markerRadius + ring + 2f

    drawText(layout, topLeft = Offset(labelX, labelY))
}
