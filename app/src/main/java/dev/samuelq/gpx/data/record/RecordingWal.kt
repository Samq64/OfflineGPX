package dev.samuelq.gpx.data.record

import dev.samuelq.gpx.core.model.Track
import dev.samuelq.gpx.core.model.TrackPoint
import dev.samuelq.gpx.core.model.TrackSegment
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileWriter
import java.time.Instant

/**
 * The append-only log a recording is written to as it happens.
 *
 * A half-finished GPX file has no closing tags, so a crash mid-ride would leave nothing
 * recoverable; one flushed line per fix does. The only format the app invents, and it
 * exists so that losing a ride is not a possible outcome.
 *
 * ```
 * <epochMillis>,<lat>,<lon>[,<ele>]   a fix
 * -                                   a segment break (a pause, or lost signal)
 * ```
 */
class RecordingWal private constructor(
    val file: File,
    private val writer: BufferedWriter,
) : Closeable {

    fun append(point: TrackPoint) {
        val elevation = point.elevation?.toString() ?: ""
        writer.write("${point.time?.toEpochMilli() ?: 0},${point.latitude},${point.longitude},$elevation")
        writer.newLine()
        // Flushed per fix, at 1 Hz. The cost is negligible and it is the entire point:
        // an unflushed buffer is a lost ride.
        writer.flush()
    }

    fun appendBreak() {
        writer.write(BREAK)
        writer.newLine()
        writer.flush()
    }

    override fun close() = writer.close()

    /** Deletes the log. Called once its contents are safely a GPX file. */
    fun discard() {
        close()
        file.delete()
    }

    companion object {
        private const val BREAK = "-"

        /**
         * Opens [file] for appending. Append mode matters: opening for write would
         * truncate the log this class exists to protect.
         */
        fun open(file: File): RecordingWal {
            file.parentFile?.mkdirs()
            return RecordingWal(file, FileWriter(file, true).buffered())
        }

        /**
         * Rebuilds a track from a log.
         *
         * Tolerant on purpose: a line half-written when the power went is dropped rather
         * than failing the recovery of everything before it.
         */
        fun recover(file: File, name: String?): Track? {
            if (!file.exists()) return null

            val segments = mutableListOf<TrackSegment>()
            var current = mutableListOf<TrackPoint>()

            file.forEachLine { line ->
                val text = line.trim()
                when {
                    text.isEmpty() -> Unit
                    text == BREAK -> {
                        if (current.isNotEmpty()) segments += TrackSegment(current)
                        current = mutableListOf()
                    }

                    else -> parsePoint(text)?.let(current::add)
                }
            }
            if (current.isNotEmpty()) segments += TrackSegment(current)

            val usable = segments.filter { it.points.isNotEmpty() }
            if (usable.isEmpty()) return null
            return Track(name = name, segments = usable)
        }

        private fun parsePoint(line: String): TrackPoint? {
            val parts = line.split(',')
            if (parts.size < 3) return null
            val millis = parts[0].toLongOrNull() ?: return null
            val latitude = parts[1].toDoubleOrNull() ?: return null
            val longitude = parts[2].toDoubleOrNull() ?: return null
            if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
            return TrackPoint(
                latitude = latitude,
                longitude = longitude,
                elevation = parts.getOrNull(3)?.toDoubleOrNull(),
                time = millis.takeIf { it > 0 }?.let(Instant::ofEpochMilli),
            )
        }
    }
}
