package dev.samuelq.gpx.data.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.io.RandomAccessFile

/** Maps at zoom 2 over the whole world: a 4 by 4 tile index and nothing else. */
class MapOverlapTest {

    private fun map(tileBytes: List<Long>, baseZoom: Int = 2) = offlineMap(
        mapFile(south = -85.0, west = -180.0, north = 85.0, east = 180.0, baseZoom = baseZoom, tileBytes = tileBytes),
    )

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
    fun `boxes that share no tile share nothing`() {
        val west = offlineMap(mapFile(south = -10.0, west = -170.0, north = 10.0, east = -100.0, baseZoom = 2))
        val east = offlineMap(mapFile(south = -10.0, west = 100.0, north = 10.0, east = 170.0, baseZoom = 2))
        assertEquals(0.0, sharedData(west, east))
        val north = offlineMap(mapFile(south = 70.0, west = -10.0, north = 80.0, east = 10.0, baseZoom = 2))
        val south = offlineMap(mapFile(south = -80.0, west = -10.0, north = -70.0, east = 10.0, baseZoom = 2))
        assertEquals(0.0, sharedData(north, south))
    }

    @Test
    fun `maps with no data share nothing`() {
        assertEquals(0.0, sharedData(map(List(16) { 0L }), map(List(16) { 0L })))
    }

    /** As when a map is deleted or cut short mid-comparison. */
    @Test
    fun `an unreadable index can't be compared`() {
        val gone = region(setOf(0)).also { it.file.delete() }
        assertNull(sharedData(region(setOf(0)), gone))
        val cut = region(setOf(0)).also { RandomAccessFile(it.file, "rw").use { f -> f.setLength(200) } }
        assertNull(sharedData(cut, region(setOf(0))))
    }

    @Test
    fun `boxes overlap only if they cross on both axes`() {
        fun box(south: Double, west: Double) =
            offlineMap(mapFile(south = south, west = west, north = south + 1, east = west + 1, baseZoom = 8))
        val centre = box(42.0, 1.0)
        assertTrue(centre.overlaps(box(42.5, 1.5)))
        assertFalse(centre.overlaps(box(42.0, 2.0)), "east, sharing an edge")
        assertFalse(centre.overlaps(box(42.0, 0.0)), "west")
        assertFalse(centre.overlaps(box(43.0, 1.0)), "north")
        assertFalse(centre.overlaps(box(41.0, 1.0)), "south")
        assertFalse(centre.duplicates(box(41.0, 1.0)))
    }

    @Test
    fun `duplicates are overlapping maps sharing most data`() {
        assertTrue(region(setOf(0, 1)).duplicates(region(setOf(0, 1))))
        assertFalse(region(setOf(0, 1)).duplicates(region(setOf(2, 3))), "neighbours")
    }

    /** Overlapping, but at different base zooms: assumed the same place, so the user is asked. */
    @Test
    fun `an overlap that can't be measured counts as a duplicate`() {
        assertTrue(region(setOf(0)).duplicates(offlineMap(mapFile(baseZoom = 14))))
    }
}
