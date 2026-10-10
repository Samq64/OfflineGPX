package dev.samuelq.gpx.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.samuelq.gpx.core.model.GeoBounds
import dev.samuelq.gpx.data.track.RouteColor

/**
 * One row per track, holding its summary so files are only read to draw or chart them. Its
 * file is named by [id]; see [dev.samuelq.gpx.data.track.TrackFiles].
 */
@Entity(tableName = "tracks")
data class TrackEntity(
    /** AUTOINCREMENT, so an id, and with it a file name, is never reused once committed. */
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The row's alone, like the colour; null is titled by when it started. */
    val trackName: String?,

    /** Last import, record or open; orders the list and the map's stacking. */
    val lastOpenedAtEpochMillis: Long,

    val visible: Boolean = true,

    /** Assigned once so a track's colour only changes when the user picks another. */
    val color: RouteColor = RouteColor.Red,

    @Embedded val summary: TrackSummary,

    /** Null is uncategorised. */
    val category: String? = null,
) {
    val bounds: GeoBounds get() = summary.bounds

    // What the list sorts and shows by.
    val startedAtEpochMillis: Long get() = summary.startedAtEpochMillis

    val distanceMeters: Double get() = summary.distanceMeters

    val totalSeconds: Double get() = summary.totalSeconds
}

/**
 * The columns a track's points decide: what the list shows and sorts by and the map frames by,
 * so neither has to read the file. The sheet's fuller stats are analysed from the points.
 */
data class TrackSummary(
    /** Every point has a time: an import without them is refused. */
    val startedAtEpochMillis: Long,
    val distanceMeters: Double,
    val totalSeconds: Double,
    @Embedded val bounds: GeoBounds,
)

/** Written alone, so a rename or recolour meanwhile survives a trim or its undo. */
data class SummaryUpdate(val id: Long, @Embedded val summary: TrackSummary)
