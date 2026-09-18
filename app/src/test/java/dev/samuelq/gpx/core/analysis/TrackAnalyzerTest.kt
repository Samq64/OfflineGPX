package dev.samuelq.gpx.core.analysis

import dev.samuelq.gpx.core.model.Track
import dev.samuelq.gpx.core.model.TrackPoint
import dev.samuelq.gpx.core.model.TrackSegment
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackAnalyzerTest {

    /** 2 pi R / 360 for the mean Earth radius the analyzer uses. */
    private val metersPerDegreeLatitude = 111_194.93

    private val start: Instant = Instant.parse("2026-05-01T08:00:00Z")

    /** A straight northward run at a constant [metersPerSecond]. */
    private fun straightRun(
        count: Int,
        metersPerSecond: Double,
        secondsBetween: Long = 1,
        timed: Boolean = true,
        elevationAt: (Int) -> Double? = { null },
    ): Track {
        val step = metersPerSecond * secondsBetween / metersPerDegreeLatitude
        val points = (0 until count).map { i ->
            TrackPoint(
                latitude = i * step,
                longitude = 8.0,
                elevation = elevationAt(i),
                time = if (timed) start.plusSeconds(i * secondsBetween) else null,
            )
        }
        return Track(name = "test", description = null, segments = listOf(TrackSegment(points)))
    }

    @Test
    fun `measures distance along a straight run`() {
        val profile = TrackAnalyzer.analyze(straightRun(count = 61, metersPerSecond = 10.0))

        // 60 hops of 10 m.
        assertEquals(600.0, profile.stats.distanceMeters, 1.0)
        assertEquals(60.0, profile.stats.totalDurationSeconds, 1e-6)
    }

    @Test
    fun `recovers a constant speed through the smoothing window`() {
        val profile = TrackAnalyzer.analyze(straightRun(count = 61, metersPerSecond = 10.0))

        assertTrue(profile.hasTime)
        // Mid-track, where the centred window is fully populated.
        assertEquals(10.0f, profile.speedMps[30], 0.2f)
        assertEquals(10.0, profile.stats.averageSpeedMps, 0.3)
        assertEquals(10.0, profile.stats.maxSpeedMps, 0.3)
    }

    @Test
    fun `smooths away single-sample GPS jitter`() {
        // A 10 m/s run with one point thrown 25 m sideways - the classic urban-canyon
        // glitch. A naive per-sample derivative would report a sprint here.
        val step = 10.0 / metersPerDegreeLatitude
        val points = (0 until 41).map { i ->
            TrackPoint(
                latitude = i * step + if (i == 20) 25.0 / metersPerDegreeLatitude else 0.0,
                longitude = 8.0,
                time = start.plusSeconds(i.toLong()),
            )
        }
        val profile = TrackAnalyzer.analyze(
            Track("jitter", null, listOf(TrackSegment(points)))
        )

        // The unsmoothed derivative at the glitch would be ~35 m/s; the window keeps the
        // reported peak close to the real pace.
        assertTrue(
            profile.stats.maxSpeedMps < 16.0,
            "expected the window to damp the spike, got ${profile.stats.maxSpeedMps}",
        )
    }

    @Test
    fun `does not accumulate distance across a segment gap`() {
        val step = 10.0 / metersPerDegreeLatitude
        val first = (0 until 11).map { TrackPoint(it * step, 8.0, time = start.plusSeconds(it.toLong())) }
        // Resumes a long way away, as it would after losing signal in a tunnel.
        val second = (0 until 11).map {
            TrackPoint(5.0 + it * step, 8.0, time = start.plusSeconds(600 + it.toLong()))
        }
        val profile = TrackAnalyzer.analyze(
            Track("gap", null, listOf(TrackSegment(first), TrackSegment(second)))
        )

        // 100 m + 100 m, and emphatically not the ~550 km straight-line jump between them.
        assertEquals(200.0, profile.stats.distanceMeters, 2.0)
        assertTrue(profile.segmentStartIndices.contentEquals(intArrayOf(0, 11)))
    }

    @Test
    fun `ignores elevation noise below the threshold but counts a real climb`() {
        val profile = TrackAnalyzer.analyze(
            straightRun(count = 60, metersPerSecond = 5.0) { i ->
                // 30 samples of +/-1 m barometric wander, then a genuine 50 m climb.
                if (i < 30) if (i % 2 == 0) 100.0 else 101.0
                else 100.0 + (i - 29) * (50.0 / 30.0)
            }
        )

        assertTrue(profile.hasElevation)
        assertTrue(
            profile.stats.ascentMeters in 44.0..51.0,
            "expected ~50 m of ascent, got ${profile.stats.ascentMeters}",
        )
        assertTrue(
            profile.stats.descentMeters < 1.0,
            "noise should not register as descent, got ${profile.stats.descentMeters}",
        )
    }

    @Test
    fun `excludes stopped time from moving time`() {
        val step = 10.0 / metersPerDegreeLatitude
        val moving = (0 until 31).map { TrackPoint(it * step, 8.0, time = start.plusSeconds(it.toLong())) }
        // Then parked at the same spot for two minutes.
        val parked = (1..120).map {
            TrackPoint(30 * step, 8.0, time = start.plusSeconds(30 + it.toLong()))
        }
        val profile = TrackAnalyzer.analyze(
            Track("stop", null, listOf(TrackSegment(moving + parked)))
        )

        assertEquals(150.0, profile.stats.totalDurationSeconds, 1e-6)
        assertTrue(
            profile.stats.movingDurationSeconds < 45.0,
            "the two-minute stop should be excluded, got ${profile.stats.movingDurationSeconds}",
        )
        // Average speed uses moving time, so a stop must not drag it toward zero.
        assertTrue(profile.stats.averageSpeedMps > 7.0)
    }

    @Test
    fun `degrades cleanly when a route has no timestamps`() {
        val profile = TrackAnalyzer.analyze(
            straightRun(count = 20, metersPerSecond = 10.0, timed = false) { 400.0 }
        )

        assertFalse(profile.hasTime)
        assertTrue(profile.hasElevation)
        assertTrue(profile.speedMps.all(Float::isNaN))
        assertEquals(0.0, profile.stats.totalDurationSeconds, 1e-9)
        // Distance is still measurable without a clock.
        assertTrue(profile.stats.distanceMeters > 100.0)
    }

    @Test
    fun `marks missing elevation samples rather than guessing them`() {
        val profile = TrackAnalyzer.analyze(
            straightRun(count = 10, metersPerSecond = 5.0) { i -> if (i == 5) null else 400.0 }
        )

        assertTrue(profile.elevationMeters[5].isNaN())
        assertFalse(profile.elevationMeters[4].isNaN())
    }
}
