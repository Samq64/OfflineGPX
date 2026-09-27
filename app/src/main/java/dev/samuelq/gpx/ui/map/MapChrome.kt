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
import dev.samuelq.gpx.ui.format.Formatters
import dev.samuelq.gpx.ui.format.LocalFormatters
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * How far across the map a hundred-odd points of screen actually is - useful on an offline
 * extract with no surrounding context to judge distance against. Snaps to a round distance
 * and changes width to match, rather than showing an awkward number at a fixed width.
 */
@Composable
fun ScaleBar(
    /**
     * The live scale, passed as state rather than a number, and read only here - so a
     * pinch recomposes the bar and not the screen it sits on.
     */
    metersPerPixel: State<Double>,
    modifier: Modifier = Modifier,
) {
    val formatters = LocalFormatters.current
    val density = LocalDensity.current
    val color = MaterialTheme.colorScheme.onSurfaceVariant

    val maxWidthPx = with(density) { MaxBarWidth.toPx() }

    // Snapped behind a `derivedStateOf`: the rounded distance changes a handful of times
    // during a pinch, where the raw scale changes every frame.
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
 * A round distance and the width it occupies. A data class so `derivedStateOf`'s
 * structural equality can skip a downstream rebuild when nothing actually changed.
 */
@Immutable
private data class SnappedScale(val meters: Double, val width: Dp)

/**
 * The snapped distance as a whole number and a unit - not the general distance formatter,
 * whose decimal would imply a precision a snapped bar doesn't have.
 */
private fun scaleLabel(meters: Double, units: UnitSystem): String = when {
    units == UnitSystem.METRIC && meters >= 1000 -> "${(meters / 1000).roundToInt()} km"
    units == UnitSystem.METRIC -> "${meters.roundToInt()} m"
    meters >= METERS_PER_MILE -> "${(meters / METERS_PER_MILE).roundToInt()} mi"
    else -> "${(meters / METERS_PER_FOOT).roundToInt()} ft"
}

/**
 * The largest round distance that still fits: 1, 2 and 5 at every power, as every map
 * scale has used forever.
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

private const val METERS_PER_MILE = Formatters.METERS_PER_MILE
private const val METERS_PER_FOOT = 1 / Formatters.FEET_PER_METER
