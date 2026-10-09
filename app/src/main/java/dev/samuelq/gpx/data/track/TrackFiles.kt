package dev.samuelq.gpx.data.track

import android.content.Context
import dev.samuelq.gpx.data.db.TrackEntity
import dev.samuelq.gpx.data.safeFileName
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * App-private track files, each named by its row's id: what a track is called lives in the row,
 * so a file's name never has to follow it. `res/xml/file_paths.xml` shares the directory.
 */
object TrackFiles {
    fun dir(context: Context): File = File(context.filesDir, "tracks").apply { mkdirs() }

    fun file(context: Context, id: Long): File = File(dir(context), "$id.gpx")

    /** A new track's file until its row exists; on the same filesystem, so moving it in is atomic. */
    fun stagingDir(context: Context): File = File(context.noBackupFilesDir, "staging").apply { mkdirs() }

    /** An unnamed track's export name, from its local start. `Locale.ROOT` keeps ASCII digits so they sort. */
    internal val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmmss", Locale.ROOT)
}

/** What a track is called: its name, else when it started. */
val TrackEntity.title: String
    get() = trackName?.takeIf(String::isNotBlank) ?: localized(startedAtEpochMillis)

/** Unnamed and titled by when it started. */
val TrackEntity.isTitledByStart: Boolean
    get() = trackName.isNullOrBlank()

/** The name to rename from; empty for a track titled by its start, which has none. */
val TrackEntity.editableName: String
    get() = trackName?.takeIf(String::isNotBlank).orEmpty()

/** The track's name, made safe as a filename; unnamed, its start as [TrackFiles.STAMP], which sorts. */
val TrackEntity.exportFileName: String
    get() {
        val base = trackName?.takeIf(String::isNotBlank)
            ?: TrackFiles.STAMP.format(Instant.ofEpochMilli(startedAtEpochMillis).atZone(ZoneId.systemDefault()))
        return safeFileName(base).ensureGpxSuffix()
    }

/** A file's name without `.gpx`, as an import is named when it has no `<name>`. */
internal fun String.withoutGpxSuffix(): String = if (endsWith(GPX, ignoreCase = true)) dropLast(GPX.length) else this

private fun String.ensureGpxSuffix(): String = if (endsWith(GPX, ignoreCase = true)) this else "$this$GPX"

private const val GPX = ".gpx"

/** The default locale is the app's: Android sets it from the app's locale too. */
private fun localized(epochMillis: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(Locale.getDefault())
        .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

/** A typed name, or null for blank so the caller falls back to its default. */
internal fun String.asTrackName(): String? = trim().ifEmpty { null }
