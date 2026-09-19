package dev.samuelq.gpx.ui.track

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
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
 * Where the viewer is looking, on top of the fit.
 *
 * The fit answers "show me everything", which stops being useful the moment two rides are
 * in different towns: each collapses to a speck and the overlay says nothing. The camera
 * is what makes the shared projection worth having - one coordinate space you can move
 * around in, rather than one picture scaled to whatever the extremes happen to be.
 *
 * [zoom] multiplies the fitted size; [pan] moves it in pixels afterwards. [Fitted] is the
 * identity, and also the answer to "show me everything" - which is why a cold start needs
 * no aiming and why pinching back out to zoom 1 lands exactly on it. Deliberately not a
 * lat/lon centre yet, but it is the same two numbers a tile layer's camera needs, so the
 * day a basemap arrives this becomes centre-and-level without the callers changing.
 */
@Immutable
class MapCamera(val zoom: Float = 1f, val pan: Offset = Offset.Zero) {

    val isFitted: Boolean get() = zoom == 1f && pan == Offset.Zero

    companion object {
        val Fitted = MapCamera()

        /** Below 1 the fit already shows everything; above ~24 a 1 Hz track is dots. */
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 24f
    }
}

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
    /** A tap that landed on no route. With a camera to move, empty canvas is now common. */
    onSelectNothing: (() -> Unit)? = null,
    camera: MapCamera = MapCamera.Fitted,
    onCameraChange: ((MapCamera) -> Unit)? = null,
    /**
     * Space the fit keeps clear on each edge. Not uniform, because the things covering
     * this canvas are not: a sheet at the bottom would otherwise hide the part of the
     * route the fit was careful to include.
     */
    contentPadding: PaddingValues = PaddingValues(24.dp),
) {
    val layoutDirection = LocalLayoutDirection.current
    val density = LocalDensity.current

    val insets = remember(contentPadding, layoutDirection, density) {
        Insets.of(contentPadding, density, layoutDirection)
    }

    // Nothing below keys a gesture detector on anything that moves. A pinch changes the
    // camera every frame and a recording changes the layers every few seconds; restarting
    // a pointerInput on either would tear down the detector mid-gesture, so they read the
    // current values through these instead of capturing them.
    val currentCamera by rememberUpdatedState(camera)
    val currentLayers by rememberUpdatedState(layers)
    val currentInsets by rememberUpdatedState(insets)
    val select by rememberUpdatedState(onSelect)
    val selectNothing by rememberUpdatedState(onSelectNothing)
    val moveCamera by rememberUpdatedState(onCameraChange)

    val markerAt = remember(layers, markerLayerId, selectedIndex) {
        val layer = layers.firstOrNull { it.trackId == markerLayerId } ?: return@remember null
        selectedIndex?.let { layer.path.sourceIndices.indexOfNearest(it) }?.let { layer to it }
    }

    Canvas(
        modifier = modifier
            .clipToBounds()
            .semantics { this.contentDescription = contentDescription }
            // Taps first: a tap never reaches the transform detector, which needs slop,
            // so the two coexist without either having to know about the other.
            .pointerInput(Unit) {
                val reach = TapReach.toPx()
                detectTapGestures { offset ->
                    val hit = currentLayers.pick(
                        at = offset,
                        geometry = size.toGeometry(currentInsets, currentCamera),
                        reach = reach,
                    )
                    if (hit == null) selectNothing?.invoke() else select?.invoke(hit.first, hit.second)
                }
            }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, panChange, zoomChange, _ ->
                    val move = moveCamera ?: return@detectTransformGestures
                    move(
                        currentCamera.nudged(
                            centroid = centroid,
                            panChange = panChange,
                            zoomChange = zoomChange,
                            viewport = size,
                            insets = currentInsets,
                        )
                    )
                }
            },
    ) {
        val geometry = size.toGeometry(insets, camera)
        layers.forEach { drawRoute(it.path, geometry, it.color) }
        markerAt?.let { (layer, at) ->
            drawMarker(layer.path, at, geometry, markerColor, markerRingColor)
        }
    }
}

/** Pixels kept clear on each edge of the canvas. */
@Immutable
private class Insets(val left: Float, val top: Float, val right: Float, val bottom: Float) {

    val width: Float get() = left + right
    val height: Float get() = top + bottom

    companion object {
        fun of(
            padding: PaddingValues,
            density: Density,
            layoutDirection: LayoutDirection,
        ): Insets = with(density) {
            Insets(
                left = padding.calculateStartPadding(layoutDirection).toPx(),
                top = padding.calculateTopPadding().toPx(),
                right = padding.calculateEndPadding(layoutDirection).toPx(),
                bottom = padding.calculateBottomPadding().toPx(),
            )
        }
    }
}

/**
 * The camera moved just enough to bring one point of a route back into view.
 *
 * Scrubbing a chart moves a marker on the map, which is the point of having both on one
 * screen - and is useless if the marker is behind the sheet or off the side. This pans to
 * the nearest position that puts it back inside the uncovered box, and returns the camera
 * unchanged when it is already there.
 *
 * Minimal rather than centred: re-centring on every scrub frame turns reading a chart
 * into a ride through a moving map, and the reader loses the surroundings that made the
 * marker mean something. The map only moves when it has to.
 */
fun followingCamera(
    path: RoutePath,
    sourceIndex: Int,
    camera: MapCamera,
    viewport: Size,
    contentPadding: PaddingValues,
    density: Density,
    layoutDirection: LayoutDirection,
): MapCamera {
    if (path.xs.isEmpty() || viewport.width <= 0f || viewport.height <= 0f) return camera

    val at = path.sourceIndices.indexOfNearest(sourceIndex) ?: return camera
    val insets = Insets.of(contentPadding, density, layoutDirection)
    val geometry = geometryOf(viewport.width, viewport.height, insets, camera)

    val x = geometry.x(path.xs[at])
    val y = geometry.y(path.ys[at])

    // A margin inside the box, so the marker is never pinned against an edge where the
    // route around it is cut off.
    val margin = with(density) { FOLLOW_MARGIN.toPx() }
    val left = insets.left + margin
    val top = insets.top + margin
    val right = viewport.width - insets.right - margin
    val bottom = viewport.height - insets.bottom - margin

    val dx = when {
        left > right -> 0f
        x < left -> left - x
        x > right -> right - x
        else -> 0f
    }
    val dy = when {
        top > bottom -> 0f
        y < top -> top - y
        y > bottom -> bottom - y
        else -> 0f
    }
    if (dx == 0f && dy == 0f) return camera

    return MapCamera(
        zoom = camera.zoom,
        pan = Offset(camera.pan.x + dx, camera.pan.y + dy),
    )
}

/** How far inside the visible box a followed marker is kept. */
private val FOLLOW_MARGIN = 32.dp

/** Where the unit square lands on the canvas, kept square so the shape is not stretched. */
private class RouteGeometry(val left: Float, val top: Float, val scale: Float)

private fun Size.toGeometry(insets: Insets, camera: MapCamera): RouteGeometry =
    geometryOf(width, height, insets, camera)

/**
 * The same fit, from a pointer input scope.
 *
 * `PointerInputScope.size` is an [IntSize] in pixels while `DrawScope.size` is a float
 * [Size]; both describe the same box, so hit testing and drawing must agree on it - and
 * now that the camera moves the picture, they must agree on that too.
 */
private fun IntSize.toGeometry(insets: Insets, camera: MapCamera): RouteGeometry =
    geometryOf(width.toFloat(), height.toFloat(), insets, camera)

/** The fitted placement, before any camera. */
private fun fittedGeometry(width: Float, height: Float, insets: Insets): RouteGeometry {
    val availableWidth = max(1f, width - insets.left - insets.right)
    val availableHeight = max(1f, height - insets.top - insets.bottom)
    val side = max(1f, minOf(availableWidth, availableHeight))
    return RouteGeometry(
        left = insets.left + (availableWidth - side) / 2f,
        top = insets.top + (availableHeight - side) / 2f,
        scale = side,
    )
}

private fun geometryOf(
    width: Float,
    height: Float,
    insets: Insets,
    camera: MapCamera,
): RouteGeometry {
    val fitted = fittedGeometry(width, height, insets)
    if (camera.isFitted) return fitted

    // Zoom about the middle of the canvas, then translate. Doing it in that order is what
    // lets the pan be clamped as a plain interval below.
    val centerX = width / 2f
    val centerY = height / 2f
    return RouteGeometry(
        left = centerX + (fitted.left - centerX) * camera.zoom + camera.pan.x,
        top = centerY + (fitted.top - centerY) * camera.zoom + camera.pan.y,
        scale = fitted.scale * camera.zoom,
    )
}

/**
 * Applies one gesture frame, keeping the result somewhere worth looking at.
 *
 * Pinching about the centroid rather than the canvas centre is the difference between
 * zooming into the bit under your fingers and zooming into the middle and then hunting
 * for it. The pan clamp then guarantees the route can never be flung off screen: larger
 * than the viewport, it must still cover it; smaller, it must stay inside it.
 */
private fun MapCamera.nudged(
    centroid: Offset,
    panChange: Offset,
    zoomChange: Float,
    viewport: IntSize,
    insets: Insets,
): MapCamera {
    val width = viewport.width.toFloat()
    val height = viewport.height.toFloat()
    val centerX = width / 2f
    val centerY = height / 2f

    val newZoom = (zoom * zoomChange).coerceIn(MapCamera.MIN_ZOOM, MapCamera.MAX_ZOOM)
    // Pinched all the way back out: snap to the fit rather than leave the route parked
    // off-centre at a zoom that already shows everything.
    if (newZoom <= MapCamera.MIN_ZOOM) return MapCamera.Fitted

    // The change that actually happened after clamping, so a pinch past the limit stops
    // scaling rather than sliding the picture out from under the fingers.
    val applied = newZoom / zoom

    // Keep whatever is under the centroid under the centroid. A point p in fitted space
    // draws at C + (p - C) * z + pan; solving that for the pan which leaves the centroid
    // fixed while z becomes z * applied gives the two lines below.
    val panX = centroid.x + panChange.x - centerX - applied * (centroid.x - centerX - pan.x)
    val panY = centroid.y + panChange.y - centerY - applied * (centroid.y - centerY - pan.y)

    return MapCamera(
        zoom = newZoom,
        pan = clampedPan(
            pan = Offset(panX, panY),
            zoom = newZoom,
            viewport = Size(width, height),
            insets = insets,
        ),
    )
}

/**
 * Keeps the projection somewhere worth looking at.
 *
 * Clamped against the uncovered box rather than the whole canvas: bigger than that box,
 * the routes have to keep covering it; smaller, they have to stay inside it. Either way
 * they cannot be flung off the edge or parked under the sheet.
 */
private fun clampedPan(
    pan: Offset,
    zoom: Float,
    viewport: Size,
    insets: Insets,
): Offset {
    val centerX = viewport.width / 2f
    val centerY = viewport.height / 2f
    val fitted = fittedGeometry(viewport.width, viewport.height, insets)
    val scale = fitted.scale * zoom
    val zoomedLeft = centerX + (fitted.left - centerX) * zoom
    val zoomedTop = centerY + (fitted.top - centerY) * zoom

    return Offset(
        x = pan.x.clampBetween(
            insets.left - zoomedLeft,
            (viewport.width - insets.right) - (zoomedLeft + scale),
        ),
        y = pan.y.clampBetween(
            insets.top - zoomedTop,
            (viewport.height - insets.bottom) - (zoomedTop + scale),
        ),
    )
}

/** Clamps to the interval the two bounds describe, in whichever order they arrive. */
private fun Float.clampBetween(a: Float, b: Float): Float =
    coerceIn(minOf(a, b), maxOf(a, b))

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

/** How far from a line a tap still counts as being on it. About a fingertip. */
private val TapReach = 40.dp

/**
 * Nearest point *on* a route across every layer, as a track id and a profile index - or
 * null if nothing is within [reach].
 *
 * Against the drawn segments, not their endpoints. Decimation throws away every vertex
 * that would land within a pixel of the last one, so a long straight stretch of road is
 * two points with a hundred pixels of line between them - and measuring to vertices meant
 * the line you can plainly see was not tappable anywhere along the middle of it. What is
 * drawn is what can be hit.
 *
 * The [reach] limit matters more than it used to. Unbounded, every tap on the canvas hit
 * *something*, which was tolerable when the fit meant the routes filled it and is not now
 * that the camera can leave most of the screen empty: a tap on nothing would open whatever
 * track happened to be closest, half a screen away.
 */
private fun List<RouteLayer>.pick(
    at: Offset,
    geometry: RouteGeometry,
    reach: Float,
): Pair<Long, Int>? {
    var bestTrack = -1L
    var bestIndex = -1
    var bestDistance = reach * reach

    for (layer in this) {
        val path = layer.path
        if (path.xs.isEmpty()) continue
        val runStarts = path.runStarts.toHashSet()

        for (i in path.xs.indices) {
            val x = geometry.x(path.xs[i])
            val y = geometry.y(path.ys[i])

            // The vertex itself, which also covers a run that is a single point.
            val dxv = x - at.x
            val dyv = y - at.y
            val vertex = dxv * dxv + dyv * dyv
            if (vertex < bestDistance) {
                bestDistance = vertex
                bestTrack = layer.trackId
                bestIndex = path.sourceIndices[i]
            }

            // The segment leading to the next vertex, unless the next one begins a new
            // run - the gap between runs is signal loss, and nothing is drawn across it.
            val next = i + 1
            if (next >= path.xs.size || next in runStarts) continue

            val nx = geometry.x(path.xs[next])
            val ny = geometry.y(path.ys[next])
            val ex = nx - x
            val ey = ny - y
            val lengthSquared = ex * ex + ey * ey
            if (lengthSquared <= 0f) continue

            // Where the tap falls along the segment, clamped to it.
            val t = (((at.x - x) * ex + (at.y - y) * ey) / lengthSquared).coerceIn(0f, 1f)
            val dxs = x + t * ex - at.x
            val dys = y + t * ey - at.y
            val segment = dxs * dxs + dys * dys

            if (segment < bestDistance) {
                bestDistance = segment
                bestTrack = layer.trackId
                // Whichever end the tap is nearer to. A scrub index has to be a real
                // sample, and interpolating one would point at a moment never recorded.
                bestIndex = path.sourceIndices[if (t < 0.5f) i else next]
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
