package dev.samuelq.gpx.data.map

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** One zoom interval's data, which starts with its tile index. */
class SubFile(val baseZoom: Int, val minZoom: Int, val maxZoom: Int, val start: Long, val size: Long)

/**
 * A mapsforge map file's header, read directly since mapsforge's `MapFile` opens and holds
 * the whole file. Big-endian; strings carry a VBE-U length, unlike the header's 2-byte counts.
 */
class MapFileHeader(
    /** The deepest zoom with stored tiles, where detail really stops (files claim more). */
    val baseZoom: Int,
    val minLongitude: Double,
    val minLatitude: Double,
    val maxLongitude: Double,
    val maxLatitude: Double,
    val attribution: String?,
    /** Non-debug files only, so each index is plain entries. */
    val subFiles: List<SubFile> = emptyList(),
) {
    /** The [baseZoom] one. */
    val deepest: SubFile? get() = subFiles.maxByOrNull { it.baseZoom }

    /** The one read for a tile at [zoom], clamped to the file's range as the reader does. */
    fun subFileFor(zoom: Int): SubFile? =
        subFiles.firstOrNull { zoom in it.minZoom..it.maxZoom }
            ?: if (zoom < (subFiles.minOfOrNull { it.minZoom } ?: 0)) subFiles.minByOrNull { it.minZoom } else deepest

    companion object {
        private const val MAGIC = "mapsforge binary OSM"

        /** The magic string plus the 4-byte header length. */
        private const val PREAMBLE_BYTES = 24

        /** Beyond this the file is malformed, not merely large. */
        private const val MAX_HEADER_BYTES = 1 shl 20

        private const val FLAG_DEBUG = 0x80
        private const val FLAG_START_POSITION = 0x40
        private const val FLAG_START_ZOOM = 0x20
        private const val FLAG_LANGUAGE = 0x10
        private const val FLAG_COMMENT = 0x08
        private const val FLAG_CREATED_BY = 0x04

        private const val COORDINATE_SCALE = 1e6

        /** Null if not a mapsforge map file or unreadable. */
        fun read(file: File): MapFileHeader? = try {
            RandomAccessFile(file, "r").use { handle ->
                if (handle.length() < PREAMBLE_BYTES) return null
                val preamble = ByteArray(PREAMBLE_BYTES)
                handle.readFully(preamble)
                if (String(preamble, 0, MAGIC.length, Charsets.ISO_8859_1) != MAGIC) return null

                val length = readInt(preamble, MAGIC.length)
                if (length !in 1..MAX_HEADER_BYTES) return null
                if (handle.length() < PREAMBLE_BYTES + length) return null

                val body = ByteArray(length)
                handle.readFully(body)
                parse(body)
            }
        } catch (_: IOException) {
            null
        }

        fun parse(body: ByteArray): MapFileHeader? {
            val cursor = Cursor(body)
            return try {
                cursor.skip(4) // file version
                cursor.skip(8) // file size
                cursor.skip(8) // creation date

                val minLatitude = cursor.int() / COORDINATE_SCALE
                val minLongitude = cursor.int() / COORDINATE_SCALE
                val maxLatitude = cursor.int() / COORDINATE_SCALE
                val maxLongitude = cursor.int() / COORDINATE_SCALE
                if (minLongitude > maxLongitude || minLatitude > maxLatitude) return null

                cursor.skip(2) // tile size
                cursor.string() // projection

                val flags = cursor.byte()
                // Debug files interleave signatures through the data; unsupported.
                if (flags and FLAG_DEBUG != 0) return null
                if (flags and FLAG_START_POSITION != 0) cursor.skip(8)
                if (flags and FLAG_START_ZOOM != 0) cursor.skip(1)
                if (flags and FLAG_LANGUAGE != 0) cursor.string()
                val comment = if (flags and FLAG_COMMENT != 0) cursor.string() else null
                val createdBy = if (flags and FLAG_CREATED_BY != 0) cursor.string() else null

                repeat(cursor.short()) { cursor.string() } // POI tag dictionary
                repeat(cursor.short()) { cursor.string() } // way tag dictionary

                val intervals = cursor.byte()
                if (intervals <= 0) return null
                val subFiles = List(intervals) {
                    SubFile(
                        baseZoom = cursor.byte(),
                        minZoom = cursor.byte(),
                        maxZoom = cursor.byte(),
                        start = cursor.long(),
                        size = cursor.long(),
                    )
                }

                MapFileHeader(
                    baseZoom = subFiles.maxOf { it.baseZoom },
                    minLongitude = minLongitude,
                    minLatitude = minLatitude,
                    maxLongitude = maxLongitude,
                    maxLatitude = maxLatitude,
                    // The comment carries the data credit; created-by is a fallback.
                    attribution = comment?.takeIf { it.isNotBlank() } ?: createdBy?.takeIf { it.isNotBlank() },
                    subFiles = subFiles,
                )
            } catch (_: IndexOutOfBoundsException) {
                null
            }
        }

        private fun readInt(bytes: ByteArray, at: Int): Int =
            ((bytes[at].toInt() and 0xFF) shl 24) or
                ((bytes[at + 1].toInt() and 0xFF) shl 16) or
                ((bytes[at + 2].toInt() and 0xFF) shl 8) or
                (bytes[at + 3].toInt() and 0xFF)
    }

    private class Cursor(private val bytes: ByteArray) {
        private var at = 0

        fun skip(count: Int) {
            at += count
            if (at > bytes.size) throw IndexOutOfBoundsException()
        }

        fun byte(): Int = bytes[at++].toInt() and 0xFF

        fun short(): Int = (byte() shl 8) or byte()

        fun int(): Int = (short() shl 16) or short()

        fun long(): Long = (int().toLong() shl 32) or (int().toLong() and 0xFFFFFFFFL)

        /** A VBE-U length, then that many UTF-8 bytes. */
        fun string(): String {
            var length = 0
            var shift = 0
            while (true) {
                val b = byte()
                if (b and 0x80 == 0) {
                    length = length or (b shl shift)
                    break
                }
                length = length or ((b and 0x7F) shl shift)
                shift += 7
                if (shift > 28) throw IndexOutOfBoundsException()
            }
            if (at + length > bytes.size) throw IndexOutOfBoundsException()
            return String(bytes, at, length, Charsets.UTF_8).also { at += length }
        }
    }
}
