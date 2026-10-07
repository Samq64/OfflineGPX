package dev.samuelq.gpx.data.track

import android.content.Context
import dev.samuelq.gpx.data.db.TrackEntity
import dev.samuelq.gpx.data.safeFileName
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** App-private track directories; `res/xml/file_paths.xml` shares both. */
object TrackFiles {
    private const val RECORDINGS = "recordings"

    fun recordingsDir(context: Context): File = File(context.filesDir, RECORDINGS).apply { mkdirs() }

    fun importsDir(context: Context): File = File(context.filesDir, "imports").apply { mkdirs() }

    /** The file behind a [TrackEntity.location]. */
    fun file(context: Context, location: String): File = File(context.filesDir, location)

    /** Relative, since a device transfer may restore filesDir under another path. */
    fun location(context: Context, file: File): String = file.relativeTo(context.filesDir).path

    /** Recorded by the app, not imported; a copy of one is too. */
    fun isRecording(entity: TrackEntity): Boolean = entity.location.substringBefore('/') == RECORDINGS

    /** A recording's filename, from its local start. `Locale.ROOT` keeps ASCII digits so they sort. */
    internal val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmmss", Locale.ROOT)

    private val STAMPED = Regex("""(\d{4}-\d{2}-\d{2}T\d{6})(?: \(\d+\))?\.gpx""")

    /** When a recording named by [STAMP] started, read off its filename; null for any other name. */
    fun stampOf(displayName: String): LocalDateTime? = STAMPED.matchEntire(displayName)?.let {
        runCatching { LocalDateTime.parse(it.groupValues[1], STAMP) }.getOrNull()
    }

    /** [stampOf], formatted as [recordedAt] is. */
    fun recordedAt(displayName: String): String? = stampOf(displayName)?.let(::localized)

    /**
     * When an app recording started, for its title while unnamed. From the row, so any
     * filename does; the stamp only stands in for a row without a start.
     */
    fun recordedAt(entity: TrackEntity): String? {
        if (!isRecording(entity)) return null
        return entity.startedAtEpochMillis
            ?.let { localized(LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault())) }
            ?: recordedAt(entity.displayName)
    }

    /** The default locale is the app's: Android sets it from the app's locale too. */
    private fun localized(at: LocalDateTime): String = DateTimeFormatter.ofLocalizedDateTime(
        FormatStyle.MEDIUM,
        FormatStyle.SHORT,
    ).withLocale(Locale.getDefault()).format(at)
}

/** What a track is called: its name, else when a recording started, else the file it arrived as. */
val TrackEntity.title: String
    get() = trackName?.takeIf(String::isNotBlank) ?: TrackFiles.recordedAt(this) ?: displayName

/** Unnamed and titled by when it was recorded, rather than by its filename. */
val TrackEntity.isTitledByStart: Boolean
    get() = trackName.isNullOrBlank() && TrackFiles.recordedAt(this) != null

/** [title] without the `.gpx` extension. */
val TrackEntity.titleStem: String
    get() = title.dropGpxSuffix()

/** The track's name, made safe as a filename; unnamed, the file it's stored under, whose stamp sorts. */
fun exportFileName(trackName: String?, displayName: String): String =
    safeFileName(trackName?.takeIf(String::isNotBlank) ?: displayName.dropGpxSuffix()).ensureGpxSuffix()

private fun String.dropGpxSuffix(): String = if (endsWith(GPX, ignoreCase = true)) dropLast(GPX.length) else this

private fun String.ensureGpxSuffix(): String = if (endsWith(GPX, ignoreCase = true)) this else "$this$GPX"

private const val GPX = ".gpx"

/** [titleStem] to rename from, but empty for a recording titled by its start: that's no name. */
val TrackEntity.editableName: String
    get() = if (isTitledByStart) "" else titleStem

/** A typed name, or null for blank so the caller falls back to its default. */
internal fun String.asTrackName(): String? = trim().ifEmpty { null }
