package dev.samuelq.gpx.data.map

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * How much of one map's data the other already has, 0 to 1, whichever way is more. Over the
 * tiles both boxes cover, each map's bytes above its own filler are compared tile by tile.
 *
 * Boxes alone can't say: an extract fills its whole box with land and sea polygons, so a
 * neighbour's box can lie inside another's. Those polygons are the filler, tens of bytes a
 * tile against kilobytes of real data, which only fills a map's own region. Neighbours
 * measured at most 0.11 and a duplicate 1.
 *
 * Null when it can't be told from the files, as with differing base zooms.
 */
internal fun sharedData(a: OfflineMap, b: OfflineMap): Double? {
    val ha = a.header
    val hb = b.header
    if (ha.baseZoom != hb.baseZoom) return null
    val left = maxOf(ha.tileLeft, hb.tileLeft)
    val right = minOf(ha.tileRight, hb.tileRight)
    val top = maxOf(ha.tileTop, hb.tileTop)
    val bottom = minOf(ha.tileBottom, hb.tileBottom)
    if (left > right || top > bottom) return 0.0

    // Rows are sampled past a budget, so a country against a continent stays quick.
    val columns = right - left + 1
    val rows = bottom - top + 1
    val step = maxOf(1L, (rows * columns + MAX_SAMPLED_TILES - 1) / MAX_SAMPLED_TILES)

    val (sa, sb) = try {
        TileIndex(a.file, ha).use { ia ->
            TileIndex(b.file, hb).use { ib ->
                val sampled = (top..bottom step step).toList()
                sampled.flatMapTo(ArrayList()) { ia.row(it, left, right).asList() }.toLongArray() to
                    sampled.flatMapTo(ArrayList()) { ib.row(it, left, right).asList() }.toLongArray()
            }
        }
    } catch (_: IOException) {
        return null
    }

    val ca = sa.aboveFiller()
    val cb = sb.aboveFiller()
    val shared = ca.indices.sumOf { minOf(ca[it], cb[it]) }.toDouble()
    return maxOf(shared / ca.sum().coerceAtLeast(1), shared / cb.sum().coerceAtLeast(1))
}

/** Each tile's bytes less a low percentile, which is the land and sea filler. */
private fun LongArray.aboveFiller(): LongArray {
    val nonEmpty = filter { it > 0 }.sorted()
    val filler = if (nonEmpty.isEmpty()) 0 else nonEmpty[(nonEmpty.size * FILLER_PERCENTILE).toInt()]
    return LongArray(size) { (this[it] - filler).coerceAtLeast(0) }
}

/** A base-zoom sub-file's index: one 5-byte entry per tile, row by row over the box. */
private class TileIndex(file: File, private val header: MapFileHeader) : AutoCloseable {
    private val handle = RandomAccessFile(file, "r")
    private val left = header.tileLeft
    private val top = header.tileTop
    private val width = header.tileRight - left + 1
    private val count = width * (header.tileBottom - top + 1)

    /** Bytes of each tile from [from] to [to] on row [y]. */
    fun row(y: Long, from: Long, to: Long): LongArray {
        val first = (y - top) * width + (from - left)
        val tiles = (to - from + 1).toInt()
        // One entry more, for the last tile's end.
        val entries = minOf(tiles + 1L, count - first).toInt()
        val bytes = ByteArray(entries * ENTRY_BYTES)
        handle.seek(header.subFileStart + first * ENTRY_BYTES)
        handle.readFully(bytes)
        val offsets = LongArray(tiles + 1) { i ->
            if (i < entries) offset(bytes, i) else header.subFileSize
        }
        return LongArray(tiles) { i -> (offsets[i + 1] - offsets[i]).coerceAtLeast(0) }
    }

    override fun close() = handle.close()

    private fun offset(bytes: ByteArray, entry: Int): Long {
        var value = 0L
        for (i in 0 until ENTRY_BYTES) value = (value shl 8) or (bytes[entry * ENTRY_BYTES + i].toLong() and 0xFF)
        return value and OFFSET_MASK
    }
}

private val MapFileHeader.tileLeft get() = longitudeToTile(minLongitude, baseZoom)
private val MapFileHeader.tileRight get() = longitudeToTile(maxLongitude, baseZoom)
private val MapFileHeader.tileTop get() = latitudeToTile(maxLatitude, baseZoom)
private val MapFileHeader.tileBottom get() = latitudeToTile(minLatitude, baseZoom)

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

/** At or above this, an import is taken to duplicate a map rather than border it. */
internal const val DUPLICATE_SHARE = 0.5

private const val ENTRY_BYTES = 5

/** The top bit marks an all-water tile; the rest is the offset. */
private const val OFFSET_MASK = (1L shl 39) - 1

/** About 2.5 MB of index per map, and 4 MB of sizes held. */
private const val MAX_SAMPLED_TILES = 500_000L

private const val FILLER_PERCENTILE = 0.05

private const val MAX_LATITUDE = 85.051128779806604
