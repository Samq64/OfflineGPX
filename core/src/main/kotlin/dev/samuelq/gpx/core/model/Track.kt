package dev.samuelq.gpx.core.model

import java.time.Instant

/** One GPX `<trkpt>`/`<rtept>`; [elevation] and [time] are often absent in real files. */
data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    /** Metres above the WGS84 ellipsoid. */
    val elevation: Double? = null,
    val time: Instant? = null,
    /** Horizontal accuracy in metres, stored in `<hdop>` as the closest GPX slot. */
    val accuracyMeters: Double? = null,
)

fun isValidCoordinate(latitude: Double, longitude: Double): Boolean =
    latitude in -90.0..90.0 && longitude in -180.0..180.0

/** Contiguous points; nothing (distance, chart lines) connects across a segment boundary. */
data class TrackSegment(val points: List<TrackPoint>)

/** A GPX `<wpt>`: a note at the last known position, not a fix of its own. */
data class Waypoint(
    val point: TrackPoint,
    val description: String? = null,
)

/** Raw geometry only; derived values live in [dev.samuelq.gpx.core.analysis.TrackProfile]. */
data class Track(
    val name: String?,
    val segments: List<TrackSegment>,
    /** The `<trk><desc>`. */
    val description: String? = null,
    /** Unordered. */
    val waypoints: List<Waypoint> = emptyList(),
) {
    val points: List<TrackPoint> = segments.flatMap(TrackSegment::points)

    /** Index into [points] at which each segment begins. */
    val segmentStartIndices: IntArray = IntArray(segments.size).also { starts ->
        var offset = 0
        segments.forEachIndexed { index, segment ->
            starts[index] = offset
            offset += segment.points.size
        }
    }

    val isEmpty: Boolean get() = points.isEmpty()
}
