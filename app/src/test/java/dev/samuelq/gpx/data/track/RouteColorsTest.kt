package dev.samuelq.gpx.data.track

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RouteColorsTest {

    @Test
    fun `each colour leaves and returns as its Garmin name`() {
        RouteColor.entries.forEach { assertEquals(it, RouteColor.ofGarmin(it.name)) }
    }

    @Test
    fun `any index wraps to a colour`() {
        assertEquals(RouteColor.Red, RouteColor.at(RouteColor.entries.size))
        assertEquals(RouteColor.Magenta, RouteColor.at(-1))
    }

    @Test
    fun `either shade of a hue is its colour`() {
        assertEquals(RouteColor.Cyan, RouteColor.ofGarmin("DarkCyan"))
        assertEquals(RouteColor.Magenta, RouteColor.ofGarmin(" DarkMagenta "))
    }

    @Test
    fun `greys, Transparent and unknown names match no colour`() {
        listOf("Black", "DarkGray", "LightGray", "White", "Transparent", "Orange", "").forEach {
            assertNull(RouteColor.ofGarmin(it), it)
        }
    }
}
