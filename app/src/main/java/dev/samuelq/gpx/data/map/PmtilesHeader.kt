package dev.samuelq.gpx.data.map

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What a PMTiles archive says about itself, read from its first 127 bytes.
 *
 * Worth reading at import rather than trusting the file extension, for three reasons. It
 * is the only way to tell the user anything true about a file they just picked - where it
 * covers and how far it zooms are the two questions anyone has about an offline map, and
 * neither is in the filename. It is how the style knows whether to build a vector or a
 * raster layer, which are not interchangeable. And it rejects a file that is not an archive
 * at all before it becomes a blank map and a bug report.
 *
 * Field offsets are from the PMTiles v3 spec and are verified against real archives cut
 * with go-pmtiles; the parse below is a fixed-size record, not a format reader, and it
 * deliberately stops at the header rather than walking the tile directories.
 */
class PmtilesHeader(
    val tileType: TileType,
    val minZoom: Int,
    val maxZoom: Int,
    val minLongitude: Double,
    val minLatitude: Double,
    val maxLongitude: Double,
    val maxLatitude: Double,
    /** Where the tile directories live, for [PmtilesCoverage] to walk. */
    val rootDirectoryOffset: Long = 0,
    val rootDirectoryLength: Long = 0,
    val leafDirectoriesOffset: Long = 0,
    /** Where the archive's own JSON metadata lives, for [PmtilesMetadata] to read. */
    val jsonMetadataOffset: Long = 0,
    val jsonMetadataLength: Long = 0,
    /** 1 = none, 2 = gzip. Applies to the directories and the metadata, not the tiles. */
    val internalCompression: Int = 0,
) {

    /**
     * How the tiles inside are encoded, which decides what kind of layer can draw them.
     *
     * [MVT] is a vector archive and needs a style that knows its schema. The image types
     * are pre-drawn and need no schema at all - any raster archive renders with the same
     * two lines of style - which is why they are worth supporting even though the maps
     * this app is really aimed at are vector.
     */
    enum class TileType { MVT, PNG, JPEG, WEBP, AVIF, UNKNOWN }

    val isVector: Boolean get() = tileType == TileType.MVT

    /** True if [latitude], [longitude] falls inside what this archive covers. */
    fun contains(latitude: Double, longitude: Double): Boolean =
        latitude in minLatitude..maxLatitude && longitude in minLongitude..maxLongitude

    companion object {
        /** The fixed-size v3 header. Everything this app needs is inside it. */
        private const val HEADER_BYTES = 127

        private val MAGIC = byteArrayOf('P'.code.toByte(), 'M'.code.toByte(), 'T'.code.toByte(),
            'i'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(), 's'.code.toByte())

        private const val OFFSET_SPEC_VERSION = 7
        private const val OFFSET_ROOT_DIR = 8
        private const val OFFSET_ROOT_DIR_LENGTH = 16
        private const val OFFSET_JSON_METADATA = 24
        private const val OFFSET_JSON_METADATA_LENGTH = 32
        private const val OFFSET_LEAF_DIRS = 40
        private const val OFFSET_INTERNAL_COMPRESSION = 97
        private const val OFFSET_TILE_TYPE = 99
        private const val OFFSET_MIN_ZOOM = 100
        private const val OFFSET_MAX_ZOOM = 101
        private const val OFFSET_MIN_LON = 102
        private const val OFFSET_MIN_LAT = 106
        private const val OFFSET_MAX_LON = 110
        private const val OFFSET_MAX_LAT = 114

        /** Coordinates are stored as signed degrees times ten million. */
        private const val COORDINATE_SCALE = 1e7

        /**
         * Reads [file]'s header, or null if it is not a PMTiles v3 archive.
         *
         * Null rather than an exception for a bad file: "the file you picked is not a map"
         * is an ordinary answer to an ordinary mistake, not an error condition. A genuine
         * IO failure is also folded into it - at this point the only caller is an import
         * that is about to fail either way, and it has one message for both.
         */
        fun read(file: File): PmtilesHeader? = try {
            RandomAccessFile(file, "r").use { handle ->
                if (handle.length() < HEADER_BYTES) return null
                val bytes = ByteArray(HEADER_BYTES)
                handle.readFully(bytes)
                parse(bytes)
            }
        } catch (_: IOException) {
            null
        }

        /** Split out from [read] so the offsets can be tested without touching a disk. */
        fun parse(bytes: ByteArray): PmtilesHeader? {
            if (bytes.size < HEADER_BYTES) return null
            if (!MAGIC.indices.all { bytes[it] == MAGIC[it] }) return null
            // Only v3 has the layout below. A v2 archive would parse into nonsense.
            if (bytes[OFFSET_SPEC_VERSION].toInt() != 3) return null

            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            fun degrees(offset: Int) = buffer.getInt(offset) / COORDINATE_SCALE

            val minLongitude = degrees(OFFSET_MIN_LON)
            val minLatitude = degrees(OFFSET_MIN_LAT)
            val maxLongitude = degrees(OFFSET_MAX_LON)
            val maxLatitude = degrees(OFFSET_MAX_LAT)
            // A header can be well-formed and still describe nothing usable.
            if (minLongitude > maxLongitude || minLatitude > maxLatitude) return null

            return PmtilesHeader(
                tileType = when (bytes[OFFSET_TILE_TYPE].toInt()) {
                    1 -> TileType.MVT
                    2 -> TileType.PNG
                    3 -> TileType.JPEG
                    4 -> TileType.WEBP
                    5 -> TileType.AVIF
                    else -> TileType.UNKNOWN
                },
                minZoom = bytes[OFFSET_MIN_ZOOM].toInt() and 0xFF,
                maxZoom = bytes[OFFSET_MAX_ZOOM].toInt() and 0xFF,
                minLongitude = minLongitude,
                minLatitude = minLatitude,
                maxLongitude = maxLongitude,
                maxLatitude = maxLatitude,
                rootDirectoryOffset = buffer.getLong(OFFSET_ROOT_DIR),
                rootDirectoryLength = buffer.getLong(OFFSET_ROOT_DIR_LENGTH),
                leafDirectoriesOffset = buffer.getLong(OFFSET_LEAF_DIRS),
                jsonMetadataOffset = buffer.getLong(OFFSET_JSON_METADATA),
                jsonMetadataLength = buffer.getLong(OFFSET_JSON_METADATA_LENGTH),
                internalCompression = bytes[OFFSET_INTERNAL_COMPRESSION].toInt() and 0xFF,
            )
        }
    }
}
