package dev.samuelq.gpx.data.map

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sinh

/** A horizontal run of covered tiles, as the ground rectangle it spans. */
class CoverageRun(
    val westLongitude: Double,
    val southLatitude: Double,
    val eastLongitude: Double,
    val northLatitude: Double,
)

/** What an archive actually contains: its outline, and the area to cut out of a mask. */
class Coverage(val edges: List<CoverageEdge>, val runs: List<CoverageRun>) {
    val isEmpty: Boolean get() = edges.isEmpty()

    companion object {
        val None = Coverage(emptyList(), emptyList())
    }
}

/** One straight piece of the boundary of what an archive actually contains. */
class CoverageEdge(
    val fromLongitude: Double,
    val fromLatitude: Double,
    val toLongitude: Double,
    val toLatitude: Double,
)

/**
 * The true shape of an archive's coverage, read from its tile directories.
 *
 * The header only carries a *bounding box*, which is a lie for any extract that was cut
 * from a drawn polygon rather than a rectangle - and drawing a polygon is the normal way
 * to cut a park or a valley. Measured against real files, a bbox extract of Perth fills
 * 100% of its declared box while a polygon extract of the Kingston region fills 56%: the
 * other 44% is ground the rectangle claimed and the file does not have.
 *
 * So the outline is computed from the tiles themselves. Every tile present at the deepest
 * zoom is a square of known extent; an edge of one of those squares is on the boundary
 * exactly when the neighbouring square is absent. That gives the real outline for any
 * shape without needing to union polygons, and it is cheap: the Kingston extract is 16,208
 * tiles and 770 boundary edges.
 */
object PmtilesCoverage {

    /**
     * Reads [file]'s coverage boundary, or an empty list if it cannot be determined.
     *
     * Empty is a usable answer everywhere it is used - it simply means no outline is drawn -
     * so a directory this cannot parse degrades to the map without an annotation rather
     * than to an error.
     */
    fun read(file: File, header: PmtilesHeader): Coverage = try {
        RandomAccessFile(file, "r").use { handle ->
            val tiles = collectTiles(handle, header)
            if (tiles.isEmpty()) {
                Coverage.None
            } else {
                Coverage(boundaryOf(tiles, header.maxZoom), runsOf(tiles, header.maxZoom))
            }
        }
    } catch (_: IOException) {
        Coverage.None
    } catch (_: IndexOutOfBoundsException) {
        // A truncated or malformed directory walks off the end of a buffer. The file is
        // still a perfectly good map; it just does not get an outline.
        Coverage.None
    }

    /** Every tile present at the archive's deepest zoom, as packed x/y pairs. */
    private fun collectTiles(handle: RandomAccessFile, header: PmtilesHeader): Set<Long> {
        val tiles = HashSet<Long>()
        val pending = ArrayDeque<Pair<Long, Long>>()
        pending.add(header.rootDirectoryOffset to header.rootDirectoryLength)

        while (pending.isNotEmpty()) {
            val (offset, length) = pending.removeFirst()
            if (length <= 0 || length > MAX_DIRECTORY_BYTES) continue

            val entries = parseDirectory(readDirectory(handle, offset, length, header))
            for (entry in entries) {
                if (entry.runLength == 0L) {
                    // A run of zero is not a tile - it points at a leaf directory holding
                    // the entries for this part of the curve.
                    pending.add(header.leafDirectoriesOffset + entry.offset to entry.length)
                    continue
                }
                for (step in 0 until entry.runLength) {
                    val tile = tileIdToZxy(entry.tileId + step) ?: continue
                    if (tile.zoom == header.maxZoom) tiles.add(pack(tile.x, tile.y))
                }
                // A pathological archive should not be able to hang an import.
                if (tiles.size > MAX_TILES) return emptySet()
            }
        }
        return tiles
    }

    private fun readDirectory(
        handle: RandomAccessFile,
        offset: Long,
        length: Long,
        header: PmtilesHeader,
    ): ByteArray {
        handle.seek(offset)
        val raw = ByteArray(length.toInt())
        handle.readFully(raw)
        return if (header.internalCompression == COMPRESSION_GZIP) {
            GZIPInputStream(ByteArrayInputStream(raw)).use { it.readBytes() }
        } else {
            raw
        }
    }

    private class Entry(val tileId: Long, val runLength: Long, val length: Long, val offset: Long)

    /**
     * A v3 directory: a count, then four columns of varints.
     *
     * Columnar rather than row-wise, which is why this reads the whole of one field before
     * starting the next. Tile ids are delta-encoded, and an offset of zero means "directly
     * after the previous entry" rather than "byte zero".
     */
    private fun parseDirectory(bytes: ByteArray): List<Entry> {
        val cursor = Cursor(bytes)
        val count = cursor.varint().toInt()
        if (count <= 0 || count > MAX_ENTRIES) return emptyList()

        val ids = LongArray(count)
        var last = 0L
        for (i in 0 until count) {
            last += cursor.varint()
            ids[i] = last
        }
        val runs = LongArray(count) { cursor.varint() }
        val lengths = LongArray(count) { cursor.varint() }
        val offsets = LongArray(count)
        for (i in 0 until count) {
            val value = cursor.varint()
            offsets[i] = if (value == 0L && i > 0) offsets[i - 1] + lengths[i - 1] else value - 1
        }
        return List(count) { Entry(ids[it], runs[it], lengths[it], offsets[it]) }
    }

    private class Cursor(private val bytes: ByteArray) {
        private var position = 0

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val byte = bytes[position++].toInt() and 0xFF
                result = result or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
                if (shift > 63) return result
            }
        }
    }

    private class Tile(val zoom: Int, val x: Long, val y: Long)

    /**
     * A tile id back to zoom, x and y.
     *
     * PMTiles orders tiles along a Hilbert curve rather than row by row, which is what
     * makes a bounding-box extract a handful of long byte runs instead of thousands of
     * scattered reads. The cost is that recovering x and y means walking the curve.
     */
    private fun tileIdToZxy(id: Long): Tile? {
        var accumulated = 0L
        var zoom = 0
        while (zoom <= MAX_ZOOM) {
            val side = 1L shl zoom
            val count = side * side
            if (accumulated + count > id) {
                val (x, y) = hilbertToXy(side, id - accumulated)
                return Tile(zoom, x, y)
            }
            accumulated += count
            zoom++
        }
        return null
    }

    private fun hilbertToXy(side: Long, distance: Long): Pair<Long, Long> {
        var x = 0L
        var y = 0L
        var remaining = distance
        var step = 1L
        while (step < side) {
            val rx = 1L and (remaining / 2)
            val ry = 1L and (remaining xor rx)
            if (ry == 0L) {
                if (rx == 1L) {
                    x = step - 1 - x
                    y = step - 1 - y
                }
                val swap = x
                x = y
                y = swap
            }
            x += step * rx
            y += step * ry
            remaining /= 4
            step *= 2
        }
        return x to y
    }

    /**
     * The edges of [tiles] that have no neighbour on the other side.
     *
     * Four checks per tile and no geometry library: an edge between two present tiles is
     * interior and is skipped, so what survives is exactly the outline - including the
     * outlines of any holes, which a polygon union would have had to be careful about.
     */
    private fun boundaryOf(tiles: Set<Long>, zoom: Int): List<CoverageEdge> {
        val edges = ArrayList<CoverageEdge>()
        val side = 1L shl zoom

        for (packed in tiles) {
            val x = unpackX(packed)
            val y = unpackY(packed)

            val left = longitudeOf(x, side)
            val right = longitudeOf(x + 1, side)
            val top = latitudeOf(y, side)
            val bottom = latitudeOf(y + 1, side)

            if (!tiles.contains(pack(x, y - 1))) edges.add(CoverageEdge(left, top, right, top))
            if (!tiles.contains(pack(x, y + 1))) {
                edges.add(CoverageEdge(left, bottom, right, bottom))
            }
            if (!tiles.contains(pack(x - 1, y))) edges.add(CoverageEdge(left, top, left, bottom))
            if (!tiles.contains(pack(x + 1, y))) {
                edges.add(CoverageEdge(right, top, right, bottom))
            }
        }
        return edges
    }

    /**
     * The covered tiles collapsed into horizontal runs, one rectangle each.
     *
     * This is what lets the mask be exact for a shape that is not a rectangle. Cutting
     * sixteen thousand individual tile squares out of a world polygon would be absurd;
     * collapsing each row to its contiguous spans turns the Kingston extract into a couple
     * of hundred rectangles, which is nothing. Runs in adjacent rows share edges exactly,
     * so they tile without seams.
     */
    private fun runsOf(tiles: Set<Long>, zoom: Int): List<CoverageRun> {
        val side = 1L shl zoom
        val byRow = tiles.groupBy({ unpackY(it) }, { unpackX(it) })
        val runs = ArrayList<CoverageRun>()

        for ((y, columns) in byRow) {
            val sorted = columns.sorted()
            var start = sorted.first()
            var previous = start
            for (x in sorted.drop(1)) {
                if (x != previous + 1) {
                    runs.add(runOf(start, previous, y, side))
                    start = x
                }
                previous = x
            }
            runs.add(runOf(start, previous, y, side))
        }
        return runs
    }

    private fun runOf(fromX: Long, toX: Long, y: Long, side: Long) = CoverageRun(
        westLongitude = longitudeOf(fromX, side),
        northLatitude = latitudeOf(y, side),
        eastLongitude = longitudeOf(toX + 1, side),
        southLatitude = latitudeOf(y + 1, side),
    )

    private fun longitudeOf(x: Long, side: Long): Double = x.toDouble() / side * 360.0 - 180.0

    private fun latitudeOf(y: Long, side: Long): Double {
        val n = PI * (1.0 - 2.0 * y.toDouble() / side)
        return Math.toDegrees(atan(sinh(n)))
    }

    // x and y are at most 2^20 at the zooms anyone ships, so both fit beside each other in
    // a Long and the boundary test stays a hash lookup on a primitive.
    private fun pack(x: Long, y: Long): Long = (x shl 32) or (y and 0xFFFFFFFFL)
    private fun unpackX(packed: Long): Long = packed shr 32
    private fun unpackY(packed: Long): Long = packed and 0xFFFFFFFFL

    private const val COMPRESSION_GZIP = 2
    private const val MAX_ZOOM = 24
    private const val MAX_ENTRIES = 500_000
    private const val MAX_TILES = 1_000_000
    private const val MAX_DIRECTORY_BYTES = 32L * 1024 * 1024
}
