package dev.samuelq.gpx.ui.chart

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * One line's worth of data.
 *
 * Primitive arrays, shared by index with the owning profile, so that a scrub can go from
 * a pixel to a value without allocating.
 */
@Immutable
class ChartSeries(
    /** Monotonically non-decreasing - required for the nearest-point binary search. */
    val x: FloatArray,
    /** `NaN` breaks the line: no value was recorded at this index. */
    val y: FloatArray,
    /** Indices where a new polyline starts. The line is never drawn across these. */
    val segmentStartIndices: IntArray,
    val color: Color,
) {
    val size: Int get() = x.size
}

/** An inclusive value range plus the tick positions to label it with. */
@Immutable
class Scale(val min: Float, val max: Float, val ticks: FloatArray) {
    val span: Float get() = (max - min).takeIf { it > 0f } ?: 1f

    /**
     * The gap between ticks, which is what decides how precisely they have to be labelled:
     * a formatter coarser than the step prints two neighbouring ticks the same.
     */
    val step: Float get() = if (ticks.size >= 2) ticks[1] - ticks[0] else span
}

/**
 * Keeps the domain exactly as measured but puts the ticks on round values inside it, so
 * the data's extremes are the plot's edges rather than a stretch of empty plot.
 *
 * Values are SI; [perUnit] is display units per SI unit. Ticks are rounded in display
 * units, since a round number of m/s is not a round number of km/h.
 */
fun axisScale(min: Float, max: Float, targetTicks: Int = 4, perUnit: Float = 1f): Scale =
    axisScale(min, max, perUnit) { niceStep(it, targetTicks) }

/** [axisScale] for elapsed seconds, ticked on steps a clock reads in. */
fun timeAxisScale(min: Float, max: Float, targetTicks: Int = 4): Scale =
    axisScale(min, max, 1f) { range -> timeStep(range, targetTicks) }

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
        // The epsilon keeps a tick that lands exactly on the maximum from being dropped
        // by float error.
        while ((first + i) * gap <= hi + gap * 1e-3f) {
            add((first + i) * gap / perUnit)
            i++
        }
    }
    return Scale(min, max, ticks.toFloatArray())
}

/** The classic 1-2-5 progression: the only step sizes that produce round labels. */
private fun niceStep(range: Float, targetTicks: Int): Float {
    if (range <= 0f || targetTicks <= 0) return 1f
    val rough = range / targetTicks
    val magnitude = 10.0.pow(floor(log10(rough.toDouble()))).toFloat()
    val normalized = rough / magnitude
    val factor = when {
        normalized <= 1f -> 1f
        normalized <= 2f -> 2f
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
    // Below a second is not a track; past half a day, whole days on the 1-2-5 progression.
    return TimeSteps.firstOrNull { it >= rough }
        ?: (niceStep(rough / SECONDS_PER_DAY, 1) * SECONDS_PER_DAY)
}

/**
 * The series' own lowest to highest value, ignoring the samples that were never recorded,
 * or 0 to its highest with [fromZero].
 *
 * Public, and computed by the caller rather than inside the chart, because the labels the
 * axis needs depend on its step - the same reason the x scale has always been passed in.
 */
fun ChartSeries.yScale(perUnit: Float = 1f, fromZero: Boolean = false): Scale {
    var min = if (fromZero) 0f else Float.POSITIVE_INFINITY
    var max = Float.NEGATIVE_INFINITY
    for (value in y) {
        if (value.isNaN()) continue
        if (value < min) min = value
        if (value > max) max = value
    }
    if (!min.isFinite() || !max.isFinite()) return evenTickScale(0f, 1f / perUnit)
    if (max <= min) {
        // A flat series still needs a readable axis around its single value.
        val pad = if (abs(max) > 0f) abs(max) * 0.1f else 1f / perUnit
        min -= pad
        max += pad
    }
    return evenTickScale(min, max)
}

private const val Y_TICKS = 5

/**
 * Exactly [Y_TICKS] ticks, evenly spaced from the data's minimum to its maximum, so the top
 * of the plot is the peak itself. Not round numbers: rounding would move the top off it.
 */
private fun evenTickScale(min: Float, max: Float): Scale {
    val step = (max - min) / (Y_TICKS - 1)
    val ticks = FloatArray(Y_TICKS) { if (it == Y_TICKS - 1) max else min + it * step }
    return Scale(min, max, ticks)
}

/**
 * [view] after a pinch: scaled by [zoom] about [anchor], then shifted by [pan] - both as
 * fractions of the plot's width, so the value under the fingers stays under them. Kept
 * within [domain], and no narrower than [maxZoom] allows.
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

/**
 * Index of the sample closest to [target] in a sorted [values]. Binary search because this
 * runs on every pointer move, and a 30k-point scan per frame is a visible stutter.
 */
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
 * Builds a polyline reduced to one column of pixels at a time.
 *
 * A 30k-point track on a 1000px chart has thirty samples per column, which is thirty times
 * the geometry for no more detail than the screen can show. Each column collapses to the
 * four values carrying its shape - first, both extremes, last. Every-nth subsampling would
 * instead delete the peaks, which on a speed chart are the point of looking.
 */
class PolylineBuilder(
    private val line: Path,
    private val area: Path? = null,
    private val baselineY: Float = 0f,
) {
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
        // Only emit the vertical extent when the column actually spans a range;
        // otherwise this adds two duplicate points per column for nothing.
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
