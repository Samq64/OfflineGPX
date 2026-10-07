package dev.samuelq.gpx.ui.chart

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** Primitive arrays indexed like the owning profile, so scrubbing doesn't allocate. */
@Immutable
class ChartSeries(
    /** Non-decreasing, for the binary search in [nearestIndex]. */
    val x: FloatArray,
    /** `NaN` breaks the line. */
    val y: FloatArray,
    /** Indices where a new polyline starts. */
    val segmentStartIndices: IntArray,
    val color: Color,
) {
    val size: Int get() = x.size
}

@Immutable
class Scale(val min: Float, val max: Float, val ticks: FloatArray, step: Float? = null) {
    val span: Float get() = (max - min).takeIf { it > 0f } ?: 1f

    /** Tick gap; labels coarser than this would print neighbouring ticks the same. */
    val step: Float = step ?: if (ticks.size >= 2) ticks[1] - ticks[0] else span
}

/**
 * Keeps the measured domain but ticks on round values inside it. Values are SI; [perUnit]
 * is display units per SI unit, so ticks round in display units.
 */
fun axisScale(min: Float, max: Float, perUnit: Float = 1f): Scale =
    axisScale(min, max, perUnit) { niceStep(it, TARGET_TICKS) }

/** [axisScale] for elapsed seconds, ticked on steps a clock reads in. */
fun timeAxisScale(min: Float, max: Float): Scale = axisScale(min, max, 1f) { range -> timeStep(range, TARGET_TICKS) }

private const val TARGET_TICKS = 4

private inline fun axisScale(min: Float, max: Float, perUnit: Float, step: (Float) -> Float): Scale {
    if (!min.isFinite() || !max.isFinite() || max <= min) {
        return Scale(min.takeIf { it.isFinite() } ?: 0f, (min + 1f).takeIf { it.isFinite() } ?: 1f, FloatArray(0))
    }
    val lo = min * perUnit
    val hi = max * perUnit
    val gap = step(hi - lo)
    val first = ceil(lo / gap)
    val ticks = buildList {
        var i = 0
        // Epsilon keeps a tick exactly on the max despite float error.
        while ((first + i) * gap <= hi + gap * 1e-3f) {
            add((first + i) * gap / perUnit)
            i++
        }
    }
    return Scale(min, max, ticks.toFloatArray())
}

/**
 * 1-2-5 progression, with 2.5 too if [quarters]. Only from 25 up, where it needs no more
 * decimals than its neighbours.
 */
private fun niceStep(range: Float, targetTicks: Int, quarters: Boolean = false): Float {
    if (range <= 0f || targetTicks <= 0) return 1f
    val rough = range / targetTicks
    val magnitude = 10.0.pow(floor(log10(rough.toDouble()))).toFloat()
    val normalized = rough / magnitude
    val factor = when {
        normalized <= 1f -> 1f
        normalized <= 2f -> 2f
        quarters && magnitude >= 10f && normalized <= 2.5f -> 2.5f
        normalized <= 5f -> 5f
        else -> 10f
    }
    return factor * magnitude
}

/** Seconds, minutes and hours divide by 60 and 24, not 10, so 1-2-5 lands on 0:33:20. */
private val TimeSteps = floatArrayOf(
    1f, 2f, 5f, 10f, 15f, 30f,
    60f, 120f, 300f, 600f, 900f, 1800f,
    3600f, 7200f, 10800f, 21600f, 43200f,
)

private const val SECONDS_PER_DAY = 86_400f

private fun timeStep(range: Float, targetTicks: Int): Float {
    if (range <= 0f || targetTicks <= 0) return 1f
    val rough = range / targetTicks
    // Past half a day, whole days on 1-2-5.
    return TimeSteps.firstOrNull { it >= rough }
        ?: (niceStep(rough / SECONDS_PER_DAY, 1) * SECONDS_PER_DAY)
}

/**
 * Min to max of the recorded values, or 0 to max with [fromZero]. Computed by the caller
 * because axis label precision depends on the step.
 */
fun ChartSeries.yScale(perUnit: Float = 1f, fromZero: Boolean = false): Scale {
    var min = if (fromZero) 0f else Float.POSITIVE_INFINITY
    var max = Float.NEGATIVE_INFINITY
    for (value in y) {
        if (value.isNaN()) continue
        if (value < min) min = value
        if (value > max) max = value
    }
    if (!min.isFinite() || !max.isFinite()) return endTickScale(0f, 1f / perUnit, perUnit)
    if (max <= min) {
        val pad = if (abs(max) > 0f) abs(max) * 0.1f else 1f / perUnit
        // A zero floor stays put.
        if (!fromZero) min -= pad
        max += pad
    }
    return endTickScale(min, max, perUnit)
}

/** Gaps between ticks the series is cut into, about. */
private const val Y_INTERVALS = 4

/** An inner tick nearer an end than this many steps would crowd its label. */
private const val END_CLEARANCE = 0.4f

/**
 * Ticks on [min] and [max] themselves, so the top of the plot is the peak, and on round values
 * in display units between them.
 */
private fun endTickScale(min: Float, max: Float, perUnit: Float): Scale {
    val lo = min * perUnit
    val hi = max * perUnit
    val step = niceStep(hi - lo, Y_INTERVALS, quarters = true)
    val first = ceil(lo / step)
    val inner = buildList {
        var i = 0
        while (true) {
            val value = (first + i++) * step
            if (value > hi - step * END_CLEARANCE) break
            if (value >= lo + step * END_CLEARANCE) add(value / perUnit)
        }
    }
    return Scale(min, max, (listOf(min) + inner + max).toFloatArray(), step / perUnit)
}

/**
 * [view] scaled by [zoom] about [anchor] and shifted by [pan] (fractions of plot width),
 * kept within [domain] and [maxZoom].
 */
fun zoomView(
    view: ClosedFloatingPointRange<Float>,
    domain: ClosedFloatingPointRange<Float>,
    anchor: Float,
    zoom: Float,
    pan: Float,
    maxZoom: Float = 50f,
): ClosedFloatingPointRange<Float> {
    val domainSpan = domain.endInclusive - domain.start
    if (domainSpan <= 0f || zoom <= 0f) return view
    val oldSpan = view.endInclusive - view.start
    val span = (oldSpan / zoom).coerceIn(domainSpan / maxZoom, domainSpan)
    val anchored = view.start + anchor * oldSpan
    val start = (anchored - anchor * span - pan * span)
        .coerceIn(domain.start, domain.endInclusive - span)
    return start..(start + span)
}

/** Closest index in sorted [values]; binary search since it runs on every pointer move. */
fun nearestIndex(values: FloatArray, target: Float): Int {
    if (values.isEmpty()) return -1
    var low = 0
    var high = values.size - 1
    while (low < high) {
        val mid = (low + high) ushr 1
        if (values[mid] < target) low = mid + 1 else high = mid
    }
    if (low > 0 && abs(values[low - 1] - target) <= abs(values[low] - target)) return low - 1
    return low
}

/**
 * Polyline reduced to first, min, max, last per pixel column. Every-nth subsampling would
 * drop the peaks.
 */
class PolylineBuilder(private val line: Path, private val area: Path? = null, private val baselineY: Float = 0f) {
    private var hasColumn = false
    private var lineStarted = false
    private var runStartColumn = 0f
    private var column = 0f
    private var first = 0f
    private var min = 0f
    private var max = 0f
    private var last = 0f

    fun add(xPx: Float, yPx: Float) {
        val col = floor(xPx)
        when {
            !hasColumn -> begin(col, yPx)
            col != column -> {
                flush()
                begin(col, yPx)
            }

            else -> {
                if (yPx < min) min = yPx
                if (yPx > max) max = yPx
                last = yPx
            }
        }
    }

    /** Ends the current polyline; the next [add] starts a detached one. */
    fun breakLine() {
        flush()
        closeArea()
        lineStarted = false
    }

    fun finish() {
        flush()
        closeArea()
    }

    private fun begin(col: Float, y: Float) {
        hasColumn = true
        column = col
        first = y
        min = y
        max = y
        last = y
    }

    private fun flush() {
        if (!hasColumn) return
        if (!lineStarted) {
            line.moveTo(column, first)
            area?.moveTo(column, first)
            runStartColumn = column
            lineStarted = true
        } else {
            line.lineTo(column, first)
            area?.lineTo(column, first)
        }
        if (min != max) {
            line.lineTo(column, min)
            line.lineTo(column, max)
            area?.lineTo(column, min)
            area?.lineTo(column, max)
        }
        line.lineTo(column, last)
        area?.lineTo(column, last)
        hasColumn = false
    }

    private fun closeArea() {
        val fill = area ?: return
        if (!lineStarted) return
        fill.lineTo(column, baselineY)
        fill.lineTo(runStartColumn, baselineY)
        fill.close()
    }
}
