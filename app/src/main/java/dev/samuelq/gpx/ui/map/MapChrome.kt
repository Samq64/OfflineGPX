package dev.samuelq.gpx.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.samuelq.gpx.core.model.UnitSystem
import dev.samuelq.gpx.ui.format.LocalFormatters
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * How far across the map a hundred-odd points of screen actually is.
 *
 * Worth having on a map you cannot pan out of and then back into for reference: an offline
 * extract has no surrounding context to judge distance against, and "is that ridge two
 * kilometres away or twenty" is the question a bar answers instantly and a zoom level
 * does not.
 *
 * The bar snaps to a round distance and changes *width* to match, rather than keeping a
 * fixed width and showing an awkward number. A bar labelled 500 m is worth reading; one
 * labelled 437 m is a measurement of the bar rather than of the ground.
 */
@Composable
fun ScaleBar(
    /**
     * The live scale, passed as state rather than as a number.
     *
     * The camera republishes this on every frame of a pan, and it is read here - inside the
     * one composable that draws from it - so that a pinch recomposes a bar and not the
     * screen the bar is sitting on.
     */
    metersPerPixel: State<Double>,
    modifier: Modifier = Modifier,
) {
    val formatters = LocalFormatters.current
    val density = LocalDensity.current
    val color = MaterialTheme.colorScheme.onSurfaceVariant

    val maxWidthPx = with(density) { MaxBarWidth.toPx() }

    // Snapped behind a `derivedStateOf`, so what this composable actually observes is the
    // *rounded* distance and its width. Those change a handful of times during a pinch,
    // while the scale behind them changes on every frame - and re-measuring "500 m" into
    // the same glyphs sixty times a second is the kind of work that shows up as a dropped
    // frame somewhere else entirely.
    val snapped by remember(metersPerPixel, maxWidthPx, formatters.units, density) {
        derivedStateOf {
            val scale = metersPerPixel.value
            if (scale <= 0.0 || !scale.isFinite()) {
                null
            } else {
                val rounded = roundDistance(scale * maxWidthPx, formatters.units)
                SnappedScale(
                    meters = rounded,
                    width = with(density) { (rounded / scale).toFloat().toDp() },
                )
            }
        }
    }

    val bar = snapped ?: return
    if (bar.width <= 0.dp) return

    Column(modifier = modifier, horizontalAlignment = Alignment.Start) {
        Text(
            text = scaleLabel(bar.meters, formatters.units),
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
        // Drawn rather than composed from boxes: it is three lines, and a Canvas keeps it
        // to one node instead of four.
        Canvas(Modifier.width(bar.width).height(BarHeight)) {
            val stroke = with(density) { 1.5.dp.toPx() }
            val top = size.height - stroke / 2f
            drawLine(color, Offset(0f, top), Offset(size.width, top), stroke, StrokeCap.Square)
            drawLine(color, Offset(stroke / 2f, 0f), Offset(stroke / 2f, size.height), stroke)
            drawLine(
                color,
                Offset(size.width - stroke / 2f, 0f),
                Offset(size.width - stroke / 2f, size.height),
                stroke,
            )
        }
    }
}

/**
 * A round distance and the width it occupies: everything the bar redraws from.
 *
 * A data class because `derivedStateOf` compares its result structurally to decide whether
 * anything downstream has to run again. Without equality every frame of a pinch would
 * produce a new-looking value, and the derivation would be an expensive way to change
 * nothing.
 */
@Immutable
private data class SnappedScale(val meters: Double, val width: Dp)

/**
 * The snapped distance as a whole number and a unit.
 *
 * Not the general distance formatter, which is built for readouts and carries a decimal:
 * a bar is snapped to 1, 2 or 5 at some power precisely so that it can be labelled exactly,
 * and "200.0 km" undoes that by implying a precision the bar does not have.
 */
private fun scaleLabel(meters: Double, units: UnitSystem): String = when {
    units == UnitSystem.METRIC && meters >= 1000 -> "${(meters / 1000).roundToInt()} km"
    units == UnitSystem.METRIC -> "${meters.roundToInt()} m"
    meters >= METERS_PER_MILE -> "${(meters / METERS_PER_MILE).roundToInt()} mi"
    else -> "${(meters / METERS_PER_FOOT).roundToInt()} ft"
}

/**
 * The largest round distance that still fits, in whichever units are on show.
 *
 * 1, 2 and 5 at every power, which is what every map scale has used forever - the steps
 * are close enough that the bar is never much shorter than the space it has, and each one
 * is a number you can halve or double in your head.
 */
internal fun roundDistance(maxMeters: Double, units: UnitSystem): Double {
    if (maxMeters <= 0.0) return 0.0

    // Imperial is chosen in feet below a mile and in miles above it, because a scale bar
    // reading "2640 ft" is a conversion rather than a distance.
    val unit = when {
        units == UnitSystem.METRIC -> 1.0
        maxMeters >= METERS_PER_MILE -> METERS_PER_MILE
        else -> METERS_PER_FOOT
    }

    val inUnit = maxMeters / unit
    val power = 10.0.pow(floor(log10(inUnit)))
    val mantissa = inUnit / power
    val snapped = when {
        mantissa >= 5.0 -> 5.0
        mantissa >= 2.0 -> 2.0
        else -> 1.0
    }
    return snapped * power * unit
}

/** The scale bar, tucked into its own corner over the map. */
@Composable
fun MapChrome(metersPerPixel: State<Double>, modifier: Modifier = Modifier) {
    ScaleBar(metersPerPixel, modifier = modifier.padding(start = 12.dp))
}

/** Wide enough to be worth reading, narrow enough to leave the map alone. */
internal val MaxBarWidth = 96.dp
private val BarHeight = 6.dp

private const val METERS_PER_MILE = 1609.344
private const val METERS_PER_FOOT = 0.3048
