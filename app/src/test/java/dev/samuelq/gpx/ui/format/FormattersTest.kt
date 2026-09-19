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
        assertEquals("5.0", metric.distanceAxis(5000f, en))
        assertEquals("3.1", imperial.distanceAxis(5000f, en))
        assertEquals("1,000", metric.elevationAxis(1000f, en))
        assertEquals("3,281", imperial.elevationAxis(1000f, en))
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

    /** The axis lambdas are what the charts key their layout on. */
    @Test
    fun `axis lambdas are stable within an instance and differ across them`() {
        assertEquals(metric.SpeedAxis, metric.SpeedAxis)
        assert(metric.SpeedAxis !== imperial.SpeedAxis)
        assertEquals("22", imperial.SpeedAxis(10f))
    }
}
