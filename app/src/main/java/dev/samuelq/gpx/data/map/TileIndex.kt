package dev.samuelq.gpx.data.map

import java.io.File
import java.io.RandomAccessFile
import org.oscim.tiling.source.mapfile.header.SubFileParameter

/** A sub-file's index: one 5-byte entry per tile, row by row, a water flag over a 39-bit offset. */
internal class TileIndex(file: File, private val subFile: SubFileParameter) : AutoCloseable {
    private val handle = RandomAccessFile(file, "r")

    /** Bytes of each tile from [from] to [to] on row [y]. */
    fun row(y: Long, from: Long, to: Long): LongArray = with(subFile) {
        val first = (y - boundaryTileTop) * blocksWidth + (from - boundaryTileLeft)
        val tiles = (to - from + 1).toInt()
        // One entry more, for the last tile's end.
        val entries = minOf(tiles + 1L, numberOfBlocks - first).toInt()
        val bytes = read(first, entries)
        val offsets = LongArray(tiles + 1) { i ->
            if (i < entries) entry(bytes, i) and OFFSET_MASK else subFileSize
        }
        return LongArray(tiles) { i -> (offsets[i + 1] - offsets[i]).coerceAtLeast(0) }
    }

    /** Whether the writer found tile [x], [y] all water; false outside the box. */
    fun isWater(x: Long, y: Long): Boolean = with(subFile) {
        if (x !in boundaryTileLeft..boundaryTileRight || y !in boundaryTileTop..boundaryTileBottom) return false
        return entry(read((y - boundaryTileTop) * blocksWidth + (x - boundaryTileLeft), 1), 0) and WATER_BIT != 0L
    }

    override fun close() = handle.close()

    private fun read(first: Long, entries: Int): ByteArray {
        val bytes = ByteArray(entries * ENTRY_BYTES)
        handle.seek(subFile.indexStartAddress + first * ENTRY_BYTES)
        handle.readFully(bytes)
        return bytes
    }

    private fun entry(bytes: ByteArray, entry: Int): Long {
        var value = 0L
        for (i in 0 until ENTRY_BYTES) value = (value shl 8) or (bytes[entry * ENTRY_BYTES + i].toLong() and 0xFF)
        return value
    }

    private companion object {
        const val ENTRY_BYTES = SubFileParameter.BYTES_PER_INDEX_ENTRY.toInt()
        const val WATER_BIT = 1L shl 39
        const val OFFSET_MASK = WATER_BIT - 1
    }
}
