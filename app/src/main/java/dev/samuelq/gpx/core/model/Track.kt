package dev.samuelq.gpx.core.model

import java.time.Instant

/**
 * One sample from a GPX `<trkpt>` (or `<rtept>`).
 *
 * [elevation] and [time] are optional because plenty of real files omit one or both -
 * a route exported from a planner has no timestamps, and some devices log no elevation.
 * Everything downstream is written to degrade rather than fail when they are absent.
 */
data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    /** Metres above the WGS84 ellipsoid, or `null` when the file carries no `<ele>`. */
    val elevation: Double? = null,
    val time: Instant? = null,
)

/**
 * A contiguous run of points. A GPX file splits a track into segments wherever the
 * recorder lost signal, so a segment boundary means "these two points are not connected"
 * - distance must not accumulate across it and the chart must not draw a line across it.
 */
data class TrackSegment(val points: List<TrackPoint>)

/**
 * A parsed GPX track: raw geometry, no derived values.
 *
 * Distance, speed and ascent live in [dev.samuelq.gpx.core.analysis.TrackProfile], so this
 * stays a faithful view of the file and can come from something other than the parser -
 * a recorder builds exactly this and hands it to the same analysis.
 */
data class Track(
    val name: String?,
    val description: String?,
    val segments: List<TrackSegment>,
) {
    /** All points, in order, flattened across segments. */
    val points: List<TrackPoint> = segments.flatMap(TrackSegment::points)

    /** Index into [points] at which each segment begins. Always starts with 0 when non-empty. */
    val segmentStartIndices: IntArray = IntArray(segments.size).also { starts ->
        var offset = 0
        segments.forEachIndexed { index, segment ->
            starts[index] = offset
            offset += segment.points.size
        }
    }

    val isEmpty: Boolean get() = points.isEmpty()
}
