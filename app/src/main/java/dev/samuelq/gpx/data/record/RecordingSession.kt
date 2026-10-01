package dev.samuelq.gpx.data.record

import dev.samuelq.gpx.core.analysis.FixFilter
import dev.samuelq.gpx.core.analysis.TrackAnalyzer
import dev.samuelq.gpx.core.analysis.SpeedWindow
import dev.samuelq.gpx.core.analysis.haversineMeters
import dev.samuelq.gpx.core.model.TrackPoint
import dev.samuelq.gpx.core.model.TrackPoints
import dev.samuelq.gpx.core.model.TrackPointsBuilder
import dev.samuelq.gpx.core.model.Waypoint
import java.time.Duration
import java.time.Instant

/**
 * One ride's running numbers and route for the UI; the WAL is the record of truth.
 * Thresholds are fixed for the run so both halves of a track mean the same thing.
 *
 * @param clock monotonic milliseconds.
 */
internal class RecordingSession(
    private val maxAccuracyMeters: Double,
    private val clock: () -> Long,
) {
    private val filter = FixFilter(maxAccuracyMeters)
    private val speedWindow = SpeedWindow()

    var paused = false
        private set
    var distanceMeters = 0.0
        private set
    private var pointCount = 0
    /** Kept through a pause, so a waypoint can still be dropped where the ride stopped. */
    private var lastPoint: TrackPoint? = null

    /** What the next point's distance is measured from; a pause clears it so the gap isn't bridged. */
    private var distanceFrom: TrackPoint? = null
    private var currentSpeedMps: Double? = null
    private var lastAccuracyMeters: Double? = null
    private val waypoints = mutableListOf<Waypoint>()

    /** First logged point, on [clock]; the ride starts where its data does. */
    private var startedAt: Long? = null

    private val tracePoints = TrackPointsBuilder()
    private var tracePublishedAt = 0

    /** On [clock]; elapsed stands still from either, as the log ends there. */
    private var pausedAt: Long? = null
    private var heldAt: Long? = null

    /** Stop was asked for; fixes wait on the answer. */
    val held: Boolean get() = heldAt != null

    /** Earlier pauses included, matching the saved track's `totalDurationSeconds`. */
    val totalSeconds: Double
        get() = startedAt?.let { ((listOfNotNull(pausedAt, heldAt).minOrNull() ?: clock()) - it) / 1000.0 } ?: 0.0

    /** Also on the first point, so the map shows a position as soon as there is one. */
    val traceDue: Boolean
        get() = tracePoints.size - tracePublishedAt >= TRACE_PUBLISH_EVERY ||
            (tracePublishedAt == 0 && tracePoints.size > 0)

    /** Returns the point to log, or null if rejected or paused. */
    fun onFix(fix: TrackPoint): TrackPoint? {
        if (paused || held) return null
        val at = fix.time ?: return null
        lastAccuracyMeters = fix.accuracyMeters

        // A still point is the anchor re-stamped, so it gets this fix's accuracy, not the anchor's.
        val point = filter.pointFor(fix)?.copy(accuracyMeters = fix.accuracyMeters)
        if (point != null) {
            if (startedAt == null) startedAt = clock()
            val from = distanceFrom
            if (from != null && isGap(from, point)) {
                tracePoints.startSegment()
            } else if (from != null) {
                distanceMeters += haversineMeters(from, point)
            }
            distanceFrom = point
            logged(point)
        }

        speedWindow.add(at.toEpochMilli() / 1000.0, distanceMeters)
        currentSpeedMps = speedWindow.speedMps
        return point
    }

    /**
     * Lost signal, which the saved track's analysis won't count as travel either. Its floor
     * only: the analysis also scales with the median interval, which a ride has yet to show.
     */
    private fun isGap(from: TrackPoint, to: TrackPoint): Boolean {
        val seconds = Duration.between(from.time ?: return false, to.time ?: return false).seconds
        return seconds > TrackAnalyzer.MIN_GAP_SECONDS
    }

    private fun logged(point: TrackPoint) {
        lastPoint = point
        pointCount++
        tracePoints.add(point)
    }

    /**
     * The last point again at [at], so the log ends when the ride stood still rather than
     * at the last fix to move. Null if paused or there is nothing to repeat.
     */
    private fun closeAt(at: Instant): TrackPoint? {
        if (paused || held) return null
        val last = lastPoint?.takeIf { it.time?.isBefore(at) == true } ?: return null
        return last.copy(time = at).also(::logged)
    }

    /** Waits for Stop's answer; returns the point that ends the log at [at], if any. */
    fun hold(at: Instant): TrackPoint? {
        val closing = closeAt(at)
        if (heldAt == null) heldAt = clock()
        return closing
    }

    /** Stop was cancelled. */
    fun release() {
        heldAt = null
    }

    /**
     * Starts a gap so resuming elsewhere doesn't count as travel. Returns the point that
     * ends the segment at [at], if any. Callers check [paused] first.
     */
    fun pause(at: Instant): TrackPoint? {
        val closing = closeAt(at)
        paused = true
        pausedAt = clock()
        tracePoints.startSegment()
        distanceFrom = null
        currentSpeedMps = null
        filter.reset()
        speedWindow.reset()
        return closing
    }

    /** False if not paused. */
    fun resume(): Boolean {
        if (!paused) return false
        paused = false
        pausedAt = null
        return true
    }

    /** At the last known position, stamped [at] (the button press). Null before the first fix. */
    fun addWaypoint(description: String, at: Instant): Waypoint? {
        val point = lastPoint ?: return null
        val waypoint = Waypoint(point.copy(time = at), description.trim().takeIf(String::isNotEmpty))
        waypoints += waypoint
        return waypoint
    }

    /** A snapshot sharing the builder's arrays, so publishing doesn't copy the ride. */
    fun trace(): TrackPoints {
        tracePublishedAt = tracePoints.size
        return tracePoints.snapshot()
    }

    fun state() = RecordingState.Active(
        paused = paused,
        pointCount = pointCount,
        distanceMeters = distanceMeters,
        totalSeconds = totalSeconds,
        lastPoint = lastPoint,
        currentSpeedMps = currentSpeedMps,
        accuracyMeters = lastAccuracyMeters,
        accuracyLimitMeters = maxAccuracyMeters,
        waypoints = waypoints.toList(),
    )

    private companion object {
        /** Fixes between live-trace snapshots, since each re-projects every route on the map. */
        const val TRACE_PUBLISH_EVERY = 5
    }
}
