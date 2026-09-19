package dev.samuelq.gpx.data.record

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.getSystemService
import dev.samuelq.gpx.core.model.TrackPoint
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.time.Instant

/**
 * Raw GPS fixes, straight from the platform.
 *
 * Deliberately not `FusedLocationProviderClient`: that lives in `play-services-location`,
 * which drags in Google's stack and is exactly the kind of dependency this app's
 * permission argument exists to avoid. `GPS_PROVIDER` at 1 Hz is what the analyzer wants
 * anyway - it does its own smoothing, and the fused provider's sensor blending would only
 * hide the noise the speed window is designed to handle.
 */
class LocationSource(context: Context) {

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService<LocationManager>()

    val isGpsEnabled: Boolean
        get() = manager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true

    /**
     * Fixes until the collector stops.
     *
     * @throws SecurityException if the location permission is not held - the caller checks
     *   first, and a throw here means a bug rather than a user decision.
     */
    @SuppressLint("MissingPermission")
    fun fixes(intervalMillis: Long = DEFAULT_INTERVAL_MILLIS): Flow<TrackPoint> = callbackFlow {
        val locationManager = manager
            ?: throw IllegalStateException("No LocationManager on this device")

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                trySend(location.toTrackPoint())
            }

            // Required on API < 30 and harmless above it; without them the platform
            // throws AbstractMethodError on some OEM builds.
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit

            @Deprecated("Removed in API 29, still dispatched by some OEM builds")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }

        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            intervalMillis,
            // No minimum distance: standing still is data. The analyzer decides what
            // counts as moving, and a filter here would silently shorten paused time.
            0f,
            listener,
            Looper.getMainLooper(),
        )

        awaitClose { locationManager.removeUpdates(listener) }
    }

    private companion object {
        const val DEFAULT_INTERVAL_MILLIS = 1000L

        fun Location.toTrackPoint() = TrackPoint(
            latitude = latitude,
            longitude = longitude,
            // hasAltitude() is false indoors and on some fixes; a null reads as "not
            // recorded", which the elevation chart already handles.
            elevation = if (hasAltitude()) altitude else null,
            time = Instant.ofEpochMilli(time.takeIf { it > 0 } ?: System.currentTimeMillis()),
        )
    }
}
