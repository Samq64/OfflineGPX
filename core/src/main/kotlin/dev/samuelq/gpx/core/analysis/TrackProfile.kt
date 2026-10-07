package dev.samuelq.gpx.core.analysis

import dev.samuelq.gpx.core.model.TrackPoint
import dev.samuelq.gpx.core.model.TrackPoints
import java.time.Instant
import kotlin.math.abs

/** Whole-track summary, in SI units; the UI formats. */
public data class TrackStats(
    val startedAt: Instant?,
    val pointCount: Int,
    val distanceMeters: Double,
    /** Wall-clock span from first to last point. */
    val totalDurationSeconds: Double,
    /** Time spent above [TrackAnalyzer.MOVING_SPEED_THRESHOLD_MPS]. */
    val movingDurationSeconds: Double,
    /** Over moving time; zero without any, as the sheet shows it beside moving time. */
    val averageSpeedMps: Double,
    val ascentMeters: Double,
    val descentMeters: Double,
)

/**
 * A track's chart series, precomputed as parallel primitive arrays indexed like [points].
 * Not a `data class`: array equality is O(n), and `remember` keys want identity.
 */
public class TrackProfile(
    /** Cut at the file's segments and at gaps the clock shows, so those are breaks here too. */
    public val points: TrackPoints,
    /** All zeroes when [hasTime] is false. */
    public val elapsedSeconds: FloatArray,
    /** Cumulative; flat across segment boundaries. */
    public val distanceMeters: FloatArray,
    /** Smoothed; `NaN` throughout when [hasTime] is false. */
    public val speedMps: FloatArray,
    /** `NaN` where `<ele>` was missing. */
    public val elevationMeters: FloatArray,
    public val hasTime: Boolean,
    public val hasElevation: Boolean,
    public val stats: TrackStats,
) {
    /**
     * Index nearest [point], or -1 if empty. By time when available, so a round trip picks
     * the right leg; by distance otherwise.
     */
    public fun indexOf(point: TrackPoint): Int {
        val at = point.time?.toEpochMilli()
        var best = -1
        var bestScore = Double.MAX_VALUE
        for (i in points.indices) {
            val score = if (at != null && hasTime) {
                abs(points.timeMillis(i) - at).toDouble()
            } else {
                haversineMeters(points.latitude(i), points.longitude(i), point.latitude, point.longitude)
            }
            if (score < bestScore) {
                bestScore = score
                best = i
            }
        }
        return best
    }

    /** Distance from the start to the point nearest [point], or null if empty. */
    public fun distanceTo(point: TrackPoint): Double? =
        indexOf(point).takeIf { it >= 0 }?.let { distanceMeters[it].toDouble() }
}
