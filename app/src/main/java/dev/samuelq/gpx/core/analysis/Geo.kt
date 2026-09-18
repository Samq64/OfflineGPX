package dev.samuelq.gpx.core.analysis

import dev.samuelq.gpx.core.model.TrackPoint
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** IUGG mean Earth radius, in metres. */
private const val EARTH_RADIUS_METERS = 6_371_008.8

/**
 * Great-circle distance between two points, in metres.
 *
 * Haversine, not Vincenty: over tens-of-metres hops the spherical-vs-ellipsoidal error is
 * far below the GPS noise in the same data, and haversine cannot fail to converge.
 *
 * Horizontal only. "Distance" in every mainstream tracker means ground distance, so
 * folding in elevation would disagree with the number on the user's watch.
 */
fun haversineMeters(from: TrackPoint, to: TrackPoint): Double {
    val lat1 = Math.toRadians(from.latitude)
    val lat2 = Math.toRadians(to.latitude)
    val dLat = lat2 - lat1
    val dLon = Math.toRadians(to.longitude - from.longitude)

    val sinHalfLat = sin(dLat / 2.0)
    val sinHalfLon = sin(dLon / 2.0)
    val a = sinHalfLat * sinHalfLat + cos(lat1) * cos(lat2) * sinHalfLon * sinHalfLon
    // min(1.0, ...) guards asin against a > 1 from floating-point error at antipodes.
    return 2.0 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(a)))
}
