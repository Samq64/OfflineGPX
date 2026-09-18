package dev.samuelq.gpx.ui.format

import androidx.compose.ui.text.TextStyle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Every number the user sees. The analysis layer is SI-only, so an imperial toggle means
 * changing this file and nothing else.
 */
object Formatters {

    private const val METERS_PER_KM = 1000.0
    private const val SECONDS_PER_HOUR = 3600.0

    /** Metres below 1 km, kilometres above - nobody reads "12437 m". */
    fun distance(meters: Double, locale: Locale = Locale.getDefault()): String = when {
        meters.isNaN() -> EMPTY
        abs(meters) < METERS_PER_KM -> String.format(locale, "%.0f m", meters)
        abs(meters) < 10 * METERS_PER_KM -> String.format(locale, "%.2f km", meters / METERS_PER_KM)
        else -> String.format(locale, "%.1f km", meters / METERS_PER_KM)
    }

    /** Compact axis form: bare kilometres, no unit suffix (the axis title carries it). */
    fun distanceAxis(meters: Float, locale: Locale = Locale.getDefault()): String {
        val km = meters / METERS_PER_KM
        return when {
            km < 1.0 -> String.format(locale, "%.1f", km)
            km < 100.0 -> String.format(locale, "%.1f", km)
            else -> String.format(locale, "%.0f", km)
        }
    }

    /** `h:mm:ss` past an hour, `m:ss` below it. */
    fun duration(seconds: Double): String {
        if (seconds.isNaN() || seconds < 0) return EMPTY
        val total = seconds.roundToLong()
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val secs = total % 60
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, secs)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, secs)
        }
    }

    /** Axis form: drops seconds once the track is long enough that they're noise. */
    fun durationAxis(seconds: Float): String {
        if (seconds.isNaN() || seconds < 0) return EMPTY
        val total = seconds.roundToLong()
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val secs = total % 60
        return when {
            hours > 0 -> String.format(Locale.ROOT, "%d:%02d", hours, minutes)
            else -> String.format(Locale.ROOT, "%d:%02d", minutes, secs)
        }
    }

    fun speed(metersPerSecond: Double, locale: Locale = Locale.getDefault()): String =
        if (metersPerSecond.isNaN()) EMPTY
        else String.format(locale, "%.1f km/h", metersPerSecond * SECONDS_PER_HOUR / METERS_PER_KM)

    fun speedAxis(metersPerSecond: Float, locale: Locale = Locale.getDefault()): String {
        val kmh = metersPerSecond * SECONDS_PER_HOUR / METERS_PER_KM
        return if (kmh < 10f) String.format(locale, "%.1f", kmh)
        else String.format(locale, "%.0f", kmh)
    }

    fun elevation(meters: Double, locale: Locale = Locale.getDefault()): String =
        if (meters.isNaN()) EMPTY else String.format(locale, "%,d m", meters.roundToInt())

    fun elevationAxis(meters: Float, locale: Locale = Locale.getDefault()): String =
        if (meters.isNaN()) EMPTY else String.format(locale, "%,d", meters.roundToInt())

    fun count(value: Int, locale: Locale = Locale.getDefault()): String =
        String.format(locale, "%,d", value)

    fun dateTime(instant: Instant?, zone: ZoneId = ZoneId.systemDefault()): String =
        instant?.let { DATE_TIME.withZone(zone).format(it) } ?: EMPTY

    fun timeOfDay(instant: Instant?, zone: ZoneId = ZoneId.systemDefault()): String =
        instant?.let { TIME_OF_DAY.withZone(zone).format(it) } ?: EMPTY

    const val EMPTY = "—"

    // Stable singletons, not `Formatters::speedAxis` at the call site: a reference to a
    // function with default arguments becomes a *new* lambda every recomposition, which
    // would defeat the chart's `remember` keys and rebuild its path on every scrub frame.
    val SpeedAxis: (Float) -> String = { speedAxis(it) }
    val ElevationAxis: (Float) -> String = { elevationAxis(it) }
    val DurationAxis: (Float) -> String = { durationAxis(it) }
    val DistanceAxis: (Float) -> String = { distanceAxis(it) }

    private val DATE_TIME: DateTimeFormatter =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    private val TIME_OF_DAY: DateTimeFormatter =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM)
}

/**
 * Equal-width digits, for numbers that redraw in place - axis ticks and the scrub readout,
 * where jitter is worse than slightly loose glyphs. Not for the large summary values: at
 * display sizes tabular figures make a number like `121` look gappy.
 */
fun TextStyle.tabularFigures(): TextStyle = copy(fontFeatureSettings = "tnum")
