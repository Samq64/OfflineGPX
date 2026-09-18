package dev.samuelq.gpx.ui.chart

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

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
    /**
     * Whether the y axis must include zero. True for speed, where distance from the
     * baseline *is* the magnitude and a truncated axis exaggerates every variation. False
     * for elevation, whose silhouette sits on a baseline the axis labels explicitly.
     */
    val zeroBased: Boolean,
) {
    val size: Int get() = x.size
}

/** An inclusive value range plus the tick positions to label it with. */
@Immutable
class Scale(val min: Float, val max: Float, val ticks: FloatArray) {
    val span: Float get() = (max - min).takeIf { it > 0f } ?: 1f
}

/**
 * Rounds a domain outward to values a reader can do arithmetic on - 0 / 20 / 40, never
 * 0 / 17.3 / 34.6.
 */
fun niceScale(rawMin: Float, rawMax: Float, zeroBased: Boolean, targetTicks: Int = 4): Scale {
    var lo = if (zeroBased) minOf(0f, rawMin) else rawMin
    var hi = rawMax

    if (!lo.isFinite() || !hi.isFinite()) return Scale(0f, 1f, floatArrayOf(0f, 1f))
    if (hi <= lo) {
        // A flat series still needs a readable axis around its single value.
        val pad = if (abs(hi) > 0f) abs(hi) * 0.1f else 1f
        lo -= pad
        hi += pad
        if (zeroBased) lo = minOf(0f, lo)
    }

    val step = niceStep(hi - lo, targetTicks)
    val niceMin = floor(lo / step) * step
    val niceMax = ceil(hi / step) * step

    val count = ((niceMax - niceMin) / step).toInt() + 1
    val ticks = FloatArray(count) { niceMin + it * step }
    return Scale(niceMin, niceMax, ticks)
}

/**
 * Keeps the domain exactly as measured but puts the ticks on round values inside it.
 *
 * Used for the x axis, where rounding the domain outward the way [niceScale] does would
 * leave the track ending in a stretch of empty plot.
 */
fun axisScale(min: Float, max: Float, targetTicks: Int = 4): Scale {
    if (!min.isFinite() || !max.isFinite() || max <= min) {
        return Scale(min.takeIf { it.isFinite() } ?: 0f, (min + 1f).takeIf { it.isFinite() } ?: 1f, FloatArray(0))
    }
    val step = niceStep(max - min, targetTicks)
    val first = ceil(min / step) * step
    val ticks = buildList {
        var tick = first
        // The epsilon keeps a tick that lands exactly on the maximum from being dropped
        // by float error.
        while (tick <= max + step * 1e-3f) {
            add(tick)
            tick += step
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
 * A 30k-point track on a 1000px chart has thirty samples per column; feeding all of them
 * to a [Path] costs thirty times the geometry for no more detail than the screen can show.
 * Each column collapses to the four values carrying its shape - first, both extremes,
 * last. Every-nth subsampling would instead delete the peaks, which on a speed chart are
 * the whole point of looking.
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
