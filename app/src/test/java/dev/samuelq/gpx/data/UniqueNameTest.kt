package dev.samuelq.gpx.data

import kotlin.test.Test
import kotlin.test.assertEquals

class UniqueNameTest {

    @Test
    fun `a free name is kept, with one extension`() {
        assertEquals("Afternoon ride.gpx", uniqueName("Afternoon ride.gpx", "gpx", "track") { false })
        assertEquals("Afternoon ride.gpx", uniqueName("Afternoon ride", "gpx", "track") { false })
    }

    @Test
    fun `a taken name is numbered before the extension`() {
        val taken = setOf("Afternoon ride.gpx", "Afternoon ride (2).gpx")
        assertEquals("Afternoon ride (3).gpx", uniqueName("Afternoon ride.gpx", "gpx", "track") { it in taken })
    }

    @Test
    fun `unsafe and blank names are replaced`() {
        assertEquals("a_b.gpx", uniqueName("a:b.gpx", "gpx", "track") { false })
        assertEquals("Mon_Tue ride.gpx", uniqueName("Mon/Tue ride", "gpx", "track") { false })
        assertEquals("track.gpx", uniqueName("  ", "gpx", "track") { false })
    }
}
