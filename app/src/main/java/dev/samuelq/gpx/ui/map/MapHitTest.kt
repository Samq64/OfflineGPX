package dev.samuelq.gpx.ui.map

import androidx.compose.ui.geometry.Offset
import dev.samuelq.gpx.core.model.Waypoint
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
    map: Map,
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
        val tip = map.screenPosition(waypoint.point)
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
    reachPx: Float,
): Pair<Long, Int>? {
    // Screen pixels equal map pixels, since the map never rotates or tilts.
    val position = map.mapPosition
    val mapSize = Tile.SIZE * position.scale
    val tapX = position.x * mapSize + (screenX - map.width / 2.0)
    val tapY = position.y * mapSize + (screenY - map.height / 2.0)

    var bestTrack: Long? = null
    var bestIndex = 0
    var bestDistance = (reachPx * reachPx).toDouble()

    // The recording too, so a tap on it doesn't read as the bare map.
    for (route in (routes + listOfNotNull(liveRoute))) {
        route.forEachRun { from, to ->
            if (to <= from) return@forEachRun

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
