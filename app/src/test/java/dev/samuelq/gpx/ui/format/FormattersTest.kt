package dev.samuelq.gpx.ui.format

import dev.samuelq.gpx.core.model.UnitSystem
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FormattersTest {

    private val metric = Formatters(UnitSystem.METRIC)
    private val imperial = Formatters(UnitSystem.IMPERIAL)
    private val en = Locale.UK

    @Test
    fun `a chart position is a duration on a time axis and a distance otherwise`() {
        assertEquals(Formatters.duration(90.0), metric.position(timed = true)(90f))
        assertEquals(metric.distance(1500.0), metric.position(timed = false)(1500f))
    }

    @Test
    fun `metric distance uses metres below a kilometre`() {
        assertEquals("850 m", metric.distance(850.0, en))
        assertEquals("1.20 km", metric.distance(1200.0, en))
        assertEquals("42.2 km", metric.distance(42_195.0, en))
    }

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
    }

    @Test
    fun `elevation converts to feet`() {
        assertEquals("1,000 m", metric.meters(1000.0, en))
        assertEquals("3,281 ft", imperial.meters(1000.0, en))
    }

    @Test
    fun `axis forms carry their unit`() {
        assertEquals("5.0 km", metric.distanceAxisFor(100f, en)(5000f))
        assertEquals("3.1 mi", imperial.distanceAxisFor(160.9344f, en)(5000f))
        assertEquals("36 km/h", metric.speedAxisFor(2.5f, en)(10f))
        assertEquals("1,000 m", metric.elevationAxisFor(10f, en)(1000f))
        assertEquals("3,281 ft", imperial.elevationAxisFor(10f, en)(1000f))
    }

    /** At half-metre ticks, whole metres would label 9.5 and 10.0 both "10". */
    @Test
    fun `elevation ticks resolve the gap between them`() {
        val flat = metric.elevationAxisFor(0.5f, en)
        assertEquals(listOf("9.0 m", "9.5 m", "10.0 m"), listOf(9f, 9.5f, 10f).map(flat))

        val hilly = metric.elevationAxisFor(100f, en)
        assertEquals(listOf("0 m", "100 m", "1,000 m"), listOf(0f, 100f, 1000f).map(hilly))
    }

    /** At one decimal, a 50 m step would label two ticks "0.1". */
    @Test
    fun `distance ticks resolve the gap between them`() {
        val short = metric.distanceAxisFor(50f, en)
        assertEquals(listOf("0.00 km", "0.05 km", "0.10 km", "0.15 km"), listOf(0f, 50f, 100f, 150f).map(short))

        val long = metric.distanceAxisFor(5000f, en)
        assertEquals(listOf("0 km", "5 km", "10 km"), listOf(0f, 5000f, 10000f).map(long))

        val miles = imperial.distanceAxisFor(50f, en)
        assertEquals(listOf("0.00 mi", "0.03 mi", "0.06 mi"), listOf(0f, 50f, 100f).map(miles))
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
        assertEquals(Formatters.EMPTY, imperial.meters(Double.NaN, en))
        assertEquals(Formatters.EMPTY, Formatters.duration(Double.NaN))
    }

    @Test
    fun `speed ticks resolve the gap between them`() {
        val kmh = metric.speedAxisFor(5f / 3.6f, en)
        assertEquals(listOf("0 km/h", "5 km/h", "10 km/h", "15 km/h"), listOf(0f, 5f, 10f, 15f).map { kmh(it / 3.6f) })

        val slow = metric.speedAxisFor(0.5f / 3.6f, en)
        assertEquals("1.5 km/h", slow(1.5f / 3.6f))
    }

    /** One format per axis: `33:20` beside `1:06` reads as minutes, then hours. */
    @Test
    fun `duration ticks share one format`() {
        val short = Formatters.durationAxisFor(1800f)
        assertEquals(listOf("0:00", "10:00", "20:00"), listOf(0f, 600f, 1200f).map(short))

        val long = Formatters.durationAxisFor(4000f)
        assertEquals(listOf("0:00", "0:30", "1:00"), listOf(0f, 1800f, 3600f).map(long))
    }

    @Test
    fun `dates use the given pattern in the given zone`() {
        val formatters = Formatters(
            UnitSystem.METRIC,
            en,
            dateTimePattern = "d MMM yyyy HH:mm",
            longDateTimePattern = "d MMMM yyyy HH:mm",
            timePattern = "HH:mm",
        )
        val at = Instant.parse("2026-05-01T23:30:00Z")
        val zone = ZoneId.of("Europe/Berlin")

        assertEquals("2 May 2026 01:30", formatters.dateTime(at, zone))
        assertEquals("2 May 2026 01:30", formatters.longDateTime(at, zone))
        assertEquals("01:30", formatters.time(at, zone))
        // The date alone follows the locale, still in the given zone.
        assertEquals("2 May 2026", formatters.longDate(at, zone))
        val september = Instant.parse("2026-09-03T08:00:00Z")
        assertEquals("3 September 2026 08:00", formatters.longDateTime(september, ZoneOffset.UTC))
        assertEquals("3 September 2026", formatters.longDate(september, ZoneOffset.UTC))
        assertEquals(Formatters.EMPTY, formatters.dateTime(null))
        assertEquals(Formatters.EMPTY, formatters.longDateTime(null))
        assertEquals(Formatters.EMPTY, formatters.longDate(null))
        assertEquals(Formatters.EMPTY, formatters.time(null))
    }

    @Test
    fun `a pattern java_time can't read falls back to the locale's style`() {
        val formatters = Formatters(UnitSystem.METRIC, en, dateTimePattern = "{bad", timePattern = "{bad")
        val at = Instant.parse("2026-05-01T08:05:00Z")

        assertEquals("08:05", formatters.time(at, ZoneOffset.UTC))
        assertTrue("2026" in formatters.dateTime(at, ZoneOffset.UTC))
    }

    @Test
    fun `dates default to the system zone`() {
        val formatters = Formatters(UnitSystem.METRIC, en, longDateTimePattern = "d MMMM yyyy HH:mm")
        val at = Instant.parse("2026-05-01T23:30:00Z")

        assertEquals(formatters.longDateTime(at, ZoneId.systemDefault()), formatters.longDateTime(at))
    }

    @Test
    fun `display units per SI unit`() {
        assertEquals(UnitSystem.IMPERIAL, imperial.units)
        assertEquals(3.6f, metric.speedPerMps, 1e-6f)
        assertEquals(2.2369363f, imperial.speedPerMps, 1e-6f)
        assertEquals(1f, metric.elevationPerMeter)
        assertEquals(3.2808399f, imperial.elevationPerMeter, 1e-6f)
        assertEquals(0.001f, metric.distancePerMeter)
        assertEquals(1f / 1609.344f, imperial.distancePerMeter, 1e-9f)
    }

    @Test
    fun `sizes round up to kilobytes and counts group digits`() {
        assertEquals("0 kB", Formatters.kilobytes(0, en))
        assertEquals("1 kB", Formatters.kilobytes(1, en))
        assertEquals("1,235 kB", Formatters.kilobytes(1_234_567, en))
        assertEquals("12,345", Formatters.count(12_345, en))
    }

    @Test
    fun `negative or missing durations read as nothing`() {
        assertEquals(Formatters.EMPTY, Formatters.duration(-1.0))
        assertEquals(Formatters.EMPTY, Formatters.durationAxis(Float.NaN))
        assertEquals(Formatters.EMPTY, Formatters.durationAxis(-1f))
        assertEquals("1:05", Formatters.durationAxis(3900f))
        assertEquals("0:59", Formatters.durationAxis(59f))
    }

    @Test
    fun `a zero step gets one decimal`() {
        assertEquals("5.0 km", metric.distanceAxisFor(0f, en)(5000f))
        assertEquals("36.0 km/h", metric.speedAxisFor(Float.NaN, en)(10f))
        assertEquals("5.0 km", metric.distanceAxisFor(Float.POSITIVE_INFINITY, en)(5000f))
    }

    @Test
    fun `without a locale, numbers follow the default one`() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            assertEquals("1,20 km", Formatters.Metric.distance(1200.0))
            assertEquals("36,0 km/h", metric.speed(10.0))
            assertEquals("1.000 m", metric.meters(1000.0))
            assertEquals("5,0 km", metric.distanceAxisFor(100f)(5000f))
            assertEquals("36 km/h", metric.speedAxisFor(2.5f)(10f))
            assertEquals("1.000 m", metric.elevationAxisFor(10f)(1000f))
            assertEquals("1.235 kB", Formatters.kilobytes(1_234_567))
            assertEquals("12.345", Formatters.count(12_345))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `tabular figures turn on the tnum feature`() {
        assertEquals("tnum", androidx.compose.ui.text.TextStyle().tabularFigures().fontFeatureSettings)
    }
}
