package dev.samuelq.gpx.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.samuelq.gpx.R
import dev.samuelq.gpx.core.model.UnitSystem
import dev.samuelq.gpx.ui.format.Formatters
import dev.samuelq.gpx.ui.format.LocalFormatters
import java.util.Locale
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/** Snaps to a round distance and sizes the bar to match. */
@Composable
fun ScaleBar(
    /** State, read only here, so a pinch recomposes the bar and not the screen. */
    metersPerPixel: State<Double>,
    modifier: Modifier = Modifier,
) {
    val formatters = LocalFormatters.current
    val density = LocalDensity.current
    val color = MaterialTheme.colorScheme.onSurfaceVariant

    val maxWidthPx = with(density) { MaxBarWidth.toPx() }

    // The rounded distance changes a few times per pinch; the raw scale every frame.
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

    val label = scaleLabel(bar.meters, formatters.units)
    val spoken = stringResource(R.string.map_scale, label)
    Column(
        modifier = modifier
            .clearAndSetSemantics { contentDescription = spoken }
            // Over routes and water the bare label's contrast isn't guaranteed.
            .background(MaterialTheme.colorScheme.surface.copy(alpha = BACKING_ALPHA), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
        // One Canvas node rather than four boxes.
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

/** A data class so `derivedStateOf` skips unchanged results. */
@Immutable
private data class SnappedScale(val meters: Double, val width: Dp)

/** Whole number and unit; the general formatter's decimal would imply false precision. */
private fun scaleLabel(meters: Double, units: UnitSystem, locale: Locale = Locale.getDefault()): String {
    val (value, unit) = when {
        units == UnitSystem.METRIC && meters >= 1000 -> meters / 1000 to "km"
        units == UnitSystem.METRIC -> meters to "m"
        meters >= METERS_PER_MILE -> meters / METERS_PER_MILE to "mi"
        else -> meters / METERS_PER_FOOT to "ft"
    }
    return String.format(locale, "%,d %s", value.roundToInt(), unit)
}

/** The largest 1-2-5 round distance that fits. */
private fun roundDistance(maxMeters: Double, units: UnitSystem): Double {
    if (maxMeters <= 0.0) return 0.0

    // Feet below a mile, miles above: "2640 ft" is a conversion, not a distance.
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

private val MaxBarWidth = 96.dp
private val BarHeight = 6.dp

private const val METERS_PER_MILE = Formatters.METERS_PER_MILE
private const val METERS_PER_FOOT = 1 / Formatters.FEET_PER_METER

private const val BACKING_ALPHA = 0.85f
