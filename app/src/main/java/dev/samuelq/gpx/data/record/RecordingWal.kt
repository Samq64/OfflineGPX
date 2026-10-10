package dev.samuelq.gpx.data.record

import dev.samuelq.gpx.core.model.Track
import dev.samuelq.gpx.core.model.TrackPoint
import dev.samuelq.gpx.core.model.TrackPointsBuilder
import dev.samuelq.gpx.core.model.Waypoint
import dev.samuelq.gpx.core.model.isValidCoordinate
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.util.Base64
import java.util.zip.CRC32

/**
 * Append-only recording log, one flushed line per fix, so a crash mid-ride stays
 * recoverable (a half-written GPX would not be).
 *
 * ```
 * <epochMillis>,<lat>,<lon>,[<ele>],[<accuracyMeters>]*<crc>   a fix
 * -*<crc>                                                      a segment break
 * W,<epochMillis>,<lat>,<lon>,[<ele>],[<base64 name>]*<crc>    a waypoint
 * C,<Garmin colour name>*<crc>                                  the line's colour; the last holds
 * ```
 *
 * The CRC-32 of the line before the `*` drops one a power cut tore or storage corrupted.
 * Base64 keeps commas and newlines in names from breaking the format.
 */
class RecordingWal private constructor(val file: File, private val writer: BufferedWriter) : Closeable {

    fun append(point: TrackPoint) {
        val elevation = point.elevation?.toString() ?: ""
        val accuracy = point.accuracyMeters?.toString() ?: ""
        writeLine("${point.time?.toEpochMilli() ?: 0},${point.latitude},${point.longitude},$elevation,$accuracy")
    }

    fun appendBreak() = writeLine(BREAK)

    fun appendWaypoint(waypoint: Waypoint) {
        val point = waypoint.point
        val elevation = point.elevation?.toString() ?: ""
        val name = waypoint.name
            ?.let { Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) }
            ?: ""
        writeLine("$WAYPOINT,${point.time?.toEpochMilli() ?: 0},${point.latitude},${point.longitude},$elevation,$name")
    }

    fun appendColor(garminName: String) = writeLine("$COLOR_PREFIX$garminName")

    private fun writeLine(line: String) {
        writer.write("$line$CHECK${checksum(line)}")
        writer.newLine()
        writer.flush()
    }

    override fun close() = writer.close()

    companion object {
        private const val BREAK = "-"
        private const val WAYPOINT = "W"
        private const val WAYPOINT_PREFIX = "$WAYPOINT,"
        private const val COLOR_PREFIX = "C,"
        private const val CHECK = '*'

        /** Opens [file] for appending, never truncating. */
        fun open(file: File): RecordingWal {
            file.parentFile?.mkdirs()
            return RecordingWal(file, FileWriter(file, true).buffered())
        }

        private fun checksum(line: String): String =
            "%08x".format(CRC32().apply { update(line.toByteArray(Charsets.UTF_8)) }.value)

        /** The line without its checksum, or null if that doesn't match or is missing. */
        private fun verified(line: String): String? {
            val at = line.lastIndexOf(CHECK)
            if (at < 0) return null
            val content = line.substring(0, at)
            return content.takeIf { line.substring(at + 1) == checksum(it) }
        }

        /** Rebuilds a track from a log, dropping torn, corrupt or malformed lines. */
        fun recover(file: File): Track? {
            if (!file.exists()) return null

            val points = TrackPointsBuilder()
            val waypoints = mutableListOf<Waypoint>()
            var color: String? = null

            file.forEachLine { line ->
                val text = verified(line.trim()) ?: return@forEachLine
                when {
                    text == BREAK -> points.startSegment()
                    text.startsWith(WAYPOINT_PREFIX) -> parseWaypoint(text)?.let(waypoints::add)
                    text.startsWith(COLOR_PREFIX) -> color = text.removePrefix(COLOR_PREFIX)
                    else -> parsePoint(text)?.let(points::add)
                }
            }
            if (points.size == 0) return null
            return Track(name = null, points = points.build(), waypoints = waypoints, displayColor = color)
        }

        private fun parsePoint(line: String): TrackPoint? {
            val parts = line.split(',')
            if (parts.size != 5) return null
            val millis = parts[0].toLongOrNull() ?: return null
            val latitude = parts[1].toDoubleOrNull() ?: return null
            val longitude = parts[2].toDoubleOrNull() ?: return null
            if (!isValidCoordinate(latitude, longitude)) return null
            return TrackPoint(
                latitude = latitude,
                longitude = longitude,
                elevation = parts[3].toDoubleOrNull(),
                time = millis.takeIf { it > 0 }?.let(Instant::ofEpochMilli),
                accuracyMeters = parts[4].toDoubleOrNull(),
            )
        }

        private fun parseWaypoint(line: String): Waypoint? {
            val parts = line.split(',', limit = 6)
            if (parts.size < 6) return null
            val millis = parts[1].toLongOrNull() ?: return null
            val latitude = parts[2].toDoubleOrNull() ?: return null
            val longitude = parts[3].toDoubleOrNull() ?: return null
            if (!isValidCoordinate(latitude, longitude)) return null
            val name = parts[5].takeIf(String::isNotEmpty)?.let {
                runCatching { String(Base64.getDecoder().decode(it), Charsets.UTF_8) }.getOrNull()
            }
            return Waypoint(
                TrackPoint(
                    latitude = latitude,
                    longitude = longitude,
                    elevation = parts[4].toDoubleOrNull(),
                    time = millis.takeIf { it > 0 }?.let(Instant::ofEpochMilli),
                ),
                name,
            )
        }
    }
}
