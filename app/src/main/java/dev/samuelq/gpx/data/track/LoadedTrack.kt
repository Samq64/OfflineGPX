package dev.samuelq.gpx.data.track

import dev.samuelq.gpx.core.analysis.TrackProfile
import dev.samuelq.gpx.core.model.Track

/** A track that has been read, parsed and analysed, ready to draw. */
class LoadedTrack(
    /** The `tracks` row, or [TRANSIENT_ID] for a track opened from an intent. */
    val id: Long,
    val displayName: String,
    val track: Track,
    val profile: TrackProfile,
    /**
     * The row's slot in the route palette, carried through so a track is the same colour
     * wherever it's drawn.
     */
    val colorIndex: Int,
) {
    /** The same track under a new name. Geometry is shared: a rename moves no points. */
    fun renamed(name: String?) = LoadedTrack(id, displayName, track.copy(name = name), profile, colorIndex)

    companion object {
        const val TRANSIENT_ID = 0L
    }
}

/** Why a file could not be turned into a [LoadedTrack]. The UI maps these to messages. */
sealed class TrackLoadException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Gone, renamed, or (for a transient, one-shot URI) no longer granted. */
    class Unreadable(message: String, cause: Throwable? = null) : TrackLoadException(message, cause)

    /** Readable, but not GPX. */
    class Invalid(message: String, cause: Throwable? = null) : TrackLoadException(message, cause)

    /** Valid GPX that contains no track points - waypoint-only files land here. */
    class Empty(message: String) : TrackLoadException(message)
}
