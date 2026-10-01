package dev.samuelq.gpx.ui.format

import android.content.res.Resources
import dev.samuelq.gpx.R
import kotlin.math.roundToLong

/** [Formatters.duration] in words, since "1:02:03" is read as a time of day. */
fun Resources.spokenDuration(seconds: Double): String {
    if (seconds.isNaN() || seconds < 0) return getString(R.string.value_unknown)
    val total = seconds.roundToLong()
    val hours = (total / 3600).toInt()
    val minutes = ((total % 3600) / 60).toInt()
    val secs = (total % 60).toInt()
    return listOfNotNull(
        hours.takeIf { it > 0 }?.let { getQuantityString(R.plurals.duration_hours, it, it) },
        minutes.takeIf { it > 0 || hours > 0 }?.let { getQuantityString(R.plurals.duration_minutes, it, it) },
        // Seconds matter under an hour, not past it.
        secs.takeIf { hours == 0 }?.let { getQuantityString(R.plurals.duration_seconds, it, it) },
    ).joinToString(" ")
}
