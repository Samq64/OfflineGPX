package dev.samuelq.gpx.core.analysis

import dev.samuelq.gpx.core.model.TrackPoint
import java.time.Instant
import kotlin.math.max

/**
 * One raw reading from the positioning hardware, before anything decides to believe it.
 *
 * Distinct from [TrackPoint], which is what the recorder has decided to write down: this is
 * the reading that decision was made from, which for the "still" grace period in
 * [FixFilter.pointFor] is not the same as the point it ends up stamped onto.
 */
data class Fix(
    val point: TrackPoint,
    /** Horizontal accuracy in metres at 68% confidence, or null if the source gave none. */
    val accuracyMeters: Double?,
)

/**
 * Decides which fixes are a position and which are noise.
 *
 * Without this a phone on a table records a ride: a stationary GPS wanders inside its own
 * error circle, and 1 Hz of that wander is distance that never happened. Two rules - a fix
 * less accurate than [maxAccuracyMeters] isn't a position, and one that hasn't moved
 * further than its own error circle (floored at [minDisplacementMeters]) hasn't moved.
 *
 * A reading failing only the second still comes back, as the last known position stamped
 * with the new time, once per [stillIntervalSeconds] - so a stop reads downstream as the
 * speed decaying to zero rather than as a silence [TrackAnalyzer] must draw as a gap.
 */
class FixFilter(
    private val maxAccuracyMeters: Double = MAX_ACCURACY_METERS,
    private val minDisplacementMeters: Double = MIN_DISPLACEMENT_METERS,
    private val stillIntervalSeconds: Double = STILL_INTERVAL_SECONDS,
) {

    /** Where the device was last shown to be. Unchanged by a reading that did not move. */
    private var lastAccepted: TrackPoint? = null

    /** When anything was last handed back, moved or still, so stillness can be thinned. */
    private var lastRecordedAt: Instant? = null

    /** Call on a pause or a restart: the next fix begins a new run and has nothing to beat. */
    fun reset() {
        lastAccepted = null
        lastRecordedAt = null
    }

    /**
     * The point to record, or null if this reading establishes nothing worth writing. Not
     * always *this* fix - see class doc.
     */
    fun pointFor(fix: Fix): TrackPoint? {
        val accuracy = fix.accuracyMeters

        // Only the accuracy test needs a number; a source reporting none still gets the
        // displacement floor.
        if (accuracy != null && accuracy > maxAccuracyMeters) return null

        val at = fix.point.time
        val previous = lastAccepted
        if (previous == null) {
            lastAccepted = fix.point
            lastRecordedAt = at
            return fix.point
        }

        val meters = haversineMeters(previous, fix.point)
        val seconds = secondsBetween(previous, fix.point)

        // A jump nothing this app is for could have made. Usually a provider switching
        // between a real fix and a cell-tower estimate, which lands kilometres away.
        if (seconds > 0.0 && meters / seconds > MAX_PLAUSIBLE_SPEED_MPS) return null

        if (meters >= max(minDisplacementMeters, accuracy ?: 0.0)) {
            lastAccepted = fix.point
            lastRecordedAt = at
            return fix.point
        }

        // Stationary. The anchor doesn't move, so two small steps in the same direction
        // still add up against where the device actually was.
        if (at == null) return null
        val sinceRecorded = lastRecordedAt
            ?.let { (at.toEpochMilli() - it.toEpochMilli()) / 1000.0 }
            ?: Double.MAX_VALUE
        if (sinceRecorded < stillIntervalSeconds) return null

        lastRecordedAt = at
        return previous.copy(time = at)
    }

    companion object {
        /** Worse than this and the fix can't tell a parked phone from a moving one. */
        const val MAX_ACCURACY_METERS = 25.0

        /** The floor under the accuracy rule, for the rare very confident fix. */
        const val MIN_DISPLACEMENT_METERS = 4.0

        /** 180 km/h. Beyond this it is a provider artefact, not a descent. */
        const val MAX_PLAUSIBLE_SPEED_MPS = 50.0

        /**
         * How often a stationary device is written down. Matched to
         * [TrackAnalyzer.SPEED_WINDOW_SECONDS] so the speed window always has a sample, and
         * comfortably under [TrackAnalyzer.MIN_GAP_SECONDS] so a stop isn't mistaken for a gap.
         */
        const val STILL_INTERVAL_SECONDS = 10.0

        private fun secondsBetween(from: TrackPoint, to: TrackPoint): Double {
            val a = from.time ?: return 0.0
            val b = to.time ?: return 0.0
            return (b.toEpochMilli() - a.toEpochMilli()) / 1000.0
        }
    }
}
