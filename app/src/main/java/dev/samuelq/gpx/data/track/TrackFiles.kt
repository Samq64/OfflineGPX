package dev.samuelq.gpx.data.track

import android.content.Context
import dev.samuelq.gpx.data.db.TrackEntity
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** App-private track directories; `res/xml/file_paths.xml` shares both. */
object TrackFiles {
    fun recordingsDir(context: Context): File = File(context.filesDir, "recordings").apply { mkdirs() }

    fun importsDir(context: Context): File = File(context.filesDir, "imports").apply { mkdirs() }

    /** The file behind a [TrackEntity.location]. */
    fun file(context: Context, location: String): File = File(context.filesDir, location)

    /** Relative, since a device transfer may restore filesDir under another path. */
    fun location(context: Context, file: File): String = file.relativeTo(context.filesDir).path

    /** A recording's filename, from its local start. `Locale.ROOT` keeps ASCII digits so they sort. */
    internal val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmmss", Locale.ROOT)

    private val STAMPED = Regex("""(\d{4}-\d{2}-\d{2}T\d{6})(?: \(\d+\))?\.gpx""")

    /** When a recording named by [STAMP] started, read off its filename; null for any other name. */
    fun stampOf(displayName: String): LocalDateTime? = STAMPED.matchEntire(displayName)?.let {
        runCatching { LocalDateTime.parse(it.groupValues[1], STAMP) }.getOrNull()
    }

    /** [stampOf] in the default locale: what an unnamed recording is called. */
    fun recordedAt(displayName: String): String? = stampOf(displayName)?.let {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(Locale.getDefault()).format(it)
    }
}

/** A typed name, or null for blank so the caller falls back to its default. */
internal fun String.asTrackName(): String? = trim().ifEmpty { null }
