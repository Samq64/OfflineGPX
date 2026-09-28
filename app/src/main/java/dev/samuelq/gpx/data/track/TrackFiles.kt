package dev.samuelq.gpx.data.track

import android.content.Context
import dev.samuelq.gpx.R
import dev.samuelq.gpx.core.analysis.TrackStats
import dev.samuelq.gpx.data.db.TrackEntity
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * Where a track's GPX file lives. Both are app-private: no permission, and ours to delete.
 * `res/xml/file_paths.xml` shares both with other apps.
 */
object TrackFiles {
    fun recordingsDir(context: Context): File = File(context.filesDir, "recordings").apply { mkdirs() }

    fun importsDir(context: Context): File = File(context.filesDir, "imports").apply { mkdirs() }

    /** The file behind a [TrackEntity.location]. */
    fun file(context: Context, location: String): File = File(context.filesDir, location)

    /** Relative to filesDir, which a device transfer may restore under another path. */
    fun location(context: Context, file: File): String = file.relativeTo(context.filesDir).path
}

/**
 * What a recording is called before anyone renames it: time of day plus walk-or-ride,
 * inferred from average moving speed (hiking 3-6 km/h, cycling 15-30, safely apart).
 */
internal fun defaultTrackName(context: Context, stats: TrackStats): String {
    val zoned = (stats.startedAt ?: Instant.now()).atZone(ZoneId.systemDefault())
    val activity = if (stats.averageSpeedMps < WALKING_SPEED_CEILING_MPS) {
        R.string.track_default_walk
    } else {
        R.string.track_default_ride
    }
    val partOfDay = when (zoned.hour) {
        in 5..11 -> R.string.track_default_morning
        in 12..16 -> R.string.track_default_afternoon
        in 17..20 -> R.string.track_default_evening
        else -> R.string.track_default_night
    }
    return context.getString(partOfDay, context.getString(activity))
}

/** 9 km/h. Above a brisk walk, well below a bicycle. */
private const val WALKING_SPEED_CEILING_MPS = 2.5
