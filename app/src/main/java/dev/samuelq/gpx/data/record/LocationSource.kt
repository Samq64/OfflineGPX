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
 * Raw GPS fixes, straight from the platform. Deliberately not
 * `FusedLocationProviderClient`, which lives in `play-services-location` and drags in
 * Google's stack; `GPS_PROVIDER` at 1 Hz is what the analyzer's own smoothing wants anyway.
 */
class LocationSource(context: Context) {

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService<LocationManager>()

    val isGpsEnabled: Boolean
        get() = manager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true

    /**
     * Fixes until the collector stops.
     *
     * @param onUnavailable called when location is switched off mid-recording. The flow
     *   stays open - the provider can come back - but the caller can't tell that silence
     *   from a slow fix, so it has to be told.
     * @throws SecurityException if the location permission is not held. The caller checks
     *   first, so a throw here is a bug rather than a user decision.
     */
    @SuppressLint("MissingPermission")
    fun fixes(onUnavailable: () -> Unit): Flow<TrackPoint> = callbackFlow {
        val locationManager = manager
            ?: throw IllegalStateException("No LocationManager on this device")

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                trySend(location.toTrackPoint())
            }

            // Required on API < 30 and harmless above it; without them the platform
            // throws AbstractMethodError on some OEM builds.
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = onUnavailable()

            @Deprecated("Removed in API 29, still dispatched by some OEM builds")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }

        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            INTERVAL_MILLIS,
            // No minimum distance, even though the recorder does apply one. This
            // parameter suppresses the *callback*, so a stationary rider would go silent
            // and the recorder would lose the seconds along with the metres. Standing
            // still is data; FixFilter drops the movement and keeps the time.
            0f,
            listener,
            Looper.getMainLooper(),
        )

        awaitClose { locationManager.removeUpdates(listener) }
    }

    private companion object {
        const val INTERVAL_MILLIS = 1000L

        /** Everything is passed on, believable or not - filtering is [FixFilter]'s job. */
        fun Location.toTrackPoint() = TrackPoint(
            latitude = latitude,
            longitude = longitude,
            // hasAltitude() is false indoors and on some fixes; a null reads as "not
            // recorded", which the elevation chart already handles.
            elevation = if (hasAltitude()) altitude else null,
            time = Instant.ofEpochMilli(time.takeIf { it > 0 } ?: System.currentTimeMillis()),
            accuracyMeters = if (hasAccuracy()) accuracy.toDouble() else null,
        )
    }
}
