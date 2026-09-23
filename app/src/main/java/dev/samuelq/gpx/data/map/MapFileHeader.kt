package dev.samuelq.gpx.data.map

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * What a mapsforge map file says about itself: the ground it covers, the zooms it can be
 * drawn at, and who the data came from - and whether it is a map file at all, before it
 * becomes a blank screen.
 *
 * Read here rather than through mapsforge's own `MapFile`, which opens the whole archive
 * and holds it: this runs over every file in the maps directory at every launch, and all
 * it needs is the header.
 *
 * Everything multi-byte is big-endian. Strings carry a VBE-U length rather than the 2-byte
 * one the rest of the header uses - the single thing in this format that desynchronises a
 * parser silently instead of loudly.
 */
class MapFileHeader(
    val minZoom: Int,
    val maxZoom: Int,
    /**
     * The deepest zoom the file actually stores tiles at. Everything between this and
     * [maxZoom] is the same data scaled up, so this - not [maxZoom] - is where the detail
     * really stops. Published files store 5/10/14 and claim 21.
     */
    val baseZoom: Int,
    val minLongitude: Double,
    val minLatitude: Double,
    val maxLongitude: Double,
    val maxLatitude: Double,
    val tileSize: Int,
    /** Where the data came from, as the file states it. Null when it does not. */
    val attribution: String?,
) {

    companion object {
        private const val MAGIC = "mapsforge binary OSM"

        /** The magic string plus the 4-byte length of the header that follows it. */
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

        /**
         * Reads [file]'s header, or null if it is not a mapsforge map file. Null rather
         * than an exception - "not a map" is an ordinary answer - and a genuine IO failure
         * folds into the same null, since the caller fails identically either way.
         */
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

        /** Split out from [read] so the layout can be tested without touching a disk. */
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

                val tileSize = cursor.short()
                cursor.string() // projection

                val flags = cursor.byte()
                // Debug files interleave 32-byte signatures through the data. Nothing
                // published ships them, and a reader that ignores them reads noise.
                if (flags and FLAG_DEBUG != 0) return null
                if (flags and FLAG_START_POSITION != 0) cursor.skip(8)
                if (flags and FLAG_START_ZOOM != 0) cursor.skip(1)
                if (flags and FLAG_LANGUAGE != 0) cursor.string()
                val comment = if (flags and FLAG_COMMENT != 0) cursor.string() else null
                val createdBy = if (flags and FLAG_CREATED_BY != 0) cursor.string() else null

                repeat(cursor.short()) { cursor.string() } // POI tag dictionary
                repeat(cursor.short()) { cursor.string() } // way tag dictionary

                // Zoom intervals, each serving a band of zooms from one stored base zoom.
                // The file's range is the union, which is what a camera needs to be capped to.
                val intervals = cursor.byte()
                if (intervals <= 0) return null
                var lowest = Int.MAX_VALUE
                var highest = Int.MIN_VALUE
                var deepestBase = Int.MIN_VALUE
                repeat(intervals) {
                    deepestBase = maxOf(deepestBase, cursor.byte())
                    lowest = minOf(lowest, cursor.byte())
                    highest = maxOf(highest, cursor.byte())
                    cursor.skip(16) // sub-file start and size
                }

                MapFileHeader(
                    minZoom = lowest,
                    maxZoom = highest,
                    baseZoom = deepestBase,
                    minLongitude = minLongitude,
                    minLatitude = minLatitude,
                    maxLongitude = maxLongitude,
                    maxLatitude = maxLatitude,
                    tileSize = tileSize,
                    // The comment is where an extract carries its data credit; created-by
                    // names the tool, which is the weaker answer but better than none.
                    attribution = comment?.takeIf { it.isNotBlank() } ?: createdBy?.takeIf { it.isNotBlank() },
                )
            } catch (_: IndexOutOfBoundsException) {
                // A truncated or malformed header runs off the end of the array. Not a map.
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
