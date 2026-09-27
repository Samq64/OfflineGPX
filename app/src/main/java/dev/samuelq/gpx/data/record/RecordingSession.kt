package dev.samuelq.gpx.data.record

import dev.samuelq.gpx.core.analysis.Fix
import dev.samuelq.gpx.core.analysis.FixFilter
import dev.samuelq.gpx.core.analysis.SpeedWindow
import dev.samuelq.gpx.core.analysis.haversineMeters
import dev.samuelq.gpx.core.model.TrackPoint
import dev.samuelq.gpx.core.model.Waypoint
import java.time.Instant

/**
 * One ride's running numbers and route, apart from the service feeding it fixes. The WAL
 * is the record of truth; this is what the UI is shown while it is written.
 *
 * Thresholds are fixed for the run: a filter changed mid-ride would make the two halves of
 * one track mean different things.
 *
 * @param clock monotonic milliseconds, for the duration.
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

    /**
     * When the first point was logged, on [clock]. Null while waiting for a fix: the ride
     * starts where its data does, as the saved track's duration does.
     */
    private var startedAt: Long? = null

    /** The route so far, kept purely so the map can draw it live. */
    private val tracePoints = mutableListOf<TrackPoint>()
    private val traceSegmentStarts = mutableListOf<Int>()
    private var traceStartsSegment = true
    private var tracePublishedAt = 0

    /**
     * Wall-clock seconds since the first point, pauses included - the same span the saved
     * track's `totalDurationSeconds` measures, so the live and saved numbers agree.
     */
    val totalSeconds: Double
        get() = startedAt?.let { (clock() - it) / 1000.0 } ?: 0.0

    /** Whether enough new points have landed to be worth re-projecting the live route. */
    val traceDue: Boolean
        get() = tracePoints.size - tracePublishedAt >= TRACE_PUBLISH_EVERY

    /**
     * One reading. Returns the point to log, or null when [FixFilter] rejects it or the
     * ride is paused.
     */
    fun onFix(fix: Fix): TrackPoint? {
        if (paused) return null
        val at = fix.point.time ?: return null
        lastAccuracyMeters = fix.accuracyMeters

        // The filter judges bare position; the accuracy is stamped on afterwards, purely
        // to write down for whatever reopens the file later.
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

    /**
     * Starts a gap in the track. Resuming somewhere else must not read as having travelled
     * there, so the next fix has nothing to measure against. False if already paused.
     */
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

    /**
     * A waypoint at the last known position, stamped [at] rather than with the fix's own
     * time - the moment worth marking is when the button was pressed. Null before the first
     * fix: there is nowhere to put it yet.
     */
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
        /**
         * Fixes between live-trace snapshots. Each one re-projects every route on the map,
         * so at 1 Hz the line grows every 5s rather than every second.
         */
        const val TRACE_PUBLISH_EVERY = 5
    }
}
