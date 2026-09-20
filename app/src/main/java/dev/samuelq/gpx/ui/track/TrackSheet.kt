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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import dev.samuelq.gpx.ui.chart.yScale
import dev.samuelq.gpx.ui.format.Formatters
import dev.samuelq.gpx.ui.format.LocalFormatters
import dev.samuelq.gpx.ui.theme.LocalChartColors

private val SheetPadding = 20.dp

/**
 * Everything the sheet shows above the fold: the handle, the name, one row of numbers.
 *
 * Anything taller peeks at a chart heading with no chart under it, which is the worst row
 * the sheet has - a label for something you cannot see, bought with a third of the map.
 */
val TrackSheetPeekHeight = 128.dp

/**
 * Everything the sheet can do to the track it is showing.
 *
 * Null for a track that arrived through an intent: it has no row in the library, so there
 * is nothing to rename, hide or delete, and the sheet leaves the menu off entirely rather
 * than offering four actions that would all have to refuse.
 */
@Immutable
class TrackActions(
    val onRename: () -> Unit,
    val onExport: () -> Unit,
    val onHide: () -> Unit,
    val onDelete: () -> Unit,
)

/**
 * What the map knows about the track it is focused on.
 *
 * A sheet rather than a screen: the route is already drawn on the map behind it, and
 * sending the reader somewhere else to see its numbers meant redrawing the same line on a
 * second canvas and losing every other track off the side of it.
 *
 * Height is the only thing that hides anything here. There used to be a fold as well - a
 * Details button that opened the secondary numbers - which meant two disclosure systems
 * stacked on one surface: the sheet was already taller-or-shorter, and the button was
 * shorter-or-taller inside it. Worse, at the peek height it opened onto content below the
 * bottom of the screen, so pressing it appeared to do nothing but turn an arrow over. Now
 * the column simply runs on, and dragging is what reveals more of it.
 */
@Composable
fun TrackSheet(
    loaded: LoadedTrack,
    routeColor: Color,
    maxHeight: Dp,
    atFullHeight: Boolean,
    onStepHeight: () -> Unit,
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
    val xScale = remember(profile, useTimeAxis) {
        axisScale(xValues.firstOrNull() ?: 0f, xValues.lastOrNull() ?: 1f)
    }
    // Keyed on the scale as well as the units: the distance ticks are labelled to whatever
    // precision tells one of them from the next, and that is a property of the step.
    val formatX: (Float) -> String = remember(formatters, useTimeAxis, xScale) {
        if (useTimeAxis) formatters.DurationAxis else formatters.distanceAxisFor(xScale.step)
    }

    // With units, unlike the axis formatters: a tick is read in a column of ticks, a
    // tooltip is read on its own and has to say what it is. Remembered because the chart
    // keys its measured layout on the identity of these.
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
    val pausedFormat = stringResource(R.string.chart_paused)
    val breakLabel: ((Float) -> String)? = remember(useTimeAxis, pausedFormat) {
        if (!useTimeAxis) null else { seconds -> pausedFormat.format(Formatters.durationAxis(seconds)) }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            // A tap anywhere that is not a chart goes back to the whole-track numbers.
            // The charts and the buttons consume their own taps, so this only ever sees
            // the ones that landed on nothing - which is exactly when "never mind" is
            // what the reader meant. It replaces a clear button that sat directly under
            // the sheet's own close icon, two X's deep.
            .pointerInput(Unit) {
                detectTapGestures { onSelectedIndexChange(null) }
            }
    ) {

        SheetTitle(
            name = loaded.track.name?.takeIf(String::isNotBlank) ?: loaded.displayName,
            routeColor = routeColor,
            atFullHeight = atFullHeight,
            onStepHeight = onStepHeight,
            actions = actions,
            modifier = Modifier.padding(start = SheetPadding, end = 4.dp),
        )

        // Always the whole track. It used to be swapped out for the scrubbed values,
        // which put the reading for the chart you were not touching beside the one you
        // were, and made reading a value off a chart a matter of looking somewhere else.
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

            // Date, elapsed time, ascent, descent and point count: the answers you go
            // looking for rather than the ones you glance at, which is why they are the
            // first thing under the fold and not on the row above it.
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
                    ProfileChart(
                        series = series,
                        xScale = xScale,
                        yScale = remember(series) { series.yScale() },
                        formatX = formatX,
                        formatY = formatters.SpeedAxis,
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
                    val yScale = remember(series) { series.yScale() }
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
 * The name, in the swatch the map and the list already know it by.
 *
 * It used to live in a top app bar that this sheet does not have. The colour is not
 * decoration: with several routes overlaid it is the only thing tying these numbers to one
 * of the lines behind them.
 *
 * No close button any more. The sheet is dragged away, tapped away on the bare map, and
 * backed away out of - three ways out already - and the icon was spending a permanent slot
 * in the one row that is on screen at every height to offer a fourth. The slot went to the
 * menu instead, which is the thing that had nowhere else to live.
 */
@Composable
private fun SheetTitle(
    name: String,
    routeColor: Color,
    atFullHeight: Boolean,
    onStepHeight: () -> Unit,
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
        // Back in the title, because it is the one row on screen at every height, and it
        // is the only way to reach the third height - the platform sheet has no fourth
        // drag anchor to put it on.
        IconButton(onClick = onStepHeight) {
            if (atFullHeight) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    stringResource(R.string.track_sheet_collapse),
                )
            } else {
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    stringResource(R.string.track_sheet_expand),
                )
            }
        }
        actions?.let { TrackMenu(it) }
    }
}

/**
 * Rename, export, hide, delete - the whole of managing a track, behind one glyph.
 *
 * A menu rather than four controls, and here rather than only in the library: these are
 * things you decide about a ride while you are looking at it, and the sheet is where you
 * are looking at it. Four buttons would have cost the sheet a row it does not have; one
 * icon costs a slot that the close button was using for a gesture you already have.
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
                text = { Text(stringResource(R.string.library_export)) },
                onClick = {
                    open = false
                    actions.onExport()
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
