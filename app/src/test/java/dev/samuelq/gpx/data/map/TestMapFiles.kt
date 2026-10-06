package dev.samuelq.gpx.data.map

import org.oscim.tiling.source.mapfile.Projection
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * A map file to the spec with one sub-file: a tile index, each tile holding its [tileBytes]
 * (none by default) and flagged all water if in [water], then those bytes.
 */
internal fun mapFile(
    south: Double = 42.0,
    west: Double = 1.0,
    north: Double = 43.0,
    east: Double = 2.0,
    baseZoom: Int = 14,
    tileBytes: List<Long>? = null,
    comment: String? = "a comment",
    createdBy: String? = "a tool",
    debug: Boolean = false,
    wayTags: List<String> = listOf("highway=path"),
    water: Set<Int> = emptySet(),
): File {
    val columns = Projection.longitudeToTileX(east, baseZoom) - Projection.longitudeToTileX(west, baseZoom) + 1
    val rows = Projection.latitudeToTileY(south, baseZoom) - Projection.latitudeToTileY(north, baseZoom) + 1
    // At least one, so an inverted box still makes a file to refuse.
    val sizes = tileBytes ?: List((columns * rows).coerceAtLeast(1).toInt()) { 0L }

    val subFile = Bytes().apply {
        if (debug) bytes(ByteArray(16))
        var offset = size().toLong() + sizes.size * 5
        sizes.forEachIndexed { i, bytes ->
            u8(((offset shr 32).toInt() and 0xFF) or (if (i in water) 0x80 else 0))
            i32(offset.toInt())
            offset += bytes
        }
        bytes(ByteArray(sizes.sum().toInt()))
    }.toByteArray()

    fun header(fileSize: Long, start: Long) = Bytes().apply {
        i32(5) // file version
        i64(fileSize)
        i64(1_700_000_000_000) // creation date
        i32((south * 1e6).toInt())
        i32((west * 1e6).toInt())
        i32((north * 1e6).toInt())
        i32((east * 1e6).toInt())
        u16(256) // tile size
        string("Mercator")

        var flags = 0
        if (debug) flags = flags or 0x80
        if (comment != null) flags = flags or 0x08
        if (createdBy != null) flags = flags or 0x04
        u8(flags)
        comment?.let(::string)
        createdBy?.let(::string)

        u16(1)
        string("place=town")
        u16(wayTags.size)
        wayTags.forEach(::string)

        u8(1) // one zoom interval
        u8(baseZoom)
        u8(0)
        u8(baseZoom)
        i64(start)
        i64(subFile.size.toLong())
    }.toByteArray()

    val preamble = MAGIC.length + 4
    val length = header(0, 0).size
    val start = (preamble + length).toLong()
    val bytes = Bytes().apply {
        bytes(MAGIC.toByteArray())
        i32(length)
        bytes(header(start + subFile.size, start))
        bytes(subFile)
    }.toByteArray()
    return File.createTempFile("test", ".map").apply {
        deleteOnExit()
        writeBytes(bytes)
    }
}

internal fun offlineMap(file: File): OfflineMap = requireNotNull(OfflineMap.read(file)) { "Unreadable test map" }

private const val MAGIC = "mapsforge binary OSM"

private class Bytes : ByteArrayOutputStream() {
    fun u8(v: Int) = write(v)
    fun u16(v: Int) { u8(v shr 8 and 0xFF); u8(v and 0xFF) }
    fun i32(v: Int) { u16(v shr 16 and 0xFFFF); u16(v and 0xFFFF) }
    fun i64(v: Long) { i32((v shr 32).toInt()); i32(v.toInt()) }
    fun bytes(b: ByteArray) = write(b)

    /** A VBE-U length, then UTF-8. */
    fun string(s: String) {
        val utf8 = s.toByteArray()
        var length = utf8.size
        while (length > 0x7F) {
            u8(length and 0x7F or 0x80)
            length = length ushr 7
        }
        u8(length)
        bytes(utf8)
    }
}
