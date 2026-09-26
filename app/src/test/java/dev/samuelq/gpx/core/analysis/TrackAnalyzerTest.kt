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
        return Track(name = "test", segments = listOf(TrackSegment(points)))
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
            Track("jitter", listOf(TrackSegment(points)))
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
            Track("gap", listOf(TrackSegment(first), TrackSegment(second)))
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
            Track("stop", listOf(TrackSegment(moving + parked)))
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

    /**
     * A ride, a dismounted break the file records only as a long interval, then a ride.
     *
     * The break is not a `<trkseg>` boundary - almost none of them are - so without gap
     * detection the chart draws a straight line from the speed going in to the speed
     * coming out, and the whole stop reads as riding pace.
     */
    private fun rideWithGap(gapSeconds: Long): Track {
        val step = 5.0 / metersPerDegreeLatitude
        val points = buildList {
            repeat(60) { i ->
                add(TrackPoint(latitude = i * step, longitude = 8.0, time = start.plusSeconds(i.toLong())))
            }
            // Resumes a few metres further on, after a long silence.
            repeat(60) { i ->
                add(
                    TrackPoint(
                        latitude = (60 + i) * step,
                        longitude = 8.0,
                        time = start.plusSeconds(59 + gapSeconds + i),
                    )
                )
            }
        }
        return Track(name = "gap", segments = listOf(TrackSegment(points)))
    }

    @Test
    fun `a long gap becomes a break in the track`() {
        val profile = TrackAnalyzer.analyze(rideWithGap(gapSeconds = 600))

        // The file declared one segment; the analyzer finds two.
        assertEquals(listOf(0, 60), profile.segmentStartIndices.toList())
    }

    @Test
    fun `a partially timed track is not split by a gap it otherwise ignores`() {
        val track = rideWithGap(gapSeconds = 600)
        val points = track.segments[0].points.toMutableList()
        // One missing timestamp is enough to make the whole track untimed - and the gap
        // used to be found anyway, because gap detection ran on the raw timestamps before
        // that all-or-nothing rule was applied to anything else.
        points[0] = points[0].copy(time = null)
        val profile = TrackAnalyzer.analyze(
            Track(name = "gap", segments = listOf(TrackSegment(points)))
        )

        assertFalse(profile.hasTime)
        assertEquals(listOf(0), profile.segmentStartIndices.toList())
    }

    @Test
    fun `a break is not counted as moving time`() {
        val profile = TrackAnalyzer.analyze(rideWithGap(gapSeconds = 600))

        // 118 seconds of riding either side, and none of the 600 in between.
        assertTrue(
            profile.stats.movingDurationSeconds < 130.0,
            "moving time was ${profile.stats.movingDurationSeconds}, expected about 118",
        )
        // Total duration is wall clock and still includes it.
        assertTrue(profile.stats.totalDurationSeconds > 700.0)
    }

    @Test
    fun `speed is not carried across a break`() {
        val profile = TrackAnalyzer.analyze(rideWithGap(gapSeconds = 600))

        // The last sample before the gap has nothing after it to difference against, so
        // its window is one-sided - but it must not be measured against the far side.
        val lastBefore = profile.speedMps[59]
        val firstAfter = profile.speedMps[60]
        assertTrue(lastBefore.isFinite() && firstAfter.isFinite())

        // Distance does not accumulate across the break: what happened in it is unknown.
        val jump = profile.distanceMeters[60] - profile.distanceMeters[59]
        assertEquals(0f, jump, 0.001f)
    }

    @Test
    fun `an ordinary sample interval is not a break`() {
        // Every interval identical: there is no gap here to find.
        val profile = TrackAnalyzer.analyze(straightRun(count = 120, metersPerSecond = 5.0))
        assertEquals(listOf(0), profile.segmentStartIndices.toList())
    }

    /**
     * A route exported with one point a minute is not a track full of breaks. The rule is
     * "unusual for this file", not "longer than half a minute".
     */
    @Test
    fun `a sparsely sampled file is left alone`() {
        val profile = TrackAnalyzer.analyze(
            straightRun(count = 30, metersPerSecond = 8.0, secondsBetween = 60)
        )
        assertEquals(listOf(0), profile.segmentStartIndices.toList())
    }

    @Test
    fun `the gap threshold decides what counts as a stop`() {
        val track = rideWithGap(gapSeconds = 45)

        // 45s clears the default 30s floor and ten times the 1s median, so it is a break.
        assertEquals(listOf(0, 60), TrackAnalyzer.analyze(track).segmentStartIndices.toList())

        // Told to expect longer stops, the same file is one continuous ride again.
        assertEquals(
            listOf(0),
            TrackAnalyzer.analyze(track, minGapSeconds = 120.0).segmentStartIndices.toList(),
        )
    }

    /** The median rule still applies underneath: it is a floor, not an override. */
    @Test
    fun `lowering the threshold cannot cut a sparse file into confetti`() {
        val sparse = straightRun(count = 30, metersPerSecond = 8.0, secondsBetween = 60)
        val profile = TrackAnalyzer.analyze(sparse, minGapSeconds = 10.0)
        assertEquals(listOf(0), profile.segmentStartIndices.toList())
    }

    /**
     * The speed window is carried from sample to sample rather than re-found. This checks
     * it against the definition it replaced - the widest centred window inside the segment
     * that spans no more than half the window either side, widened to the neighbouring
     * sample when that leaves none - at every sample of a file with breaks, varying
     * intervals and a stalled clock in it.
     */
    @Test
    fun `the carried speed window agrees with a search at every sample`() {
        val track = awkwardlyTimedRide()
        val profile = TrackAnalyzer.analyze(track)
        val half = (TrackAnalyzer.SPEED_WINDOW_SECONDS / 2.0).toFloat()
        val starts = profile.segmentStartIndices

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
     * A file that stamps thousands of points with one time - some exporters do, and the
     * monotonic clamp produces the same shape from out-of-order stamps. The window search
     * this replaced walked the whole run for every sample of it, so analysing a ride of
     * this size took minutes; the bound is loose enough to be about the algorithm and not
     * about the machine.
     */
    @Test
    fun `a stalled clock does not make analysis quadratic`() {
        val step = 1.0 / metersPerDegreeLatitude
        val points = (0 until 30_000).map { i ->
            TrackPoint(latitude = i * step, longitude = 8.0, elevation = null, time = start)
        }
        val track = Track(name = null, segments = listOf(TrackSegment(points)))

        val elapsed = kotlin.system.measureTimeMillis {
            val profile = TrackAnalyzer.analyze(track)
            // No time passed, so there is no speed to report - only a finite one.
            assertEquals(0f, profile.speedMps[15_000])
        }
        assertTrue(elapsed < 5_000, "analysis took ${elapsed}ms")
    }

    @Test
    fun `a file sparser than the speed window still has a speed`() {
        // A point every 20 s: no sample has a neighbour within the 10 s window.
        val profile = TrackAnalyzer.analyze(
            straightRun(count = 30, metersPerSecond = 8.0, secondsBetween = 20)
        )

        assertEquals(8.0f, profile.speedMps[15], 0.1f)
        assertEquals(580.0, profile.stats.movingDurationSeconds, 1.0)
        assertEquals(8.0, profile.stats.averageSpeedMps, 0.1)
    }

    /**
     * Rolling hills 16 m high every 300 m along a northward run at 5 m/s, with +/-1.5 m of
     * seeded jitter. [secondsBetween] thins it without changing the terrain.
     */
    private fun hillyRun(
        meters: Double,
        secondsBetween: Long = 1,
        reverse: Boolean = false,
        seed: Int = 1,
    ): Track {
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
        return Track(name = "hills", segments = listOf(TrackSegment(points)))
    }

    @Test
    fun `ascent does not depend on how densely a track was sampled`() {
        // Ten 16 m hills: 160 m, less what the 3 m threshold drops at each crest and trough.
        val dense = TrackAnalyzer.analyze(hillyRun(3000.0)).stats.ascentMeters
        val sparse = TrackAnalyzer.analyze(hillyRun(3000.0, secondsBetween = 10)).stats.ascentMeters

        assertTrue(dense in 110.0..170.0, "dense ascent $dense")
        assertTrue(sparse in 110.0..170.0, "sparse ascent $sparse")
        // Not equal: 50 m apart, the sparse points have no neighbours to average their jitter
        // with. Averaging by sample count instead flattened this one to about 6 m.
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
        val profile = TrackAnalyzer.analyze(Track("break", listOf(TrackSegment(first), TrackSegment(second))))

        assertEquals(4.0, profile.stats.ascentMeters, 0.01)
        assertEquals(0.0, profile.stats.descentMeters, 0.01)
    }

    /** Uneven intervals, a stalled stretch and a real break, in one file. */
    private fun awkwardlyTimedRide(): Track {
        val step = 10.0 / metersPerDegreeLatitude
        var seconds = 0L
        val points = (0 until 120).map { i ->
            seconds += when {
                // A run of samples sharing one timestamp.
                i in 30..39 -> 0L
                // A silence long enough for the analyser to cut the track here.
                i == 80 -> 600L
                // Longer than the window is wide, but not long enough to be a break: the
                // sample either side of this has only its neighbour to average with.
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
        return Track(name = null, segments = listOf(TrackSegment(points)))
    }
}
