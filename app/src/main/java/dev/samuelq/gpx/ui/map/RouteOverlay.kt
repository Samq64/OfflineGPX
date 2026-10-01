package dev.samuelq.gpx.ui.map

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import dev.samuelq.gpx.core.model.GeoBounds
import dev.samuelq.gpx.core.model.TrackPoints
import dev.samuelq.gpx.core.model.bounds
import dev.samuelq.gpx.data.track.LoadedTrack

/** One track's positions in degrees; the map projects. */
@Immutable
class RouteOverlay(
    val trackId: Long,
    /** A new polyline starts at each of its segments. */
    val points: TrackPoints,
    val color: Color,
    knownBounds: GeoBounds? = null,
) {
    /** Measured once, lazily, unless known. `PUBLICATION`, not a lock: a race just computes it twice. */
    val bounds: GeoBounds? by lazy(LazyThreadSafetyMode.PUBLICATION) { knownBounds ?: points.bounds() }

    /** Each segment's `[from, to)` range. */
    inline fun forEachRun(block: (from: Int, to: Int) -> Unit) {
        for (segment in 0 until points.segmentCount) {
            block(points.segmentStart(segment), points.segmentEnd(segment))
        }
    }
}

fun LoadedTrack.toOverlay(color: Color, bounds: GeoBounds? = null) = RouteOverlay(
    trackId = id,
    points = profile.points,
    color = color,
    knownBounds = bounds,
)

/** Camera position, kept so returning to the map doesn't re-fit. */
@Immutable
data class CameraSnapshot(val latitude: Double, val longitude: Double, val zoom: Double)
