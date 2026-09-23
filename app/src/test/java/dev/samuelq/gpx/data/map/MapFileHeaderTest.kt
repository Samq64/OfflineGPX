package dev.samuelq.gpx.data.map

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The header reader, against a real file and against the shapes that have to be refused.
 *
 * `andorra-fragment.map` is a genuine extract cut from `download.mapsforge.org`, truncated
 * to exactly its own header - the reader never looks past it, and 4 KB is a fixture where
 * 300 KB would be a liability.
 */
class MapFileHeaderTest {

    private fun fixture(): File =
        File(javaClass.getResource("/andorra-fragment.map")!!.toURI())

    @Test
    fun `reads a real extract`() {
        val header = assertNotNull(MapFileHeader.read(fixture()))

        assertEquals(42.535, header.minLatitude)
        assertEquals(1.520, header.minLongitude)
        assertEquals(42.550, header.maxLatitude)
        assertEquals(1.545, header.maxLongitude)
    }

    /**
     * The deepest of the file's three stored base zooms, not the z21 it claims: the camera
     * is capped from this, and z15 to z21 are the z14 tile scaled up.
     */
    @Test
    fun `the base zoom is the deepest stored`() {
        val header = assertNotNull(MapFileHeader.read(fixture()))
        assertEquals(14, header.baseZoom)
    }

    /** Where an extract's ODbL credit actually lives, and what the settings screen shows. */
    @Test
    fun `attribution comes from the comment`() {
        val header = assertNotNull(MapFileHeader.read(fixture()))
        assertEquals("Map data (c) OpenStreetMap contributors", header.attribution)
    }

    @Test
    fun `falls back to created-by when there is no comment`() {
        val header = assertNotNull(MapFileHeader.parse(header(comment = null, createdBy = "osmosis")))
        assertEquals("osmosis", header.attribution)
    }

    @Test
    fun `no comment and no created-by is no attribution`() {
        val header = assertNotNull(MapFileHeader.parse(header(comment = null, createdBy = null)))
        assertNull(header.attribution)
    }

    /** A blank comment is a file that did not say, not a file that said nothing. */
    @Test
    fun `a blank comment is not attribution`() {
        val header = assertNotNull(MapFileHeader.parse(header(comment = "   ", createdBy = "osmosis")))
        assertEquals("osmosis", header.attribution)
    }

    @Test
    fun `a file that is not a map is not a map`() {
        val notAMap = File.createTempFile("not-a", ".map").apply {
            writeBytes(ByteArray(4096) { 0x7F })
            deleteOnExit()
        }
        assertNull(MapFileHeader.read(notAMap))
    }

    @Test
    fun `a file shorter than its own header is refused`() {
        val truncated = File.createTempFile("truncated", ".map").apply {
            writeBytes(fixture().readBytes().copyOf(64))
            deleteOnExit()
        }
        assertNull(MapFileHeader.read(truncated))
    }

    /**
     * Debug files interleave 32-byte signatures through the data. Reading one as if it
     * were ordinary would draw noise, so it is refused outright.
     */
    @Test
    fun `a debug build is refused`() {
        assertNull(MapFileHeader.parse(header(debug = true)))
    }

    @Test
    fun `an inverted bounding box is refused`() {
        assertNull(MapFileHeader.parse(header(minLatitude = 50.0, maxLatitude = 40.0)))
    }

    /**
     * String lengths are VBE-U, not the 2-byte short the rest of the header uses. A tag
     * list long enough to need a two-byte length is where getting that wrong shows up:
     * everything after it decodes as garbage.
     */
    @Test
    fun `reads past a multi-byte string length`() {
        val header = assertNotNull(
            MapFileHeader.parse(header(wayTags = List(4) { "k".repeat(200) }))
        )
        assertEquals(42.0, header.minLatitude)
        assertEquals(14, header.baseZoom)
    }

    // --- A header, built to the spec so the reader can be pointed at edges -------------

    private fun header(
        minLatitude: Double = 42.0,
        maxLatitude: Double = 43.0,
        comment: String? = "a comment",
        createdBy: String? = "a tool",
        debug: Boolean = false,
        wayTags: List<String> = listOf("highway=path"),
    ): ByteArray {
        val body = ByteArrayOutputStream()

        fun u8(v: Int) = body.write(v)
        fun u16(v: Int) { u8(v shr 8 and 0xFF); u8(v and 0xFF) }
        fun i32(v: Int) { u16(v shr 16 and 0xFFFF); u16(v and 0xFFFF) }
        fun i64(v: Long) { i32((v shr 32).toInt()); i32(v.toInt()) }
        fun string(s: String) {
            val bytes = s.toByteArray()
            var length = bytes.size
            while (length > 0x7F) {
                u8(length and 0x7F or 0x80)
                length = length ushr 7
            }
            u8(length)
            body.write(bytes)
        }

        i32(5)              // file version
        i64(0)              // file size
        i64(0)              // creation date
        i32((minLatitude * 1e6).toInt())
        i32((1.0 * 1e6).toInt())
        i32((maxLatitude * 1e6).toInt())
        i32((2.0 * 1e6).toInt())
        u16(256)            // tile size
        string("Mercator")

        var flags = 0
        if (debug) flags = flags or 0x80
        if (comment != null) flags = flags or 0x08
        if (createdBy != null) flags = flags or 0x04
        u8(flags)
        comment?.let(::string)
        createdBy?.let(::string)

        u16(1); string("place=town")
        u16(wayTags.size); wayTags.forEach(::string)

        u8(1)               // one zoom interval
        u8(14); u8(12); u8(14)
        i64(0); i64(0)

        return body.toByteArray()
    }
}
