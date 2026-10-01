package dev.samuelq.gpx.data.track

import dev.samuelq.gpx.core.analysis.TrackProfile
import dev.samuelq.gpx.core.model.Track

/**
 * A parsed and analysed track, ready to draw. A saved track's name and colour are its row's,
 * read from there rather than copied here so they can't go stale.
 */
class LoadedTrack(
    /** The `tracks` row, or [TRANSIENT_ID] for a track opened from an intent. */
    val id: Long,
    /** The filename. */
    val displayName: String,
    val track: Track,
    val profile: TrackProfile,
) {
    companion object {
        const val TRANSIENT_ID = 0L
    }
}

/** The UI maps these to messages. */
sealed class TrackLoadException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Unreadable(message: String, cause: Throwable? = null) : TrackLoadException(message, cause)

    /** Readable, but not GPX. */
    class Invalid(message: String, cause: Throwable? = null) : TrackLoadException(message, cause)

    /** No track points, e.g. waypoint-only files. */
    class Empty(message: String) : TrackLoadException(message)
}
