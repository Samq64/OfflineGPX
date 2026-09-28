package dev.samuelq.gpx.ui.map

import dev.samuelq.gpx.core.model.TrackPoint
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
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
internal data class Insets(val left: Int, val top: Int, val right: Int, val bottom: Int)

internal fun PaddingValues.toInsets(density: Density, layoutDirection: LayoutDirection) = with(density) {
    Insets(
        left = calculateStartPadding(layoutDirection).roundToPx(),
        top = calculateTopPadding().roundToPx(),
        right = calculateEndPadding(layoutDirection).roundToPx(),
        bottom = calculateBottomPadding().roundToPx(),
    )
}

/** Where [point] sits on screen at [position]; valid since the map never rotates or tilts. */
internal fun Map.screenPosition(point: TrackPoint, position: MapPosition = mapPosition): Offset {
    val mapSize = Tile.SIZE * position.scale
    val x = (MercatorProjection.longitudeToX(point.longitude) - position.x) * mapSize + width / 2.0
    val y = (MercatorProjection.latitudeToY(point.latitude) - position.y) * mapSize + height / 2.0
    return Offset(x.toFloat(), y.toFloat())
}

/**
 * Every route's extent with a margin, plus every shown map's flush (past a map's edge is
 * nothing). Null when there's neither.
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

    // From each route's cached bounds, so a growing recording doesn't re-walk every position.
    for (route in routes + listOfNotNull(liveRoute)) {
        val b = route.bounds ?: continue
        val latPad = (b.northLatitude - b.southLatitude) * TRACK_MARGIN
        val lonPad = (b.eastLongitude - b.westLongitude) * TRACK_MARGIN
        include(b.southLatitude - latPad, b.westLongitude - lonPad, b.northLatitude + latPad, b.eastLongitude + lonPad)
    }
    for (map in basemaps) {
        val h = map.header
        include(h.minLatitude, h.minLongitude, h.maxLatitude, h.maxLongitude)
    }

    if (!south.isFinite() || !north.isFinite() || !west.isFinite() || !east.isFinite()) return null
    // A single position isn't a box to fit.
    if (north == south && east == west) return null

    return BoundingBox(south, west, north, east)
}

internal fun BoundingBox.including(other: BoundingBox?): BoundingBox = if (other == null) this else BoundingBox(
    minOf(minLatitude, other.minLatitude),
    minOf(minLongitude, other.minLongitude),
    maxOf(maxLatitude, other.maxLatitude),
    maxOf(maxLongitude, other.maxLongitude),
)

/** What a [size] view shows with the camera at [this]. */
internal fun MapPosition.visibleBox(size: IntSize): BoundingBox {
    val mapSize = Tile.SIZE * scale
    val halfWidth = size.width / 2.0 / mapSize
    val halfHeight = size.height / 2.0 / mapSize
    return BoundingBox(
        MercatorProjection.toLatitude(y + halfHeight),
        MercatorProjection.toLongitude(x - halfWidth),
        MercatorProjection.toLatitude(y - halfHeight),
        MercatorProjection.toLongitude(x + halfWidth),
    )
}

/** A track's margin on each side, as a fraction of its own span. */
private const val TRACK_MARGIN = 0.05

/** Expanded by [fraction] of its span per side, so a pan clamp stops just past the data. */
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

/** [target] fitted and centred in the uncovered part of the view, not the screen. */
internal fun fit(target: BoundingBox, usable: IntSize, insets: Insets, maxScale: Double): MapPosition {
    val position = MapPosition().apply { setByBoundingBox(target, usable.width, usable.height) }
    // Capped here rather than by VTM, so the offset below is worked out at the scale it lands at.
    position.setScale(minOf(position.scale, maxScale))
    val mapSize = Tile.SIZE * position.scale
    val offsetX = (insets.left - insets.right) / 2.0
    val offsetY = (insets.top - insets.bottom) / 2.0
    position.x -= offsetX / mapSize
    position.y -= offsetY / mapSize
    return position
}

/**
 * Moves the camera the least it can so [extent]'s edges stay outside the screen's (or
 * [cover]'s), centred on an axis where it fits. The range is also VTM's map limit so drags
 * stop cleanly; this catches pinches, which change the scale it was worked out for.
 */
internal fun Map.keepInView(extent: BoundingBox, cover: Insets) {
    if (width <= 0 || height <= 0) return
    val position = mapPosition
    if (constrain(position, extent, cover)) setMapPosition(position)
}

/**
 * Moves to [target] clamped as [keepInView] would. The limit is set at [target]'s scale first:
 * VTM clamps to the current limit, and a whole-world one pins framing to the extent's centre.
 */
internal fun Map.moveTo(target: MapPosition, extent: BoundingBox?, cover: Insets) {
    if (extent != null && width > 0 && height > 0) constrain(target, extent, cover)
    setMapPosition(target)
}

/** Sets VTM's limit for [position]'s scale and pulls [position] inside it. True if it moved. */
private fun Map.constrain(position: MapPosition, extent: BoundingBox, cover: Insets): Boolean {
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
    if (x == position.x && y == position.y) return false
    position.x = x
    position.y = y
    return true
}

/**
 * Camera-centre range on one axis keeping [start] and [end] no further in than [visibleStart]
 * and [visibleEnd]. If both can't hold, the extent is narrower than visible and is centred.
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

/** Pans the least it can to bring the point inside the uncovered box, or not at all. */
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

    // Not animated: this follows a drag, and easing would lag the finger.
    position.x -= dx / mapSize
    position.y -= dy / mapSize
    setMapPosition(position)
}
