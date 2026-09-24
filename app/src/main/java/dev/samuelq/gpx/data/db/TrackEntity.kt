package dev.samuelq.gpx.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Where a track came from. Both are an app-private file now; this is only their history. */
enum class TrackSource {
    /** Copied in from a file the user picked elsewhere. */
    IMPORTED,

    /** Written by this app's own recorder. */
    RECORDED,
}

/**
 * One row per track, over both sources, because the library and the stats screens want
 * them in one query.
 *
 * Holds no geometry - see the README. [location] is an app-private GPX file path for either
 * [TrackSource]. Everything else is summary, denormalised so a list row never reparses a file.
 */
@Entity(
    tableName = "tracks",
    // Not load-bearing (two imports never land on the same generated path), but still
    // catches an app-private naming bug rather than silently overwriting a row.
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

    /**
     * The last interaction: imported, recorded or opened. The list is ordered by it, most
     * recent first, and the map stacks by it, most recent on top.
     */
    val lastOpenedAtEpochMillis: Long,

    /**
     * Whether this track is drawn on the map. Persisted, not a UI-session flag, since which
     * tracks are overlaid is a curation decision. New tracks arrive visible.
     */
    @ColumnInfo(index = true, defaultValue = "1") val visible: Boolean = true,

    /**
     * Which slot of the route palette this track is drawn in. Assigned once at import and
     * never recomputed, so a track's colour never changes under the user.
     */
    @ColumnInfo(defaultValue = "0") val colorIndex: Int = 0,

    val distanceMeters: Double,
    val movingSeconds: Double,
    val totalSeconds: Double,
    val ascentMeters: Double,
    val descentMeters: Double,
    val pointCount: Int,
)
