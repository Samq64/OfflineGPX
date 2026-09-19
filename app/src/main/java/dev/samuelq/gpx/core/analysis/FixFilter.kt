package dev.samuelq.gpx.core.analysis

import dev.samuelq.gpx.core.model.TrackPoint
import java.time.Instant
import kotlin.math.max

/**
 * One raw reading from the positioning hardware, before anything decides to believe it.
 *
 * Distinct from [TrackPoint], which is what the recorder has decided to write down.
 * [accuracyMeters] is the whole reason for the distinction: it is the difference between a
 * position and a guess, and GPX has nowhere to put it.
 */
data class Fix(
    val point: TrackPoint,
    /** Horizontal accuracy in metres at 68% confidence, or null if the source gave none. */
    val accuracyMeters: Double?,
)

/**
 * Decides which fixes are a position and which are noise.
 *
 * Without this a phone sitting on a table records a ride. A stationary consumer GPS does
 * not report the same coordinate twice; it wanders inside its error circle, and a 1 Hz
 * stream of that wander is a few metres of "movement" every second, which is several km/h
 * of speed and hundreds of metres of distance per hour - none of which happened. Smoothing
 * the speed afterwards cannot undo it, because by then the distance has already been
 * accumulated from noise.
 *
 * Two rules, in order:
 *
 *  - A fix whose own accuracy is worse than [maxAccuracyMeters] is not a position at all.
 *    Indoors that is most of them, which is why recording indoors should look like waiting
 *    for a fix rather than like a slow walk.
 *  - A fix that has not moved further than the error circle it arrived with has not been
 *    shown to have moved. The floor is the accuracy itself, never below
 *    [minDisplacementMeters]: believing a 3m step reported with +/-30m of confidence is
 *    believing the noise.
 *
 * A reading that fails the second rule is still a reading. Rather than drop it, the filter
 * hands back the *last known position* carrying the new timestamp - the device is recorded
 * as having stayed put, which is precisely what was measured. Only readings that fail the
 * first rule, where nothing at all can be concluded, produce nothing.
 *
 * That distinction decides what a stop looks like everywhere downstream. Dropping the
 * readings leaves a silence, and a silence has to be *inferred* back into a stop by
 * [TrackAnalyzer] and then drawn as a hole in the chart. Keeping them means the position
 * stops changing while time carries on, so the speed line walks down to zero, sits there,
 * and walks back up - with nothing invented, because a point that repeats a coordinate is
 * a claim the measurement actually supports. One per [stillIntervalSeconds] is enough to
 * carry that: an hour's coffee stop is 360 points rather than 3,600.
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

    /** Accuracy of the most recent reading, believable or not. For telling the user why. */
    var lastAccuracyMeters: Double? = null
        private set

    /** Call on a pause or a restart: the next fix begins a new run and has nothing to beat. */
    fun reset() {
        lastAccepted = null
        lastRecordedAt = null
        lastAccuracyMeters = null
    }

    /**
     * The point to record, or null if this reading establishes nothing worth writing.
     *
     * Not always *this* fix: a believable reading that has not moved yields the last known
     * position stamped with the new time. The caller can treat both the same - a repeated
     * coordinate adds no distance by construction, so nothing downstream has to know which
     * kind it got.
     */
    fun pointFor(fix: Fix): TrackPoint? {
        val accuracy = fix.accuracyMeters
        lastAccuracyMeters = accuracy

        // A source that reports no accuracy at all still gets the displacement floor; it
        // is only the accuracy *test* that needs a number.
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

        // Stationary. The anchor does not move - two small steps in the same direction
        // must still add up against where the device actually was, not against the last
        // thing written down.
        if (at == null) return null
        val sinceRecorded = lastRecordedAt
            ?.let { (at.toEpochMilli() - it.toEpochMilli()) / 1000.0 }
            ?: Double.MAX_VALUE
        if (sinceRecorded < stillIntervalSeconds) return null

        lastRecordedAt = at
        return previous.copy(time = at)
    }

    companion object {
        /**
         * Worse than this and the fix cannot tell a parked phone from a moving one.
         *
         * A clear outdoor GPS fix is 3-10m. 25m is a generous indoor or urban-canyon
         * reading, and the point at which the displacement rule would be rejecting
         * everything short of a sprint anyway.
         */
        const val MAX_ACCURACY_METERS = 25.0

        /** The floor under the accuracy rule, for the rare very confident fix. */
        const val MIN_DISPLACEMENT_METERS = 4.0

        /** 180 km/h. Beyond this it is a provider artefact, not a descent. */
        const val MAX_PLAUSIBLE_SPEED_MPS = 50.0

        /**
         * How often a stationary device is written down.
         *
         * Matched to [TrackAnalyzer.SPEED_WINDOW_SECONDS] so the speed window always has a
         * sample to work with, and comfortably under [TrackAnalyzer.MIN_GAP_SECONDS] so a
         * stop is never mistaken for the silence it used to be.
         */
        const val STILL_INTERVAL_SECONDS = 10.0

        private fun secondsBetween(from: TrackPoint, to: TrackPoint): Double {
            val a = from.time ?: return 0.0
            val b = to.time ?: return 0.0
            return (b.toEpochMilli() - a.toEpochMilli()) / 1000.0
        }
    }
}
