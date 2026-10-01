package dev.samuelq.gpx.data.track

import dev.samuelq.gpx.core.analysis.TrackProfile
import dev.samuelq.gpx.core.model.Track
import dev.samuelq.gpx.data.db.TrackEntity

/** A parsed and analysed track, ready to draw. */
class LoadedTrack(
    /** The `tracks` row, or [TRANSIENT_ID] for a track opened from an intent. */
    val id: Long,
    val displayName: String,
    val track: Track,
    val profile: TrackProfile,
    val colorIndex: Int,
) {
    /** With its row's name and colour, which can change after it was read. */
    fun synced(name: String?, colorIndex: Int) =
        LoadedTrack(id, displayName, track.copy(name = name), profile, colorIndex)

    fun matches(row: TrackEntity) = track.name == row.trackName && colorIndex == row.colorIndex

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
