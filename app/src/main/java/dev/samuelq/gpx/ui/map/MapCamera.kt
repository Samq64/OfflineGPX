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

/** Where [point] sits on screen at [position]; valid since the map never tilts. */
internal fun Map.screenPosition(point: TrackPoint, position: MapPosition = mapPosition): Offset =
    screenPosition(point, position, width, height)

/** [Map.screenPosition] for a [width] by [height] view; VTM's own map needs GL to construct. */
internal fun screenPosition(point: TrackPoint, position: MapPosition, width: Int, height: Int): Offset {
    val mapSize = Tile.SIZE * position.scale
    val (x, y) = mapToScreen(
        (MercatorProjection.longitudeToX(point.longitude) - position.x) * mapSize,
        (MercatorProjection.latitudeToY(point.latitude) - position.y) * mapSize,
        position.bearing,
    )
    return Offset((x + width / 2.0).toFloat(), (y + height / 2.0).toFloat())
}

/**
 * A screen offset as a map-pixel one at [bearing], as VTM turns a drag: positive turns the
 * map anticlockwise, so heading-up is the negated heading.
 */
internal fun screenToMap(dx: Double, dy: Double, bearing: Float): Pair<Double, Double> {
    if (bearing == 0f) return dx to dy
    val radians = Math.toRadians(bearing.toDouble())
    val cos = kotlin.math.cos(radians)
    val sin = kotlin.math.sin(radians)
    return dx * cos + dy * sin to -dx * sin + dy * cos
}

/** The inverse of [screenToMap]. */
internal fun mapToScreen(dx: Double, dy: Double, bearing: Float): Pair<Double, Double> = screenToMap(dx, dy, -bearing)

/**
 * Every route's extent with a margin, plus every shown map's flush (past a map's edge is
 * nothing). Null when there's neither.
 */
internal fun extentOf(routes: List<RouteOverlay>, liveRoute: RouteOverlay?, basemaps: List<OfflineMap>): BoundingBox? {
    // Saved tracks' bounds come from their rows, so only the recording is walked each fix.
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

/** What a [size] view shows with the camera at [this]; turned, the box around it. */
internal fun MapPosition.visibleBox(size: IntSize): BoundingBox {
    val (west, north, east, south) = mapBox(size, Insets(0, 0, 0, 0))
    return BoundingBox(
        MercatorProjection.toLatitude(south),
        MercatorProjection.toLongitude(west),
        MercatorProjection.toLatitude(north),
        MercatorProjection.toLongitude(east),
    )
}

/**
 * What a [size] view shows with the camera at [this], less [insets]; turned, the box around it.
 * Longitudes run past ±180 when it spans the antimeridian, so west is always less than east.
 */
internal fun MapPosition.uncoveredArea(size: IntSize, insets: Insets): GeoBounds {
    val (west, north, east, south) = mapBox(size, insets)
    fun longitude(x: Double) = (x - 0.5) * 360.0
    return GeoBounds(
        southLatitude = MercatorProjection.toLatitude(south),
        westLongitude = longitude(west),
        northLatitude = MercatorProjection.toLatitude(north),
        eastLongitude = longitude(east),
    )
}

/** The map-unit box, west, north, east, south, around the view less [insets]: unwrapped, unclamped. */
private fun MapPosition.mapBox(size: IntSize, insets: Insets): DoubleArray {
    val mapSize = Tile.SIZE * scale
    val box = doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
    for (sx in doubleArrayOf(insets.left - size.width / 2.0, size.width / 2.0 - insets.right)) {
        for (sy in doubleArrayOf(insets.top - size.height / 2.0, size.height / 2.0 - insets.bottom)) {
            val (dx, dy) = screenToMap(sx, sy, bearing)
            box[0] = minOf(box[0], x + dx / mapSize)
            box[1] = minOf(box[1], y + dy / mapSize)
            box[2] = maxOf(box[2], x + dx / mapSize)
            box[3] = maxOf(box[3], y + dy / mapSize)
        }
    }
    return box
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

/** [target] fitted and centred in the uncovered part of the view, not the screen, turned to [bearing]. */
internal fun fit(
    target: BoundingBox,
    usable: IntSize,
    insets: Insets,
    maxScale: Double,
    bearing: Float = 0f,
): MapPosition {
    val position = MapPosition().apply {
        setByBoundingBox(target, usable.width, usable.height)
        // Turned, the box's corners reach further; the scale that fits them, 0 when it's a point.
        setScale(fitScale(target, usable, bearing))
        // Wrapped: VTM clamps a bearing past ±180 rather than wrapping it.
        setBearing(bearing)
    }
    // Capped here rather than by VTM, so the offset below is worked out at the scale it lands at.
    position.setScale(minOf(position.scale, maxScale))
    position.centreIn(insets)
    return position
}

/** The scale at which [target], turned to [bearing], just fits a [size] view. */
internal fun fitScale(target: BoundingBox, size: IntSize, bearing: Float): Double {
    val width = kotlin.math.abs(
        MercatorProjection.longitudeToX(target.maxLongitude) - MercatorProjection.longitudeToX(target.minLongitude),
    )
    val height = kotlin.math.abs(
        MercatorProjection.latitudeToY(target.minLatitude) - MercatorProjection.latitudeToY(target.maxLatitude),
    )
    val radians = Math.toRadians(bearing.toDouble())
    val cos = kotlin.math.abs(kotlin.math.cos(radians))
    val sin = kotlin.math.abs(kotlin.math.sin(radians))
    return minOf(
        size.width / ((width * cos + height * sin) * Tile.SIZE),
        size.height / ((width * sin + height * cos) * Tile.SIZE),
    )
}

/** Moves the centre from the screen's to the uncovered part's, at the current scale and bearing. */
internal fun MapPosition.centreIn(insets: Insets) {
    val mapSize = Tile.SIZE * scale
    val (dx, dy) = screenToMap((insets.left - insets.right) / 2.0, (insets.top - insets.bottom) / 2.0, bearing)
    x -= dx / mapSize
    y -= dy / mapSize
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
    val limit = centreLimit(extent, cover, width, height, position.scale, position.bearing)
    viewport().setMapLimit(limit.xmin, limit.ymin, limit.xmax, limit.ymax)

    val x = position.x.coerceIn(limit.xmin, limit.xmax)
    val y = position.y.coerceIn(limit.ymin, limit.ymax)
    if (x == position.x && y == position.y) return false
    position.x = x
    position.y = y
    return true
}

/**
 * Where a [width] by [height] camera's centre may go at [scale], in map units. Turned to
 * [bearing], the view held to the extent is the largest upright box inside the uncovered part:
 * the whole of it at 0, its sides swapped at 90, and unbroken between.
 */
internal fun centreLimit(
    extent: BoundingBox,
    cover: Insets,
    width: Int,
    height: Int,
    scale: Double,
    bearing: Float = 0f,
): Box {
    val mapSize = Tile.SIZE * scale
    val (viewWidth, viewHeight) = inscribed(
        (width - cover.left - cover.right).coerceAtLeast(1).toDouble(),
        (height - cover.top - cover.bottom).coerceAtLeast(1).toDouble(),
        bearing,
    )
    // The uncovered part's centre, from the camera's, in map pixels.
    val (offsetX, offsetY) = screenToMap(
        (cover.left - cover.right) / 2.0,
        (cover.top - cover.bottom) / 2.0,
        bearing,
    )
    val (minX, maxX) = centreRange(
        MercatorProjection.longitudeToX(extent.minLongitude),
        MercatorProjection.longitudeToX(extent.maxLongitude),
        halfView = viewWidth / 2,
        offset = offsetX,
        mapSize,
    )
    val (minY, maxY) = centreRange(
        MercatorProjection.latitudeToY(extent.maxLatitude),
        MercatorProjection.latitudeToY(extent.minLatitude),
        halfView = viewHeight / 2,
        offset = offsetY,
        mapSize,
    )
    return Box(minX, minY, maxX, maxY)
}

/** The largest upright box, width by height, inside a [width] by [height] one turned to [bearing]. */
internal fun inscribed(width: Double, height: Double, bearing: Float): Pair<Double, Double> {
    val radians = Math.toRadians(bearing.toDouble())
    val sin = kotlin.math.abs(kotlin.math.sin(radians))
    val cos = kotlin.math.abs(kotlin.math.cos(radians))
    val long = maxOf(width, height)
    val short = minOf(width, height)
    if (short <= 2 * sin * cos * long || kotlin.math.abs(sin - cos) < 1e-10) {
        // Held by the short sides alone: a box with two corners on each.
        val half = short / 2
        return if (width >= height) half / sin to half / cos else half / cos to half / sin
    }
    val cos2 = cos * cos - sin * sin
    return (width * cos - height * sin) / cos2 to (height * cos - width * sin) / cos2
}

/**
 * Camera-centre range on one axis keeping a view [halfView] pixels each side of a point
 * [offset] from the camera within [start] to [end]. If it can't, the extent is narrower than
 * the view and is centred on that point.
 */
private fun centreRange(
    start: Double,
    end: Double,
    halfView: Double,
    offset: Double,
    mapSize: Double,
): Pair<Double, Double> {
    val min = start + (halfView - offset) / mapSize
    val max = end - (halfView + offset) / mapSize
    if (min <= max) return min to max
    val centred = (start + end) / 2 - offset / mapSize
    return centred to centred
}

/** Pans the least it can to bring the point inside the uncovered box, or not at all. */
internal fun Map.nudgeIntoView(point: TrackPoint, insets: Insets, margin: Int) {
    if (width <= 0 || height <= 0) return
    val position = mapPosition
    val (panX, panY) = nudge(screenPosition(point, position), width, height, insets, margin) ?: return
    // Not animated: this follows a drag, and easing would lag the finger.
    val mapSize = Tile.SIZE * position.scale
    val (dx, dy) = screenToMap(panX, panY, position.bearing)
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
