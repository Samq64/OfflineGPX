package dev.samuelq.gpx.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Where a track's points live, and therefore who owns the file. */
enum class TrackSource {
    /** A file the user owns elsewhere, reached through a persisted SAF grant. */
    IMPORTED,

    /** A file this app wrote into its own storage. Deleting the row deletes the file. */
    RECORDED,
}

/**
 * One row per track, over both sources, because the library lists them together and the
 * stats screens want them in one query.
 *
 * Holds no geometry - see the README. [location] points at a GPX file: a SAF URI for
 * [TrackSource.IMPORTED], an app-private path for [TrackSource.RECORDED]. Everything else
 * is summary, denormalised so a list row never reparses a file.
 */
@Entity(
    tableName = "tracks",
    // Re-importing the same file updates the existing row rather than duplicating it.
    indices = [Index(value = ["location"], unique = true)],
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: TrackSource,
    val location: String,
    /** The filename. The `<name>` inside the file is often absent, so this is the fallback. */
    val displayName: String,
    val trackName: String?,

    /** When the activity happened. Null for a file with no timestamps. */
    @ColumnInfo(index = true) val startedAtEpochMillis: Long?,

    /** When the app last showed it - the library's sort order when there is no start time. */
    val lastOpenedAtEpochMillis: Long,

    val distanceMeters: Double,
    val movingSeconds: Double,
    val totalSeconds: Double,
    val ascentMeters: Double,
    val descentMeters: Double,
    val pointCount: Int,
)
