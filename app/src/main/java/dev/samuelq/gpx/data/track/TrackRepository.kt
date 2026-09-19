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
     * wherever it is drawn. Without it the detail screen picked its own hue and every
     * track was blue there while the list and the map agreed on something else.
     */
    val colorIndex: Int = 0,
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

    /** Most recently interacted with first. The app's only ordering. */
    val tracks: Flow<List<TrackEntity>>

    /**
     * What the map draws: [tracks] filtered to the visible ones and reversed, so the most
     * recently touched is painted last and lands on top of the pile. The list and the map
     * therefore agree by construction rather than by two orderings kept in step.
     */
    val visibleTracks: Flow<List<TrackEntity>>

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
     * Writes a finished recording to app-private storage as GPX and indexes it. Returns
     * the new row id.
     */
    suspend fun saveRecording(track: Track, startedAt: Instant): Result<Long>

    /**
     * Rescues a recording whose process died before it could be stopped.
     *
     * The write-ahead log only earns its place if something reads it back, so this runs
     * once at startup. Returns the new row id, or null when there was nothing to recover.
     */
    suspend fun recoverAbandonedRecording(): Long?

    /**
     * Copies a track's GPX to [destination], a document the user chose through SAF.
     *
     * The point of recording into GPX rather than a table: what the app has on disk is
     * already the file the user wants, so this is a byte copy and not a serializer that
     * could disagree with the recorder.
     */
    suspend fun export(id: Long, destination: String): Result<Unit>

    /**
     * Reads a track without indexing it, for VIEW and SEND intents. Those URIs are
     * one-shot grants, so a library row for one would only fail when tapped.
     */
    suspend fun openTransient(location: String): Result<LoadedTrack>

    /**
     * Reads a track's geometry without recording that it was opened.
     *
     * The map draws every visible track, which is not the same as the user looking at any
     * of them - routing that through [open] would rewrite the whole list's sort order on
     * every redraw.
     */
    suspend fun geometry(id: Long): Result<LoadedTrack>

    /** Records that the user touched this track, which is what the map stacks on. */
    suspend fun touch(id: Long)

    /**
     * Renames a track. A blank [name] clears it, falling the row back to its filename.
     *
     * For a recording the GPX on disk is rewritten too, so an export carries the name the
     * user gave it. An imported file is never modified - it is the user's, and an app that
     * edits files it was only asked to read is a bad neighbour.
     */
    suspend fun rename(id: Long, name: String): Result<Unit>

    suspend fun setVisible(ids: List<Long>, visible: Boolean)

    suspend fun setAllVisible(visible: Boolean)

    suspend fun forget(id: Long)

    suspend fun forgetAll(ids: List<Long>)

    suspend fun clearAll()
}
