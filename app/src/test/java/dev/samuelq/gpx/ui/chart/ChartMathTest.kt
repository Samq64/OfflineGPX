package dev.samuelq.gpx.ui.chart

import androidx.compose.ui.graphics.Color
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
        val kmh = axisScale(0f, 8f, perUnit = 3.6f)
        assertTicks(listOf(0f, 10f, 20f), kmh.ticksIn(3.6f))
    }

    @Test
    fun `a y scale spans exactly the data in five even ticks`() {
        val series = ChartSeries(
            x = floatArrayOf(0f, 1f, 2f),
            y = floatArrayOf(12f, Float.NaN, 31f),
            segmentStartIndices = intArrayOf(0),
            color = Color.Red,
        )
        val scale = series.yScale()
        assertEquals(12f, scale.min)
        // The top is the peak itself, not the next round number above it.
        assertEquals(31f, scale.max)
        assertTicks(listOf(12f, 16.75f, 21.5f, 26.25f, 31f), scale.ticks.toList())
    }

    @Test
    fun `a speed scale starts at zero`() {
        val series = ChartSeries(floatArrayOf(0f, 1f), floatArrayOf(8f, 20f), intArrayOf(0), Color.Red)
        val scale = series.yScale(fromZero = true)
        assertEquals(0f, scale.min)
        assertEquals(20f, scale.max)
    }

    @Test
    fun `a y scale always has five ticks`() {
        for ((lo, hi) in listOf(0f to 1f, 0f to 10f, 3f to 97f, 101.5f to 102.3f, -40f to 2500f, 19f to 21f)) {
            val series = ChartSeries(floatArrayOf(0f, 1f), floatArrayOf(lo, hi), intArrayOf(0), Color.Red)
            val scale = series.yScale(3.6f)
            assertEquals(5, scale.ticks.size, "$lo..$hi: ${scale.ticks.toList()}")
            assert(scale.min <= lo && scale.max >= hi) { "$lo..$hi: ${scale.ticks.toList()}" }
        }
    }

    @Test
    fun `distance ticks are round in miles`() {
        val perMile = (1 / 1609.344).toFloat()
        val scale = axisScale(0f, 8000f, perUnit = perMile)
        assertTicks(listOf(0f, 2f, 4f), scale.ticksIn(perMile))
        // The domain stays as measured, in metres.
        assertEquals(8000f, scale.max)
    }

    @Test
    fun `time ticks fall on clock steps`() {
        assertTicks(listOf(0f, 60f, 120f, 180f), timeAxisScale(0f, 200f).ticks.toList())
        assertTicks(listOf(0f, 1800f, 3600f), timeAxisScale(0f, 4000f).ticks.toList())
        assertTicks(listOf(0f, 3f * 3600, 6f * 3600, 9f * 3600), timeAxisScale(0f, 10f * 3600).ticks.toList())
    }

    @Test
    fun `a pinch keeps the value under the fingers in place`() {
        val view = zoomView(0f..100f, 0f..100f, anchor = 0.25f, zoom = 2f, pan = 0f)
        assertEquals(12.5f, view.start, 1e-3f)
        assertEquals(62.5f, view.endInclusive, 1e-3f)
    }

    @Test
    fun `a pinch stays within the domain and its zoom limit`() {
        val panned = zoomView(0f..50f, 0f..100f, anchor = 0.5f, zoom = 1f, pan = 1f)
        assertEquals(0f, panned.start, 1e-3f)
        assertEquals(50f, panned.endInclusive, 1e-3f)

        val zoomedOut = zoomView(40f..60f, 0f..100f, anchor = 0.5f, zoom = 0.1f, pan = 0f)
        assertEquals(0f, zoomedOut.start, 1e-3f)
        assertEquals(100f, zoomedOut.endInclusive, 1e-3f)

        val zoomedIn = zoomView(0f..100f, 0f..100f, anchor = 0.5f, zoom = 1000f, pan = 0f, maxZoom = 50f)
        assertEquals(2f, zoomedIn.endInclusive - zoomedIn.start, 1e-3f)
    }
}
