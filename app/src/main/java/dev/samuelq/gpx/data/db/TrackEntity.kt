package dev.samuelq.gpx.data.db

import androidx.room.ColumnInfo
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

    /** Its columns come last, as version 2 added them; the start time predates it. */
    @Embedded val summary: TrackSummary,
) {
    /** Whether the summary has been read off the file; rows from before it was kept start without. */
    val summarised: Boolean get() = summary.pointCount >= 0

    /** Null until [summarised]. */
    val bounds: GeoBounds?
        get() = if (summarised) with(summary) { GeoBounds(southLatitude, westLongitude, northLatitude, eastLongitude) } else null

    // What the list sorts and shows by.
    val distanceMeters: Double get() = summary.distanceMeters

    val totalSeconds: Double get() = summary.totalSeconds

    companion object {
        const val PALETTE_SIZE = 7
    }
}

/**
 * The whole TrackStats but its start, and the bounds, so a list or a framing never has to read
 * the file. Defaults for rows from before these columns: pointCount -1 marks one
 * TrackRepository has yet to read.
 */
data class TrackSummary(
    @ColumnInfo(defaultValue = "-1") val pointCount: Int,
    val distanceMeters: Double,
    val totalSeconds: Double,
    @ColumnInfo(defaultValue = "0") val movingSeconds: Double,
    @ColumnInfo(defaultValue = "0") val averageSpeedMps: Double,
    @ColumnInfo(defaultValue = "0") val ascentMeters: Double,
    @ColumnInfo(defaultValue = "0") val descentMeters: Double,

    @ColumnInfo(defaultValue = "0") val southLatitude: Double,
    @ColumnInfo(defaultValue = "0") val westLongitude: Double,
    @ColumnInfo(defaultValue = "0") val northLatitude: Double,
    @ColumnInfo(defaultValue = "0") val eastLongitude: Double,
)

/** The columns a file's contents decide, written alone so a concurrent rename or recolour survives. */
data class SummaryUpdate(
    val id: Long,
    val startedAtEpochMillis: Long?,
    @Embedded val summary: TrackSummary,
)
