package dev.samuelq.gpx.ui.map

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import dev.samuelq.gpx.core.model.TrackPoints
import dev.samuelq.gpx.data.track.LoadedTrack

/** One track's positions in degrees; the map projects. */
@Immutable
class RouteOverlay(
    val trackId: Long,
    /** A new polyline starts at each of its segments. */
    val points: TrackPoints,
    val color: Color,
    knownBounds: RouteBounds? = null,
) {
    /** Measured once, lazily, unless known. `PUBLICATION`, not a lock: a race just computes it twice. */
    val bounds: RouteBounds? by lazy(LazyThreadSafetyMode.PUBLICATION) { knownBounds ?: RouteBounds.of(points) }

    /** Each segment's `[from, to)` range. */
    inline fun forEachRun(block: (from: Int, to: Int) -> Unit) {
        for (segment in 0 until points.segmentCount) {
            block(points.segmentStart(segment), points.segmentEnd(segment))
        }
    }
}

fun LoadedTrack.toOverlay(color: Color) = RouteOverlay(
    trackId = id,
    points = profile.points,
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
        fun of(points: TrackPoints): RouteBounds? {
            if (points.size == 0) return null
            var south = Double.POSITIVE_INFINITY
            var west = Double.POSITIVE_INFINITY
            var north = Double.NEGATIVE_INFINITY
            var east = Double.NEGATIVE_INFINITY
            for (i in points.indices) {
                val latitude = points.latitude(i)
                val longitude = points.longitude(i)
                if (latitude < south) south = latitude
                if (latitude > north) north = latitude
                if (longitude < west) west = longitude
                if (longitude > east) east = longitude
            }
            return RouteBounds(south, west, north, east)
        }
    }
}

/** Camera position, kept so returning to the map doesn't re-fit. */
@Immutable
data class CameraSnapshot(val latitude: Double, val longitude: Double, val zoom: Double)
