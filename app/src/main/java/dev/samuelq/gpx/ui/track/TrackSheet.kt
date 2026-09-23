package dev.samuelq.gpx.ui.track

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.samuelq.gpx.R
import dev.samuelq.gpx.data.track.LoadedTrack
import dev.samuelq.gpx.ui.chart.ChartSeries
import dev.samuelq.gpx.ui.chart.ProfileChart
import dev.samuelq.gpx.ui.chart.axisScale
import dev.samuelq.gpx.ui.chart.timeAxisScale
import dev.samuelq.gpx.ui.chart.yScale
import dev.samuelq.gpx.ui.format.Formatters
import dev.samuelq.gpx.ui.format.LocalFormatters
import dev.samuelq.gpx.ui.theme.LocalChartColors

private val SheetPadding = 20.dp

/**
 * Everything the sheet shows above the fold: the handle, the name, one row of numbers.
 * Anything taller peeks at a chart heading with no chart under it.
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
) {
    val profile = loaded.profile
    val stats = profile.stats
    val chartColors = LocalChartColors.current
    val formatters = LocalFormatters.current

    val xValues = if (useTimeAxis) profile.elapsedSeconds else profile.distanceMeters
    // One domain, shared by both charts, so the same pixel column is the same moment in
    // each and the scrubber means the same thing in both - and on the route behind.
    val xScale = remember(profile, useTimeAxis, formatters) {
        val first = xValues.firstOrNull() ?: 0f
        val last = xValues.lastOrNull() ?: 1f
        if (useTimeAxis) {
            timeAxisScale(first, last)
        } else {
            axisScale(first, last, perUnit = formatters.distancePerMeter)
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
        remember(formatters) { { formatters.elevation(it.toDouble()) } }
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

        SheetTitle(
            name = loaded.track.name?.takeIf(String::isNotBlank) ?: loaded.displayName,
            routeColor = routeColor,
            actions = actions,
            modifier = Modifier.padding(start = SheetPadding, end = 4.dp),
        )

        // Always the whole track - scrubbed values live on the charts themselves instead.
        StatRow(
            stats = trackHeadline(stats, profile.hasTime),
            modifier = Modifier.padding(start = SheetPadding, end = 8.dp),
        )

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

            // Date, elapsed time, ascent, descent, point count: answers you go looking
            // for rather than glance at, hence under the fold and not the row above it.
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

            ChartSection(
                title = stringResource(R.string.chart_speed),
                unit = formatters.speedUnit,
                modifier = Modifier.padding(horizontal = SheetPadding),
            ) {
                if (!profile.hasTime) {
                    Unavailable(stringResource(R.string.chart_speed_empty))
                } else {
                    val series = remember(profile, useTimeAxis, chartColors) {
                        ChartSeries(
                            x = xValues,
                            y = profile.speedMps,
                            segmentStartIndices = profile.segmentStartIndices,
                            color = chartColors.speed,
                            // Speed is a magnitude: the distance from the baseline is the
                            // value, so the axis has to include zero.
                            zeroBased = true,
                        )
                    }
                    val yScale = remember(series, formatters) {
                        series.yScale(formatters.speedPerMps)
                    }
                    ProfileChart(
                        series = series,
                        xScale = xScale,
                        yScale = yScale,
                        formatX = formatX,
                        formatY = remember(formatters, yScale) {
                            formatters.speedAxisFor(yScale.step)
                        },
                        selectedIndex = selectedIndex,
                        onSelectedIndexChange = onSelectedIndexChange,
                        contentDescription = stringResource(R.string.chart_speed),
                        highlightIndex = stats.maxSpeedIndex,
                        highlightLabel = formatters.speed(stats.maxSpeedMps),
                        breakLabel = breakLabel,
                        formatValue = speedValue,
                        formatPosition = positionValue,
                        // The elevation chart below carries the ticks for both, unless
                        // this file has no elevation to draw and there is no chart there
                        // to carry them.
                        showXAxis = !profile.hasElevation,
                    )
                }
            }

            // Tighter than it was: the two plots share an axis now, which only reads as
            // one axis under two charts if they are close enough to be one object.
            Spacer(Modifier.height(20.dp))

            ChartSection(
                title = stringResource(R.string.chart_elevation),
                unit = formatters.elevationUnit,
                modifier = Modifier.padding(horizontal = SheetPadding),
            ) {
                if (!profile.hasElevation) {
                    Unavailable(stringResource(R.string.chart_elevation_empty))
                } else {
                    val series = remember(profile, useTimeAxis, chartColors) {
                        ChartSeries(
                            x = xValues,
                            y = profile.elevationMeters,
                            segmentStartIndices = profile.segmentStartIndices,
                            color = chartColors.elevation,
                            // An elevation profile sits on a baseline near the low point,
                            // as every mapping tool draws it. The y axis labels that
                            // baseline explicitly so it can't be mistaken for sea level.
                            zeroBased = false,
                        )
                    }
                    val yScale = remember(series, formatters) {
                        series.yScale(formatters.elevationPerMeter)
                    }
                    ProfileChart(
                        series = series,
                        xScale = xScale,
                        yScale = yScale,
                        formatX = formatX,
                        // A flat towpath gets half-metre gridlines, which whole metres
                        // cannot label without repeating themselves.
                        formatY = remember(formatters, yScale) {
                            formatters.elevationAxisFor(yScale.step)
                        },
                        selectedIndex = selectedIndex,
                        onSelectedIndexChange = onSelectedIndexChange,
                        contentDescription = stringResource(R.string.chart_elevation),
                        highlightIndex = stats.maxElevationIndex,
                        highlightLabel = stats.maxElevationMeters?.let { formatters.elevation(it) },
                        breakLabel = breakLabel,
                        formatValue = elevationValue,
                        formatPosition = positionValue,
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * The name, beside the swatch the map and the list know it by - the only thing tying these
 * numbers to one line among several overlaid routes.
 *
 * No close or expand control: dragged away, tapped away on the bare map and backed out of
 * are three ways out already, and the drag handle reaches both heights.
 */
@Composable
private fun SheetTitle(
    name: String,
    routeColor: Color,
    actions: TrackActions?,
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
        actions?.let { TrackMenu(it) }
    }
}

/**
 * Rename, export, hide, delete - the whole of managing a track, behind one glyph. Also
 * here, not just in the library, since these are decisions made while looking at the ride.
 */
@Composable
private fun TrackMenu(actions: TrackActions) {
    var open by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.MoreVert, stringResource(R.string.track_manage))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.library_rename)) },
                onClick = {
                    open = false
                    actions.onRename()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.library_share)) },
                onClick = {
                    open = false
                    actions.onShare()
                },
            )
            // Takes the line off the map and the sheet with it, which is the only reading
            // of "hide" that leaves the screen in a state that makes sense.
            DropdownMenuItem(
                text = { Text(stringResource(R.string.track_hide)) },
                onClick = {
                    open = false
                    actions.onHide()
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(R.string.library_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    open = false
                    actions.onDelete()
                },
            )
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
    SingleChoiceSegmentedButtonRow(modifier) {
        SegmentedButton(
            selected = useTimeAxis,
            onClick = { onChange(true) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
        ) { Text(stringResource(R.string.axis_time)) }

        SegmentedButton(
            selected = !useTimeAxis,
            onClick = { onChange(false) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
        ) { Text(stringResource(R.string.axis_distance)) }
    }
}

/**
 * A titled chart block. No card chrome: a border and a fill around every chart is ink
 * that isn't data, and the title plus the spacing already separate the two sections.
 */
@Composable
private fun ChartSection(
    title: String,
    unit: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            // The axis ticks are bare numbers; the unit lives here once.
            Text(
                text = unit,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
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
