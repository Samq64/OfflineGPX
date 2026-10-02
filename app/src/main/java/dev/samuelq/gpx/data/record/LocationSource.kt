package dev.samuelq.gpx.data.record

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.getSystemService
import androidx.core.location.LocationCompat
import androidx.core.location.altitude.AltitudeConverterCompat
import dev.samuelq.gpx.core.model.TrackPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.time.Instant

/** Raw `GPS_PROVIDER` fixes; not the fused provider, which needs Play Services. */
class LocationSource(context: Context) {

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService<LocationManager>()

    val isGpsEnabled: Boolean
        get() = manager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true

    /**
     * @param onUnavailable called when location is switched off; the flow stays open.
     * @throws SecurityException if the location permission is not held.
     */
    @SuppressLint("MissingPermission")
    fun fixes(onUnavailable: () -> Unit): Flow<TrackPoint> = callbackFlow<Location> {
        val locationManager = manager
            ?: throw IllegalStateException("No LocationManager on this device")

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                trySend(location)
            }

            // Required below API 30, else AbstractMethodError on some OEM builds.
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = onUnavailable()

            @Deprecated("Removed in API 29, still dispatched by some OEM builds")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }

        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            INTERVAL_MILLIS,
            // No minimum distance: it suppresses callbacks, losing time while stationary.
            // FixFilter handles displacement.
            0f,
            listener,
            Looper.getMainLooper(),
        )

        awaitClose { locationManager.removeUpdates(listener) }
    }
        .map { it.toTrackPoint(elevation = elevationOf(it)) }
        // The conversion may read the geoid model from disk.
        .flowOn(Dispatchers.IO)

    /**
     * GPS altitude is above the WGS84 ellipsoid, up to ~100 m from sea level. Converted with a
     * bundled geoid map, offline; ellipsoid height if that fails.
     */
    private fun elevationOf(location: Location): Double? {
        if (!location.hasAltitude()) return null
        if (!LocationCompat.hasMslAltitude(location)) {
            try {
                AltitudeConverterCompat.addMslAltitudeToLocation(appContext, location)
            } catch (_: IOException) {
            } catch (_: IllegalArgumentException) {
            }
        }
        return if (LocationCompat.hasMslAltitude(location)) LocationCompat.getMslAltitudeMeters(location) else location.altitude
    }

    private companion object {
        const val INTERVAL_MILLIS = 1000L

        fun Location.toTrackPoint(elevation: Double?) = TrackPoint(
            latitude = latitude,
            longitude = longitude,
            elevation = elevation,
            time = Instant.ofEpochMilli(time.takeIf { it > 0 } ?: System.currentTimeMillis()),
            accuracyMeters = if (hasAccuracy()) accuracy.toDouble() else null,
        )
    }
}

