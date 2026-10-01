package dev.samuelq.gpx.data.track

import android.content.Context
import dev.samuelq.gpx.R
import dev.samuelq.gpx.core.analysis.TrackStats
import dev.samuelq.gpx.data.db.TrackEntity
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/** App-private track directories; `res/xml/file_paths.xml` shares both. */
object TrackFiles {
    fun recordingsDir(context: Context): File = File(context.filesDir, "recordings").apply { mkdirs() }

    fun importsDir(context: Context): File = File(context.filesDir, "imports").apply { mkdirs() }

    /** The file behind a [TrackEntity.location]. */
    fun file(context: Context, location: String): File = File(context.filesDir, location)

    /** Relative, since a device transfer may restore filesDir under another path. */
    fun location(context: Context, file: File): String = file.relativeTo(context.filesDir).path
}

/** A typed name, or null for blank so the caller falls back to its default. */
internal fun String.asTrackName(): String? = trim().ifEmpty { null }

/** Weekday and part of day, like "Saturday morning". Saying nothing of the activity, which speed can't tell. */
internal fun defaultTrackName(context: Context, stats: TrackStats): String =
    defaultTrackName(context, stats.startedAt)

internal fun defaultTrackName(context: Context, startedAt: Instant?): String {
    val (partOfDay, weekday) = defaultNameParts(
        (startedAt ?: Instant.now()).atZone(ZoneId.systemDefault()),
        context.resources.configuration.locales[0],
    )
    return context.getString(partOfDay, weekday)
}

/** The part-of-day string and the weekday it takes. */
internal fun defaultNameParts(at: ZonedDateTime, locale: Locale): Pair<Int, String> {
    val partOfDay = when (at.hour) {
        in 5..11 -> R.string.track_default_morning
        in 12..16 -> R.string.track_default_afternoon
        in 17..20 -> R.string.track_default_evening
        else -> R.string.track_default_night
    }
    // 1 am on a Sunday is Saturday night.
    val day = if (at.hour < 5) at.minusDays(1) else at
    return partOfDay to day.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
}
