package dev.samuelq.gpx.ui.map

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import dev.samuelq.gpx.core.model.TrackPoint
import dev.samuelq.gpx.data.track.LoadedTrack

/** One track's positions in degrees; the map projects. */
@Immutable
class RouteOverlay(
    val trackId: Long,
    val points: List<TrackPoint>,
    /** Indices into [points] where a new polyline starts. */
    val segmentStartIndices: IntArray,
    val color: Color,
) {
    /** Measured once, lazily. `PUBLICATION`, not a lock: a race just computes it twice. */
    val bounds: RouteBounds? by lazy(LazyThreadSafetyMode.PUBLICATION) { RouteBounds.of(points) }

    /** Each segment's `[from, to)` range. Starts are ascending from 0, so no sort or dedupe. */
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

fun LoadedTrack.toOverlay(color: Color) = RouteOverlay(
    trackId = id,
    points = profile.points,
    segmentStartIndices = profile.segmentStartIndices,
    color = color,
)

@Immutable
class RouteBounds(
    val southLatitude: Double,
    val westLongitude: Double,
    val northLatitude: Double,
    val eastLongitude: Double,
) {
    companion object {
        /** Null for no points. */
        fun of(points: List<TrackPoint>): RouteBounds? {
            if (points.isEmpty()) return null
            var south = Double.POSITIVE_INFINITY
            var west = Double.POSITIVE_INFINITY
            var north = Double.NEGATIVE_INFINITY
            var east = Double.NEGATIVE_INFINITY
            // Indexed loop: this touches every drawn position, so it allocates nothing.
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

/** Camera position, kept so returning to the map doesn't re-fit. */
@Immutable
data class CameraSnapshot(val latitude: Double, val longitude: Double, val zoom: Double)
