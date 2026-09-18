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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
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
private val HighlightBand = 16.dp
private const val AreaFillAlpha = 0.10f

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
    ) {
        ChartRender(
            series = series,
            xScale = xScale,
            yScale = yScale,
            yTicks = yScale.ticks.map { textMeasurer.measure(formatY(it), labelStyle) },
            xTicks = xScale.ticks.map { textMeasurer.measure(formatX(it), labelStyle) },
            highlightIndex = highlightIndex,
            highlightLayout = highlightLabel?.let { textMeasurer.measure(it, labelStyle) },
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
        ScrubberLayer(render, geometry, chartColors, selectedIndex, onSelectedIndexChange)
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

/** A hairline and a dot - cheap enough to redraw on every pointer move. */
@Composable
private fun ScrubberLayer(
    render: ChartRender,
    geometry: ChartGeometry,
    chartColors: ChartColors,
    selectedIndex: Int?,
    onSelectedIndexChange: (Int?) -> Unit,
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
        if (!value.isNaN()) {
            val y = plot.yFor(value, render.yScale)
            // Surface ring first, so the dot stays legible where it sits on the line.
            drawCircle(chartColors.surface, MarkerRadius.toPx() + SurfaceRing.toPx(), Offset(x, y))
            drawCircle(series.color, MarkerRadius.toPx(), Offset(x, y))
        }
    }
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
)

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
