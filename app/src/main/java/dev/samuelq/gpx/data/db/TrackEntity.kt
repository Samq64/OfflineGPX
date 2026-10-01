package dev.samuelq.gpx.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per track, holding what a list row shows so files are never reparsed.
 * [location] is the GPX file's path under filesDir.
 */
@Entity(
    tableName = "tracks",
    // Catches a naming bug rather than silently overwriting a row.
    indices = [Index(value = ["location"], unique = true)],
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val location: String,
    /** The filename; fallback when the file has no `<name>`. */
    val displayName: String,
    val trackName: String?,

    val startedAtEpochMillis: Long?,

    /** Last import, record or open; orders the list and the map's stacking. */
    val lastOpenedAtEpochMillis: Long,

    val visible: Boolean = true,

    /** Assigned once so a track's colour only changes when the user picks another. */
    val colorIndex: Int = 0,

    val distanceMeters: Double,
    val totalSeconds: Double,
) {
    companion object {
        const val PALETTE_SIZE = 7

        /** Grey, for tracks worth showing but not noticing; picked by hand, never assigned. */
        const val NEUTRAL_SLOT = PALETTE_SIZE - 1
    }
}
