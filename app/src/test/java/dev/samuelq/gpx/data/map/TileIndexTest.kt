package dev.samuelq.gpx.data.map

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A zoom 2 world map: 4 by 4 tiles, numbered row by row. */
class TileIndexTest {

    private val map = offlineMap(
        mapFile(
            south = -85.0,
            west = -180.0,
            north = 85.0,
            east = 180.0,
            baseZoom = 2,
            tileBytes = List(16) { it * 10L },
            water = setOf(5, 15),
        ),
    )

    @Test
    fun `the water flag is read per tile`() {
        TileIndex(map.file, map.deepest).use { index ->
            assertTrue(index.isWater(1, 1))
            assertTrue(index.isWater(3, 3))
            assertFalse(index.isWater(0, 1))
        }
    }

    @Test
    fun `outside the box is never water`() {
        TileIndex(map.file, map.deepest).use { index ->
            assertFalse(index.isWater(-1, 1))
            assertFalse(index.isWater(4, 1))
            assertFalse(index.isWater(1, -1))
            assertFalse(index.isWater(1, 4))
        }
    }
}
