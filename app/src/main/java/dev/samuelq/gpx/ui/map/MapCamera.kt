package dev.samuelq.gpx.ui.map

import dev.samuelq.gpx.core.model.TrackPoint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import dev.samuelq.gpx.data.map.OfflineMap
import org.oscim.core.BoundingBox
import org.oscim.core.MapPosition
import org.oscim.core.MercatorProjection
import org.oscim.core.Tile
import org.oscim.map.Map
import org.oscim.map.Viewport

/** Runs [block] with VTM's own zoom limits in place, then puts the camera's back. */
internal inline fun <T> Viewport.withFullZoomRange(block: () -> T): T {
    val min = minScale
    val max = maxScale
    setMinZoomLevel(Viewport.MIN_ZOOM_LEVEL)
    setMaxZoomLevel(Viewport.MAX_ZOOM_LEVEL)
    try {
        return block()
    } finally {
        minScale = min
        maxScale = max
    }
}

/** Pixels kept clear on each edge, for whatever is floating over the map. */
internal class Insets(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * Where [point] sits on screen with the camera at [position]. The map is never rotated or
 * tilted, so this is the whole projection.
 */
internal fun Map.screenPosition(point: TrackPoint, position: MapPosition = mapPosition): Offset {
    val mapSize = Tile.SIZE * position.scale
    val x = (MercatorProjection.longitudeToX(point.longitude) - position.x) * mapSize + width / 2.0
    val y = (MercatorProjection.latitudeToY(point.latitude) - position.y) * mapSize + height / 2.0
    return Offset(x.toFloat(), y.toFloat())
}

/**
 * Everything worth looking at, as one box: every route's extent and every shown map's.
 * Null when there's neither - a fresh install with nothing to frame.
 */
internal fun extentOf(
    routes: List<RouteOverlay>,
    liveRoute: RouteOverlay?,
    basemaps: List<OfflineMap>,
): BoundingBox? {
    var south = Double.POSITIVE_INFINITY
    var west = Double.POSITIVE_INFINITY
    var north = Double.NEGATIVE_INFINITY
    var east = Double.NEGATIVE_INFINITY
    fun include(s: Double, w: Double, n: Double, e: Double) {
        if (s < south) south = s
        if (n > north) north = n
        if (w < west) west = w
        if (e > east) east = e
    }

    // A box of boxes: each route already knows its own extent, so this doesn't walk every
    // position of every ride on the map each time the one being recorded grows.
    for (route in routes + listOfNotNull(liveRoute)) {
        val b = route.bounds ?: continue
        include(b.southLatitude, b.westLongitude, b.northLatitude, b.eastLongitude)
    }
    for (map in basemaps) {
        val h = map.header
        include(h.minLatitude, h.minLongitude, h.maxLatitude, h.maxLongitude)
    }

    if (!south.isFinite() || !north.isFinite() || !west.isFinite() || !east.isFinite()) return null
    // A single position isn't a box - nothing to fit a camera to, same as no routes at all.
    if (north == south && east == west) return null

    return BoundingBox(south, west, north, east)
}

/**
 * [this] expanded outward by [fraction] of its own span on every side, for a pan clamp
 * that stops just past the edge of the data rather than dead against it.
 */
internal fun BoundingBox.padded(fraction: Double): BoundingBox {
    val latitudePad = latitudeSpan * fraction
    val longitudePad = longitudeSpan * fraction
    return BoundingBox(
        (minLatitude - latitudePad).coerceAtLeast(MercatorProjection.LATITUDE_MIN),
        (minLongitude - longitudePad).coerceAtLeast(MercatorProjection.LONGITUDE_MIN),
        (maxLatitude + latitudePad).coerceAtMost(MercatorProjection.LATITUDE_MAX),
        (maxLongitude + longitudePad).coerceAtMost(MercatorProjection.LONGITUDE_MAX),
    )
}

/** The view's size minus whatever is floating over it, or null before it is laid out. */
internal fun IntSize?.usable(insets: Insets): IntSize? {
    if (this == null) return null
    val usableWidth = width - insets.left - insets.right
    val usableHeight = height - insets.top - insets.bottom
    return if (usableWidth > 0 && usableHeight > 0) IntSize(usableWidth, usableHeight) else null
}

/**
 * [target] fitted into the uncovered part of the view, centred there rather than on the
 * screen - otherwise the sheet covers the bottom of whatever was just framed.
 */
internal fun fit(target: BoundingBox, size: IntSize, usable: IntSize, insets: Insets): MapPosition {
    val position = MapPosition().apply { setByBoundingBox(target, usable.width, usable.height) }
    val mapSize = Tile.SIZE * position.scale
    // Where the uncovered box's centre sits relative to the screen's, in pixels.
    val offsetX = (insets.left - insets.right) / 2.0
    val offsetY = (insets.top - insets.bottom) / 2.0
    position.x -= offsetX / mapSize
    position.y -= offsetY / mapSize
    return position
}

/** Pixels a sheet or panel hides along each edge. */
internal data class Cover(val left: Int, val right: Int, val bottom: Int)

/**
 * Moves the camera the least it can so no edge of [extent] comes inside the screen's - or
 * inside whatever [cover] hides - holding it centred on any axis where it fits.
 *
 * The range is also handed to VTM as its map limit, so a drag stops cleanly against it; a
 * pinch changes the scale that range was worked out for, which the correction here catches.
 */
internal fun Map.keepInView(extent: BoundingBox, cover: Cover) {
    if (width <= 0 || height <= 0) return
    val position = mapPosition
    val mapSize = Tile.SIZE * position.scale

    val (minX, maxX) = centreRange(
        MercatorProjection.longitudeToX(extent.minLongitude),
        MercatorProjection.longitudeToX(extent.maxLongitude),
        view = width, visibleStart = cover.left, visibleEnd = width - cover.right, mapSize,
    )
    val (minY, maxY) = centreRange(
        MercatorProjection.latitudeToY(extent.maxLatitude),
        MercatorProjection.latitudeToY(extent.minLatitude),
        view = height, visibleStart = 0, visibleEnd = height - cover.bottom, mapSize,
    )
    viewport().setMapLimit(minX, minY, maxX, maxY)

    val x = position.x.coerceIn(minX, maxX)
    val y = position.y.coerceIn(minY, maxY)
    if (x == position.x && y == position.y) return
    position.x = x
    position.y = y
    setMapPosition(position)
}

/**
 * Where the camera centre may sit on one axis, in projected units: [start] no further in
 * than [visibleStart] and [end] no further in than [visibleEnd] - the screen's edges, or
 * those of whatever covers them. When both can't hold at once the extent is narrower than
 * what is visible, and the only answer is centred in it.
 */
private fun centreRange(
    start: Double,
    end: Double,
    view: Int,
    visibleStart: Int,
    visibleEnd: Int,
    mapSize: Double,
): Pair<Double, Double> {
    val min = start + (view / 2.0 - visibleStart) / mapSize
    val max = end + (view / 2.0 - visibleEnd) / mapSize
    if (min <= max) return min to max
    val centred = (start + end) / 2 + (view - visibleStart - visibleEnd) / 2.0 / mapSize
    return centred to centred
}

/**
 * Pans the least it can to bring the target inside the uncovered box, or not at all -
 * expressed as a camera-centre move so the amount moved equals the amount out of bounds.
 */
internal fun Map.nudgeIntoView(point: TrackPoint, insets: Insets, margin: Int) {
    if (width <= 0 || height <= 0) return
    val position = mapPosition
    val mapSize = Tile.SIZE * position.scale
    val at = screenPosition(point, position)
    val atX = at.x.toDouble()
    val atY = at.y.toDouble()

    val left = insets.left + margin
    val top = insets.top + margin
    val right = width - insets.right - margin
    val bottom = height - insets.bottom - margin
    if (left >= right || top >= bottom) return

    val dx = when {
        atX < left -> left - atX
        atX > right -> right - atX
        else -> 0.0
    }
    val dy = when {
        atY < top -> top - atY
        atY > bottom -> bottom - atY
        else -> 0.0
    }
    if (dx == 0.0 && dy == 0.0) return

    // Moving the picture right by dx means moving the camera left by dx. Not animated:
    // this answers a drag happening right now, and an easing curve would arrive after the
    // finger had moved on.
    position.x -= dx / mapSize
    position.y -= dy / mapSize
    setMapPosition(position)
}
