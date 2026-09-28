package dev.samuelq.gpx.data.record

import dev.samuelq.gpx.core.model.Track
import dev.samuelq.gpx.core.model.TrackPoint
import dev.samuelq.gpx.core.model.TrackSegment
import dev.samuelq.gpx.core.model.isValidCoordinate
import dev.samuelq.gpx.core.model.Waypoint
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.util.Base64

/**
 * The append-only log a recording is written to as it happens.
 *
 * A half-finished GPX file has no closing tags, so a crash mid-ride would leave nothing
 * recoverable; one flushed line per fix does. The only format the app invents, and it
 * exists so that losing a ride is not a possible outcome.
 *
 * ```
 * <epochMillis>,<lat>,<lon>[,<ele>][,<accuracyMeters>]   a fix
 * -                                                       a segment break (a pause, or lost signal)
 * W,<epochMillis>,<lat>,<lon>[,<ele>][,<base64 desc>]     a waypoint
 * ```
 *
 * The waypoint line is its own kind rather than a fix with an extra field: a free-text
 * description can hold a comma or a newline, either of which would otherwise be read back
 * as more fields or more lines. Base64 sidesteps escaping it for what is, in the end, a
 * write-only log nobody but this class ever reads.
 */
class RecordingWal private constructor(
    val file: File,
    private val writer: BufferedWriter,
) : Closeable {

    fun append(point: TrackPoint) {
        val elevation = point.elevation?.toString() ?: ""
        val accuracy = point.accuracyMeters?.toString() ?: ""
        writer.write(
            "${point.time?.toEpochMilli() ?: 0},${point.latitude},${point.longitude},$elevation,$accuracy"
        )
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

    fun appendWaypoint(waypoint: Waypoint) {
        val point = waypoint.point
        val elevation = point.elevation?.toString() ?: ""
        val description = waypoint.description
            ?.let { Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) }
            ?: ""
        writer.write(
            "$WAYPOINT,${point.time?.toEpochMilli() ?: 0},${point.latitude},${point.longitude}," +
                "$elevation,$description"
        )
        writer.newLine()
        writer.flush()
    }

    override fun close() = writer.close()

    /** Deletes the log. A failed close is ignored: the contents are going either way. */
    fun discard() {
        runCatching(::close)
        file.delete()
    }

    companion object {
        private const val BREAK = "-"
        private const val WAYPOINT = "W"
        private const val WAYPOINT_PREFIX = "$WAYPOINT,"

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
        fun recover(file: File): Track? {
            if (!file.exists()) return null

            val segments = mutableListOf<TrackSegment>()
            var current = mutableListOf<TrackPoint>()
            val waypoints = mutableListOf<Waypoint>()

            file.forEachLine { line ->
                val text = line.trim()
                when {
                    text.isEmpty() -> Unit
                    text == BREAK -> {
                        if (current.isNotEmpty()) segments += TrackSegment(current)
                        current = mutableListOf()
                    }

                    text.startsWith(WAYPOINT_PREFIX) -> parseWaypoint(text)?.let(waypoints::add)
                    else -> parsePoint(text)?.let(current::add)
                }
            }
            if (current.isNotEmpty()) segments += TrackSegment(current)

            val usable = segments.filter { it.points.isNotEmpty() }
            if (usable.isEmpty()) return null
            return Track(name = null, segments = usable, waypoints = waypoints)
        }

        private fun parsePoint(line: String): TrackPoint? {
            val parts = line.split(',')
            if (parts.size < 3) return null
            val millis = parts[0].toLongOrNull() ?: return null
            val latitude = parts[1].toDoubleOrNull() ?: return null
            val longitude = parts[2].toDoubleOrNull() ?: return null
            if (!isValidCoordinate(latitude, longitude)) return null
            return TrackPoint(
                latitude = latitude,
                longitude = longitude,
                elevation = parts.getOrNull(3)?.toDoubleOrNull(),
                time = millis.takeIf { it > 0 }?.let(Instant::ofEpochMilli),
                accuracyMeters = parts.getOrNull(4)?.toDoubleOrNull(),
            )
        }

        private fun parseWaypoint(line: String): Waypoint? {
            // limit = 6: a base64 description never contains a comma, but split has no way
            // to know that, and one more field than expected would otherwise be silently lost.
            val parts = line.split(',', limit = 6)
            if (parts.size < 4) return null
            val millis = parts[1].toLongOrNull() ?: return null
            val latitude = parts[2].toDoubleOrNull() ?: return null
            val longitude = parts[3].toDoubleOrNull() ?: return null
            if (!isValidCoordinate(latitude, longitude)) return null
            val description = parts.getOrNull(5)?.takeIf(String::isNotEmpty)?.let {
                runCatching { String(Base64.getDecoder().decode(it), Charsets.UTF_8) }.getOrNull()
            }
            return Waypoint(
                TrackPoint(
                    latitude = latitude,
                    longitude = longitude,
                    elevation = parts.getOrNull(4)?.toDoubleOrNull(),
                    time = millis.takeIf { it > 0 }?.let(Instant::ofEpochMilli),
                ),
                description,
            )
        }
    }
}
