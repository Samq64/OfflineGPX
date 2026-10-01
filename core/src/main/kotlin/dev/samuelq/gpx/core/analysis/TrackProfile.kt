package dev.samuelq.gpx.core.analysis

import dev.samuelq.gpx.core.model.TrackPoint
import java.time.Instant

/** Whole-track summary, in SI units; the UI formats. */
data class TrackStats(
    val startedAt: Instant?,
    val pointCount: Int,
    val distanceMeters: Double,
    /** Wall-clock span from first to last point. */
    val totalDurationSeconds: Double,
    /** Time spent above [TrackAnalyzer.MOVING_SPEED_THRESHOLD_MPS]. */
    val movingDurationSeconds: Double,
    /** Over moving time when there is any. */
    val averageSpeedMps: Double,
    val ascentMeters: Double,
    val descentMeters: Double,
)

/**
 * A track's chart series, precomputed as parallel primitive arrays indexed like [points].
 * Not a `data class`: array equality is O(n), and `remember` keys want identity.
 */
class TrackProfile(
    val points: List<TrackPoint>,
    val segmentStartIndices: IntArray,
    /** All zeroes when [hasTime] is false. */
    val elapsedSeconds: FloatArray,
    /** Cumulative; flat across segment boundaries. */
    val distanceMeters: FloatArray,
    /** Smoothed; `NaN` throughout when [hasTime] is false. */
    val speedMps: FloatArray,
    /** `NaN` where `<ele>` was missing. */
    val elevationMeters: FloatArray,
    val hasTime: Boolean,
    val hasElevation: Boolean,
    val stats: TrackStats,
) {
    /**
     * Index nearest [point], or -1 if empty. By time when available, so a round trip picks
     * the right leg; by distance otherwise.
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
