package dev.samuelq.gpx.ui.format

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import dev.samuelq.gpx.core.model.UnitSystem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Every number the user sees.
 *
 * The analysis layer is SI-only and stays that way, so switching units is this class and
 * nothing else - which was the standing promise, and is why it is a class now rather than
 * an object. One instance per unit system, held in [LocalFormatters], so a call site never
 * has to pass the preference around or know it exists.
 *
 * The axis lambdas are instance properties rather than functions for a reason spelled out
 * at the bottom: the charts key their layout on lambda identity.
 */
@Immutable
class Formatters(val units: UnitSystem) {

    private val metric: Boolean get() = units == UnitSystem.METRIC

    /** What the distance axis ticks are in. The ticks themselves are bare numbers. */
    val distanceUnit: String get() = if (metric) "km" else "mi"
    val speedUnit: String get() = if (metric) "km/h" else "mph"
    val elevationUnit: String get() = if (metric) "m" else "ft"

    /**
     * The small unit below the large one, so a 400m walk is not "0.40 km".
     *
     * The imperial break is a tenth of a mile rather than a whole one: 0.9 miles in feet
     * is a four-digit number nobody reads as a distance.
     */
    fun distance(meters: Double, locale: Locale = Locale.getDefault()): String = when {
        meters.isNaN() -> EMPTY
        metric -> when {
            abs(meters) < METERS_PER_KM -> String.format(locale, "%.0f m", meters)
            abs(meters) < 10 * METERS_PER_KM ->
                String.format(locale, "%.2f km", meters / METERS_PER_KM)

            else -> String.format(locale, "%.1f km", meters / METERS_PER_KM)
        }

        else -> {
            val miles = meters / METERS_PER_MILE
            when {
                abs(miles) < 0.1 -> String.format(locale, "%,.0f ft", meters * FEET_PER_METER)
                abs(miles) < 10.0 -> String.format(locale, "%.2f mi", miles)
                else -> String.format(locale, "%.1f mi", miles)
            }
        }
    }

    /** Compact axis form: bare kilometres or miles, no unit (the axis title carries it). */
    fun distanceAxis(meters: Float, locale: Locale = Locale.getDefault()): String {
        val value = meters / if (metric) METERS_PER_KM else METERS_PER_MILE
        return if (abs(value) < 100.0) {
            String.format(locale, "%.1f", value)
        } else {
            String.format(locale, "%.0f", value)
        }
    }

    fun speed(metersPerSecond: Double, locale: Locale = Locale.getDefault()): String =
        if (metersPerSecond.isNaN()) {
            EMPTY
        } else {
            String.format(locale, "%.1f $speedUnit", speedIn(metersPerSecond))
        }

    fun speedAxis(metersPerSecond: Float, locale: Locale = Locale.getDefault()): String {
        val value = speedIn(metersPerSecond.toDouble())
        return if (value < 10.0) {
            String.format(locale, "%.1f", value)
        } else {
            String.format(locale, "%.0f", value)
        }
    }

    /** Plain rounded metres or feet. Elevation is the usual caller; GPS accuracy the other. */
    fun meters(value: Double, locale: Locale = Locale.getDefault()): String =
        if (value.isNaN()) {
            EMPTY
        } else {
            String.format(locale, "%,d $elevationUnit", elevationIn(value).roundToInt())
        }

    fun elevation(meters: Double, locale: Locale = Locale.getDefault()): String =
        meters(meters, locale)

    fun elevationAxis(meters: Float, locale: Locale = Locale.getDefault()): String =
        if (meters.isNaN()) {
            EMPTY
        } else {
            String.format(locale, "%,d", elevationIn(meters.toDouble()).roundToInt())
        }

    private fun speedIn(metersPerSecond: Double): Double =
        metersPerSecond * SECONDS_PER_HOUR / if (metric) METERS_PER_KM else METERS_PER_MILE

    private fun elevationIn(meters: Double): Double =
        if (metric) meters else meters * FEET_PER_METER

    /**
     * Stable singletons, not `this::speedAxis` at the call site: a reference to a function
     * with default arguments becomes a *new* lambda every recomposition, which would
     * defeat the chart's `remember` keys and rebuild its path on every scrub frame. They
     * change identity when the unit system does, which is exactly when the axis labels
     * need redrawing.
     */
    val SpeedAxis: (Float) -> String = { speedAxis(it) }
    val ElevationAxis: (Float) -> String = { elevationAxis(it) }
    val DurationAxis: (Float) -> String = { durationAxis(it) }
    val DistanceAxis: (Float) -> String = { distanceAxis(it) }

    companion object {
        /** The fallback, and what every preview and test gets unless it says otherwise. */
        val Metric = Formatters(UnitSystem.METRIC)

        const val EMPTY = "—"

        private const val METERS_PER_KM = 1000.0
        private const val METERS_PER_MILE = 1609.344
        private const val FEET_PER_METER = 3.280839895
        private const val SECONDS_PER_HOUR = 3600.0

        /** `h:mm:ss` past an hour, `m:ss` below it. The one thing units do not change. */
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

        fun count(value: Int, locale: Locale = Locale.getDefault()): String =
            String.format(locale, "%,d", value)

        fun dateTime(instant: Instant?, zone: ZoneId = ZoneId.systemDefault()): String =
            instant?.let { DATE_TIME.withZone(zone).format(it) } ?: EMPTY

        fun timeOfDay(instant: Instant?, zone: ZoneId = ZoneId.systemDefault()): String =
            instant?.let { TIME_OF_DAY.withZone(zone).format(it) } ?: EMPTY

        private val DATE_TIME: DateTimeFormatter =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        private val TIME_OF_DAY: DateTimeFormatter =
            DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM)
    }
}

/**
 * The unit system in force, for everything that renders a number.
 *
 * Static because it changes about once in the life of an install: when it does, the whole
 * tree below it should redraw, and no call site should pay to observe it in the meantime.
 */
val LocalFormatters = staticCompositionLocalOf { Formatters.Metric }

/**
 * Equal-width digits, for numbers that redraw in place - axis ticks and the scrub readout,
 * where jitter is worse than slightly loose glyphs. Not for the large summary values: at
 * display sizes tabular figures make a number like `121` look gappy.
 */
fun TextStyle.tabularFigures(): TextStyle = copy(fontFeatureSettings = "tnum")
