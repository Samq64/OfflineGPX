package dev.samuelq.gpx.data.record

import dev.samuelq.gpx.core.analysis.FixFilter
import dev.samuelq.gpx.core.analysis.SpeedWindow
import dev.samuelq.gpx.core.analysis.haversineMeters
import dev.samuelq.gpx.core.model.TrackPoint
import dev.samuelq.gpx.core.model.Waypoint
import java.time.Instant

/**
 * One ride's running numbers and route for the UI; the WAL is the record of truth.
 * Thresholds are fixed for the run so both halves of a track mean the same thing.
 *
 * @param clock monotonic milliseconds.
 */
internal class RecordingSession(
    private val maxAccuracyMeters: Double,
    minDisplacementMeters: Double,
    private val clock: () -> Long,
) {
    private val filter = FixFilter(maxAccuracyMeters, minDisplacementMeters)
    private val speedWindow = SpeedWindow()

    var paused = false
        private set
    var distanceMeters = 0.0
        private set
    private var pointCount = 0
    private var lastPoint: TrackPoint? = null
    private var currentSpeedMps: Double? = null
    private var lastAccuracyMeters: Double? = null
    private val waypoints = mutableListOf<Waypoint>()

    /** First logged point, on [clock]; the ride starts where its data does. */
    private var startedAt: Long? = null

    private val tracePoints = mutableListOf<TrackPoint>()
    private val traceSegmentStarts = mutableListOf<Int>()
    private var traceStartsSegment = true
    private var tracePublishedAt = 0

    /** Pauses included, matching the saved track's `totalDurationSeconds`. */
    val totalSeconds: Double
        get() = startedAt?.let { (clock() - it) / 1000.0 } ?: 0.0

    val traceDue: Boolean
        get() = tracePoints.size - tracePublishedAt >= TRACE_PUBLISH_EVERY

    /** Returns the point to log, or null if rejected or paused. */
    fun onFix(fix: TrackPoint): TrackPoint? {
        if (paused) return null
        val at = fix.time ?: return null
        lastAccuracyMeters = fix.accuracyMeters

        // A still point is the anchor re-stamped, so it gets this fix's accuracy, not the anchor's.
        val point = filter.pointFor(fix)?.copy(accuracyMeters = fix.accuracyMeters)
        if (point != null) {
            if (startedAt == null) startedAt = clock()
            lastPoint?.let { distanceMeters += haversineMeters(it, point) }
            lastPoint = point
            pointCount++

            if (traceStartsSegment) {
                traceSegmentStarts += tracePoints.size
                traceStartsSegment = false
            }
            tracePoints += point
        }

        speedWindow.add(at.toEpochMilli() / 1000.0, distanceMeters)
        currentSpeedMps = speedWindow.speedMps
        return point
    }

    /** Starts a gap so resuming elsewhere doesn't count as travel. False if already paused. */
    fun pause(): Boolean {
        if (paused) return false
        paused = true
        traceStartsSegment = true
        lastPoint = null
        currentSpeedMps = null
        filter.reset()
        speedWindow.reset()
        return true
    }

    /** False if not paused. */
    fun resume(): Boolean {
        if (!paused) return false
        paused = false
        return true
    }

    /** At the last known position, stamped [at] (the button press). Null before the first fix. */
    fun addWaypoint(description: String, at: Instant): Waypoint? {
        val point = lastPoint ?: return null
        val waypoint = Waypoint(point.copy(time = at), description.trim().takeIf(String::isNotEmpty))
        waypoints += waypoint
        return waypoint
    }

    fun trace(): LiveTrace {
        tracePublishedAt = tracePoints.size
        return LiveTrace(tracePoints.toList(), traceSegmentStarts.toIntArray())
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
