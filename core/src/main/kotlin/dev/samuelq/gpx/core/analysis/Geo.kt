package dev.samuelq.gpx.core.analysis

import dev.samuelq.gpx.core.model.TrackPoint
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** IUGG mean Earth radius, in metres. */
private const val EARTH_RADIUS_METERS = 6_371_008.8

/**
 * Horizontal great-circle distance in metres. Haversine, not Vincenty: the spherical error
 * is far below GPS noise and it cannot fail to converge.
 */
public fun haversineMeters(from: TrackPoint, to: TrackPoint): Double =
    haversineMeters(from.latitude, from.longitude, to.latitude, to.longitude)

public fun haversineMeters(
    fromLatitude: Double,
    fromLongitude: Double,
    toLatitude: Double,
    toLongitude: Double,
): Double {
    val lat1 = Math.toRadians(fromLatitude)
    val lat2 = Math.toRadians(toLatitude)
    val dLat = lat2 - lat1
    val dLon = Math.toRadians(toLongitude - fromLongitude)

    val sinHalfLat = sin(dLat / 2.0)
    val sinHalfLon = sin(dLon / 2.0)
    val a = sinHalfLat * sinHalfLat + cos(lat1) * cos(lat2) * sinHalfLon * sinHalfLon
    // Guards asin against rounding past 1 at antipodes.
    return 2.0 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(a)))
}

/** Initial great-circle bearing, in degrees clockwise from north, 0 until 360. */
public fun bearingDegrees(from: TrackPoint, to: TrackPoint): Double {
    val lat1 = Math.toRadians(from.latitude)
    val lat2 = Math.toRadians(to.latitude)
    val dLon = Math.toRadians(to.longitude - from.longitude)
    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}
