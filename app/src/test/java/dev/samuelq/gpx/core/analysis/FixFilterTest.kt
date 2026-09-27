package dev.samuelq.gpx.core.analysis

import dev.samuelq.gpx.core.model.TrackPoint
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FixFilterTest {

    private val origin = Instant.parse("2026-09-18T09:00:00Z")

    /** Metres north of the origin, as a fix at [second] with [accuracy] metres of error. */
    private fun fix(second: Long, metersNorth: Double, accuracy: Double? = 5.0) = Fix(
        point = TrackPoint(
            latitude = 51.5 + metersNorth / 111_320.0,
            longitude = -0.1,
            time = origin.plusSeconds(second),
        ),
        accuracyMeters = accuracy,
    )

    @Test
    fun `first believable fix is accepted`() {
        assertNotNull(FixFilter().pointFor(fix(0, 0.0)))
    }

    @Test
    fun `fixes worse than the accuracy limit are never positions`() {
        val filter = FixFilter()
        assertNull(filter.pointFor(fix(0, 0.0, accuracy = 40.0)))
        assertNull(filter.pointFor(fix(1, 100.0, accuracy = 40.0)))
    }

    /**
     * The bug this class exists for: a phone on a table, wandering inside its error
     * circle. Every step is smaller than the accuracy it arrived with, so none of them is
     * evidence of having moved.
     */
    @Test
    fun `jitter inside the error circle never becomes movement`() {
        val filter = FixFilter()
        val wander = listOf(0.0, 2.5, -1.8, 3.1, -2.2, 1.4, -3.0, 2.0)

        val points = wander.mapIndexedNotNull { second, offset ->
            filter.pointFor(fix(second.toLong(), offset, accuracy = 8.0))
        }

        // Whatever came back, none of it moved from where the first fix put us.
        val first = points.first()
        assertTrue(points.all { it.latitude == first.latitude })
    }

    /**
     * The other half of the same rule: standing still is *recorded* as standing still,
     * not left as a hole in the file for something downstream to infer a stop from.
     */
    @Test
    fun `a stop is written down as repeated positions`() {
        val filter = FixFilter(stillIntervalSeconds = 10.0)
        val anchor = assertNotNull(filter.pointFor(fix(0, 0.0, accuracy = 6.0)))

        val still = (1..60L).mapNotNull { second ->
            filter.pointFor(fix(second, (second % 5) - 2.0, accuracy = 6.0))
        }

        // One every ten seconds, and every one of them exactly where the anchor was.
        assertEquals(6, still.size)
        assertTrue(still.all { it.latitude == anchor.latitude && it.longitude == anchor.longitude })
        // Carrying the time they were taken, which is the whole point of keeping them.
        assertEquals(origin.plusSeconds(10), still.first().time)
        assertEquals(origin.plusSeconds(60), still.last().time)
    }

    /** Nothing believable arrived, so nothing is claimed - not even that we stayed put. */
    @Test
    fun `an unusable reading yields no point at all`() {
        val filter = FixFilter(stillIntervalSeconds = 1.0)
        filter.pointFor(fix(0, 0.0, accuracy = 6.0))
        assertNull(filter.pointFor(fix(30, 0.0, accuracy = 80.0)))
    }

    @Test
    fun `real movement past the error circle is accepted`() {
        val filter = FixFilter()
        assertNotNull(filter.pointFor(fix(0, 0.0, accuracy = 8.0)))
        assertNotNull(filter.pointFor(fix(1, 20.0, accuracy = 8.0)))
    }

    @Test
    fun `a confident fix still has to clear the minimum displacement`() {
        val filter = FixFilter()
        filter.pointFor(fix(0, 0.0, accuracy = 1.0))
        // 2m with 1m of accuracy: believable as a position, but under the floor that
        // keeps a stationary sub-metre fix from accumulating a walk.
        assertNull(filter.pointFor(fix(1, 2.0, accuracy = 1.0)))
        assertNotNull(filter.pointFor(fix(2, 9.0, accuracy = 1.0)))
    }

    @Test
    fun `a teleport is rejected`() {
        val filter = FixFilter()
        filter.pointFor(fix(0, 0.0))
        // 5km in one second.
        assertNull(filter.pointFor(fix(1, 5_000.0)))
    }

    @Test
    fun `reset forgets where here was`() {
        val filter = FixFilter()
        filter.pointFor(fix(0, 0.0, accuracy = 8.0))
        assertNull(filter.pointFor(fix(1, 1.0, accuracy = 8.0)))

        filter.reset()
        assertNotNull(filter.pointFor(fix(2, 1.0, accuracy = 8.0)))
    }

    @Test
    fun `a source with no accuracy still gets the displacement floor`() {
        val filter = FixFilter()
        assertNotNull(filter.pointFor(fix(0, 0.0, accuracy = null)))
        assertNull(filter.pointFor(fix(1, 1.0, accuracy = null)))
        assertNotNull(filter.pointFor(fix(2, 10.0, accuracy = null)))
    }

    /** End to end: an hour on a table must not become a ride. */
    @Test
    fun `an hour of standing still accumulates no distance`() {
        val filter = FixFilter()
        val speed = SpeedWindow()
        var distance = 0.0
        var last: TrackPoint? = null

        repeat(3600) { second ->
            val reading = fix(second.toLong(), (second % 7) - 3.0, accuracy = 9.0)
            filter.pointFor(reading)?.let { point ->
                last?.let { distance += haversineMeters(it, point) }
                last = point
            }
            speed.add(second.toDouble(), distance)
        }

        assertEquals(0.0, distance)
        assertEquals(0.0, assertNotNull(speed.speedMps))
    }

    /**
     * And the hour is *in* the file rather than missing from it, which is what lets the
     * speed chart draw a stop instead of a hole. One point per ten seconds, not per fix.
     */
    @Test
    fun `an hour of standing still is 360 points, not 3600 and not one`() {
        val filter = FixFilter()
        val recorded = (0 until 3600).mapNotNull { second ->
            filter.pointFor(fix(second.toLong(), (second % 7) - 3.0, accuracy = 9.0))
        }
        // The anchor, plus one every ten seconds after it.
        assertEquals(360, recorded.size)
    }

    /** And the same hour walked really is a walk. */
    @Test
    fun `a steady walk is recorded at about the right speed`() {
        val filter = FixFilter()
        val speed = SpeedWindow()
        var distance = 0.0
        var last: TrackPoint? = null

        // 1.4 m/s, with a metre of noise on top of every reading.
        repeat(600) { second ->
            val jitter = ((second % 5) - 2) * 0.5
            val reading = fix(second.toLong(), second * 1.4 + jitter, accuracy = 5.0)
            filter.pointFor(reading)?.let { point ->
                last?.let { distance += haversineMeters(it, point) }
                last = point
            }
            speed.add(second.toDouble(), distance)
        }

        val measured = assertNotNull(speed.speedMps)
        assertTrue(measured in 1.2..1.6, "walked at $measured m/s, expected about 1.4")
        assertTrue(distance in 780.0..860.0, "walked $distance m, expected about 838")
    }
}
