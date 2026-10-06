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

    @Test
    fun `only characters a filesystem refuses are replaced`() {
        assertEquals("Tom & Jerry's ride", safeFileName("Tom & Jerry's ride"))
        assertEquals("a_b_c_d_e_f_g_h_i_j", safeFileName("a/b\\c:d*e?f\"g<h>i|j"))
        assertEquals("tab_new_line_del_", safeFileName("tab\tnew\nline\u0000del\u007f"))
        assertEquals("Café, 9.5 km (copy) #2", safeFileName("Café, 9.5 km (copy) #2"))
    }
}
