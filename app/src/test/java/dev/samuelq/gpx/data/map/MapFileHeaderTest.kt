package dev.samuelq.gpx.data.map

import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** `andorra-fragment.map` is a real mapsforge extract truncated to its header, which is all the reader reads. */
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

    /** Not the z21 it claims: z15 to z21 are the z14 tile scaled up. */
    @Test
    fun `the base zoom is the deepest stored`() {
        val header = assertNotNull(MapFileHeader.read(fixture()))
        assertEquals(14, header.baseZoom)
    }

    @Test
    fun `locates the deepest sub-file`() {
        val header = assertNotNull(MapFileHeader.read(fixture()))
        assertEquals(171_810L, header.subFileStart)
        assertEquals(144_339L, header.subFileSize)
    }

    /** Where an extract's ODbL credit lives. */
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

    /** Debug files interleave 32-byte signatures through the data. */
    @Test
    fun `a debug build is refused`() {
        assertNull(MapFileHeader.parse(header(debug = true)))
    }

    @Test
    fun `an inverted bounding box is refused`() {
        assertNull(MapFileHeader.parse(header(minLatitude = 50.0, maxLatitude = 40.0)))
    }

    /** String lengths are VBE-U, not the 2-byte short used elsewhere in the header. */
    @Test
    fun `reads past a multi-byte string length`() {
        val header = assertNotNull(
            MapFileHeader.parse(header(wayTags = List(4) { "k".repeat(200) }))
        )
        assertEquals(42.0, header.minLatitude)
        assertEquals(14, header.baseZoom)
    }

    /** A header built to the spec. */
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
