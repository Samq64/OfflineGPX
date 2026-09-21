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
 * One row per track, over both sources, because the library lists them together and the
 * stats screens want them in one query.
 *
 * Holds no geometry - see the README. [location] points at a GPX file: an app-private path
 * for either [TrackSource], since an import is copied in rather than read where the user
 * left it - the same reasoning `MapStore` copies a basemap in for, applied here so a
 * recording and an import are the same kind of thing to the rest of this class. Everything
 * else is summary, denormalised so a list row never reparses a file.
 */
@Entity(
    tableName = "tracks",
    // Not load-bearing any more - two imports never land on the same generated path - but
    // still worth having as a constraint an app-private naming bug would trip rather than
    // silently overwrite a row.
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

    /** When the app last showed it - the list's sort order when there is no start time. */
    val lastOpenedAtEpochMillis: Long,

    /**
     * Whether this track is drawn on the map screen.
     *
     * Persisted rather than a UI-session flag: which tracks you want overlaid is a
     * curation decision, and one that should not evaporate when the process dies. New
     * tracks arrive visible - you just made or imported it, so you want to see it - and
     * the list is where a pile-up gets pruned.
     */
    @ColumnInfo(index = true, defaultValue = "1") val visible: Boolean = true,

    /**
     * Which slot of the route palette this track is drawn in.
     *
     * Assigned once, at import, and never recomputed. Colour here identifies *a track*,
     * so it has to be the same every time the user looks - deriving it from a position in
     * a list would repaint the map on every sort, and from stacking order would repaint it
     * on every tap.
     */
    @ColumnInfo(defaultValue = "0") val colorIndex: Int = 0,

    val distanceMeters: Double,
    val movingSeconds: Double,
    val totalSeconds: Double,
    val ascentMeters: Double,
    val descentMeters: Double,
    val pointCount: Int,
)
