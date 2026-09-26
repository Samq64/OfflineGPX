package dev.samuelq.gpx.core.analysis

import dev.samuelq.gpx.core.model.TrackPoint
import java.time.Instant

/**
 * Whole-track summary. Every field is SI; formatting for display happens in the UI layer
 * so that adding an imperial toggle later touches one file and not this one.
 */
data class TrackStats(
    val name: String?,
    val startedAt: Instant?,
    val pointCount: Int,
    val distanceMeters: Double,
    /** Wall-clock span from first to last point. */
    val totalDurationSeconds: Double,
    /** Time spent above [TrackAnalyzer.MOVING_SPEED_THRESHOLD_MPS]. */
    val movingDurationSeconds: Double,
    /** Distance over *moving* time - the number trackers report as "average speed". */
    val averageSpeedMps: Double,
    val maxSpeedMps: Double,
    val ascentMeters: Double,
    val descentMeters: Double,
    val minElevationMeters: Double?,
    val maxElevationMeters: Double?,
    /** Index into the profile arrays of the fastest sample, or -1. Used to direct-label the extreme. */
    val maxSpeedIndex: Int,
    /** Index into the profile arrays of the highest sample, or -1. */
    val maxElevationIndex: Int,
)

/**
 * A track with everything the charts need, precomputed once.
 *
 * Parallel primitive arrays, not a list of objects: a long ride is 30-50k points and the
 * chart re-reads these every scrub frame. All are length [size] and share indices with
 * [points]. Not a `data class` - array equality would be O(n), where `remember` keys want
 * identity.
 */
class TrackProfile(
    val points: List<TrackPoint>,
    /** Index at which each recording segment starts. The chart breaks its line here. */
    val segmentStartIndices: IntArray,
    /** Seconds since the first point. All zeroes when [hasTime] is false. */
    val elapsedSeconds: FloatArray,
    /** Cumulative ground distance in metres. Does not increase across a segment boundary. */
    val distanceMeters: FloatArray,
    /** Smoothed speed in m/s; `NaN` throughout when [hasTime] is false. */
    val speedMps: FloatArray,
    /** Elevation in metres; `NaN` at any point whose `<ele>` was missing. */
    val elevationMeters: FloatArray,
    val hasTime: Boolean,
    val hasElevation: Boolean,
    val stats: TrackStats,
) {
    val size: Int get() = points.size

    /**
     * The one index [point] belongs to, or -1 on an empty track. Nearest in time when both
     * have it, so a round trip past the same spot still picks the right leg; nearest in
     * space otherwise.
     */
    fun indexOf(point: TrackPoint): Int {
        val at = point.time
        return if (at != null && hasTime) {
            points.indices.minByOrNull { i ->
                points[i].time?.let { kotlin.math.abs(it.toEpochMilli() - at.toEpochMilli()) } ?: Long.MAX_VALUE
            }
        } else {
            points.indices.minByOrNull { haversineMeters(points[it], point) }
        } ?: -1
    }
}
