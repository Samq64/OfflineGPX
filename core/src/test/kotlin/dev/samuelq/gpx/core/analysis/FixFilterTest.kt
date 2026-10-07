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

    private fun fix(second: Long, metersNorth: Double, accuracy: Double? = 5.0) = TrackPoint(
        latitude = 51.5 + metersNorth / 111_320.0,
        longitude = -0.1,
        time = origin.plusSeconds(second),
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

    /** A phone on a table: every step is smaller than its accuracy, so none is movement. */
    @Test
    fun `jitter inside the error circle never becomes movement`() {
        val filter = FixFilter()
        val wander = listOf(0.0, 2.5, -1.8, 3.1, -2.2, 1.4, -3.0, 2.0)

        val points = wander.mapIndexedNotNull { second, offset ->
            filter.pointFor(fix(second.toLong(), offset, accuracy = 8.0))
        }

        val first = points.first()
        assertTrue(points.all { it.latitude == first.latitude })
    }

    /** Standing still is recorded, not left as a hole to infer a stop from. */
    @Test
    fun `a stop is written down as repeated positions`() {
        val filter = FixFilter(stillIntervalSeconds = 10.0)
        val anchor = assertNotNull(filter.pointFor(fix(0, 0.0, accuracy = 6.0)))

        val still = (1..60L).mapNotNull { second ->
            filter.pointFor(fix(second, (second % 5) - 2.0, accuracy = 6.0))
        }

        assertEquals(6, still.size)
        assertTrue(still.all { it.latitude == anchor.latitude && it.longitude == anchor.longitude })
        assertEquals(origin.plusSeconds(10), still.first().time)
        assertEquals(origin.plusSeconds(60), still.last().time)
    }

    /** Not even a still point: nothing believable arrived. */
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
        // Under the floor that stops a sub-metre fix accumulating a walk.
        assertNull(filter.pointFor(fix(1, 2.0, accuracy = 1.0)))
        assertNotNull(filter.pointFor(fix(2, 9.0, accuracy = 1.0)))
    }

    @Test
    fun `a teleport is rejected`() {
        val filter = FixFilter()
        filter.pointFor(fix(0, 0.0))
        assertNull(filter.pointFor(fix(1, 5_000.0)))
    }

    @Test
    fun `travel too fast to believe is accepted once it keeps up`() {
        val filter = FixFilter()
        filter.pointFor(fix(0, 0.0))
        // 70 m/s: each fix is implausible from the last one kept.
        val kept = (1L..20L).filter { filter.pointFor(fix(it, it * 70.0)) != null }
        assertEquals(listOf(10L, 20L), kept)
    }

    @Test
    fun `a plausible fix ends a run of teleports`() {
        val filter = FixFilter()
        filter.pointFor(fix(0, 0.0))
        for (second in 1L..30L) {
            val far = second % 5 != 0L
            val point = filter.pointFor(fix(second, if (far) 5_000.0 else second * 1.5))
            if (far) assertNull(point)
        }
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

    /** Recorded so the speed chart draws a stop instead of a hole. */
    @Test
    fun `an hour of standing still is 360 points, not 3600 and not one`() {
        val filter = FixFilter()
        val recorded = (0 until 3600).mapNotNull { second ->
            filter.pointFor(fix(second.toLong(), (second % 7) - 3.0, accuracy = 9.0))
        }
        // The anchor, plus one every ten seconds after it.
        assertEquals(360, recorded.size)
    }

    @Test
    fun `a steady walk is recorded at about the right speed`() {
        val filter = FixFilter()
        val speed = SpeedWindow()
        var distance = 0.0
        var last: TrackPoint? = null

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

    @Test
    fun `an untimed stationary reading is not re-stamped`() {
        val filter = FixFilter()
        filter.pointFor(fix(0, 0.0).copy(time = null))

        assertNull(filter.pointFor(fix(0, 1.0).copy(time = null)))
        // Untimed movement can't be judged for speed, so any distance is accepted.
        assertNotNull(filter.pointFor(fix(0, 10_000.0).copy(time = null)))
    }

    @Test
    fun `a still reading after an untimed anchor is recorded at once`() {
        val filter = FixFilter()
        filter.pointFor(fix(0, 0.0).copy(time = null))

        val still = assertNotNull(filter.pointFor(fix(1, 1.0)))
        assertEquals(origin.plusSeconds(1), still.time)
        assertEquals(51.5, still.latitude, 1e-9)
    }

    @Test
    fun `a fix stamped no later than the anchor is judged on distance alone`() {
        val filter = FixFilter()
        filter.pointFor(fix(10, 0.0))

        assertNotNull(filter.pointFor(fix(10, 1000.0)))
        assertNotNull(filter.pointFor(fix(5, 2000.0)))
    }
}
