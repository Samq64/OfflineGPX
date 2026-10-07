package dev.samuelq.gpx.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.samuelq.gpx.core.model.GeoBounds

/**
 * One row per track, holding its summary so files are only read to draw or chart them.
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

    @Embedded val summary: TrackSummary,

    /** Null is uncategorised. */
    val category: String? = null,
) {
    val bounds: GeoBounds
        get() = with(summary) { GeoBounds(southLatitude, westLongitude, northLatitude, eastLongitude) }

    // What the list sorts and shows by.
    val distanceMeters: Double get() = summary.distanceMeters

    val totalSeconds: Double get() = summary.totalSeconds

    companion object {
        const val PALETTE_SIZE = 7
    }
}

/** The whole TrackStats but its start, and the bounds, so a list or a framing never has to read the file. */
data class TrackSummary(
    val pointCount: Int,
    val distanceMeters: Double,
    val totalSeconds: Double,
    val movingSeconds: Double,
    val averageSpeedMps: Double,
    val ascentMeters: Double,
    val descentMeters: Double,

    val southLatitude: Double,
    val westLongitude: Double,
    val northLatitude: Double,
    val eastLongitude: Double,
)

/** The columns a file's contents decide, written alone so a concurrent rename or recolour survives. */
data class SummaryUpdate(val id: Long, val startedAtEpochMillis: Long?, @Embedded val summary: TrackSummary)
