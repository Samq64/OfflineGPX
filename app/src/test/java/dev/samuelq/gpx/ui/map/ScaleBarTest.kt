package dev.samuelq.gpx.ui.map

import dev.samuelq.gpx.core.model.UnitSystem
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class ScaleBarTest {

    private val feet = 0.3048
    private val mile = 1609.344

    @Test
    fun `a scale snaps down to 1, 2 or 5 of a power of ten`() {
        assertEquals(
            listOf(1000.0, 500.0, 200.0, 100.0, 1.0),
            listOf(1234.0, 999.0, 499.0, 199.9, 1.0).map {
                roundDistance(it, UnitSystem.METRIC)
            },
        )
        // Exact powers of ten, where log10 could round down a step.
        assertEquals(
            listOf(10.0, 100.0, 1000.0, 10000.0),
            listOf(10.0, 100.0, 1000.0, 10000.0).map {
                roundDistance(it, UnitSystem.METRIC)
            },
        )
    }

    @Test
    fun `imperial is feet below a mile and miles from one`() {
        assertEquals(5000 * feet, roundDistance(1600.0, UnitSystem.IMPERIAL), 1e-6)
        assertEquals(mile, roundDistance(mile, UnitSystem.IMPERIAL), 1e-6)
        assertEquals(2 * mile, roundDistance(3 * mile, UnitSystem.IMPERIAL), 1e-6)
    }

    @Test
    fun `no room is no scale`() {
        assertEquals(0.0, roundDistance(0.0, UnitSystem.METRIC))
    }

    @Test
    fun `a label is a whole number in the unit the distance was snapped in`() {
        fun label(meters: Double, units: UnitSystem) = scaleLabel(meters, units, Locale.US)
        assertEquals("500 m", label(500.0, UnitSystem.METRIC))
        assertEquals("2 km", label(2000.0, UnitSystem.METRIC))
        assertEquals("5,000 ft", label(roundDistance(1600.0, UnitSystem.IMPERIAL), UnitSystem.IMPERIAL))
        assertEquals("2 mi", label(roundDistance(3 * mile, UnitSystem.IMPERIAL), UnitSystem.IMPERIAL))
    }
}
