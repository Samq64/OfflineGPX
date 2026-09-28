package dev.samuelq.gpx.ui.track

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.samuelq.gpx.R
import dev.samuelq.gpx.data.track.LoadedTrack
import dev.samuelq.gpx.ui.chart.ChartAxisGroup
import dev.samuelq.gpx.ui.chart.ChartSeries
import dev.samuelq.gpx.ui.chart.ProfileChart
import dev.samuelq.gpx.ui.chart.axisScale
import dev.samuelq.gpx.ui.chart.timeAxisScale
import dev.samuelq.gpx.ui.chart.yScale
import dev.samuelq.gpx.ui.chart.zoomView
import dev.samuelq.gpx.ui.format.Formatters
import dev.samuelq.gpx.ui.format.LocalFormatters
import dev.samuelq.gpx.ui.theme.LocalChartColors

private val SheetPadding = 20.dp

/**
 * The collapsed height before a track has loaded, and the least it can be after. Once one
 * has, the peek is measured from what it shows instead; see `onPeekHeightChange`.
 */
val TrackSheetPeekHeight = 128.dp

/**
 * Everything the sheet can do to the track it is showing. Null for a track that arrived
 * through an intent - no library row, so the sheet leaves the menu off rather than
 * offering actions that would all have to refuse.
 */
@Immutable
class TrackActions(
    val onRename: () -> Unit,
    val onShare: () -> Unit,
    val onHide: () -> Unit,
    val onDelete: () -> Unit,
)

/**
 * What the map knows about the track it is focused on. A sheet rather than a screen: the
 * route is already drawn on the map behind it. Height is the only thing that hides
 * anything - there's no separate fold/details button stacking a second disclosure system
 * on top of the sheet's own; the column just runs on, and dragging reveals more of it.
 */
@Composable
fun TrackSheet(
    loaded: LoadedTrack,
    routeColor: Color,
    maxHeight: Dp,
    selectedIndex: Int?,
    onSelectedIndexChange: (Int?) -> Unit,
    useTimeAxis: Boolean,
    onAxisChange: (Boolean) -> Unit,
    actions: TrackActions?,
    /** The height of the part shown collapsed - name, numbers, date - so the peek fits it. */
    onPeekHeightChange: (Dp) -> Unit,
    /** Shown as a close button, for the landscape panel. */
    onClose: (() -> Unit)? = null,
) {
    val density = LocalDensity.current
    val profile = loaded.profile
    val stats = profile.stats
    val chartColors = LocalChartColors.current
    val formatters = LocalFormatters.current

    val xValues = if (useTimeAxis) profile.elapsedSeconds else profile.distanceMeters
    val xDomain = (xValues.firstOrNull() ?: 0f)..(xValues.lastOrNull() ?: 1f)
    // The stretch a pinch has zoomed to. A new track or axis starts zoomed out again.
    var xView by remember(profile, useTimeAxis) { mutableStateOf(xDomain) }
    // New per track and unit system, since the widest label only ever grows within one.
    val axisGroup = remember(profile, formatters) { ChartAxisGroup() }
    val onZoom: (Float, Float, Float) -> Unit = remember(profile, useTimeAxis) {
        { anchor, zoom, pan -> xView = zoomView(xView, xDomain, anchor, zoom, pan) }
    }
    // One view, shared by both charts, so the same pixel column is the same moment in
    // each and the scrubber means the same thing in both - and on the route behind.
    val xScale = remember(xView, useTimeAxis, formatters) {
        if (useTimeAxis) {
            timeAxisScale(xView.start, xView.endInclusive)
        } else {
            axisScale(xView.start, xView.endInclusive, perUnit = formatters.distancePerMeter)
        }
    }
    // Keyed on the scale as well as the units: the ticks are labelled to whatever precision
    // tells one of them from the next, and that is a property of the scale.
    val formatX: (Float) -> String = remember(formatters, useTimeAxis, xScale) {
        if (useTimeAxis) {
            Formatters.durationAxisFor(xScale.max)
        } else {
            formatters.distanceAxisFor(xScale.step)
        }
    }

    // With units, unlike the axis formatters, since a tooltip is read on its own. Remembered
    // because the chart keys its measured layout on the identity of these.
    val speedValue: (Float) -> String =
        remember(formatters) { { formatters.speed(it.toDouble()) } }
    val elevationValue: (Float) -> String =
        remember(formatters) { { formatters.meters(it.toDouble()) } }
    val positionValue: (Float) -> String = remember(formatters, useTimeAxis) {
        if (useTimeAxis) {
            { Formatters.duration(it.toDouble()) }
        } else {
            { formatters.distance(it.toDouble()) }
        }
    }

    // Only on the time axis. On a distance axis a stop is zero wide - correctly, because
    // no distance passed during it - so there is no band to label and nothing missing.
    val gapFormat = stringResource(R.string.chart_gap)
    val breakLabel: ((Float) -> String)? = remember(useTimeAxis, gapFormat) {
        if (!useTimeAxis) null else { seconds -> gapFormat.format(Formatters.durationAxis(seconds)) }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            // A tap anywhere not a chart goes back to the whole-track numbers - charts and
            // buttons consume their own taps, so this only sees ones that landed on nothing.
            .pointerInput(Unit) {
                detectTapGestures { onSelectedIndexChange(null) }
            }
    ) {

        // What the collapsed sheet shows, measured rather than assumed: a larger font
        // would otherwise push the date under the gesture bar.
        Column(Modifier.onSizeChanged { onPeekHeightChange(with(density) { it.height.toDp() }) }) {
            SheetTitle(
                name = trackTitle(loaded.track.name, loaded.displayName),
                routeColor = routeColor,
                actions = actions,
                onClose = onClose,
                modifier = Modifier.padding(start = SheetPadding, end = 4.dp),
            )

            // Always the whole track - scrubbed values live on the charts themselves instead.
            StatRow(
                stats = trackHeadline(stats, profile.hasTime),
                modifier = Modifier.padding(start = SheetPadding, end = 8.dp),
            )

            stats.startedAt?.let {
                Text(
                    text = Formatters.dateTime(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = SheetPadding, vertical = 8.dp),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                // fill = false: with nothing to scroll the sheet should be short, not
                // padded out to the cap.
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
        ) {
            HorizontalDivider(Modifier.padding(horizontal = SheetPadding, vertical = 8.dp))

            // Whatever the file itself said this ride was, verbatim - the app never writes
            // one, so this only ever shows up on an import that carried its own <desc>.
            loaded.track.description?.takeIf(String::isNotBlank)?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = SheetPadding, vertical = 4.dp),
                )
            }

            // Elapsed time, ascent, descent, point count: answers you go looking for
            // rather than glance at, hence under the fold and not the row above it.
            Column(Modifier.padding(horizontal = SheetPadding, vertical = 4.dp)) {
                TrackDetails(
                    stats = stats,
                    hasTime = profile.hasTime,
                    hasElevation = profile.hasElevation,
                )
            }

            Spacer(Modifier.height(20.dp))

            // With the charts, because it is a fact about them - what they are plotted
            // against - and not a fact about the ride.
            if (profile.hasTime) {
                AxisSelector(
                    useTimeAxis = useTimeAxis,
                    onChange = onAxisChange,
                    modifier = Modifier.padding(horizontal = SheetPadding),
                )
                Spacer(Modifier.height(16.dp))
            }

            // The two charts differ only in what they plot.
            @Composable
            fun Profile(
                @StringRes title: Int,
                @StringRes empty: Int,
                available: Boolean,
                y: FloatArray,
                color: Color,
                perUnit: Float,
                fromZero: Boolean,
                axisFor: (step: Float) -> (Float) -> String,
                formatValue: (Float) -> String,
            ) {
                ChartSection(
                    title = stringResource(title),
                    modifier = Modifier.padding(horizontal = SheetPadding),
                ) {
                    if (!available) {
                        Unavailable(stringResource(empty))
                        return@ChartSection
                    }
                    val series = remember(profile, useTimeAxis, color) {
                        ChartSeries(
                            x = xValues,
                            y = y,
                            segmentStartIndices = profile.segmentStartIndices,
                            color = color,
                        )
                    }
                    val yScale = remember(series, perUnit) { series.yScale(perUnit, fromZero) }
                    ProfileChart(
                        series = series,
                        xScale = xScale,
                        yScale = yScale,
                        formatX = formatX,
                        formatY = remember(formatters, yScale) { axisFor(yScale.step) },
                        selectedIndex = selectedIndex,
                        onSelectedIndexChange = onSelectedIndexChange,
                        onZoom = onZoom,
                        axisGroup = axisGroup,
                        contentDescription = stringResource(title),
                        breakLabel = breakLabel,
                        formatValue = formatValue,
                        formatPosition = positionValue,
                    )
                }
            }

            Profile(
                title = R.string.chart_speed,
                empty = R.string.chart_speed_empty,
                available = profile.hasTime,
                y = profile.speedMps,
                color = chartColors.speed,
                perUnit = formatters.speedPerMps,
                fromZero = true,
                axisFor = formatters::speedAxisFor,
                formatValue = speedValue,
            )

            Spacer(Modifier.height(12.dp))

            // A flat towpath gets half-metre gridlines, which whole metres can't label
            // without repeating themselves - hence the step-aware axis.
            Profile(
                title = R.string.chart_elevation,
                empty = R.string.chart_elevation_empty,
                available = profile.hasElevation,
                y = profile.elevationMeters,
                color = chartColors.elevation,
                perUnit = formatters.elevationPerMeter,
                fromZero = false,
                axisFor = formatters::elevationAxisFor,
                formatValue = elevationValue,
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * The name, beside the swatch the map and the list know it by - the only thing tying these
 * numbers to one line among several overlaid routes.
 *
 * No close control on the sheet: dragged away, tapped away on the bare map and backed out
 * of are three ways out already. The landscape panel has no handle, so it gets one.
 */
@Composable
private fun SheetTitle(
    name: String,
    routeColor: Color,
    actions: TrackActions?,
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(routeColor))
        Spacer(Modifier.width(10.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        actions?.let { TrackMenu(it.onRename, it.onShare, it.onHide, it.onDelete) }
        onClose?.let {
            IconButton(onClick = it) {
                Icon(Icons.Default.Close, stringResource(R.string.track_close))
            }
        }
    }
}

/** While the file is being read. Sized like the peek so the sheet does not jump open. */
@Composable
fun TrackSheetLoading(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = TrackSheetPeekHeight)
            .padding(horizontal = SheetPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CircularProgressIndicator(Modifier.size(20.dp))
        Text(stringResource(R.string.track_loading), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun TrackSheetError(
    messageRes: Int,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = TrackSheetPeekHeight)
            .padding(horizontal = SheetPadding)
            .navigationBarsPadding(),
    ) {
        Text(
            text = stringResource(R.string.track_error_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(messageRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            TextButton(onClick = onClose) { Text(stringResource(R.string.track_close)) }
        }
    }
}

@Composable
private fun AxisSelector(
    useTimeAxis: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Full width, halves shared equally: the same span as the charts it switches.
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = !useTimeAxis,
            onClick = { onChange(false) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.axis_distance)) }

        SegmentedButton(
            selected = useTimeAxis,
            onClick = { onChange(true) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.axis_time)) }
    }
}

/**
 * A titled chart block. No card chrome: a border and a fill around every chart is ink
 * that isn't data, and the title plus the spacing already separate the two sections.
 */
@Composable
private fun ChartSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@Composable
private fun Unavailable(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 24.dp),
    )
}
