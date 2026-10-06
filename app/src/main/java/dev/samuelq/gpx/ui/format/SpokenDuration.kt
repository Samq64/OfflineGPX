package dev.samuelq.gpx.ui.format

import android.content.res.Resources
import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import dev.samuelq.gpx.R
import kotlin.math.roundToLong

/** [Formatters.duration] in words, since "1:02:03" is read as a time of day. */
fun Resources.spokenDuration(seconds: Double): String {
    if (seconds.isNaN() || seconds < 0) return getString(R.string.value_unknown)
    val total = seconds.roundToLong()
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    val parts = listOfNotNull(
        hours.takeIf { it > 0 }?.let { Measure(it, MeasureUnit.HOUR) },
        minutes.takeIf { it > 0 || hours > 0 }?.let { Measure(it, MeasureUnit.MINUTE) },
        // Seconds matter under an hour, not past it.
        secs.takeIf { hours == 0L }?.let { Measure(it, MeasureUnit.SECOND) },
    )
    return MeasureFormat.getInstance(configuration.locales[0], MeasureFormat.FormatWidth.WIDE)
        .formatMeasures(*parts.toTypedArray())
}

/** [Formatters.kilobytes] in words, rounded up the same way. */
fun Resources.spokenKilobytes(bytes: Long): String =
    MeasureFormat.getInstance(configuration.locales[0], MeasureFormat.FormatWidth.WIDE)
        .format(Measure((bytes + 999) / 1000, MeasureUnit.KILOBYTE))
