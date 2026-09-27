package dev.samuelq.gpx.ui.map

import androidx.compose.ui.geometry.Offset
import dev.samuelq.gpx.core.model.TrackPoint
import dev.samuelq.gpx.core.model.Waypoint
import org.oscim.core.MapPosition
import org.oscim.core.MercatorProjection
import org.oscim.core.Tile
import org.oscim.map.Map

/**
 * The waypoint whose drawn pin a tap landed on, [onTop] first since it's drawn over the
 * rest, then nearest head, or null - checked ahead of [pick] so a waypoint sitting on a
 * track's line is read as itself rather than as a scrub.
 */
internal fun pickWaypoint(
    screenX: Float,
    screenY: Float,
    map: Map,
    waypoints: List<Waypoint>,
    headRadiusPx: Float,
    tipLengthPx: Float,
    onTop: Waypoint?,
): Waypoint? {
    val tap = Offset(screenX, screenY)
    var best: Waypoint? = null
    var bestDistance = Float.MAX_VALUE
    for (waypoint in waypoints) {
        val tip = map.screenPosition(waypoint.point)
        // The icon's bounds: head width across, from the tip up to the top of the head.
        val onIcon = kotlin.math.abs(tap.x - tip.x) <= headRadiusPx &&
            tap.y <= tip.y && tap.y >= tip.y - tipLengthPx - headRadiusPx
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
 * Which track was tapped, and where along it.
 *
 * VTM's vector layers can say whether a tap hit *something*, not what or where along it,
 * so this projects the drawn positions itself and measures to the *line* rather than to
 * its vertices. An imported route can be a point per kilometre, and a tap halfway along one
 * of those must still land on the track someone can plainly see.
 *
 * The index reported back is the nearer end of whichever segment was hit, since that is
 * what the charts and the marker are addressed by. One scan per tap over every route,
 * allocating nothing.
 */
internal fun pick(
    screenX: Float,
    screenY: Float,
    map: Map,
    routes: List<RouteOverlay>,
    liveRoute: RouteOverlay?,
    reachPx: Float,
): Pair<Long, Int>? {
    // In map pixels at the current scale, where a screen pixel is a map pixel: the map is
    // never rotated or tilted.
    val position = map.mapPosition
    val mapSize = Tile.SIZE * position.scale
    val tapX = position.x * mapSize + (screenX - map.width / 2.0)
    val tapY = position.y * mapSize + (screenY - map.height / 2.0)

    var bestTrack: Long? = null
    var bestIndex = 0
    var bestDistance = (reachPx * reachPx).toDouble()

    // The recording is checked too - a tap on it must not read as a tap on the bare map.
    for (route in (routes + listOfNotNull(liveRoute))) {
        route.forEachRun { from, to ->
            if (to <= from) return@forEachRun

            // Nothing is drawn across a segment break, so nothing is hit across one either.
            var previousX = 0.0
            var previousY = 0.0
            for (index in from until to) {
                val point = route.points[index]
                val x = MercatorProjection.longitudeToX(point.longitude) * mapSize
                val y = MercatorProjection.latitudeToY(point.latitude) * mapSize

                if (index == from) {
                    // A lone position is a point, not a line: measured to itself.
                    if (to - from == 1) {
                        val dx = x - tapX
                        val dy = y - tapY
                        val distance = dx * dx + dy * dy
                        if (distance < bestDistance) {
                            bestDistance = distance
                            bestTrack = route.trackId
                            bestIndex = index
                        }
                    }
                } else {
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
                        bestIndex = if (along < 0.5) index - 1 else index
                    }
                }
                previousX = x
                previousY = y
            }
        }
    }
    return bestTrack?.let { it to bestIndex }
}
