package dev.samuelq.gpx.data.track

import dev.samuelq.gpx.core.analysis.TrackProfile
import dev.samuelq.gpx.core.model.Track
import dev.samuelq.gpx.data.db.TrackEntity
import kotlinx.coroutines.flow.Flow
import java.time.Instant

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
    val colorIndex: Int = 0,
) {
    companion object {
        const val TRANSIENT_ID = 0L
    }
}

/** A recording left unsaved by a crash, read back and waiting to be saved or discarded. */
class AbandonedRecording(
    internal val file: java.io.File,
    val track: Track,
    val profile: TrackProfile,
    /** What it would be called if saved without a name. */
    val defaultName: String,
)

/** Why a file could not be turned into a [LoadedTrack]. The UI maps these to messages. */
sealed class TrackLoadException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Gone, renamed, or (for a transient, one-shot URI) no longer granted. */
    class Unreadable(message: String, cause: Throwable? = null) : TrackLoadException(message, cause)

    /** Readable, but not GPX. */
    class Invalid(message: String, cause: Throwable? = null) : TrackLoadException(message, cause)

    /** Valid GPX that contains no track points - waypoint-only files land here. */
    class Empty(message: String) : TrackLoadException(message)
}

/**
 * The single way the app gets at track data, so a track's source is never baked into a
 * screen - the seam recording uses to add a write method here without touching the charts.
 */
interface TrackRepository {

    /** Most recently interacted with first. The app's only ordering. */
    val tracks: Flow<List<TrackEntity>>

    /**
     * What the map draws: [tracks] filtered to the visible ones and reversed, so the most
     * recently touched is painted last and lands on top.
     */
    val visibleTracks: Flow<List<TrackEntity>>

    /**
     * Copies [location] into app-private storage, reads it, and indexes the copy. Returns
     * the row id. No dedupe - picking the same file twice makes two rows, like recording
     * twice does.
     */
    suspend fun import(location: String): Result<Long>

    suspend fun open(id: Long): Result<LoadedTrack>

    /**
     * Writes a finished recording to app-private storage as GPX and indexes it. Returns
     * the new row id.
     *
     * @param analyzed the profile of this exact [track], when the caller already has one -
     *   recovery does, and re-deriving it walks every point again for the same answer.
     */
    suspend fun saveRecording(
        track: Track,
        startedAt: Instant,
        analyzed: TrackProfile? = null,
    ): Result<Long>

    /**
     * Moves a recording that was never saved - the process died, or the save failed - out
     * of the live log's way, to wait for [abandonedRecordings]. Runs at startup and before
     * each recording. Returns whether the live log's name is free, so a new recording
     * can't be appended to an old one.
     */
    suspend fun claimAbandonedRecording(): Boolean

    /**
     * Every claimed recording worth asking about, oldest first. Ones a clean stop would
     * have thrown away are deleted rather than offered.
     */
    suspend fun abandonedRecordings(): List<AbandonedRecording>

    /** Saves [recording] as a track called [name], or the default name if blank. */
    suspend fun saveAbandoned(recording: AbandonedRecording, name: String): Result<Long>

    suspend fun discardAbandoned(recording: AbandonedRecording)

    /**
     * Writes several tracks into [treeUri], a folder chosen through SAF, under the [names]
     * given here. Returns how many landed - one unwritable name doesn't cost the rest.
     */
    suspend fun exportAll(names: Map<Long, String>, treeUri: String): Result<Int>

    /**
     * Reads a track without indexing it, for VIEW and SEND intents. Those URIs are
     * one-shot grants, so a library row for one would only fail when tapped.
     */
    suspend fun openTransient(location: String): Result<LoadedTrack>

    /**
     * Reads a track's geometry without recording that it was opened - the map drawing a
     * track isn't the user looking at it, and [open] would rewrite the sort order every redraw.
     */
    suspend fun geometry(id: Long): Result<LoadedTrack>

    /** Records that the user touched this track, which is what the map stacks on. */
    suspend fun touch(id: Long)

    /**
     * Renames a track. A blank [name] clears it, falling the row back to its filename.
     *
     * The GPX on disk is rewritten too, whichever source it came from, so an export
     * carries the name the user gave it rather than the one the file arrived with.
     */
    suspend fun rename(id: Long, name: String): Result<Unit>

    suspend fun setVisible(ids: List<Long>, visible: Boolean)

    suspend fun setAllVisible(visible: Boolean)

    suspend fun forgetAll(ids: List<Long>)
}
