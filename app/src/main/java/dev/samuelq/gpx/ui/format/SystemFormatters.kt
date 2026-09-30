package dev.samuelq.gpx.ui.format

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import dev.samuelq.gpx.core.model.UnitSystem

/**
 * [Formatters] in the system's locale and 12/24-hour setting. A 12/24-hour change isn't a
 * configuration change, so it shows from the next launch.
 */
@Composable
fun rememberSystemFormatters(units: UnitSystem): Formatters {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    return remember(units, locale) {
        val time = if (DateFormat.is24HourFormat(context)) "Hm" else "hma"
        Formatters(
            units = units,
            locale = locale,
            dateTimePattern = DateFormat.getBestDateTimePattern(locale, "yMMMd$time"),
            timePattern = DateFormat.getBestDateTimePattern(locale, time),
        )
    }
}
