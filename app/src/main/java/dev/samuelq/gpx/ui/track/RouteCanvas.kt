package dev.samuelq.gpx.ui.track

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.samuelq.gpx.core.model.TrackPoint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max

/**
 * The geographic extent a projection is fitted to.
 *
 * Separated from the projection so several tracks can share one: overlaid routes only mean
 * anything if they are drawn in the same coordinate space, and each normalising to its own
 * bounds would put two rides in different places on the same canvas.
 */
@Immutable
class GeoBounds(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
) {
    fun union(other: GeoBounds) = GeoBounds(
        minLat = minOf(minLat, other.minLat),
        maxLat = maxOf(maxLat, other.maxLat),
        minLon = minOf(minLon, other.minLon),
        maxLon = maxOf(maxLon, other.maxLon),
    )

    companion object {
        fun of(points: List<TrackPoint>): GeoBounds? {
            if (points.isEmpty()) return null
            return GeoBounds(
                minLat = points.minOf { it.latitude },
                maxLat = points.maxOf { it.latitude },
                minLon = points.minOf { it.longitude },
                maxLon = points.maxOf { it.longitude },
            )
        }

        fun union(all: List<GeoBounds>): GeoBounds? =
            all.reduceOrNull { acc, bounds -> acc.union(bounds) }
    }
}

/**
 * A track's shape in unit space.
 *
 * Coordinates are normalised into 0..1 with the aspect ratio already applied, so drawing
 * is a multiply and the projection never has to be redone on resize. [sourceIndices] maps
 * each kept point back to its index in the profile, which is what makes a tap on the route
 * addressable as a scrubber position.
 */
@Immutable
class RoutePath(
    val xs: FloatArray,
    val ys: FloatArray,
    val sourceIndices: IntArray,
    /** Indices into [xs] where a new polyline starts. The route is never drawn across these. */
    val runStarts: IntArray,
)

/** One route on the canvas, with the identity a tap should resolve to. */
@Immutable
class RouteLayer(
    val trackId: Long,
    val path: RoutePath,
    val color: Color,
)

/**
 * Projects a track for display and decimates it to what a screen can show.
 *
 * Equirectangular with the longitude scaled by cos(latitude): over one activity's extent
 * the distortion against a true Mercator is far below a pixel, and it keeps the maths to
 * two multiplies. This is a route *shape*, not a map - north is up and nothing is claimed
 * about the ground beneath it.
 *
 * @param bounds the extent to fit. Pass the union across every drawn track to overlay them.
 */
fun routePathOf(
    points: List<TrackPoint>,
    segmentStartIndices: IntArray,
    bounds: GeoBounds,
    /** Minimum gap in unit space between kept points; ~1px on a 1000px canvas. */
    epsilon: Float = 0.001f,
): RoutePath? {
    if (points.isEmpty()) return null

    val cosLat = cos(Math.toRadians((bounds.minLat + bounds.maxLat) / 2.0))

    // Project the extent first, then measure it. Taking the span in raw degrees would
    // stretch the shape east-west by 1/cos(lat) - a third too wide at 50N.
    val minX = bounds.minLon * cosLat
    val maxX = bounds.maxLon * cosLat
    val minY = -bounds.maxLat
    val maxY = -bounds.minLat

    // One scale for both axes, so a shape is never stretched to fill the box.
    val span = max(maxX - minX, maxY - minY).takeIf { it > 0.0 } ?: 1.0
    val midX = (minX + maxX) / 2.0
    val midY = (minY + maxY) / 2.0

    val segmentStarts = segmentStartIndices.toHashSet()
    val keptX = ArrayList<Float>(points.size / 4 + 8)
    val keptY = ArrayList<Float>(points.size / 4 + 8)
    val keptIndex = ArrayList<Int>(points.size / 4 + 8)
    val runs = ArrayList<Int>()

    var lastX = Float.NaN
    var lastY = Float.NaN

    for (i in points.indices) {
        val x = (((points[i].longitude * cosLat) - midX) / span + 0.5).toFloat()
        val y = ((-points[i].latitude - midY) / span + 0.5).toFloat()
        val startsRun = i in segmentStarts

        // Always keep a segment's first and last point, so a run is never shortened into
        // nothing and the join between runs stays where the data put it.
        val far = lastX.isNaN() || abs(x - lastX) >= epsilon || abs(y - lastY) >= epsilon
        if (!startsRun && !far && i != points.lastIndex) continue

        if (startsRun || runs.isEmpty()) runs += keptX.size
        keptX += x
        keptY += y
        keptIndex += i
        lastX = x
        lastY = y
    }

    return RoutePath(
        xs = keptX.toFloatArray(),
        ys = keptY.toFloatArray(),
        sourceIndices = keptIndex.toIntArray(),
        runStarts = runs.toIntArray(),
    )
}

/**
 * Routes, and optionally the marker a scrubber is pointing at.
 *
 * This is the slot an offline map will render behind: the projection and the hit testing
 * do not change when tiles arrive, only what is drawn underneath. Until then the shape
 * alone is worth showing - people recognise their own loops.
 */
@Composable
fun RouteCanvas(
    layers: List<RouteLayer>,
    contentDescription: String,
    modifier: Modifier = Modifier,
    /** Highlighted point within [markerLayerId]'s path, as a profile index. */
    selectedIndex: Int? = null,
    markerLayerId: Long? = null,
    markerColor: Color = Color.Unspecified,
    markerRingColor: Color = Color.Unspecified,
    onSelect: ((trackId: Long, index: Int) -> Unit)? = null,
    padding: Dp = 24.dp,
) {
    val markerAt = remember(layers, markerLayerId, selectedIndex) {
        val layer = layers.firstOrNull { it.trackId == markerLayerId } ?: return@remember null
        selectedIndex?.let { layer.path.sourceIndices.indexOfNearest(it) }?.let { layer to it }
    }

    Canvas(
        modifier = modifier
            .semantics { this.contentDescription = contentDescription }
            .pointerInput(layers, onSelect) {
                if (onSelect == null) return@pointerInput
                detectTapGestures { offset ->
                    layers.pick(offset, size.toGeometry(padding.toPx()))?.let { onSelect(it.first, it.second) }
                }
            }
            .pointerInput(layers, onSelect) {
                if (onSelect == null) return@pointerInput
                detectDragGestures(
                    onDragStart = { offset ->
                        layers.pick(offset, size.toGeometry(padding.toPx()))
                            ?.let { onSelect(it.first, it.second) }
                    },
                ) { change, _ ->
                    change.consume()
                    layers.pick(change.position, size.toGeometry(padding.toPx()))
                        ?.let { onSelect(it.first, it.second) }
                }
            },
    ) {
        val geometry = size.toGeometry(padding.toPx())
        layers.forEach { drawRoute(it.path, geometry, it.color) }
        markerAt?.let { (layer, at) ->
            drawMarker(layer.path, at, geometry, markerColor, markerRingColor)
        }
    }
}

/** Where the unit square lands on the canvas, kept square so the shape is not stretched. */
private class RouteGeometry(val left: Float, val top: Float, val scale: Float)

private fun Size.toGeometry(pad: Float): RouteGeometry = geometryOf(width, height, pad)

/**
 * The same fit, from a pointer input scope.
 *
 * `PointerInputScope.size` is an [IntSize] in pixels while `DrawScope.size` is a float
 * [Size]; both describe the same box, so hit testing and drawing must agree on it.
 */
private fun IntSize.toGeometry(pad: Float): RouteGeometry =
    geometryOf(width.toFloat(), height.toFloat(), pad)

private fun geometryOf(width: Float, height: Float, pad: Float): RouteGeometry {
    val side = max(1f, minOf(width, height) - 2 * pad)
    return RouteGeometry(
        left = (width - side) / 2f,
        top = (height - side) / 2f,
        scale = side,
    )
}

private fun RouteGeometry.x(unit: Float) = left + unit * scale
private fun RouteGeometry.y(unit: Float) = top + unit * scale

private fun DrawScope.drawRoute(route: RoutePath, geometry: RouteGeometry, color: Color) {
    if (route.xs.isEmpty()) return

    val path = Path()
    val runStarts = route.runStarts.toHashSet()
    for (i in route.xs.indices) {
        val px = geometry.x(route.xs[i])
        val py = geometry.y(route.ys[i])
        if (i in runStarts) path.moveTo(px, py) else path.lineTo(px, py)
    }

    drawPath(
        path = path,
        color = color,
        style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

private fun DrawScope.drawMarker(
    route: RoutePath,
    at: Int,
    geometry: RouteGeometry,
    color: Color,
    ringColor: Color,
) {
    if (at !in route.xs.indices) return
    val center = Offset(geometry.x(route.xs[at]), geometry.y(route.ys[at]))
    drawCircle(ringColor, 7.dp.toPx(), center)
    drawCircle(color, 5.dp.toPx(), center)
}

/** Nearest drawn vertex across every layer, as a track id and a profile index. */
private fun List<RouteLayer>.pick(at: Offset, geometry: RouteGeometry): Pair<Long, Int>? {
    var bestTrack = -1L
    var bestIndex = -1
    var bestDistance = Float.MAX_VALUE

    for (layer in this) {
        val path = layer.path
        for (i in path.xs.indices) {
            val dx = geometry.x(path.xs[i]) - at.x
            val dy = geometry.y(path.ys[i]) - at.y
            val distance = dx * dx + dy * dy
            if (distance < bestDistance) {
                bestDistance = distance
                bestTrack = layer.trackId
                bestIndex = path.sourceIndices[i]
            }
        }
    }

    return if (bestIndex >= 0) bestTrack to bestIndex else null
}

/**
 * Position of the drawn vertex closest to a profile index. The array is sorted, so this is
 * a binary search rather than a scan over every kept point on each scrub frame.
 */
private fun IntArray.indexOfNearest(target: Int): Int? {
    if (isEmpty()) return null
    var low = 0
    var high = size - 1
    while (low < high) {
        val mid = (low + high) ushr 1
        if (this[mid] < target) low = mid + 1 else high = mid
    }
    if (low > 0 && abs(this[low - 1] - target) <= abs(this[low] - target)) return low - 1
    return low
}
