package dev.samuelq.gpx.data.map

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Maps at zoom 2 over the whole world: a 4 by 4 tile index and nothing else. */
class MapOverlapTest {

    private fun map(tileBytes: List<Long>, baseZoom: Int = 2): OfflineMap {
        val tiles = 1 shl (2 * baseZoom)
        require(tileBytes.size == tiles)
        val index = ByteArray(tiles * 5)
        var offset = index.size.toLong()
        tileBytes.forEachIndexed { i, bytes ->
            for (b in 0 until 5) index[i * 5 + b] = (offset shr (8 * (4 - b))).toByte()
            offset += bytes
        }
        val file = File.createTempFile("overlap", ".map").apply {
            deleteOnExit()
            writeBytes(index)
        }
        val header = MapFileHeader(
            baseZoom = baseZoom,
            minLongitude = -180.0,
            minLatitude = -85.0,
            maxLongitude = 180.0,
            maxLatitude = 85.0,
            attribution = null,
            subFileStart = 0,
            subFileSize = offset,
        )
        return OfflineMap(file, header, offset)
    }

    /** Land and sea everywhere, and a region's real data on one side. */
    private fun region(heavyColumns: Set<Int>) =
        map(List(16) { i -> if (i % 4 in heavyColumns) 1_000L else 50L })

    @Test
    fun `a map shares all of its data with a copy`() {
        val a = region(setOf(0, 1))
        assertEquals(1.0, sharedData(a, region(setOf(0, 1))))
    }

    @Test
    fun `neighbours share little despite the same box`() {
        val share = sharedData(region(setOf(0, 1)), region(setOf(2, 3)))!!
        assertTrue(share < DUPLICATE_SHARE, "shared $share")
    }

    @Test
    fun `a region inside a bigger map is a duplicate`() {
        val share = sharedData(region(setOf(1)), region(setOf(0, 1, 2)))!!
        assertTrue(share >= DUPLICATE_SHARE, "shared $share")
    }

    /** Where one box reaches only over the other's region, holding just its own filler. */
    @Test
    fun `filler isn't shared data`() {
        assertEquals(0.0, sharedData(map(List(16) { 50L }), region(setOf(0, 1))))
    }

    @Test
    fun `differing base zooms can't be compared`() {
        assertNull(sharedData(region(setOf(0)), map(List(4) { 1L }, baseZoom = 1)))
    }

    @Test
    fun `tiles are numbered as mapsforge does`() {
        assertEquals(0, longitudeToTile(-180.0, 2))
        assertEquals(3, longitudeToTile(180.0, 2))
        assertEquals(2, longitudeToTile(0.0, 2))
        assertEquals(0, latitudeToTile(85.0, 2))
        assertEquals(2, latitudeToTile(0.0, 2))
        assertEquals(3, latitudeToTile(-85.0, 2))
    }
}
