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
    /**
     * Horizontal accuracy in metres, written to and read from `<hdop>` - not a true
     * dilution-of-precision figure (Android never hands us satellite geometry), but the
     * closest slot GPX has for "how good was this fix".
     */
    val accuracyMeters: Double? = null,
)

/** Whether a latitude and longitude, in degrees, name a place on the globe. */
fun isValidCoordinate(latitude: Double, longitude: Double): Boolean =
    latitude in -90.0..90.0 && longitude in -180.0..180.0

/**
 * A contiguous run of points. A GPX file splits a track into segments wherever the
 * recorder lost signal, so a segment boundary means "these two points are not connected"
 * - distance must not accumulate across it and the chart must not draw a line across it.
 */
data class TrackSegment(val points: List<TrackPoint>)

/**
 * A note at a position, independent of the track's own points - a GPX `<wpt>`, dropped by
 * hand rather than sampled on a timer. [point] is wherever the recorder last knew it was,
 * not a new fix of its own; a waypoint marks a moment, not a measurement.
 */
data class Waypoint(
    val point: TrackPoint,
    /** What the user typed, or `null` for a bare marker - blank is a valid waypoint. */
    val description: String? = null,
)

/**
 * A parsed GPX track: raw geometry, no derived values.
 *
 * Distance, speed and ascent live in [dev.samuelq.gpx.core.analysis.TrackProfile], so this
 * stays a faithful view of the file and can come from something other than the parser -
 * a recorder builds exactly this and hands it to the same analysis.
 */
data class Track(
    val name: String?,
    val segments: List<TrackSegment>,
    /** The `<trk><desc>`, or `null` when the file has none - most don't. */
    val description: String? = null,
    /** The file's own `<wpt>` elements, in no particular order - GPX doesn't promise one. */
    val waypoints: List<Waypoint> = emptyList(),
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
