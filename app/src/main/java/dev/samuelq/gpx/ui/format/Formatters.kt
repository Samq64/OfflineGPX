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
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Every number the user sees. The analysis layer is SI-only, so switching units is this
 * class and nothing else. One instance per unit system, held in [LocalFormatters].
 */
@Immutable
class Formatters(val units: UnitSystem) {

    private val metric: Boolean get() = units == UnitSystem.METRIC

    val speedUnit: String get() = if (metric) "km/h" else "mph"
    val elevationUnit: String get() = if (metric) "m" else "ft"

    /**
     * Display units per SI unit, for the charts: axis ticks are chosen in what the reader
     * sees, since 5 m/s is a round number and 18 km/h is not.
     */
    val speedPerMps: Float get() = speedIn(1.0).toFloat()
    val elevationPerMeter: Float get() = elevationIn(1.0).toFloat()
    val distancePerMeter: Float get() = (1.0 / if (metric) METERS_PER_KM else METERS_PER_MILE).toFloat()

    /** The small unit below the large one, so a 400m walk isn't "0.40 km". */
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

    /**
     * Compact axis form: bare kilometres or miles, no unit. [decimals] is keyed to the gap
     * between ticks, not the value's own magnitude - see [distanceAxisFor].
     */
    fun distanceAxis(
        meters: Float,
        decimals: Int = 1,
        locale: Locale = Locale.getDefault(),
    ): String = String.format(
        locale,
        "%.${decimals.coerceIn(0, MAX_AXIS_DECIMALS)}f",
        meters / if (metric) METERS_PER_KM else METERS_PER_MILE,
    )

    /**
     * Tick labels for a distance axis whose ticks stand [stepMeters] apart. Precision comes
     * from the step, not the value - otherwise two different ticks can print the same label.
     * Returned as a lambda since the charts key their layout on its identity.
     */
    fun distanceAxisFor(stepMeters: Float, locale: Locale = Locale.getDefault()): (Float) -> String {
        val decimals = axisDecimals(
            abs(stepMeters) / if (metric) METERS_PER_KM else METERS_PER_MILE
        )
        return { meters -> distanceAxis(meters, decimals, locale) }
    }

    fun speed(metersPerSecond: Double, locale: Locale = Locale.getDefault()): String =
        if (metersPerSecond.isNaN()) {
            EMPTY
        } else {
            String.format(locale, "%.1f $speedUnit", speedIn(metersPerSecond))
        }

    fun speedAxis(
        metersPerSecond: Float,
        decimals: Int = 0,
        locale: Locale = Locale.getDefault(),
    ): String = String.format(
        locale,
        "%.${decimals.coerceIn(0, MAX_AXIS_DECIMALS)}f",
        speedIn(metersPerSecond.toDouble()),
    )

    /** Tick labels for a speed axis whose ticks stand [stepMps] apart - see [distanceAxisFor]. */
    fun speedAxisFor(stepMps: Float, locale: Locale = Locale.getDefault()): (Float) -> String {
        val decimals = axisDecimals(speedIn(abs(stepMps).toDouble()))
        return { mps -> speedAxis(mps, decimals, locale) }
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

    fun elevationAxis(
        meters: Float,
        decimals: Int = 0,
        locale: Locale = Locale.getDefault(),
    ): String = if (meters.isNaN()) {
        EMPTY
    } else {
        String.format(
            locale,
            "%,.${decimals.coerceIn(0, MAX_AXIS_DECIMALS)}f",
            elevationIn(meters.toDouble()),
        )
    }

    /**
     * Tick labels for an elevation axis whose ticks stand [stepMeters] apart. Same rule as
     * [distanceAxisFor]: whole metres are right for a mountain, wrong for a towpath's
     * half-metre ticks.
     */
    fun elevationAxisFor(stepMeters: Float, locale: Locale = Locale.getDefault()): (Float) -> String {
        val decimals = axisDecimals(elevationIn(abs(stepMeters).toDouble()))
        return { meters -> elevationAxis(meters, decimals, locale) }
    }

    private fun speedIn(metersPerSecond: Double): Double =
        metersPerSecond * SECONDS_PER_HOUR / if (metric) METERS_PER_KM else METERS_PER_MILE

    private fun elevationIn(meters: Double): Double =
        if (metric) meters else meters * FEET_PER_METER

    companion object {
        /** The fallback, and what every preview and test gets unless it says otherwise. */
        val Metric = Formatters(UnitSystem.METRIC)

        const val EMPTY = "—"

        /** Past three, an axis tick is reading out float noise rather than a distance. */
        const val MAX_AXIS_DECIMALS = 3

        /**
         * How many decimals it takes to tell one tick from the next: steps come off the
         * 1-2-5 progression, so this is just where the step sits against the decimal point.
         */
        private fun axisDecimals(step: Double): Int =
            if (step > 0.0 && step.isFinite()) {
                // The tolerance absorbs the SI round trip: 0.1 km comes back as 0.09999999.
                ceil(-log10(step) - 1e-4).toInt().coerceIn(0, MAX_AXIS_DECIMALS)
            } else {
                1
            }

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

        /** Compact form: `h:mm` when [hours], else `m:ss`. */
        fun durationAxis(seconds: Float, hours: Boolean = seconds >= SECONDS_PER_HOUR): String {
            if (seconds.isNaN() || seconds < 0) return EMPTY
            val total = seconds.roundToLong()
            return if (hours) {
                String.format(Locale.ROOT, "%d:%02d", total / 3600, (total % 3600) / 60)
            } else {
                String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60)
            }
        }

        /**
         * Tick labels for a time axis running to [maxSeconds]. One format for every tick,
         * or `33:20` and `1:06` sit side by side meaning minutes and hours.
         */
        fun durationAxisFor(maxSeconds: Float): (Float) -> String {
            val hours = maxSeconds >= SECONDS_PER_HOUR
            return { seconds -> durationAxis(seconds, hours) }
        }

        fun count(value: Int, locale: Locale = Locale.getDefault()): String =
            String.format(locale, "%,d", value)

        fun dateTime(instant: Instant?, zone: ZoneId = ZoneId.systemDefault()): String =
            instant?.let { DATE_TIME.withZone(zone).format(it) } ?: EMPTY

        private val DATE_TIME: DateTimeFormatter =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    }
}

/**
 * The unit system in force. Static because it changes about once in the life of an
 * install, so no call site should pay to observe it in the meantime.
 */
val LocalFormatters = staticCompositionLocalOf { Formatters.Metric }

/**
 * Equal-width digits, for numbers that redraw in place - axis ticks and the scrub readout,
 * where jitter is worse than slightly loose glyphs. Not for the large summary values: at
 * display sizes tabular figures make a number like `121` look gappy.
 */
fun TextStyle.tabularFigures(): TextStyle = copy(fontFeatureSettings = "tnum")
