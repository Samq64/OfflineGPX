package dev.samuelq.gpx.ui.format

import androidx.compose.runtime.Immutable
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
 * SI to display strings; the only place units are applied. Numbers take the default locale
 * per call; dates are fixed at construction, so a locale or 12/24-hour change needs a new one.
 *
 * @param dateTimePattern, [longDateTimePattern] and [timePattern] from the platform, which knows
 * the 12/24-hour setting; java.time's localized styles only know the locale's habit.
 */
@Immutable
class Formatters(
    val units: UnitSystem,
    locale: Locale = Locale.getDefault(),
    dateTimePattern: String? = null,
    longDateTimePattern: String? = null,
    timePattern: String? = null,
) {
    private val dateTimeFormat = patternOr(dateTimePattern, locale) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    }
    private val longDateTimeFormat = patternOr(longDateTimePattern, locale) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.LONG, FormatStyle.SHORT)
    }
    private val longDateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale)
    private val timeFormat = patternOr(timePattern, locale) {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    }

    fun dateTime(instant: Instant?, zone: ZoneId = ZoneId.systemDefault()): String =
        instant?.let { dateTimeFormat.withZone(zone).format(it) } ?: EMPTY

    /** Month spelt out, where there's room for it. */
    fun longDateTime(instant: Instant?, zone: ZoneId = ZoneId.systemDefault()): String =
        instant?.let { longDateTimeFormat.withZone(zone).format(it) } ?: EMPTY

    fun longDate(instant: Instant?, zone: ZoneId = ZoneId.systemDefault()): String =
        instant?.let { longDateFormat.withZone(zone).format(it) } ?: EMPTY

    fun time(instant: Instant?, zone: ZoneId = ZoneId.systemDefault()): String =
        instant?.let { timeFormat.withZone(zone).format(it) } ?: EMPTY

    private val metric: Boolean get() = units == UnitSystem.METRIC

    private val speedUnit: String get() = if (metric) "km/h" else "mph"
    private val elevationUnit: String get() = if (metric) "m" else "ft"

    /** Display units per SI unit, so chart ticks round in display units. */
    val speedPerMps: Float get() = speedIn(1.0).toFloat()
    val elevationPerMeter: Float get() = elevationIn(1.0).toFloat()
    val distancePerMeter: Float get() = (1.0 / if (metric) METERS_PER_KM else METERS_PER_MILE).toFloat()

    fun distance(meters: Double, locale: Locale = Locale.getDefault()): String = when {
        meters.isNaN() -> EMPTY
        metric -> when {
            abs(meters) < METERS_PER_KM -> String.format(locale, "%.0f m", meters)
            abs(meters) < 10 * METERS_PER_KM ->
                String.format(locale, "%.2f km", meters / METERS_PER_KM)

            else -> String.format(locale, "%,.1f km", meters / METERS_PER_KM)
        }

        else -> {
            val miles = meters / METERS_PER_MILE
            when {
                abs(miles) < 0.1 -> String.format(locale, "%,.0f ft", meters * FEET_PER_METER)
                abs(miles) < 10.0 -> String.format(locale, "%.2f mi", miles)
                else -> String.format(locale, "%,.1f mi", miles)
            }
        }
    }

    /** [distance] to the metre or so at any length, where a step of one point must show. */
    fun preciseDistance(meters: Double, locale: Locale = Locale.getDefault()): String = when {
        meters.isNaN() -> EMPTY
        metric -> if (abs(meters) < METERS_PER_KM) {
            String.format(locale, "%.0f m", meters)
        } else {
            String.format(locale, "%,.3f km", meters / METERS_PER_KM)
        }

        abs(meters / METERS_PER_MILE) < 0.1 -> String.format(locale, "%,.0f ft", meters * FEET_PER_METER)
        else -> String.format(locale, "%,.3f mi", meters / METERS_PER_MILE)
    }

    /**
     * Axis labels with precision from the tick step, so neighbouring ticks never print the
     * same. A lambda since the charts key their layout on its identity.
     */
    fun distanceAxisFor(stepMeters: Float, locale: Locale = Locale.getDefault()): (Float) -> String {
        val perUnit = if (metric) METERS_PER_KM else METERS_PER_MILE
        val format = "%.${axisDecimals(abs(stepMeters) / perUnit)}f ${if (metric) "km" else "mi"}"
        return { meters -> String.format(locale, format, meters / perUnit) }
    }

    fun speed(metersPerSecond: Double, locale: Locale = Locale.getDefault()): String = if (metersPerSecond.isNaN()) {
        EMPTY
    } else {
        String.format(locale, "%.1f $speedUnit", speedIn(metersPerSecond))
    }

    /** See [distanceAxisFor]. */
    fun speedAxisFor(stepMps: Float, locale: Locale = Locale.getDefault()): (Float) -> String {
        val format = "%.${axisDecimals(speedIn(abs(stepMps).toDouble()))}f $speedUnit"
        return { mps -> String.format(locale, format, speedIn(mps.toDouble())) }
    }

    /** Rounded metres or feet. */
    fun meters(value: Double, locale: Locale = Locale.getDefault()): String = if (value.isNaN()) {
        EMPTY
    } else {
        String.format(locale, "%,d $elevationUnit", elevationIn(value).roundToInt())
    }

    /** See [distanceAxisFor]. */
    fun elevationAxisFor(stepMeters: Float, locale: Locale = Locale.getDefault()): (Float) -> String {
        val format = "%,.${axisDecimals(elevationIn(abs(stepMeters).toDouble()))}f $elevationUnit"
        return { meters -> String.format(locale, format, elevationIn(meters.toDouble())) }
    }

    private fun speedIn(metersPerSecond: Double): Double =
        metersPerSecond * SECONDS_PER_HOUR / if (metric) METERS_PER_KM else METERS_PER_MILE

    private fun elevationIn(meters: Double): Double = if (metric) meters else meters * FEET_PER_METER

    companion object {
        /** Between items sharing one line. */
        const val SEPARATOR = "  ·  "

        val Metric = Formatters(UnitSystem.METRIC)

        const val EMPTY = "—"

        private const val MAX_AXIS_DECIMALS = 3

        /** Decimals needed to tell ticks [step] apart. */
        private fun axisDecimals(step: Double): Int = if (step > 0.0 && step.isFinite()) {
            // Tolerance absorbs the SI round trip: 0.1 km comes back as 0.09999999.
            ceil(-log10(step) - 1e-4).toInt().coerceIn(0, MAX_AXIS_DECIMALS)
        } else {
            1
        }

        private const val METERS_PER_KM = 1000.0
        internal const val METERS_PER_MILE = 1609.344
        internal const val FEET_PER_METER = 3.280839895
        private const val SECONDS_PER_HOUR = 3600.0

        /** `h:mm:ss` in the locale's digits; hours even when zero, so it never reads as `m:ss`. */
        fun duration(seconds: Double, locale: Locale = Locale.getDefault()): String {
            if (seconds.isNaN() || seconds < 0) return EMPTY
            val total = seconds.roundToLong()
            return String.format(locale, "%d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60)
        }

        /** [duration] beside a label that says it is one, so a zero hour is dropped: `m:ss`. */
        fun labelledDuration(seconds: Double, locale: Locale = Locale.getDefault()): String {
            val total = seconds.takeIf { it >= 0 }?.roundToLong() ?: return EMPTY
            if (total >= SECONDS_PER_HOUR) return duration(seconds, locale)
            return String.format(locale, "%d:%02d", total / 60, total % 60)
        }

        /** Always kilobytes, rounded up, so tracks compare at a glance. */
        fun kilobytes(bytes: Long, locale: Locale = Locale.getDefault()): String =
            String.format(locale, "%,d kB", (bytes + BYTES_PER_KB - 1) / BYTES_PER_KB)

        private const val BYTES_PER_KB = 1000L

        fun count(value: Int, locale: Locale = Locale.getDefault()): String = String.format(locale, "%,d", value)

        /** A platform pattern java.time can't parse falls back to the locale's style. */
        private fun patternOr(pattern: String?, locale: Locale, style: () -> DateTimeFormatter) =
            pattern?.let { runCatching { DateTimeFormatter.ofPattern(it, locale) }.getOrNull() }
                ?: style().withLocale(locale)
    }
}

/** Equal-width digits for numbers that redraw in place; gappy at large display sizes. */
fun TextStyle.tabularFigures(): TextStyle = copy(fontFeatureSettings = "tnum")
