package dev.samuelq.gpx.data.track

import dev.samuelq.gpx.core.analysis.TrackProfile
import dev.samuelq.gpx.core.model.Track
import dev.samuelq.gpx.data.db.TrackEntity
import kotlinx.coroutines.flow.Flow

/** A track that has been read, parsed and analysed, ready to draw. */
class LoadedTrack(
    /** The `tracks` row, or [TRANSIENT_ID] for a track opened from an intent. */
    val id: Long,
    val displayName: String,
    val track: Track,
    val profile: TrackProfile,
) {
    companion object {
        const val TRANSIENT_ID = 0L
    }
}

/** Why a file could not be turned into a [LoadedTrack]. The UI maps these to messages. */
sealed class TrackLoadException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Gone, renamed, or the persisted permission grant was revoked. */
    class Unreadable(message: String, cause: Throwable? = null) : TrackLoadException(message, cause)

    /** Readable, but not GPX. */
    class Invalid(message: String, cause: Throwable? = null) : TrackLoadException(message, cause)

    /** Valid GPX that contains no track points - waypoint-only files land here. */
    class Empty(message: String) : TrackLoadException(message)
}

/**
 * The single way the app gets at track data.
 *
 * The UI depends on this rather than on the GPX parser, so a track's source is not baked
 * into the screens. That is the seam recording uses: a recorder produces the same [Track]
 * and adds a write method here, and the chart screens carry on unchanged.
 */
interface TrackRepository {

    val tracks: Flow<List<TrackEntity>>

    /**
     * Persists the SAF grant, reads the file, and indexes it. Returns the row id, which is
     * what navigation carries.
     *
     * Re-importing a file already in the library updates that row instead of adding a
     * second one.
     */
    suspend fun import(location: String): Result<Long>

    suspend fun open(id: Long): Result<LoadedTrack>

    /**
     * Reads a track without indexing it, for VIEW and SEND intents. Those URIs are
     * one-shot grants, so a library row for one would only fail when tapped.
     */
    suspend fun openTransient(location: String): Result<LoadedTrack>

    suspend fun forget(id: Long)

    suspend fun clearAll()
}
