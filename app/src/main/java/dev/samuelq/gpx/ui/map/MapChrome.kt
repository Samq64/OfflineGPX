package dev.samuelq.gpx.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.samuelq.gpx.R
import dev.samuelq.gpx.core.model.UnitSystem
import dev.samuelq.gpx.ui.format.LocalFormatters
import kotlinx.coroutines.launch
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
    metersPerPixel: Double,
    modifier: Modifier = Modifier,
) {
    if (metersPerPixel <= 0.0 || !metersPerPixel.isFinite()) return

    val formatters = LocalFormatters.current
    val density = LocalDensity.current
    val color = MaterialTheme.colorScheme.onSurfaceVariant

    val maxWidthPx = with(density) { MaxBarWidth.toPx() }
    val rounded = roundDistance(metersPerPixel * maxWidthPx, formatters.units)
    val barWidth = with(density) { (rounded / metersPerPixel).toFloat().toDp() }
    if (barWidth <= 0.dp) return

    Column(modifier = modifier, horizontalAlignment = Alignment.Start) {
        Text(
            text = scaleLabel(rounded, formatters.units),
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
        // Drawn rather than composed from boxes: it is three lines, and a Canvas keeps it
        // to one node instead of four.
        Canvas(Modifier.width(barWidth).height(BarHeight)) {
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

/**
 * OpenStreetMap's credit, behind a tap.
 *
 * The licence requires the attribution to be shown, not to be permanently in the way, and
 * a map this small needs its corners. MapLibre's own attribution control is switched off
 * in favour of this: it is the same information in less space, and its tooltip can say
 * where the data came from in words rather than opening a browser at a licence page
 * nobody reads.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttributionBadge(modifier: Modifier = Modifier) {
    val state = rememberTooltipState()
    val scope = rememberCoroutineScope()

    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(stringResource(R.string.map_attribution)) } },
        state = state,
        modifier = modifier,
    ) {
        IconButton(
            onClick = { scope.launch { state.show() } },
            modifier = Modifier.size(BadgeSize),
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = stringResource(R.string.map_attribution_show),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(BadgeIconSize),
            )
        }
    }
}

/** The scale bar and the credit, which share a corner and a baseline. */
@Composable
fun MapChrome(metersPerPixel: Double, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(start = 12.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ScaleBar(metersPerPixel)
        AttributionBadge()
    }
}

/** Wide enough to be worth reading, narrow enough to leave the map alone. */
internal val MaxBarWidth = 96.dp
private val BarHeight = 6.dp
private val BadgeSize = 28.dp
private val BadgeIconSize = 16.dp

private const val METERS_PER_MILE = 1609.344
private const val METERS_PER_FOOT = 0.3048
