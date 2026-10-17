package dev.samuelq.gpx.ui.map

import androidx.compose.ui.geometry.Offset
import dev.samuelq.gpx.core.model.TrackPoint
import dev.samuelq.gpx.core.model.Waypoint
import org.oscim.core.MapPosition
import org.oscim.core.MercatorProjection
import org.oscim.core.Tile
import org.oscim.map.Map

/**
 * The waypoint whose pin was tapped: [onTop] first, since it's drawn over the rest, else the
 * nearest. The pin's box is padded out to [minHalfPx] each way, as it's smaller than a finger.
 */
internal fun pickWaypoint(
    screenX: Float,
    screenY: Float,
    /** Where a point is on screen. */
    screenPosition: (TrackPoint) -> Offset,
    waypoints: List<Waypoint>,
    headRadiusPx: Float,
    tipLengthPx: Float,
    minHalfPx: Float,
    onTop: Waypoint?,
): Waypoint? {
    val tap = Offset(screenX, screenY)
    val halfWidth = maxOf(headRadiusPx, minHalfPx)
    val height = tipLengthPx + headRadiusPx
    val padY = maxOf(0f, minHalfPx - height / 2)
    var best: Waypoint? = null
    var bestDistance = Float.MAX_VALUE
    for (waypoint in waypoints) {
        val tip = screenPosition(waypoint.point)
        val onIcon = kotlin.math.abs(tap.x - tip.x) <= halfWidth &&
            tap.y <= tip.y + padY && tap.y >= tip.y - height - padY
        if (!onIcon) continue
        if (waypoint == onTop) return waypoint
        val distance = (tip - Offset(0f, tipLengthPx) - tap).getDistanceSquared()
        if (distance < bestDistance) {
            bestDistance = distance
            best = waypoint
        }
    }
    return best
}

/**
 * Which track was tapped, and the nearer end of the hit segment. VTM can only say whether
 * something was hit, so this measures to the line, not its vertices: an imported route can
 * have a point per kilometre.
 */
internal fun pick(
    screenX: Float,
    screenY: Float,
    map: Map,
    routes: List<RouteOverlay>,
    liveRoute: RouteOverlay?,
    focusedId: Long?,
    reachPx: Float,
): Pair<Long, Int>? {
    val position = map.mapPosition
    val (tapX, tapY) = mapPixel(screenX, screenY, position, map.width, map.height)
    return nearestOnRoutes(
        tapX = tapX,
        tapY = tapY,
        mapSize = Tile.SIZE * position.scale,
        routes = topmostFirst(routes, liveRoute, focusedId),
        reachPx = reachPx,
    )
}

/**
 * As drawn, top down: the recording, the focused track, then the rest from the last. The
 * recording is in too, so a tap on it doesn't read as the bare map.
 */
internal fun topmostFirst(routes: List<RouteOverlay>, liveRoute: RouteOverlay?, focusedId: Long?): List<RouteOverlay> {
    val (focused, rest) = routes.partition { it.trackId == focusedId }
    return listOfNotNull(liveRoute) + focused.asReversed() + rest.asReversed()
}

/** A screen point in map pixels, the inverse of [screenPosition] for a [width] by [height] view. */
internal fun mapPixel(
    screenX: Float,
    screenY: Float,
    position: MapPosition,
    width: Int,
    height: Int,
): Pair<Double, Double> {
    // Screen pixels equal map pixels, since the map never rotates or tilts.
    val mapSize = Tile.SIZE * position.scale
    return position.x * mapSize + (screenX - width / 2.0) to position.y * mapSize + (screenY - height / 2.0)
}

/**
 * [pick] in map pixels, with the world [mapSize] across. Of equally near lines, as where
 * tracks coincide, the first in [routes] wins: the one on top, the one seen.
 */
internal fun nearestOnRoutes(
    tapX: Double,
    tapY: Double,
    mapSize: Double,
    routes: List<RouteOverlay>,
    reachPx: Float,
): Pair<Long, Int>? {
    var bestTrack: Long? = null
    var bestIndex = 0
    var bestDistance = (reachPx * reachPx).toDouble()

    for (route in routes) {
        route.forEachRun { from, to ->
            var previousX = 0.0
            var previousY = 0.0
            for (index in from until to) {
                val x = MercatorProjection.longitudeToX(route.points.longitude(index)) * mapSize
                val y = MercatorProjection.latitudeToY(route.points.latitude(index)) * mapSize
                // A zero-length segment onto itself, so a lone position is measured as a point.
                // A longer run's first segment covers the same point again.
                if (index == from) {
                    previousX = x
                    previousY = y
                }

                val spanX = x - previousX
                val spanY = y - previousY
                val lengthSquared = spanX * spanX + spanY * spanY
                val along = if (lengthSquared == 0.0) {
                    0.0
                } else {
                    (((tapX - previousX) * spanX + (tapY - previousY) * spanY) / lengthSquared)
                        .coerceIn(0.0, 1.0)
                }
                val dx = previousX + along * spanX - tapX
                val dy = previousY + along * spanY - tapY
                val distance = dx * dx + dy * dy
                if (distance < bestDistance) {
                    bestDistance = distance
                    bestTrack = route.trackId
                    bestIndex = if (along < 0.5 && index > from) index - 1 else index
                }
                previousX = x
                previousY = y
            }
        }
    }
    return bestTrack?.let { it to bestIndex }
}
