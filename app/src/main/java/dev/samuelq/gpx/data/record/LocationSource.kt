package dev.samuelq.gpx.data.record

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.getSystemService
import androidx.core.location.LocationCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.altitude.AltitudeConverterCompat
import dev.samuelq.gpx.core.model.TrackPoint
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** Raw `GPS_PROVIDER` fixes; not the fused provider, which needs Play Services. Stateless, so shared. */
class LocationSource(
    context: Context,
    /** For the geoid model's disk reads; a parameter so tests can substitute one. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService<LocationManager>()

    val isGpsEnabled: Boolean
        get() = manager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true

    /**
     * @param onAvailable called with false when location is switched off and true when it's back;
     *   the flow stays open throughout.
     * @throws SecurityException if the location permission is not held.
     */
    @SuppressLint("MissingPermission")
    fun fixes(onAvailable: (Boolean) -> Unit): Flow<TrackPoint> = callbackFlow<Location> {
        val locationManager = manager
            ?: throw IllegalStateException("No LocationManager on this device")

        // Compat, which supplies the callbacks that are abstract below API 30.
        val listener = object : LocationListenerCompat {
            override fun onLocationChanged(location: Location) {
                trySend(location)
            }

            override fun onProviderDisabled(provider: String) = onAvailable(false)

            override fun onProviderEnabled(provider: String) = onAvailable(true)
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
        .flowOn(io)

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
        return if (LocationCompat.hasMslAltitude(
                location,
            )
        ) {
            LocationCompat.getMslAltitudeMeters(location)
        } else {
            location.altitude
        }
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
