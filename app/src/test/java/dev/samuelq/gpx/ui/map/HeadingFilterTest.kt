package dev.samuelq.gpx.ui.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeadingFilterTest {

    @Test
    fun `the first reading is taken as it is`() {
        assertEquals(90.0, HeadingFilter().compass(90.0, 0), 1e-9)
    }

    @Test
    fun `it swings from where the map was, the short way through north`() {
        val filter = HeadingFilter()
        filter.startAt(350.0)
        assertEquals(350.0, filter.compass(10.0, 0), 1e-9)
        val next = filter.compass(10.0, 100)
        // Past north, never back through 180.
        assertTrue(next > 350.0 || next < 10.0, "$next")
        var heading = next
        for (at in 200L..3_000L step 100) heading = filter.compass(10.0, at)
        assertEquals(10.0, heading, 0.1)
    }

    @Test
    fun `a sound course wins over the compass for a while`() {
        val filter = HeadingFilter()
        filter.compass(0.0, 0)
        assertEquals(180.0, filter.course(180.0, meters = 5.0, seconds = 1.0, atMillis = 1_000)!!, 1.0)
        // The compass says north, but the course holds.
        var heading = 0.0
        for (at in 1_100L..3_000L step 100) heading = filter.compass(0.0, at)
        assertEquals(180.0, heading, 1.0)
        // Then lapses back to the compass.
        for (at in 3_600L..6_000L step 100) heading = filter.compass(0.0, at)
        assertEquals(0.0, turn(0.0, heading), 1.0)
    }

    @Test
    fun `a course from fixes far apart holds until the next is due`() {
        val filter = HeadingFilter()
        filter.compass(0.0, 0)
        filter.course(180.0, meters = 30.0, seconds = 5.0, atMillis = 1_000)
        var heading = 0.0
        for (at in 1_100L..10_900L step 100) heading = filter.compass(0.0, at)
        assertEquals(180.0, heading, 1.0)
    }

    @Test
    fun `a slow fix hands back to the compass at once`() {
        val filter = HeadingFilter()
        filter.compass(0.0, 0)
        filter.course(180.0, meters = 30.0, seconds = 5.0, atMillis = 1_000)
        assertNull(filter.course(90.0, meters = 1.0, seconds = 5.0, atMillis = 6_000))
        var heading = 180.0
        for (at in 6_100L..8_000L step 100) heading = filter.compass(0.0, at)
        assertEquals(0.0, turn(0.0, heading), 1.0)
    }

    @Test
    fun `a slow or short course is ignored`() {
        val filter = HeadingFilter()
        filter.compass(0.0, 0)
        assertNull(filter.course(180.0, meters = 1.5, seconds = 0.5, atMillis = 1_000))
        assertNull(filter.course(180.0, meters = 3.0, seconds = 2.0, atMillis = 1_000))
        assertNull(filter.course(180.0, meters = 3.0, seconds = 0.0, atMillis = 1_000))
        assertEquals(0.0, filter.compass(0.0, 1_100), 1e-9)
    }

    @Test
    fun `the shortest turn crosses north`() {
        assertEquals(20.0, turn(350.0, 10.0), 1e-9)
        assertEquals(-20.0, turn(10.0, 350.0), 1e-9)
        assertEquals(-90.0, turn(0.0, -90.0), 1e-9)
    }

    @Test
    fun `the phone faces where its top points flat, and its back points upright`() {
        // Flat, top to the east: the phone's x is south, y east, z up.
        val flatEast = floatArrayOf(0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f)
        assertEquals(90.0, facing(flatEast), 1e-6)
        // Upright facing north, its back away from the user: x east, y up, z south.
        val uprightNorth = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)
        assertEquals(0.0, facing(uprightNorth), 1e-6)
        // Tilted halfway between, facing west: x north, y west and up, z east and up.
        val c = kotlin.math.sqrt(0.5f)
        val tiltedWest = floatArrayOf(0f, -c, c, 1f, 0f, 0f, 0f, c, c)
        assertEquals(-90.0, facing(tiltedWest), 1e-6)
    }
}
