package dev.samuelq.gpx.data.track

import android.util.Log
import dev.samuelq.gpx.core.model.Track
import dev.samuelq.gpx.core.model.TrackPoint
import dev.samuelq.gpx.core.model.TrackPoints
import dev.samuelq.gpx.core.model.TrackPointsBuilder
import dev.samuelq.gpx.core.model.Waypoint
import dev.samuelq.gpx.data.writeAtomically
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.time.Instant

/**
 * Parsed tracks in a binary form that reads far faster than GPX, one file per row. Only a
 * cache: in [dir] under cacheDir, so the system may clear it and a device transfer skips it.
 *
 * Valid while the GPX's size and modification time match what was cached. Holds what's read
 * from the file after import: points, waypoints and the description. Not the name, type or
 * colour, which the row holds, nor accuracy, which a GPX file doesn't.
 */
internal class TrackCache(private val dir: File) {

    /** Null on a miss, a stale entry, or an unreadable one. */
    fun read(id: Long, source: File): Track? {
        val file = fileFor(id)
        if (!file.exists()) return null
        return try {
            decode(ByteBuffer.wrap(file.readBytes()), Stamp.of(source))
        } catch (e: Exception) {
            // Truncated by a crash mid-write, or another version.
            Log.d(TAG, "Dropping cache for $id", e)
            file.delete()
            null
        }
    }

    /** Best effort: a failed write just means parsing again next time. */
    fun write(id: Long, source: File, track: Track) {
        runCatching {
            val bytes = encode(track, Stamp.of(source))
            writeAtomically(fileFor(id)) { it.write(bytes) }
        }.onFailure { Log.d(TAG, "Could not cache $id", it) }
    }

    /** Drops entries older than [before] whose row is gone, as a crash mid-delete leaves. */
    fun retain(ids: Set<Long>, before: Long) {
        dir.listFiles().orEmpty()
            .filter { it.lastModified() < before && it.name.removeSuffix(".bin").toLongOrNull() !in ids }
            .forEach(File::delete)
    }

    fun delete(id: Long) {
        fileFor(id).delete()
    }

    private fun fileFor(id: Long) = File(dir, "$id.bin")

    internal data class Stamp(val length: Long, val modified: Long) {
        companion object {
            fun of(file: File) = Stamp(file.length(), file.lastModified())
        }
    }

    internal companion object {
        private const val TAG = "TrackCache"
        private const val MAGIC = 0x47505843 // "GPXC"
        private const val VERSION = 1

        fun encode(track: Track, stamp: Stamp): ByteArray {
            val points = track.points
            val bytes = ByteArrayOutputStream(64 + points.size * BYTES_PER_POINT)
            DataOutputStream(bytes).run {
                writeInt(MAGIC)
                writeInt(VERSION)
                writeLong(stamp.length)
                writeLong(stamp.modified)
                writeString(track.description)

                writeInt(track.waypoints.size)
                for (waypoint in track.waypoints) {
                    val point = waypoint.point
                    writeDouble(point.latitude)
                    writeDouble(point.longitude)
                    writeDouble(point.elevation ?: Double.NaN)
                    writeLong(point.time?.toEpochMilli() ?: TrackPoints.NO_TIME)
                    writeString(waypoint.name)
                }

                writeInt(points.segmentCount)
                for (segment in 0 until points.segmentCount) writeInt(points.segmentStart(segment))
                writeInt(points.size)
                for (i in points.indices) {
                    writeDouble(points.latitude(i))
                    writeDouble(points.longitude(i))
                    writeFloat(points.elevation(i))
                    writeLong(points.timeMillis(i))
                }
            }
            return bytes.toByteArray()
        }

        /** Throws if [buffer] isn't a whole entry; null if it is one for another [stamp]. */
        fun decode(buffer: ByteBuffer, stamp: Stamp): Track? {
            check(buffer.int == MAGIC) { "Not a track cache" }
            check(buffer.int == VERSION) { "Another cache version" }
            if (Stamp(buffer.long, buffer.long) != stamp) return null
            val description = buffer.getString()

            val waypoints = List(buffer.int) {
                val point = TrackPoint(
                    latitude = buffer.double,
                    longitude = buffer.double,
                    elevation = buffer.double.takeUnless(Double::isNaN),
                    time = buffer.long.takeIf { it != TrackPoints.NO_TIME }?.let(Instant::ofEpochMilli),
                )
                Waypoint(point, buffer.getString())
            }

            val starts = IntArray(buffer.int) { buffer.int }
            val size = buffer.int
            check(size >= 0 && buffer.remaining() == size * BYTES_PER_POINT) { "Truncated" }
            val points = TrackPointsBuilder(capacity = size)
            var segment = 0
            for (i in 0 until size) {
                if (segment < starts.size && starts[segment] == i) {
                    points.startSegment()
                    segment++
                }
                points.add(buffer.double, buffer.double, buffer.float, buffer.long)
            }
            return Track(name = null, points = points.build(), description = description, waypoints = waypoints)
        }

        private const val BYTES_PER_POINT = 8 + 8 + 4 + 8

        private fun DataOutputStream.writeString(value: String?) {
            if (value == null) {
                writeInt(-1)
            } else {
                val bytes = value.toByteArray(Charsets.UTF_8)
                writeInt(bytes.size)
                write(bytes)
            }
        }

        private fun ByteBuffer.getString(): String? {
            val length = int
            if (length < 0) return null
            if (length > remaining()) throw BufferUnderflowException()
            val bytes = ByteArray(length).also(::get)
            return String(bytes, Charsets.UTF_8)
        }
    }
}
