package dev.samuelq.gpx.core.analysis

import dev.samuelq.gpx.core.model.Track
import dev.samuelq.gpx.core.model.TrackPoints
import java.time.Instant
import kotlin.math.abs
import kotlin.math.max

/** Turns raw [Track] geometry into the derived series the charts plot. Pure, no Android types. */
object TrackAnalyzer {

    /** Centred window for speed; 1 Hz GPS noise makes per-sample speed useless. */
    const val SPEED_WINDOW_SECONDS = 10.0

    /** Below this the time is excluded from moving time. */
    const val MOVING_SPEED_THRESHOLD_MPS = 0.5

    /** Hysteresis for ascent/descent; altitude wanders a metre or two at rest. */
    const val ELEVATION_NOISE_THRESHOLD_METERS = 3.0

    /** Half-width of the elevation moving average, by distance so sparse files aren't flattened. */
    private const val ELEVATION_SMOOTHING_METERS = 25.0

    /** A gap must exceed both this and [GAP_INTERVAL_MULTIPLE] median intervals to be a break. */
    const val MIN_GAP_SECONDS = 30.0

    /** Relative to the median interval, which the gaps themselves can't drag up like a mean. */
    const val GAP_INTERVAL_MULTIPLE = 10.0

    /** @param minGapSeconds exposed for tests; not a setting, as it would re-cut stored stats. */
    fun analyze(track: Track, minGapSeconds: Double = MIN_GAP_SECONDS): TrackProfile =
        analyze(track.points, minGapSeconds)

    fun analyze(points: TrackPoints, minGapSeconds: Double = MIN_GAP_SECONDS): TrackProfile {
        val size = points.size

        // Partially timed files are treated as untimed rather than inventing speeds.
        val hasTime = size > 1 && points.indices.all(points::hasTime)
        val starts =
            if (hasTime) breaksAt(points, minGapSeconds)
            else points.segmentStarts()
        val ends = IntArray(starts.size) { i -> if (i + 1 < starts.size) starts[i + 1] else size }

        val elapsed = FloatArray(size)
        val distance = FloatArray(size)
        val elevation = FloatArray(size)
        val speed = FloatArray(size)

        val startedAt = if (size > 0 && points.hasTime(0)) Instant.ofEpochMilli(points.timeMillis(0)) else null
        var hasElevation = false

        for (i in 0 until size) {
            elevation[i] = points.elevation(i)
            if (!elevation[i].isNaN()) hasElevation = true
        }

        if (hasTime) {
            val origin = points.timeMillis(0)
            for (i in 0 until size) {
                val seconds = (points.timeMillis(i) - origin) / 1000.0
                // Clamp to monotonic: some exporters emit out-of-order timestamps.
                elapsed[i] = if (i == 0) 0f else max(elapsed[i - 1], seconds.toFloat())
            }
        }

        var cumulative = 0.0
        for (segment in starts.indices) {
            val start = starts[segment]
            val end = ends[segment]
            for (i in start until end) {
                // Gaps between segments are signal loss, not travel.
                if (i > start) {
                    cumulative += haversineMeters(
                        points.latitude(i - 1), points.longitude(i - 1), points.latitude(i), points.longitude(i),
                    )
                }
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

        // Across breaks: height gained during a gap was still climbed.
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

        val stats = TrackStats(
            startedAt = startedAt,
            pointCount = size,
            distanceMeters = totalDistance,
            totalDurationSeconds = totalDuration,
            movingDurationSeconds = movingSeconds,
            averageSpeedMps = if (movingSeconds > 0.0) totalDistance / movingSeconds else 0.0,
            ascentMeters = ascent,
            descentMeters = descent,
        )

        return TrackProfile(
            points = points.withSegmentStarts(starts),
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
     * The file's own segment boundaries, plus one wherever the clock jumps: auto-pause
     * leaves one long interval that would otherwise read as riding through the stop.
     */
    private fun breaksAt(points: TrackPoints, minGapSeconds: Double): IntArray {
        val declared = points.segmentStarts()
        if (points.size < 3) return declared

        // Only called when every point is timed.
        val intervals = DoubleArray(points.size - 1)
        var timed = 0
        for (i in 1 until points.size) {
            val seconds = (points.timeMillis(i) - points.timeMillis(i - 1)) / 1000.0
            if (seconds > 0) intervals[timed++] = seconds
        }
        if (timed < MIN_INTERVALS_FOR_GAPS) return declared

        val sorted = intervals.copyOf(timed).apply { sort() }
        val median = sorted[timed / 2]
        val threshold = maxOf(minGapSeconds, median * GAP_INTERVAL_MULTIPLE)

        val breaks = sortedSetOf<Int>().apply { addAll(declared.toList()) }
        for (i in 1 until points.size) {
            if ((points.timeMillis(i) - points.timeMillis(i - 1)) / 1000.0 > threshold) breaks += i
        }
        return breaks.toIntArray()
    }

    /** Below this there is no meaningful median to call anything unusual against. */
    private const val MIN_INTERVALS_FOR_GAPS = 8

    /**
     * Centred difference over [SPEED_WINDOW_SECONDS], clamped to the segment. Always spans
     * at least one neighbour, else sparse files read as stopped. Edges only move forward
     * to stay linear when the clock stalls.
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

    /** Commits a climb or drop once it exceeds [ELEVATION_NOISE_THRESHOLD_METERS]. */
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
