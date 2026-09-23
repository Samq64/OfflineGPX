package dev.samuelq.gpx.ui.chart

import kotlin.test.Test
import kotlin.test.assertEquals

class ChartMathTest {

    private fun Scale.ticksIn(perUnit: Float) = ticks.map { it * perUnit }

    private fun assertTicks(expected: List<Float>, actual: List<Float>) {
        assertEquals(expected.size, actual.size, "ticks $actual")
        expected.zip(actual).forEach { (e, a) -> assertEquals(e, a, 1e-3f, "ticks $actual") }
    }

    /** Round in km/h, not in m/s: 8 m/s used to tick at 0, 7.2, 14.4, 21.6, 28.8 km/h. */
    @Test
    fun `speed ticks are round in display units`() {
        val kmh = niceScale(0f, 8f, zeroBased = true, perUnit = 3.6f)
        assertTicks(listOf(0f, 10f, 20f, 30f), kmh.ticksIn(3.6f))
        assertEquals(30f, kmh.max * 3.6f, 1e-3f)
    }

    @Test
    fun `distance ticks are round in miles`() {
        val perMile = (1 / 1609.344).toFloat()
        val scale = axisScale(0f, 8000f, perUnit = perMile)
        assertTicks(listOf(0f, 2f, 4f), scale.ticksIn(perMile))
        // The domain stays as measured, in metres.
        assertEquals(8000f, scale.max)
    }

    /** Float error once truncated the count and dropped the top tick. */
    @Test
    fun `nice scale keeps its top tick`() {
        val scale = niceScale(50f, 50.75f, zeroBased = false)
        assertEquals(scale.max, scale.ticks.last(), 1e-4f)
        assertEquals(5, scale.ticks.size)
    }

    @Test
    fun `time ticks fall on clock steps`() {
        assertTicks(listOf(0f, 60f, 120f, 180f), timeAxisScale(0f, 200f).ticks.toList())
        assertTicks(listOf(0f, 1800f, 3600f), timeAxisScale(0f, 4000f).ticks.toList())
        assertTicks(listOf(0f, 3f * 3600, 6f * 3600, 9f * 3600), timeAxisScale(0f, 10f * 3600).ticks.toList())
    }
}
