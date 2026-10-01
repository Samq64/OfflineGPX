package dev.samuelq.gpx.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.samuelq.gpx.core.analysis.TrackStats
import dev.samuelq.gpx.core.model.GeoBounds
import java.time.Instant

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

    // The whole TrackStats, so a list or a framing never has to read the file.
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
) {
    val bounds: GeoBounds get() = GeoBounds(southLatitude, westLongitude, northLatitude, eastLongitude)

    val stats: TrackStats
        get() = TrackStats(
            startedAt = startedAtEpochMillis?.let(Instant::ofEpochMilli),
            pointCount = pointCount,
            distanceMeters = distanceMeters,
            totalDurationSeconds = totalSeconds,
            movingDurationSeconds = movingSeconds,
            averageSpeedMps = averageSpeedMps,
            ascentMeters = ascentMeters,
            descentMeters = descentMeters,
        )

    companion object {
        const val PALETTE_SIZE = 7

        /** Grey, for tracks worth showing but not noticing; picked by hand, never assigned. */
        const val NEUTRAL_SLOT = PALETTE_SIZE - 1
    }
}
