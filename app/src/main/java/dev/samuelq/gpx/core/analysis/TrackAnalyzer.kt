package dev.samuelq.gpx.core.analysis

import dev.samuelq.gpx.core.model.Track
import dev.samuelq.gpx.core.model.TrackPoint
import java.time.Duration
import kotlin.math.abs
import kotlin.math.max

/**
 * Turns raw [Track] geometry into the derived series the charts plot.
 *
 * Pure and synchronous - no Android types, no I/O - so it is directly unit-testable and
 * can be reused unchanged by a future live recorder.
 */
object TrackAnalyzer {

    /**
     * Width of the centred window used to differentiate distance into speed.
     *
     * Per-sample speed is unusable: a 1 Hz consumer GPS has metres of horizontal noise, so
     * the naive derivative swings by several km/h between adjacent points and its "max
     * speed" is pure noise. ~10 s averages that out while still resolving real
     * accelerations.
     */
    const val SPEED_WINDOW_SECONDS = 10.0

    /** Below this, the recorder is considered stopped and the time is excluded from moving time. */
    const val MOVING_SPEED_THRESHOLD_MPS = 0.5

    /**
     * Elevation must change by this much before it counts toward ascent or descent.
     *
     * Barometric and GPS altitude both wander by a metre or two at rest. Without a
     * threshold, a track that sat still for an hour accumulates hundreds of metres of
     * phantom climbing.
     */
    const val ELEVATION_NOISE_THRESHOLD_METERS = 3.0

    /**
     * Half-width, in metres along the track, of the moving average applied to elevation
     * before ascent. By distance rather than samples, so a sparse file isn't flattened.
     */
    private const val ELEVATION_SMOOTHING_METERS = 25.0

    /**
     * The shortest time gap that can be a break rather than a sample interval.
     *
     * Paired with [GAP_INTERVAL_MULTIPLE] below: a gap has to clear *both*. Thirty seconds
     * alone would cut a file that only carries a point every minute into confetti.
     */
    const val MIN_GAP_SECONDS = 30.0

    /**
     * How many typical intervals a gap must span to count as one.
     *
     * Against the median interval rather than a fixed number of seconds, since "unusually
     * long" differs for a 1 Hz recording and a route with one point per kilometre. Median
     * rather than mean precisely because the gaps sought would drag a mean up with them.
     */
    const val GAP_INTERVAL_MULTIPLE = 10.0

    /**
     * @param minGapSeconds the shortest silence that can be a break. A parameter rather than
     *   a constant so the rule is testable at both ends; not a setting, since changing it
     *   would re-cut every stored track's distance and moving time.
     */
    fun analyze(track: Track, minGapSeconds: Double = MIN_GAP_SECONDS): TrackProfile {
        val points = track.points
        val size = points.size

        // Timed only if *every* point has a timestamp - interpolating a partial file's gaps
        // would invent speeds never measured, so it's treated as untimed instead.
        val hasTime = size > 1 && points.all { it.time != null }
        // An untimed track must not have breaks inserted from a signal it's otherwise
        // treated as not having.
        val starts =
            if (hasTime) breaksAt(points, track.segmentStartIndices, minGapSeconds)
            else track.segmentStartIndices
        val ends = IntArray(starts.size) { i -> if (i + 1 < starts.size) starts[i + 1] else size }

        val elapsed = FloatArray(size)
        val distance = FloatArray(size)
        val elevation = FloatArray(size)
        val speed = FloatArray(size)

        val startedAt = points.firstOrNull()?.time
        var hasElevation = false

        for (i in 0 until size) {
            val ele = points[i].elevation
            if (ele != null) {
                hasElevation = true
                elevation[i] = ele.toFloat()
            } else {
                elevation[i] = Float.NaN
            }
        }

        if (hasTime) {
            val origin = points[0].time!!
            for (i in 0 until size) {
                val seconds = Duration.between(origin, points[i].time!!).toNanos() / 1e9
                // Clamp to monotonic: a few exporters emit out-of-order or duplicated
                // timestamps, and a non-monotonic x-axis would make the chart fold back
                // on itself and break nearest-point lookup.
                elapsed[i] = if (i == 0) 0f else max(elapsed[i - 1], seconds.toFloat())
            }
        }

        var cumulative = 0.0
        for (segment in starts.indices) {
            val start = starts[segment]
            val end = ends[segment]
            for (i in start until end) {
                // No increment at a segment start: the gap between segments is signal
                // loss, not travel, so it must not be added to the total.
                if (i > start) cumulative += haversineMeters(points[i - 1], points[i])
                distance[i] = cumulative.toFloat()
            }
        }

        if (hasTime) {
            computeSpeed(starts, ends, elapsed, distance, speed)
        } else {
            speed.fill(Float.NaN)
        }

        val smoothedElevation =
            if (hasElevation) smoothElevation(elevation, distance, starts, ends) else elevation

        // One pass across breaks: height gained during a gap was still climbed, and a
        // fresh reference per segment would drop up to the threshold at every pause.
        val (ascent, descent) =
            if (hasElevation) accumulateVertical(smoothedElevation) else 0.0 to 0.0

        var movingSeconds = 0.0
        if (hasTime) {
            for (segment in starts.indices) {
                for (i in (starts[segment] + 1) until ends[segment]) {
                    val meanSpeed = (speed[i] + speed[i - 1]) / 2f
                    if (meanSpeed >= MOVING_SPEED_THRESHOLD_MPS) {
                        movingSeconds += (elapsed[i] - elapsed[i - 1]).toDouble()
                    }
                }
            }
        }

        val totalDistance = if (size > 0) distance[size - 1].toDouble() else 0.0
        val totalDuration = if (hasTime && size > 0) elapsed[size - 1].toDouble() else 0.0
        // Prefer moving time for the average - a track with a lunch stop in it would
        // otherwise report an average speed nobody rode at.
        val averageBasis = if (movingSeconds > 0.0) movingSeconds else totalDuration

        val stats = TrackStats(
            name = track.name,
            startedAt = startedAt,
            pointCount = size,
            distanceMeters = totalDistance,
            totalDurationSeconds = totalDuration,
            movingDurationSeconds = movingSeconds,
            averageSpeedMps = if (averageBasis > 0.0) totalDistance / averageBasis else 0.0,
            ascentMeters = ascent,
            descentMeters = descent,
        )

        return TrackProfile(
            points = points,
            segmentStartIndices = starts,
            elapsedSeconds = elapsed,
            distanceMeters = distance,
            speedMps = speed,
            elevationMeters = elevation,
            hasTime = hasTime,
            hasElevation = hasElevation,
            stats = stats,
        )
    }

    /**
     * The file's own segment boundaries, plus one wherever the clock jumps.
     *
     * A dismounted break usually isn't recorded as one - auto-pause leaves one long,
     * ordinary-looking interval, which untouched reads as riding straight through the stop
     * at speed. Splitting there says what's known (nothing was recorded) rather than
     * interpolating a stop, which would invent decelerations nobody measured.
     */
    private fun breaksAt(
        points: List<TrackPoint>,
        declared: IntArray,
        minGapSeconds: Double,
    ): IntArray {
        if (points.size < 3) return declared

        val intervals = DoubleArray(points.size - 1)
        var timed = 0
        for (i in 1 until points.size) {
            val previous = points[i - 1].time
            val current = points[i].time
            if (previous != null && current != null) {
                val seconds = (current.toEpochMilli() - previous.toEpochMilli()) / 1000.0
                if (seconds > 0) intervals[timed++] = seconds
            }
        }
        // Too little to judge "unusual" against. Fewer than a handful of intervals is a
        // sparse route file, where any gap rule would be guessing.
        if (timed < MIN_INTERVALS_FOR_GAPS) return declared

        val sorted = intervals.copyOf(timed).apply { sort() }
        val median = sorted[timed / 2]
        val threshold = maxOf(minGapSeconds, median * GAP_INTERVAL_MULTIPLE)

        val breaks = sortedSetOf<Int>().apply { addAll(declared.toList()) }
        for (i in 1 until points.size) {
            val previous = points[i - 1].time ?: continue
            val current = points[i].time ?: continue
            if ((current.toEpochMilli() - previous.toEpochMilli()) / 1000.0 > threshold) {
                breaks += i
            }
        }
        return breaks.toIntArray()
    }

    /** Below this there is no meaningful median to call anything unusual against. */
    private const val MIN_INTERVALS_FOR_GAPS = 8

    /**
     * Centred finite difference of distance over [SPEED_WINDOW_SECONDS], clamped to the
     * enclosing segment so the window never spans a signal-loss gap. Always reaches at
     * least the neighbouring sample either side: a sample alone in its window would read
     * as stopped, which on a sparse file is every sample.
     *
     * Both edges only move forward, carried from one sample to the next: `elapsed` is
     * monotonic, and re-searching from `i` each time is quadratic whenever the clock
     * doesn't advance.
     */
    private fun computeSpeed(
        starts: IntArray,
        ends: IntArray,
        elapsed: FloatArray,
        distance: FloatArray,
        out: FloatArray,
    ) {
        val half = (SPEED_WINDOW_SECONDS / 2.0).toFloat()
        for (segment in starts.indices) {
            val start = starts[segment]
            val end = ends[segment]
            var lo = start
            var hi = start
            for (i in start until end) {
                // The trailing edge may not fall behind the sample itself: a window is
                // centred on `i`, and `hi` is only ever carried forward from the last one.
                if (hi < i) hi = i
                while (lo < i && elapsed[i] - elapsed[lo] > half) lo++
                while (hi < end - 1 && elapsed[hi + 1] - elapsed[i] <= half) hi++
                if (lo == i && i > start) lo = i - 1
                if (hi == i && i < end - 1) hi = i + 1

                val dt = elapsed[hi] - elapsed[lo]
                out[i] = if (dt > 0f) max(0f, (distance[hi] - distance[lo]) / dt) else 0f
            }
        }
    }

    /**
     * Moving average over +/-[ELEVATION_SMOOTHING_METERS] of [distance], NaN-tolerant,
     * segment-local. Prefix sums, since a stop can put hundreds of samples in one window.
     */
    private fun smoothElevation(
        elevation: FloatArray,
        distance: FloatArray,
        starts: IntArray,
        ends: IntArray,
    ): FloatArray {
        val out = FloatArray(elevation.size)
        val sums = DoubleArray(elevation.size + 1)
        val counts = IntArray(elevation.size + 1)
        for (i in elevation.indices) {
            val e = elevation[i]
            sums[i + 1] = sums[i] + if (e.isNaN()) 0.0 else e.toDouble()
            counts[i + 1] = counts[i] + if (e.isNaN()) 0 else 1
        }
        val half = ELEVATION_SMOOTHING_METERS.toFloat()
        for (segment in starts.indices) {
            val start = starts[segment]
            val end = ends[segment]
            var lo = start
            var hi = start
            for (i in start until end) {
                if (hi < i) hi = i
                while (distance[i] - distance[lo] > half) lo++
                while (hi < end - 1 && distance[hi + 1] - distance[i] <= half) hi++
                val count = counts[hi + 1] - counts[lo]
                out[i] = if (count > 0) ((sums[hi + 1] - sums[lo]) / count).toFloat() else Float.NaN
            }
        }
        return out
    }

    /**
     * Hysteresis accumulator: only commit a climb or a drop once it exceeds
     * [ELEVATION_NOISE_THRESHOLD_METERS] from the last committed reference.
     */
    private fun accumulateVertical(elevation: FloatArray): Pair<Double, Double> {
        var ascent = 0.0
        var descent = 0.0
        var reference = Float.NaN

        for (i in elevation.indices) {
            val e = elevation[i]
            if (e.isNaN()) continue
            if (reference.isNaN()) {
                reference = e
                continue
            }
            val delta = e - reference
            if (abs(delta) >= ELEVATION_NOISE_THRESHOLD_METERS) {
                if (delta > 0) ascent += delta.toDouble() else descent += -delta.toDouble()
                reference = e
            }
        }
        return ascent to descent
    }
}
