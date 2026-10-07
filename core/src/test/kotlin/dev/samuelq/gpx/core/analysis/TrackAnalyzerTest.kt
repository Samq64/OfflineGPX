package dev.samuelq.gpx.core.analysis

import dev.samuelq.gpx.core.model.Track
import dev.samuelq.gpx.core.model.TrackPoint
import dev.samuelq.gpx.core.model.TrackPoints
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackAnalyzerTest {

    /** 2 pi R / 360 for the mean Earth radius the analyzer uses. */
    private val metersPerDegreeLatitude = 111_194.93

    private val start: Instant = Instant.parse("2026-05-01T08:00:00Z")

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
        return Track(name = "test", segments = listOf(segment(points)))
    }

    @Test
    fun `measures distance along a straight run`() {
        val profile = TrackAnalyzer.analyze(straightRun(count = 61, metersPerSecond = 10.0))

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
        assertEquals(10.0, profile.maxSpeed(), 0.3)
    }

    @Test
    fun `a track never moving has no moving speed`() {
        // 0.15 m/s, about 0.3 mph: under the moving threshold throughout.
        val profile = TrackAnalyzer.analyze(straightRun(count = 61, metersPerSecond = 0.15, secondsBetween = 10))

        assertTrue(profile.stats.distanceMeters > 0.0)
        assertEquals(0.0, profile.stats.movingDurationSeconds)
        assertEquals(0.0, profile.stats.averageSpeedMps)
    }

    @Test
    fun `smooths away single-sample GPS jitter`() {
        // One point thrown 25 m off, the classic urban-canyon glitch.
        val step = 10.0 / metersPerDegreeLatitude
        val points = (0 until 41).map { i ->
            TrackPoint(
                latitude = i * step + if (i == 20) 25.0 / metersPerDegreeLatitude else 0.0,
                longitude = 8.0,
                time = start.plusSeconds(i.toLong()),
            )
        }
        val profile = TrackAnalyzer.analyze(
            Track("jitter", listOf(segment(points))),
        )

        // Unsmoothed, the glitch would read ~35 m/s.
        assertTrue(
            profile.maxSpeed() < 16.0,
            "expected the window to damp the spike, got ${profile.maxSpeed()}",
        )
    }

    @Test
    fun `does not accumulate distance across a segment gap`() {
        val step = 10.0 / metersPerDegreeLatitude
        val first = (0 until 11).map { TrackPoint(it * step, 8.0, time = start.plusSeconds(it.toLong())) }
        val second = (0 until 11).map {
            TrackPoint(5.0 + it * step, 8.0, time = start.plusSeconds(600 + it.toLong()))
        }
        val profile = TrackAnalyzer.analyze(
            Track("gap", listOf(segment(first), segment(second))),
        )

        // 100 m + 100 m, not the ~550 km jump between them.
        assertEquals(200.0, profile.stats.distanceMeters, 2.0)
        assertTrue(profile.points.segmentStarts().contentEquals(intArrayOf(0, 11)))
    }

    @Test
    fun `ignores elevation noise below the threshold but counts a real climb`() {
        val profile = TrackAnalyzer.analyze(
            straightRun(count = 60, metersPerSecond = 5.0) { i ->
                // 30 samples of +/-1 m barometric wander, then a genuine 50 m climb.
                if (i < 30) if (i % 2 == 0) 100.0 else 101.0
                else {
                    100.0 + (i - 29) * (50.0 / 30.0)
                }
            },
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
        val parked = (1..120).map {
            TrackPoint(30 * step, 8.0, time = start.plusSeconds(30 + it.toLong()))
        }
        val profile = TrackAnalyzer.analyze(
            Track("stop", listOf(segment(moving + parked))),
        )

        assertEquals(150.0, profile.stats.totalDurationSeconds, 1e-6)
        assertTrue(
            profile.stats.movingDurationSeconds < 45.0,
            "the two-minute stop should be excluded, got ${profile.stats.movingDurationSeconds}",
        )
        assertTrue(profile.stats.averageSpeedMps > 7.0)
    }

    @Test
    fun `degrades cleanly when a route has no timestamps`() {
        val profile = TrackAnalyzer.analyze(
            straightRun(count = 20, metersPerSecond = 10.0, timed = false) { 400.0 },
        )

        assertFalse(profile.hasTime)
        assertTrue(profile.hasElevation)
        assertTrue(profile.speedMps.all(Float::isNaN))
        assertEquals(0.0, profile.stats.totalDurationSeconds, 1e-9)
        assertTrue(profile.stats.distanceMeters > 100.0)
    }

    @Test
    fun `marks missing elevation samples rather than guessing them`() {
        val profile = TrackAnalyzer.analyze(
            straightRun(count = 10, metersPerSecond = 5.0) { i -> if (i == 5) null else 400.0 },
        )

        assertTrue(profile.elevationMeters[5].isNaN())
        assertFalse(profile.elevationMeters[4].isNaN())
    }

    /** A ride with a break recorded only as a long interval, not a `<trkseg>` boundary. */
    private fun rideWithGap(gapSeconds: Long): Track {
        val step = 5.0 / metersPerDegreeLatitude
        val points = buildList {
            repeat(60) { i ->
                add(TrackPoint(latitude = i * step, longitude = 8.0, time = start.plusSeconds(i.toLong())))
            }
            repeat(60) { i ->
                add(
                    TrackPoint(
                        latitude = (60 + i) * step,
                        longitude = 8.0,
                        time = start.plusSeconds(59 + gapSeconds + i),
                    ),
                )
            }
        }
        return Track(name = "gap", segments = listOf(segment(points)))
    }

    @Test
    fun `a long gap becomes a break in the track`() {
        val profile = TrackAnalyzer.analyze(rideWithGap(gapSeconds = 600))

        assertEquals(listOf(0, 60), profile.points.segmentStarts().toList())
    }

    @Test
    fun `a partially timed track is not split by a gap it otherwise ignores`() {
        val track = rideWithGap(gapSeconds = 600)
        val points = track.points.indices.map(track.points::get).toMutableList()
        // One missing timestamp makes the whole track untimed, gap detection included.
        points[0] = points[0].copy(time = null)
        val profile = TrackAnalyzer.analyze(
            Track(name = "gap", segments = listOf(segment(points))),
        )

        assertFalse(profile.hasTime)
        assertEquals(listOf(0), profile.points.segmentStarts().toList())
    }

    @Test
    fun `a break is not counted as moving time`() {
        val profile = TrackAnalyzer.analyze(rideWithGap(gapSeconds = 600))

        assertTrue(
            profile.stats.movingDurationSeconds < 130.0,
            "moving time was ${profile.stats.movingDurationSeconds}, expected about 118",
        )
        // Total duration is wall clock.
        assertTrue(profile.stats.totalDurationSeconds > 700.0)
    }

    @Test
    fun `speed is not carried across a break`() {
        val profile = TrackAnalyzer.analyze(rideWithGap(gapSeconds = 600))

        // Windows either side of the gap are one-sided, never measured across it.
        val lastBefore = profile.speedMps[59]
        val firstAfter = profile.speedMps[60]
        assertTrue(lastBefore.isFinite() && firstAfter.isFinite())

        val jump = profile.distanceMeters[60] - profile.distanceMeters[59]
        assertEquals(0f, jump, 0.001f)
    }

    @Test
    fun `an ordinary sample interval is not a break`() {
        val profile = TrackAnalyzer.analyze(straightRun(count = 120, metersPerSecond = 5.0))
        assertEquals(listOf(0), profile.points.segmentStarts().toList())
    }

    /** A gap is "unusual for this file", not "longer than half a minute". */
    @Test
    fun `a sparsely sampled file is left alone`() {
        val profile = TrackAnalyzer.analyze(
            straightRun(count = 30, metersPerSecond = 8.0, secondsBetween = 60),
        )
        assertEquals(listOf(0), profile.points.segmentStarts().toList())
    }

    @Test
    fun `the gap threshold decides what counts as a stop`() {
        val track = rideWithGap(gapSeconds = 45)

        // 45s clears the default 30s floor and ten times the 1s median, so it is a break.
        assertEquals(listOf(0, 60), TrackAnalyzer.analyze(track).points.segmentStarts().toList())

        assertEquals(
            listOf(0),
            TrackAnalyzer.analyze(track, minGapSeconds = 120.0).points.segmentStarts().toList(),
        )
    }

    /** The median rule still applies underneath: it is a floor, not an override. */
    @Test
    fun `lowering the threshold cannot cut a sparse file into confetti`() {
        val sparse = straightRun(count = 30, metersPerSecond = 8.0, secondsBetween = 60)
        val profile = TrackAnalyzer.analyze(sparse, minGapSeconds = 10.0)
        assertEquals(listOf(0), profile.points.segmentStarts().toList())
    }

    /**
     * Reference: the widest centred window within the segment spanning at most half the
     * window either side, widened to the neighbouring sample when that leaves none.
     */
    @Test
    fun `the carried speed window agrees with a search at every sample`() {
        val track = awkwardlyTimedRide()
        val profile = TrackAnalyzer.analyze(track)
        val half = (TrackAnalyzer.SPEED_WINDOW_SECONDS / 2.0).toFloat()
        val starts = profile.points.segmentStarts()

        assertTrue(starts.size > 1, "the fixture is meant to contain a break")

        for (segment in starts.indices) {
            val start = starts[segment]
            val end = if (segment + 1 < starts.size) starts[segment + 1] else profile.speedMps.size
            for (i in start until end) {
                var lo = i
                while (lo > start && profile.elapsedSeconds[i] - profile.elapsedSeconds[lo - 1] <= half) lo--
                var hi = i
                while (hi < end - 1 && profile.elapsedSeconds[hi + 1] - profile.elapsedSeconds[i] <= half) hi++
                if (lo == i && i > start) lo = i - 1
                if (hi == i && i < end - 1) hi = i + 1

                val dt = profile.elapsedSeconds[hi] - profile.elapsedSeconds[lo]
                val expected = if (dt > 0f) {
                    maxOf(0f, (profile.distanceMeters[hi] - profile.distanceMeters[lo]) / dt)
                } else {
                    0f
                }
                assertEquals(expected, profile.speedMps[i], 1e-4f, "sample $i")
            }
        }
    }

    /**
     * Some exporters stamp thousands of points with one time, and the monotonic clamp does
     * the same to out-of-order stamps. The bound is loose: it tests the algorithm, not the machine.
     */
    @Test
    fun `a stalled clock does not make analysis quadratic`() {
        val step = 1.0 / metersPerDegreeLatitude
        val points = (0 until 30_000).map { i ->
            TrackPoint(latitude = i * step, longitude = 8.0, elevation = null, time = start)
        }
        val track = Track(name = null, segments = listOf(segment(points)))

        val elapsed = kotlin.system.measureTimeMillis {
            val profile = TrackAnalyzer.analyze(track)
            assertEquals(0f, profile.speedMps[15_000])
        }
        assertTrue(elapsed < 5_000, "analysis took ${elapsed}ms")
    }

    @Test
    fun `a file sparser than the speed window still has a speed`() {
        // No sample has a neighbour within the 10 s window.
        val profile = TrackAnalyzer.analyze(
            straightRun(count = 30, metersPerSecond = 8.0, secondsBetween = 20),
        )

        assertEquals(8.0f, profile.speedMps[15], 0.1f)
        assertEquals(580.0, profile.stats.movingDurationSeconds, 1.0)
        assertEquals(8.0, profile.stats.averageSpeedMps, 0.1)
    }

    /** 16 m hills every 300 m at 5 m/s, +/-1.5 m jitter; [secondsBetween] thins it. */
    private fun hillyRun(meters: Double, secondsBetween: Long = 1, reverse: Boolean = false, seed: Int = 1): Track {
        val random = kotlin.random.Random(seed)
        val count = (meters / (5.0 * secondsBetween)).toInt() + 1
        val step = 5.0 * secondsBetween
        val points = (0 until count).map { i ->
            val along = if (reverse) meters - i * step else i * step
            TrackPoint(
                latitude = along / metersPerDegreeLatitude,
                longitude = 8.0,
                elevation = 100.0 + 8.0 * kotlin.math.sin(2 * Math.PI * along / 300.0) +
                    random.nextDouble(-1.5, 1.5),
                time = start.plusSeconds(i * secondsBetween),
            )
        }
        return Track(name = "hills", segments = listOf(segment(points)))
    }

    @Test
    fun `ascent does not depend on how densely a track was sampled`() {
        // Ten 16 m hills: 160 m, less what the 3 m threshold drops at each crest and trough.
        val dense = TrackAnalyzer.analyze(hillyRun(3000.0)).stats.ascentMeters
        val sparse = TrackAnalyzer.analyze(hillyRun(3000.0, secondsBetween = 10)).stats.ascentMeters

        assertTrue(dense in 110.0..170.0, "dense ascent $dense")
        assertTrue(sparse in 110.0..170.0, "sparse ascent $sparse")
        // Not equal: 50 m apart, sparse points have no neighbours to average jitter with.
        assertEquals(dense, sparse, dense * 0.2)
    }

    @Test
    fun `an out-and-back climbs what it descends`() {
        val out = TrackAnalyzer.analyze(hillyRun(3000.0, seed = 1)).stats
        val back = TrackAnalyzer.analyze(hillyRun(3000.0, reverse = true, seed = 2)).stats

        assertEquals(out.ascentMeters, back.descentMeters, out.ascentMeters * 0.1)
        assertEquals(out.descentMeters, back.ascentMeters, out.descentMeters * 0.1)
    }

    @Test
    fun `height gained across a break is still climbed`() {
        val step = 5.0 / metersPerDegreeLatitude
        val first = (0 until 20).map {
            TrackPoint(it * step, 8.0, elevation = 100.0, time = start.plusSeconds(it.toLong()))
        }
        // Each side is flat; the 4 m came during the gap, and neither side alone clears 3 m.
        val second = (20 until 40).map {
            TrackPoint(it * step, 8.0, elevation = 104.0, time = start.plusSeconds(600 + it.toLong()))
        }
        val profile = TrackAnalyzer.analyze(Track("break", listOf(segment(first), segment(second))))

        assertEquals(4.0, profile.stats.ascentMeters, 0.01)
        assertEquals(0.0, profile.stats.descentMeters, 0.01)
    }

    /** Uneven intervals, a stalled stretch and a real break, in one file. */
    private fun awkwardlyTimedRide(): Track {
        val step = 10.0 / metersPerDegreeLatitude
        var seconds = 0L
        val points = (0 until 120).map { i ->
            seconds += when {
                i in 30..39 -> 0L
                i == 80 -> 600L
                // Wider than the window, too short for a break.
                i % 17 == 0 -> 20L
                i % 7 == 0 -> 3L
                else -> 1L
            }
            TrackPoint(
                latitude = i * step,
                longitude = 8.0,
                elevation = null,
                time = start.plusSeconds(seconds),
            )
        }
        return Track(name = null, segments = listOf(segment(points)))
    }

    @Test
    fun `an empty track has zero stats`() {
        val profile = TrackAnalyzer.analyze(TrackPoints.EMPTY)

        assertFalse(profile.hasTime)
        assertFalse(profile.hasElevation)
        assertEquals(null, profile.stats.startedAt)
        assertEquals(0, profile.stats.pointCount)
        assertEquals(0.0, profile.stats.distanceMeters)
        assertEquals(0.0, profile.stats.averageSpeedMps)
        assertEquals(-1, profile.indexOf(TrackPoint(0.0, 0.0)))
    }

    @Test
    fun `two timed points are too few to look for gaps`() {
        val points = TrackPoints.of(
            listOf(TrackPoint(0.0, 8.0, time = start), TrackPoint(0.001, 8.0, time = start.plusSeconds(3600))),
        )
        val profile = TrackAnalyzer.analyze(points)

        assertTrue(profile.hasTime)
        assertEquals(1, profile.points.segmentStarts().size)
        assertEquals(start, profile.stats.startedAt)
        // Too slow to count as moving, so no moving speed either: the sheet shows both.
        assertTrue(profile.stats.distanceMeters > 0.0)
        assertEquals(0.0, profile.stats.movingDurationSeconds)
        assertEquals(0.0, profile.stats.averageSpeedMps)
    }

    @Test
    fun `a segment without elevation stays unsmoothed`() {
        val points = TrackPoints.of(
            listOf(TrackPoint(0.0, 8.0, elevation = 100.0), TrackPoint(0.001, 8.0, elevation = 110.0)),
            listOf(TrackPoint(0.002, 8.0), TrackPoint(0.003, 8.0)),
        )
        val profile = TrackAnalyzer.analyze(points)

        assertTrue(profile.hasElevation)
        assertTrue(profile.elevationMeters[2].isNaN())
        assertEquals(10.0, profile.stats.ascentMeters, 1e-3)
    }

    @Test
    fun `indexOf matches by time when timed, else by position`() {
        val timed = TrackAnalyzer.analyze(straightRun(count = 10, metersPerSecond = 10.0))
        // A round trip passes the same spot twice; the time picks the leg.
        val probe = TrackPoint(latitude = 0.0, longitude = 8.0, time = start.plusSeconds(7))
        assertEquals(7, timed.indexOf(probe))
        assertEquals(0, timed.indexOf(probe.copy(time = null)))

        val untimed = TrackAnalyzer.analyze(straightRun(count = 10, metersPerSecond = 10.0, timed = false))
        assertEquals(4, untimed.indexOf(TrackPoint(4 * 10.0 / metersPerDegreeLatitude, 8.0, time = start)))
    }

    @Test
    fun `distanceTo is along the track to the nearest point`() {
        val profile = TrackAnalyzer.analyze(straightRun(count = 10, metersPerSecond = 10.0))
        val probe = TrackPoint(latitude = 0.0, longitude = 8.0, time = start.plusSeconds(7))
        assertEquals(profile.distanceMeters[7].toDouble(), profile.distanceTo(probe))
        assertEquals(70.0, profile.distanceTo(probe)!!, 1.0)
        assertNull(TrackAnalyzer.analyze(TrackPoints.EMPTY).distanceTo(probe))
    }
}

private fun TrackProfile.maxSpeed(): Double = speedMps.filterNot(Float::isNaN).max().toDouble()

/** Fixtures read as segments; a segment is just its points. */
private fun segment(points: List<TrackPoint>) = points

private fun Track(name: String?, segments: List<List<TrackPoint>>): Track =
    Track(name, TrackPoints.of(*segments.toTypedArray()))
