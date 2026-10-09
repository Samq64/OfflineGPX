package dev.samuelq.gpx.data.track

import dev.samuelq.gpx.core.analysis.TrackProfile
import dev.samuelq.gpx.core.model.Track
import dev.samuelq.gpx.data.db.TrackEntity
import java.io.File

/**
 * A parsed and analysed track, ready to draw. A saved track's name and colour are its row's,
 * read from there rather than copied here so they can't go stale.
 */
class LoadedTrack(
    /** The `tracks` row. */
    val id: Long,
    val track: Track,
    val profile: TrackProfile,
) {
    /** Each waypoint's nearest point; a trim keeps the waypoints whose point it keeps. */
    val waypointIndices: IntArray by lazy {
        IntArray(track.waypoints.size) { profile.indexOf(track.waypoints[it].point) }
    }
}

/** The UI maps these to messages. */
sealed class TrackLoadException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Unreadable(message: String, cause: Throwable? = null) : TrackLoadException(message, cause)

    /** Readable, but not GPX. */
    class Invalid(message: String, cause: Throwable? = null) : TrackLoadException(message, cause)

    /** No track points, e.g. waypoint-only files. */
    class Empty(message: String) : TrackLoadException(message)

    /** A point without a time, e.g. a planned route: speed, duration and the title need them. */
    class Untimed(message: String) : TrackLoadException(message)

    class TooLarge(message: String, cause: Throwable? = null) : TrackLoadException(message, cause)
}

/** What undoing a trim needs: the replaced file and row. */
class TrackEdit internal constructor(val id: Long, internal val backup: File, internal val before: TrackEntity)
