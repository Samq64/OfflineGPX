package dev.samuelq.gpx.data.track

import dev.samuelq.gpx.data.gpx.GpxColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RouteColorsTest {

    @Test
    fun `each slot's own colour, in either theme, comes back to it`() {
        RouteColors.LIGHT.indices.forEach { slot ->
            assertEquals(slot, RouteColors.slotOf(RouteColors.rgb(slot)))
            assertEquals(slot, RouteColors.slotOf(RouteColors.DARK[slot] and 0xFFFFFF))
        }
    }

    @Test
    fun `greys match no slot`() {
        listOf(0x000000, 0x808080, 0xC0C0C0, 0xFFFFFF).forEach { assertNull(RouteColors.slotOf(it)) }
    }

    @Test
    fun `garmin's colours go to the slot of their hue`() {
        assertEquals(0, RouteColors.slotOf(GpxColors.garminRgb("DarkCyan")!!))
        assertEquals(5, RouteColors.slotOf(GpxColors.garminRgb("Blue")!!))
        assertEquals(6, RouteColors.slotOf(GpxColors.garminRgb("Red")!!))
    }

    @Test
    fun `garmin's names for the slots`() {
        assertEquals(
            listOf("DarkCyan", "Magenta", "DarkGreen", "DarkYellow", "Magenta", "Blue", "Red"),
            RouteColors.LIGHT.indices.map { GpxColors.garminName(RouteColors.rgb(it)) },
        )
    }
}
