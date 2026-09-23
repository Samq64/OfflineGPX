package dev.samuelq.gpx.ui.format

import dev.samuelq.gpx.core.model.UnitSystem
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class FormattersTest {

    private val metric = Formatters(UnitSystem.METRIC)
    private val imperial = Formatters(UnitSystem.IMPERIAL)
    private val en = Locale.UK

    @Test
    fun `metric distance uses metres below a kilometre`() {
        assertEquals("850 m", metric.distance(850.0, en))
        assertEquals("1.20 km", metric.distance(1200.0, en))
        assertEquals("42.2 km", metric.distance(42_195.0, en))
    }

    /** The small unit runs to a tenth of a mile: 0.9 miles in feet is not a distance. */
    @Test
    fun `imperial distance uses feet below a tenth of a mile`() {
        assertEquals("328 ft", imperial.distance(100.0, en))
        assertEquals("0.75 mi", imperial.distance(1207.0, en))
        assertEquals("26.2 mi", imperial.distance(42_195.0, en))
    }

    @Test
    fun `speed converts and labels itself`() {
        assertEquals("36.0 km/h", metric.speed(10.0, en))
        assertEquals("22.4 mph", imperial.speed(10.0, en))
        assertEquals("km/h", metric.speedUnit)
        assertEquals("mph", imperial.speedUnit)
    }

    @Test
    fun `elevation converts to feet`() {
        assertEquals("1,000 m", metric.elevation(1000.0, en))
        assertEquals("3,281 ft", imperial.elevation(1000.0, en))
    }

    /** Axis ticks are bare numbers - the section heading carries the unit, once. */
    @Test
    fun `axis forms carry no unit`() {
        assertEquals("5.0", metric.distanceAxis(5000f, 1, en))
        assertEquals("3.1", imperial.distanceAxis(5000f, 1, en))
        assertEquals("1,000", metric.elevationAxis(1000f, 0, en))
        assertEquals("3,281", imperial.elevationAxis(1000f, 0, en))
    }

    /**
     * Whole metres are right for a mountain and wrong for a towpath: at half-metre ticks
     * the integer form labels 9.5 and 10.0 both "10".
     */
    @Test
    fun `elevation ticks resolve the gap between them`() {
        val flat = metric.elevationAxisFor(0.5f, en)
        assertEquals(listOf("9.0", "9.5", "10.0"), listOf(9f, 9.5f, 10f).map(flat))

        val hilly = metric.elevationAxisFor(100f, en)
        assertEquals(listOf("0", "100", "1,000"), listOf(0f, 100f, 1000f).map(hilly))
    }

    /**
     * The whole point of taking the step: at one decimal a 50 m step labels two ticks
     * "0.1", which is a chart quietly lying about where its own gridlines are.
     */
    @Test
    fun `distance ticks resolve the gap between them`() {
        val short = metric.distanceAxisFor(50f, en)
        assertEquals(listOf("0.00", "0.05", "0.10", "0.15"), listOf(0f, 50f, 100f, 150f).map(short))

        val long = metric.distanceAxisFor(5000f, en)
        assertEquals(listOf("0", "5", "10"), listOf(0f, 5000f, 10000f).map(long))

        // Miles are the smaller unit, so the same ground needs one more decimal to split.
        val miles = imperial.distanceAxisFor(50f, en)
        assertEquals(listOf("0.00", "0.03", "0.06"), listOf(0f, 50f, 100f).map(miles))
    }

    @Test
    fun `duration is the same in both systems`() {
        assertEquals("1:04:12", Formatters.duration(3852.0))
        assertEquals("4:12", Formatters.duration(252.0))
    }

    @Test
    fun `nothing measured reads as nothing measured`() {
        assertEquals(Formatters.EMPTY, metric.distance(Double.NaN, en))
        assertEquals(Formatters.EMPTY, imperial.speed(Double.NaN, en))
        assertEquals(Formatters.EMPTY, imperial.elevation(Double.NaN, en))
        assertEquals(Formatters.EMPTY, Formatters.duration(Double.NaN))
    }

    @Test
    fun `speed ticks resolve the gap between them`() {
        val kmh = metric.speedAxisFor(5f / 3.6f, en)
        assertEquals(listOf("0", "5", "10", "15"), listOf(0f, 5f, 10f, 15f).map { kmh(it / 3.6f) })

        val slow = metric.speedAxisFor(0.5f / 3.6f, en)
        assertEquals("1.5", slow(1.5f / 3.6f))
    }

    /** One format per axis: `33:20` beside `1:06` reads as minutes, then hours. */
    @Test
    fun `duration ticks share one format`() {
        val short = Formatters.durationAxisFor(1800f)
        assertEquals(listOf("0:00", "10:00", "20:00"), listOf(0f, 600f, 1200f).map(short))

        val long = Formatters.durationAxisFor(4000f)
        assertEquals(listOf("0:00", "0:30", "1:00"), listOf(0f, 1800f, 3600f).map(long))
    }
}
