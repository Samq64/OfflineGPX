package dev.samuelq.gpx.ui.chart

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChartMathTest {

    private fun Scale.ticksIn(perUnit: Float) = ticks.map { it * perUnit }

    private fun assertTicks(expected: List<Float>, actual: List<Float>) {
        assertEquals(expected.size, actual.size, "ticks $actual")
        expected.zip(actual).forEach { (e, a) -> assertEquals(e, a, 1e-3f, "ticks $actual") }
    }

    @Test
    fun `speed ticks are round in display units`() {
        val kmh = axisScale(0f, 8f, perUnit = 3.6f)
        assertTicks(listOf(0f, 10f, 20f), kmh.ticksIn(3.6f))
    }

    @Test
    fun `a y scale ends on the data and ticks round values between`() {
        val series = ChartSeries(
            x = floatArrayOf(0f, 1f, 2f),
            y = floatArrayOf(12f, Float.NaN, 31f),
            segmentStartIndices = intArrayOf(0),
            color = Color.Red,
        )
        val scale = series.yScale()
        assertEquals(12f, scale.min)
        assertEquals(31f, scale.max)
        assertTicks(listOf(12f, 15f, 20f, 25f, 31f), scale.ticks.toList())
        assertEquals(5f, scale.step)
    }

    @Test
    fun `inner y ticks are round in display units and clear of the ends`() {
        // 45.3 to 75.2 m in feet: 148.6 to 246.7, on a 25 ft step.
        val feet = 3.280839895f
        val series = ChartSeries(floatArrayOf(0f, 1f), floatArrayOf(45.3f, 75.2f), intArrayOf(0), Color.Red)
        val scale = series.yScale(feet)
        assertTicks(listOf(45.3f * feet, 175f, 200f, 225f, 75.2f * feet), scale.ticksIn(feet))
        assertEquals(25f, scale.step * feet, 1e-3f)
        // 50 and 60 are too near the ends to label beside them.
        val near = ChartSeries(floatArrayOf(0f, 1f), floatArrayOf(48.9f, 61f), intArrayOf(0), Color.Red).yScale()
        assertTicks(listOf(48.9f, 55f, 61f), near.ticks.toList())
    }

    @Test
    fun `a speed scale starts at zero`() {
        val series = ChartSeries(floatArrayOf(0f, 1f), floatArrayOf(8f, 20f), intArrayOf(0), Color.Red)
        val scale = series.yScale(fromZero = true)
        assertEquals(0f, scale.min)
        assertEquals(20f, scale.max)
    }

    @Test
    fun `a from-zero scale starts at zero even when flat`() {
        val series = ChartSeries(floatArrayOf(0f, 1f), floatArrayOf(0f, 0f), intArrayOf(0), Color.Red)
        val scale = series.yScale(3.6f, fromZero = true)
        assertEquals(0f, scale.min)
        assert(scale.ticks.all { it >= 0f }) { scale.ticks.toList().toString() }
    }

    @Test
    fun `a y scale always has its ends and a step of 1, 2, 2,5 or 5 times a power of ten`() {
        for ((lo, hi) in listOf(0f to 1f, 0f to 10f, 3f to 97f, 101.5f to 102.3f, -40f to 2500f, 19f to 21f)) {
            val series = ChartSeries(floatArrayOf(0f, 1f), floatArrayOf(lo, hi), intArrayOf(0), Color.Red)
            val scale = series.yScale(3.6f)
            val ticks = scale.ticks.toList()
            assertEquals(lo, ticks.first(), "$lo..$hi: $ticks")
            assertEquals(hi, ticks.last(), "$lo..$hi: $ticks")
            assert(ticks.size in 3..6) { "$lo..$hi: $ticks" }
            val step = scale.step * 3.6f
            val mantissa = step / 10f.pow(floor(log10(step)))
            assert(listOf(1f, 2f, 2.5f, 5f).any { abs(it - mantissa) < 1e-3f }) { "$lo..$hi: step $step" }
            ticks.drop(1).dropLast(1).forEach { tick ->
                val inUnits = tick * 3.6f / step
                assertEquals(inUnits.roundToInt().toFloat(), inUnits, 1e-2f, "$lo..$hi: $ticks")
            }
        }
    }

    @Test
    fun `distance ticks are round in miles`() {
        val perMile = (1 / 1609.344).toFloat()
        val scale = axisScale(0f, 8000f, perUnit = perMile)
        assertTicks(listOf(0f, 2f, 4f), scale.ticksIn(perMile))
        // The domain stays in metres.
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

    @Test
    fun `ticks step 1, 2, 5 or 10 times a power of ten`() {
        assertTicks(listOf(0f, 1f, 2f, 3f, 4f), axisScale(0f, 4f).ticks.toList())
        assertTicks(listOf(0f, 5f, 10f, 15f), axisScale(0f, 16f).ticks.toList())
        assertTicks(listOf(0f, 10f, 20f, 30f), axisScale(0f, 30f).ticks.toList())
        // Inside the domain, not at its ends.
        assertTicks(listOf(20f, 30f, 40f), axisScale(13f, 47f).ticks.toList())
    }

    @Test
    fun `an empty or broken domain has no ticks`() {
        for (scale in listOf(axisScale(5f, 5f), axisScale(5f, 1f), timeAxisScale(0f, 0f))) {
            assertEquals(0, scale.ticks.size)
            assertEquals(1f, scale.span)
        }
        val nan = axisScale(Float.NaN, Float.NaN)
        assertEquals(0f, nan.min)
        assertEquals(1f, nan.max)
        val huge = axisScale(Float.MAX_VALUE, Float.POSITIVE_INFINITY)
        assertEquals(Float.MAX_VALUE, huge.min)
    }

    @Test
    fun `a scale's step is its tick gap, else its span`() {
        assertEquals(10f, axisScale(0f, 30f).step)
        assertEquals(4f, Scale(2f, 6f, floatArrayOf(3f)).step)
    }

    @Test
    fun `time ticks past half a day are whole days`() {
        val day = 86_400f
        assertTicks(listOf(0f, day, 2 * day, 3 * day, 4 * day), timeAxisScale(0f, 4 * day).ticks.toList())
        assertTicks(listOf(0f, 5f, 10f, 15f, 20f), timeAxisScale(0f, 20f).ticks.toList())
    }

    @Test
    fun `a series with no values gets a unit scale`() {
        val series = ChartSeries(floatArrayOf(0f, 1f), floatArrayOf(Float.NaN, Float.NaN), intArrayOf(0), Color.Red)
        val scale = series.yScale(perUnit = 2f)
        assertEquals(0f, scale.min)
        assertEquals(0.5f, scale.max)
        assertEquals(2, series.size)
    }

    @Test
    fun `a flat series is padded both ways by a tenth`() {
        val series = ChartSeries(floatArrayOf(0f, 1f), floatArrayOf(-50f, -50f), intArrayOf(0), Color.Red)
        val scale = series.yScale()
        assertEquals(-55f, scale.min)
        assertEquals(-45f, scale.max)
    }

    @Test
    fun `a pinch over an empty domain or by zero leaves the view`() {
        assertEquals(10f..20f, zoomView(10f..20f, 5f..5f, anchor = 0.5f, zoom = 2f, pan = 0f))
        assertEquals(10f..20f, zoomView(10f..20f, 0f..100f, anchor = 0.5f, zoom = 0f, pan = 0f))
    }

    @Test
    fun `nearest index picks the closer neighbour, the earlier on a tie`() {
        val xs = floatArrayOf(0f, 10f, 20f, 30f)
        assertEquals(-1, nearestIndex(FloatArray(0), 5f))
        assertEquals(0, nearestIndex(xs, -5f))
        assertEquals(1, nearestIndex(xs, 12f))
        assertEquals(2, nearestIndex(xs, 16f))
        assertEquals(0, nearestIndex(xs, 5f))
        assertEquals(3, nearestIndex(xs, 99f))
    }

    @Test
    fun `a polyline keeps each column's first, extremes and last`() {
        val line = RecordingPath()
        PolylineBuilder(line.path).apply {
            add(0.2f, 5f)
            add(0.5f, 1f)
            add(0.7f, 9f)
            add(0.9f, 4f)
            add(1.5f, 3f)
            finish()
        }

        assertEquals(
            listOf("M0,5", "L0,1", "L0,9", "L0,4", "L1,3", "L1,3"),
            line.calls,
        )
    }

    @Test
    fun `a break starts a detached polyline and closes each area to the baseline`() {
        val line = RecordingPath()
        val area = RecordingPath()
        PolylineBuilder(line.path, area.path, baselineY = 100f).apply {
            add(0f, 10f)
            add(1f, 20f)
            breakLine()
            breakLine()
            add(5f, 30f)
            finish()
        }

        assertEquals(listOf("M0,10", "L0,10", "L1,20", "L1,20", "M5,30", "L5,30"), line.calls)
        assertEquals(
            listOf(
                "M0,10", "L0,10", "L1,20", "L1,20", "L1,100", "L0,100", "Z",
                "M5,30", "L5,30", "L5,100", "L5,100", "Z",
            ),
            area.calls,
        )
    }

    @Test
    fun `an area with nothing added stays empty`() {
        val area = RecordingPath()
        PolylineBuilder(RecordingPath().path, area.path).finish()
        assertEquals(emptyList(), area.calls)
    }

    private fun series(x: FloatArray, vararg starts: Int) =
        ChartSeries(x = x, y = FloatArray(x.size), segmentStartIndices = starts, color = Color.Red)

    @Test
    fun `a break spans the last point of one segment to the first of the next`() {
        val timed = series(floatArrayOf(0f, 5f, 10f, 610f, 615f, 900f, 905f), 0, 3, 5)
        assertEquals(listOf(10f..610f, 615f..900f), timed.breaks())
    }

    @Test
    fun `a break that x doesn't advance across, or that isn't inside the series, isn't one`() {
        // Distance doesn't advance through signal loss.
        assertEquals(emptyList(), series(floatArrayOf(0f, 5f, 5f, 9f), 0, 2).breaks())
        assertEquals(emptyList(), series(floatArrayOf(0f, 5f), 0).breaks())
        assertEquals(emptyList(), series(floatArrayOf(0f, 5f), 0, 0, 2).breaks())
    }

    @Test
    fun `a label is centred in its span only with a margin either side`() {
        val label = Size(40f, 10f)
        assertEquals(Rect(30f, 45f, 70f, 55f), centredLabel(0f, 100f, 50f, label, margin = 30f))
        assertNull(centredLabel(0f, 100f, 50f, label, margin = 31f))
    }

    private val plot = Rect(0f, 0f, 100f, 100f)
    private fun scale(min: Float, max: Float) = Scale(min, max, FloatArray(0))

    @Test
    fun `x maps to the plot and back`() {
        val x = scale(10f, 20f)
        assertEquals(25f, plot.xFor(12.5f, x))
        assertEquals(12.5f, plot.valueForX(25f, x))
        // Up from the bottom.
        assertEquals(75f, plot.yFor(12.5f, x))
    }

    /** The x of each point drawn, and of each start of a detached run. */
    private fun traced(series: ChartSeries, xScale: Scale): Pair<List<Int>, List<Int>> {
        val line = RecordingPath()
        buildPaths(series, xScale, scale(0f, 100f), plot, line.path, RecordingPath().path)
        fun xs(prefix: String) =
            line.calls.filter { it.startsWith(prefix) }.map { it.drop(1).substringBefore(',').toInt() }
        return xs("").distinct() to xs("M")
    }

    @Test
    fun `a zoomed path draws what's in view and one point either side`() {
        val series = ChartSeries(
            x = FloatArray(11) { it.toFloat() },
            y = FloatArray(11) { 50f },
            segmentStartIndices = intArrayOf(0),
            color = Color.Red,
        )
        assertEquals(listOf(-50, 0, 50, 100, 150) to listOf(-50), traced(series, scale(4f, 6f)))
    }

    @Test
    fun `a path is cut at each segment and missing value`() {
        val series = ChartSeries(
            x = floatArrayOf(0f, 1f, 2f, 3f, 4f, 5f),
            y = floatArrayOf(10f, Float.NaN, 30f, 40f, 50f, 60f),
            segmentStartIndices = intArrayOf(0, 4),
            color = Color.Red,
        )
        assertEquals(listOf(0, 40, 80), traced(series, scale(0f, 5f)).second)
    }

    @Test
    fun `the scrubber is on the line, off a gap's, and nowhere off the plot`() {
        val series = series(floatArrayOf(0f, 5f, 10f), 0).let {
            ChartSeries(it.x, floatArrayOf(20f, Float.NaN, 80f), it.segmentStartIndices, it.color)
        }
        val x = scale(0f, 10f)
        val y = scale(0f, 100f)
        assertEquals(Offset(100f, 20f), series.pointAt(2, x, y, plot))
        assertTrue(series.pointAt(1, x, y, plot)!!.y.isNaN())
        assertNull(series.pointAt(null, x, y, plot))
        assertNull(series.pointAt(3, x, y, plot))
        assertNull(series.pointAt(2, scale(0f, 5f), y, plot))
    }

    @Test
    fun `a spoken range skips missing and infinite values`() {
        assertEquals(
            3f..7f,
            series(floatArrayOf(0f, 1f, 2f, 3f), 0).let {
                ChartSeries(
                    it.x,
                    floatArrayOf(Float.NaN, 3f, Float.NEGATIVE_INFINITY, 7f),
                    it.segmentStartIndices,
                    it.color,
                )
            }.yRange(),
        )
        assertNull(ChartSeries(floatArrayOf(0f), floatArrayOf(Float.NaN), intArrayOf(0), Color.Red).yRange())
    }

    /** Compose's Path is an interface; this records the calls the builder makes. */
    private class RecordingPath {
        val calls = mutableListOf<String>()
        val path = java.lang.reflect.Proxy.newProxyInstance(
            Path::class.java.classLoader,
            arrayOf(Path::class.java),
        ) { _, method, args ->
            fun f(i: Int) = (args[i] as Float).let {
                if (it ==
                    it.toInt().toFloat()
                ) {
                    it.toInt().toString()
                } else {
                    it.toString()
                }
            }
            when (method.name) {
                "moveTo" -> calls += "M${f(0)},${f(1)}"
                "lineTo" -> calls += "L${f(0)},${f(1)}"
                "close" -> calls += "Z"
                else -> error("unexpected ${method.name}")
            }
            Unit
        } as Path
    }
}
