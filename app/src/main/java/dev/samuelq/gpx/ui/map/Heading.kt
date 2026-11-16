package dev.samuelq.gpx.ui.map

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import dev.samuelq.gpx.core.model.TrackPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * The direction of travel, in degrees clockwise from true north: the course between fixes when
 * moving fast enough for it to be sound, else the compass, smoothed so the map turns steadily.
 * Compass readings are the clock; without a compass, courses are taken as they come.
 */
internal class HeadingFilter(private val timeConstantMillis: Double = TIME_CONSTANT_MS) {
    private var x = 0.0
    private var y = 0.0
    private var started = false

    /** Null until the clock starts, at the first reading after [startAt]. */
    private var lastAt: Long? = null
    private var course = 0.0
    private var courseUntil = Long.MIN_VALUE

    /** Where to start from, as the map's current heading, so turning on swings rather than jumps. */
    fun startAt(degrees: Double) {
        val radians = Math.toRadians(degrees)
        x = cos(radians)
        y = sin(radians)
        started = true
        lastAt = null
        courseUntil = Long.MIN_VALUE
    }

    /** A compass reading at [atMillis]; the smoothed heading. */
    fun compass(degrees: Double, atMillis: Long): Double {
        val target = if (atMillis < courseUntil) course else degrees
        return step(target, atMillis)
    }

    /**
     * A course from fixes [meters] apart over [seconds], held until the next is due: twice that
     * gap, as a recording's trace comes a few fixes at a time. Null if too slow to trust, which
     * hands back to the compass at once: a stop.
     */
    fun course(degrees: Double, meters: Double, seconds: Double, atMillis: Long): Double? {
        if (seconds <= 0) return null
        if (meters < COURSE_MIN_METERS || meters / seconds < COURSE_MIN_SPEED) {
            courseUntil = Long.MIN_VALUE
            return null
        }
        course = degrees
        courseUntil = atMillis + (seconds * 2_000).toLong().coerceIn(COURSE_HOLD_MS, COURSE_HOLD_MAX_MS)
        return step(degrees, atMillis)
    }

    private fun step(target: Double, atMillis: Long): Double {
        val radians = Math.toRadians(target)
        val last = lastAt
        val weight = when {
            // Nothing to swing from.
            !started -> 1.0
            last == null -> 0.0
            else -> 1 - exp(-(atMillis - last).coerceAtLeast(0) / timeConstantMillis)
        }
        x += (cos(radians) - x) * weight
        y += (sin(radians) - y) * weight
        started = true
        lastAt = atMillis
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    companion object {
        /** Long enough to calm a hand's shake, short enough to keep up with a turn. */
        private const val TIME_CONSTANT_MS = 300.0

        /** A brisk walk: below it, GPS jitter swings the course. */
        private const val COURSE_MIN_SPEED = 2.0
        private const val COURSE_MIN_METERS = 2.0

        /** For a fix a second, with room for one missed. */
        private const val COURSE_HOLD_MS = 2_500L

        /** Past this, a course is too old to steer by. */
        private const val COURSE_HOLD_MAX_MS = 15_000L
    }
}

/** The shortest turn from [from] to [to], in degrees from -180 to 180. */
internal fun turn(from: Double, to: Double): Double = ((to - from) % 360 + 540) % 360 - 180

/**
 * The phone's heading from true north while [enabled], as [onHeading] with the reading's
 * elapsed-realtime milliseconds. Turned for the screen's rotation, and from magnetic north by
 * the declination at [near].
 */
@Composable
internal fun HeadingSensorEffect(
    enabled: Boolean,
    near: TrackPoint?,
    onHeading: (degrees: Double, atMillis: Long) -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val report by rememberUpdatedState(onHeading)
    val declination by rememberUpdatedState(near?.let(::declinationAt) ?: 0.0)
    DisposableEffect(enabled, context, view) {
        val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        // Fused, so no permission and steadier than magnetometer and accelerometer read apart.
        val sensor = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (!enabled || sensor == null) return@DisposableEffect onDispose {}
        val listener = object : SensorEventListener {
            private val rotation = FloatArray(9)
            private val remapped = FloatArray(9)

            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                remapForScreen(rotation, remapped, view)
                report((facing(remapped) + declination + 360) % 360, event.timestamp / 1_000_000)
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sensors.unregisterListener(listener) }
    }
}

private fun remapForScreen(rotation: FloatArray, out: FloatArray, view: View) {
    val (x, y) = when (view.display?.rotation) {
        Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
        Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
        Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
        else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
    }
    SensorManager.remapCoordinateSystem(rotation, x, y, out)
}

/**
 * Where the screen's top and the phone's back point, summed on the level, from magnetic north:
 * the top while it lies flat, the back while held upright, and smooth between. getOrientation's
 * azimuth swings wildly as the phone nears upright, which is how a map is often held on foot.
 */
internal fun facing(rotation: FloatArray): Double {
    // Columns are the phone's axes in east, north, up: the top is y, the back is -z.
    val east = rotation[1] - rotation[2]
    val north = rotation[4] - rotation[5]
    return Math.toDegrees(atan2(east.toDouble(), north.toDouble()))
}

private fun declinationAt(point: TrackPoint): Double = GeomagneticField(
    point.latitude.toFloat(),
    point.longitude.toFloat(),
    (point.elevation ?: 0.0).toFloat(),
    System.currentTimeMillis(),
).declination.toDouble()
