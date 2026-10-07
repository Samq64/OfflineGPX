package dev.samuelq.gpx.ui.map

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import dev.samuelq.gpx.core.model.GeoBounds
import dev.samuelq.gpx.core.model.TrackPoint
import dev.samuelq.gpx.data.map.OfflineMap
import org.oscim.core.BoundingBox
import org.oscim.core.Box
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
        // Physical sides: VTM's view isn't mirrored, so start is the right in RTL.
        left = calculateLeftPadding(layoutDirection).roundToPx(),
        top = calculateTopPadding().roundToPx(),
        right = calculateRightPadding(layoutDirection).roundToPx(),
        bottom = calculateBottomPadding().roundToPx(),
    )
}

/** Where [point] sits on screen at [position]; valid since the map never rotates or tilts. */
internal fun Map.screenPosition(point: TrackPoint, position: MapPosition = mapPosition): Offset =
    screenPosition(point, position, width, height)

/** [Map.screenPosition] for a [width] by [height] view; VTM's own map needs GL to construct. */
internal fun screenPosition(point: TrackPoint, position: MapPosition, width: Int, height: Int): Offset {
    val mapSize = Tile.SIZE * position.scale
    val x = (MercatorProjection.longitudeToX(point.longitude) - position.x) * mapSize + width / 2.0
    val y = (MercatorProjection.latitudeToY(point.latitude) - position.y) * mapSize + height / 2.0
    return Offset(x.toFloat(), y.toFloat())
}

/**
 * Every route's extent with a margin, plus every shown map's flush (past a map's edge is
 * nothing). Null when there's neither.
 */
internal fun extentOf(routes: List<RouteOverlay>, liveRoute: RouteOverlay?, basemaps: List<OfflineMap>): BoundingBox? {
    // From each route's cached bounds, so a growing recording doesn't re-walk every position.
    val tracks = (routes + listOfNotNull(liveRoute)).mapNotNull {
        it.bounds?.toBoundingBox()?.extendMargin(TRACK_MARGIN_FACTOR)
    }
    val extent = (tracks + basemaps.map { it.bounds }).reduceOrNull(BoundingBox::extendBoundingBox)
    // A single position isn't a box to fit.
    return extent?.takeIf { it.latitudeSpan > 0 || it.longitudeSpan > 0 }
}

internal fun GeoBounds.toBoundingBox() = BoundingBox(southLatitude, westLongitude, northLatitude, eastLongitude)

/** [this] widened to take in [other], if any. */
internal fun BoundingBox.including(other: BoundingBox?): BoundingBox = other?.let(::extendBoundingBox) ?: this

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

/**
 * What a [size] view shows with the camera at [this], less [insets]. Longitudes run past ±180
 * when it spans the antimeridian, so west is always less than east.
 */
internal fun MapPosition.uncoveredArea(size: IntSize, insets: Insets): GeoBounds {
    val mapSize = Tile.SIZE * scale
    fun longitude(x: Double) = (x - 0.5) * 360.0
    return GeoBounds(
        southLatitude = MercatorProjection.toLatitude(y + (size.height / 2.0 - insets.bottom) / mapSize),
        westLongitude = longitude(x + (insets.left - size.width / 2.0) / mapSize),
        northLatitude = MercatorProjection.toLatitude(y + (insets.top - size.height / 2.0) / mapSize),
        eastLongitude = longitude(x + (size.width / 2.0 - insets.right) / mapSize),
    )
}

/** 5% of a track's span on each side, as VTM's total-span factor. */
private const val TRACK_MARGIN_FACTOR = 1.1f

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
 * Whether fitting [extent] to [usable] leaves every one of [tracks] under [minPx] across: then
 * they're too far apart to show together, as specks. Never at the zoom cap, where the whole
 * extent is small rather than spread.
 */
internal fun tooFarApart(
    extent: BoundingBox,
    tracks: List<BoundingBox>,
    usable: IntSize,
    maxScale: Double,
    minPx: Float,
): Boolean {
    val scale = MapPosition().apply { setByBoundingBox(extent, usable.width, usable.height) }.scale
    if (scale >= maxScale) return false
    val mapSize = Tile.SIZE * scale
    return tracks.none {
        val width = MercatorProjection.longitudeToX(it.maxLongitude) - MercatorProjection.longitudeToX(it.minLongitude)
        val height = MercatorProjection.latitudeToY(it.minLatitude) - MercatorProjection.latitudeToY(it.maxLatitude)
        maxOf(width, height) * mapSize >= minPx
    }
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
    val limit = centreLimit(extent, cover, width, height, position.scale)
    viewport().setMapLimit(limit.xmin, limit.ymin, limit.xmax, limit.ymax)

    val x = position.x.coerceIn(limit.xmin, limit.xmax)
    val y = position.y.coerceIn(limit.ymin, limit.ymax)
    if (x == position.x && y == position.y) return false
    position.x = x
    position.y = y
    return true
}

/** Where a [width] by [height] camera's centre may go at [scale], in map units. */
internal fun centreLimit(extent: BoundingBox, cover: Insets, width: Int, height: Int, scale: Double): Box {
    val mapSize = Tile.SIZE * scale
    val (minX, maxX) = centreRange(
        MercatorProjection.longitudeToX(extent.minLongitude),
        MercatorProjection.longitudeToX(extent.maxLongitude),
        view = width,
        visibleStart = cover.left,
        visibleEnd = width - cover.right,
        mapSize,
    )
    val (minY, maxY) = centreRange(
        MercatorProjection.latitudeToY(extent.maxLatitude),
        MercatorProjection.latitudeToY(extent.minLatitude),
        view = height,
        visibleStart = 0,
        visibleEnd = height - cover.bottom,
        mapSize,
    )
    return Box(minX, minY, maxX, maxY)
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
    val (dx, dy) = nudge(screenPosition(point, position), width, height, insets, margin) ?: return
    // Not animated: this follows a drag, and easing would lag the finger.
    val mapSize = Tile.SIZE * position.scale
    position.x -= dx / mapSize
    position.y -= dy / mapSize
    setMapPosition(position)
}

/** Screen pixels to pan so [at] is inside the uncovered box less [margin], or null for none. */
internal fun nudge(at: Offset, width: Int, height: Int, insets: Insets, margin: Int): Pair<Double, Double>? {
    val atX = at.x.toDouble()
    val atY = at.y.toDouble()
    val left = insets.left + margin
    val top = insets.top + margin
    val right = width - insets.right - margin
    val bottom = height - insets.bottom - margin
    if (left >= right || top >= bottom) return null

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
    return if (dx == 0.0 && dy == 0.0) null else dx to dy
}
