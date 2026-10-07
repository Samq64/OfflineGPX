package dev.samuelq.gpx.core.analysis

import dev.samuelq.gpx.core.model.TrackPoint
import java.time.Instant
import kotlin.math.max

/**
 * Rejects GPS noise so a stationary phone doesn't record distance: a fix worse than
 * [maxAccuracyMeters] is dropped, and one within its own error circle (floored at
 * [MIN_DISPLACEMENT_METERS]) hasn't moved.
 *
 * A still reading comes back as the last position re-stamped, once per
 * [stillIntervalSeconds], so a stop reads as speed decaying to zero rather than a gap.
 */
public class FixFilter(
    private val maxAccuracyMeters: Double = MAX_ACCURACY_METERS,
    private val stillIntervalSeconds: Double = STILL_INTERVAL_SECONDS,
) {

    private var lastAccepted: TrackPoint? = null

    /** Last time anything was returned, moved or still. */
    private var lastRecordedAt: Instant? = null

    /** Call on a pause or restart. */
    public fun reset() {
        lastAccepted = null
        lastRecordedAt = null
    }

    /** The point to record, or null. Not always [fix] itself. */
    public fun pointFor(fix: TrackPoint): TrackPoint? {
        val accuracy = fix.accuracyMeters

        if (accuracy != null && accuracy > maxAccuracyMeters) return null

        val at = fix.time
        val previous = lastAccepted
        if (previous == null) {
            lastAccepted = fix
            lastRecordedAt = at
            return fix
        }

        val meters = haversineMeters(previous, fix)
        val seconds = secondsBetween(previous, fix)

        // Usually a provider flipping to a cell-tower estimate kilometres away.
        if (seconds > 0.0 && meters / seconds > MAX_PLAUSIBLE_SPEED_MPS) return null

        if (meters >= max(MIN_DISPLACEMENT_METERS, accuracy ?: 0.0)) {
            lastAccepted = fix
            lastRecordedAt = at
            return fix
        }

        // Stationary. The anchor stays put so small steps still add up against it.
        if (at == null) return null
        val sinceRecorded = lastRecordedAt
            ?.let { (at.toEpochMilli() - it.toEpochMilli()) / 1000.0 }
            ?: Double.MAX_VALUE
        if (sinceRecorded < stillIntervalSeconds) return null

        lastRecordedAt = at
        return previous.copy(time = at)
    }

    public companion object {
        public const val MAX_ACCURACY_METERS: Double = 25.0

        internal const val MIN_DISPLACEMENT_METERS = 4.0

        /** 180 km/h; faster is a provider artefact. */
        internal const val MAX_PLAUSIBLE_SPEED_MPS = 50.0

        /**
         * Matches [TrackAnalyzer.SPEED_WINDOW_SECONDS] and stays under
         * [TrackAnalyzer.MIN_GAP_SECONDS] so a stop isn't read as a gap.
         */
        internal const val STILL_INTERVAL_SECONDS = 10.0

        private fun secondsBetween(from: TrackPoint, to: TrackPoint): Double {
            val a = from.time ?: return 0.0
            val b = to.time ?: return 0.0
            return (b.toEpochMilli() - a.toEpochMilli()) / 1000.0
        }
    }
}
