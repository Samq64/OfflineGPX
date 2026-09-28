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
 * Append-only recording log, one flushed line per fix, so a crash mid-ride stays
 * recoverable (a half-written GPX would not be).
 *
 * ```
 * <epochMillis>,<lat>,<lon>[,<ele>][,<accuracyMeters>]   a fix
 * -                                                       a segment break
 * W,<epochMillis>,<lat>,<lon>[,<ele>][,<base64 desc>]     a waypoint
 * ```
 *
 * Base64 keeps commas and newlines in descriptions from breaking the format.
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

    /** Deletes the log, ignoring a failed close. */
    fun discard() {
        runCatching(::close)
        file.delete()
    }

    companion object {
        private const val BREAK = "-"
        private const val WAYPOINT = "W"
        private const val WAYPOINT_PREFIX = "$WAYPOINT,"

        /** Opens [file] for appending, never truncating. */
        fun open(file: File): RecordingWal {
            file.parentFile?.mkdirs()
            return RecordingWal(file, FileWriter(file, true).buffered())
        }

        /** Rebuilds a track from a log, dropping malformed (half-written) lines. */
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
