package dev.samuelq.gpx.data.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The header parse is a table of byte offsets, which is exactly the kind of code that is
 * wrong in a way nothing notices: every field lands somewhere, and a map cut to z14 that
 * reports z0 just looks like a map with no detail. So the fixture below is not synthetic -
 * it is the real first 127 bytes of an archive cut with go-pmtiles from the Protomaps
 * daily build, and the expectations are what `pmtiles show` prints for that same file.
 */
class PmtilesHeaderTest {

    /**
     * `pmtiles extract --bbox=-76.05,45.15,-75.40,45.60 --maxzoom=14`, first 127 bytes.
     *
     * `pmtiles show` reports: tile type mvt, min zoom 0, max zoom 14, bounds
     * (-76.050000, 45.150000) to (-75.400000, 45.600000).
     */
    private val realHeader = byteArrayOf(
        80, 77, 84, 105, 108, 101, 115, 3, 127, 0, 0, 0, 0, 0, 0, 0, 92, 12, 0, 0,
        0, 0, 0, 0, -37, 12, 0, 0, 0, 0, 0, 0, -101, 4, 0, 0, 0, 0, 0, 0, 118, 17,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 118, 17, 0, 0, 0, 0, 0, 0, 93,
        -35, 10, 1, 0, 0, 0, 0, 67, 5, 0, 0, 0, 0, 0, 0, 67, 5, 0, 0, 0, 0, 0, 0,
        67, 5, 0, 0, 0, 0, 0, 0, 1, 2, 2, 1, 0, 14, -32, -80, -85, -46, -32, 87,
        -23, 26, -128, -33, 14, -45, 0, 2, 46, 27, 0, 48, 72, -35, -46, -16, -84,
        11, 27,
    )

    @Test
    fun `reads the fields of a real archive`() {
        val header = requireNotNull(PmtilesHeader.parse(realHeader))

        assertEquals(PmtilesHeader.TileType.MVT, header.tileType)
        assertTrue(header.isVector)
        assertEquals(0, header.minZoom)
        assertEquals(14, header.maxZoom)

        // Six decimal places, because that is the precision the spec's fixed-point
        // encoding actually carries and comparing doubles for equality would be luck.
        assertEquals(-76.05, header.minLongitude, 1e-6)
        assertEquals(45.15, header.minLatitude, 1e-6)
        assertEquals(-75.40, header.maxLongitude, 1e-6)
        assertEquals(45.60, header.maxLatitude, 1e-6)
    }

    @Test
    fun `knows what it covers`() {
        val header = requireNotNull(PmtilesHeader.parse(realHeader))

        // Parliament Hill, comfortably inside the extract.
        assertTrue(header.contains(latitude = 45.4236, longitude = -75.7009))
        // Kingston, two hours away and outside it.
        assertTrue(!header.contains(latitude = 44.2312, longitude = -76.4860))
    }

    @Test
    fun `rejects a file that is not an archive`() {
        assertNull(PmtilesHeader.parse(ByteArray(127)))
        assertNull(PmtilesHeader.parse("not a map at all, just some bytes".toByteArray()))
    }

    @Test
    fun `rejects a truncated header`() {
        assertNull(PmtilesHeader.parse(realHeader.copyOf(126)))
    }

    @Test
    fun `rejects a version it cannot read`() {
        // Byte 7 is the spec version. v2 has an entirely different layout, so parsing it
        // with these offsets would produce confident nonsense rather than nothing.
        val v2 = realHeader.copyOf().also { it[7] = 2 }
        assertNull(PmtilesHeader.parse(v2))
    }

    @Test
    fun `reads a raster archive as raster`() {
        // Byte 99 is the tile type; 2 is PNG. A raster archive carries no schema, so the
        // style has to build a raster layer for it rather than the vector one.
        val png = realHeader.copyOf().also { it[99] = 2 }
        val header = requireNotNull(PmtilesHeader.parse(png))

        assertEquals(PmtilesHeader.TileType.PNG, header.tileType)
        assertTrue(!header.isVector)
    }
}
