package dev.samuelq.gpx.data.track

import dev.samuelq.gpx.data.db.TrackEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RouteColorsTest {

    @Test
    fun `each slot leaves and returns as its Garmin name`() {
        (0 until TrackEntity.PALETTE_SIZE).forEach { slot ->
            assertEquals(slot, RouteColors.slotOf(RouteColors.garminName(slot)))
        }
        assertEquals("Red", RouteColors.garminName(TrackEntity.PALETTE_SIZE))
    }

    @Test
    fun `either shade of a hue is its slot`() {
        assertEquals(RouteColors.slotOf("Cyan"), RouteColors.slotOf("DarkCyan"))
        assertEquals(RouteColors.slotOf("Magenta"), RouteColors.slotOf(" DarkMagenta "))
    }

    @Test
    fun `greys, Transparent and unknown names match no slot`() {
        listOf("Black", "DarkGray", "LightGray", "White", "Transparent", "Orange", "").forEach {
            assertNull(RouteColors.slotOf(it), it)
        }
    }
}
