package dev.samuelq.gpx.data.map

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/** The tiles a file's box spans at [zoom], which its index lists row by row. */
internal class TileRange(header: MapFileHeader, zoom: Int) {
    val left = longitudeToTile(header.minLongitude, zoom)
    val right = longitudeToTile(header.maxLongitude, zoom)
    val top = latitudeToTile(header.maxLatitude, zoom)
    val bottom = latitudeToTile(header.minLatitude, zoom)
    val width = right - left + 1
    val count = width * (bottom - top + 1)

    fun contains(x: Long, y: Long) = x in left..right && y in top..bottom
}

/** A sub-file's index: one 5-byte entry per tile, a water flag over a 39-bit offset. */
internal class TileIndex(file: File, header: MapFileHeader, private val subFile: SubFile) : AutoCloseable {
    private val handle = RandomAccessFile(file, "r")
    private val range = TileRange(header, subFile.baseZoom)

    /** Bytes of each tile from [from] to [to] on row [y]. */
    fun row(y: Long, from: Long, to: Long): LongArray {
        val first = (y - range.top) * range.width + (from - range.left)
        val tiles = (to - from + 1).toInt()
        // One entry more, for the last tile's end.
        val entries = minOf(tiles + 1L, range.count - first).toInt()
        val bytes = read(first, entries)
        val offsets = LongArray(tiles + 1) { i ->
            if (i < entries) entry(bytes, i) and OFFSET_MASK else subFile.size
        }
        return LongArray(tiles) { i -> (offsets[i + 1] - offsets[i]).coerceAtLeast(0) }
    }

    /** Whether the writer found tile [x], [y] all water; false outside the box. */
    fun isWater(x: Long, y: Long): Boolean {
        if (!range.contains(x, y)) return false
        return entry(read((y - range.top) * range.width + (x - range.left), 1), 0) and WATER_BIT != 0L
    }

    override fun close() = handle.close()

    private fun read(first: Long, entries: Int): ByteArray {
        val bytes = ByteArray(entries * ENTRY_BYTES)
        handle.seek(subFile.start + first * ENTRY_BYTES)
        handle.readFully(bytes)
        return bytes
    }

    private fun entry(bytes: ByteArray, entry: Int): Long {
        var value = 0L
        for (i in 0 until ENTRY_BYTES) value = (value shl 8) or (bytes[entry * ENTRY_BYTES + i].toLong() and 0xFF)
        return value
    }

    private companion object {
        const val ENTRY_BYTES = 5
        const val WATER_BIT = 1L shl 39
        const val OFFSET_MASK = WATER_BIT - 1
    }
}

/** Mapsforge's tile numbering, clamped to the grid as its writer does. */
internal fun longitudeToTile(longitude: Double, zoom: Int): Long {
    val n = 1L shl zoom
    return floor((longitude + 180) / 360 * n).toLong().coerceIn(0, n - 1)
}

internal fun latitudeToTile(latitude: Double, zoom: Int): Long {
    val n = 1L shl zoom
    val r = Math.toRadians(latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE))
    val y = (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2
    return floor(y * n).toLong().coerceIn(0, n - 1)
}

private const val MAX_LATITUDE = 85.051128779806604
