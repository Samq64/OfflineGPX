package dev.samuelq.gpx.ui.map

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import dev.samuelq.gpx.core.model.TrackPoint

/**
 * One track as the map should draw it: the positions themselves, in degrees. The map
 * does its own projecting.
 */
@Immutable
class RouteOverlay(
    val trackId: Long,
    val points: List<TrackPoint>,
    /** Indices into [points] where a new polyline starts. Never drawn across. */
    val segmentStartIndices: IntArray,
    val color: Color,
) {
    /**
     * The box this route occupies, measured once and kept - four numbers rather than
     * re-walking every position on every change. Lazy, since most overlays are never
     * framed against. `PUBLICATION`, not a lock: a race just computes the same answer twice.
     */
    val bounds: RouteBounds? by lazy(LazyThreadSafetyMode.PUBLICATION) { RouteBounds.of(points) }

    /**
     * Calls [block] with each segment's `[from, to)` range of [points]. Both producers hand
     * the starts over ascending and from 0, so this neither sorts nor dedupes.
     */
    inline fun forEachRun(block: (from: Int, to: Int) -> Unit) {
        val starts = segmentStartIndices
        val runs = if (starts.isEmpty()) 1 else starts.size
        for (i in 0 until runs) {
            val from = if (starts.isEmpty()) 0 else starts[i]
            val to = if (i + 1 < starts.size) starts[i + 1] else points.size
            block(from, to)
        }
    }
}

/** A route's extent, as the four numbers a camera fit actually needs. */
@Immutable
class RouteBounds(
    val southLatitude: Double,
    val westLongitude: Double,
    val northLatitude: Double,
    val eastLongitude: Double,
) {
    companion object {
        /** Null for no points at all: an empty route has no box, not a box of zero size. */
        fun of(points: List<TrackPoint>): RouteBounds? {
            if (points.isEmpty()) return null
            var south = Double.POSITIVE_INFINITY
            var west = Double.POSITIVE_INFINITY
            var north = Double.NEGATIVE_INFINITY
            var east = Double.NEGATIVE_INFINITY
            // Indexed doubles, not objects: this touches every position of every drawn
            // route, so it allocates nothing.
            for (i in points.indices) {
                val point = points[i]
                if (point.latitude < south) south = point.latitude
                if (point.latitude > north) north = point.latitude
                if (point.longitude < west) west = point.longitude
                if (point.longitude > east) east = point.longitude
            }
            return RouteBounds(south, west, north, east)
        }
    }
}

/**
 * Where the camera is, in the three numbers worth remembering across a screen this
 * composable doesn't survive - navigating away and back recomposes it from scratch, and
 * without this the camera re-fits from nothing every time, discarding wherever the user
 * had actually panned to.
 */
@Immutable
data class CameraSnapshot(val latitude: Double, val longitude: Double, val zoom: Double)
