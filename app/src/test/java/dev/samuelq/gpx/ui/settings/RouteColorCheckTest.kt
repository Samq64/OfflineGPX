package dev.samuelq.gpx.ui.settings

import dev.samuelq.gpx.data.track.RouteColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteColorCheckTest {

    private val lightGround =
        MapGround(land = 0xFFFFFFFF.toInt(), water = 0xFFA6CBE3.toInt(), vegetation = 0xFFD5E9CF.toInt())
    private val darkGround =
        MapGround(land = 0xFF101114.toInt(), water = 0xFF16323F.toInt(), vegetation = 0xFF1E3220.toInt())

    @Test
    fun `the defaults raise nothing`() {
        RouteColors.LIGHT.forEachIndexed { slot, argb ->
            assertNull(colorWarning(argb, slot, RouteColors.LIGHT, dark = false, lightGround), "light $slot")
        }
        RouteColors.DARK.forEachIndexed { slot, argb ->
            assertNull(colorWarning(argb, slot, RouteColors.DARK, dark = true, darkGround), "dark $slot")
        }
    }

    @Test
    fun `a pale colour on light land is hard to see`() {
        val warning = colorWarning(0xFFFFE0E0.toInt(), 0, RouteColors.LIGHT, dark = false, lightGround)
        assertEquals(ColorWarning.LowContrast, warning)
    }

    @Test
    fun `another slot's colour is too close to it`() {
        val green = RouteColors.DARK[2]
        assertEquals(ColorWarning.CloseTo(2), colorWarning(green, 3, RouteColors.DARK, dark = true, darkGround))
    }

    @Test
    fun `low contrast outranks closeness`() {
        // Light mode's green on dark land, next to dark mode's green.
        assertTrue(deltaE2000(lab(RouteColors.LIGHT[2]), lab(RouteColors.DARK[2])) < 25)
        val warning = colorWarning(RouteColors.LIGHT[2], 3, RouteColors.DARK, dark = true, darkGround)
        assertEquals(ColorWarning.LowContrast, warning)
    }

    @Test
    fun `CIEDE2000 matches Sharma's test data`() {
        val d = deltaE2000(doubleArrayOf(50.0, 2.6772, -79.7751), doubleArrayOf(50.0, 0.0, -82.7485))
        assertEquals(2.0425, d, 1e-4)
        val e = deltaE2000(doubleArrayOf(50.0, 2.5, 0.0), doubleArrayOf(73.0, 25.0, -18.0))
        assertEquals(27.1492, e, 1e-4)
    }

    @Test
    fun `contrast is WCAG's`() {
        assertEquals(21.0, contrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 1e-9)
    }
}
