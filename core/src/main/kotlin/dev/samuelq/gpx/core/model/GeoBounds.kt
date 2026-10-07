package dev.samuelq.gpx.core.model

/** A latitude/longitude box, in degrees. Never crosses the antimeridian: tracks that do get the long way round. */
public data class GeoBounds(
    val southLatitude: Double,
    val westLongitude: Double,
    val northLatitude: Double,
    val eastLongitude: Double,
)

/** Null for no points. */
public fun TrackPoints.bounds(): GeoBounds? {
    if (size == 0) return null
    var south = Double.POSITIVE_INFINITY
    var west = Double.POSITIVE_INFINITY
    var north = Double.NEGATIVE_INFINITY
    var east = Double.NEGATIVE_INFINITY
    for (i in indices) {
        val latitude = latitude(i)
        val longitude = longitude(i)
        if (latitude < south) south = latitude
        if (latitude > north) north = latitude
        if (longitude < west) west = longitude
        if (longitude > east) east = longitude
    }
    return GeoBounds(south, west, north, east)
}
