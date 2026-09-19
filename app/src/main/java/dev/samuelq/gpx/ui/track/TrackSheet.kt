package dev.samuelq.gpx.ui.track

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
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
 * What the map knows about the track it is focused on.
 *
 * A sheet rather than a screen: the route is already drawn on the map behind it, and
 * sending the reader somewhere else to see its numbers meant redrawing the same line on a
 * second canvas and losing every other track off the side of it.
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
    onClose: () -> Unit,
) {
    val profile = loaded.profile
    val stats = profile.stats
    val chartColors = LocalChartColors.current
    val formatters = LocalFormatters.current
    var detailsShown by rememberSaveable { mutableStateOf(false) }

    val xValues = if (useTimeAxis) profile.elapsedSeconds else profile.distanceMeters
    // One domain, shared by both charts, so the same pixel column is the same moment in
    // each and the scrubber means the same thing in both - and on the route behind.
    val xScale = remember(profile, useTimeAxis) {
        axisScale(xValues.firstOrNull() ?: 0f, xValues.lastOrNull() ?: 1f)
    }
    val formatX: (Float) -> String =
        if (useTimeAxis) formatters.DurationAxis else formatters.DistanceAxis

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
            onClose = onClose,
            modifier = Modifier.padding(start = SheetPadding, end = 4.dp),
        )

        // Always the whole track. It used to be swapped out for the scrubbed values,
        // which put the reading for the chart you were not touching beside the one you
        // were, and made reading a value off a chart a matter of looking somewhere else.
        StatRow(
            stats = trackHeadline(stats, profile.hasTime),
            modifier = Modifier.padding(start = SheetPadding, end = 8.dp),
        ) {
            // Beside the readings it opens more of, rather than on a row of its own
            // underneath them. Drops to its own line when the numbers have used the width.
            TextButton(
                onClick = { detailsShown = !detailsShown },
                modifier = Modifier.align(Alignment.CenterVertically),
            ) {
                Text(stringResource(R.string.track_details))
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    // A triangle, and one that turns over - not the chevron in the title,
                    // which opens the sheet rather than the numbers.
                    modifier = Modifier.rotate(if (detailsShown) 180f else 0f),
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

            // The whole stretch between the headline and the first chart, in one fold.
            // Date, moving time, ascent, descent and point count are answers to questions
            // you go looking for, and what the charts are plotted against is a decision
            // you make once - none of them is worth the room above a graph you came to
            // read. Sticky rather than per-track: someone who wants them wants them every
            // time.
            AnimatedVisibility(visible = detailsShown) {
                Column(Modifier.padding(horizontal = SheetPadding, vertical = 4.dp)) {
                    TrackDetails(
                        stats = stats,
                        hasTime = profile.hasTime,
                        hasElevation = profile.hasElevation,
                    )

                    if (profile.hasTime) {
                        Spacer(Modifier.height(16.dp))
                        AxisSelector(useTimeAxis = useTimeAxis, onChange = onAxisChange)
                    }

                    Spacer(Modifier.height(16.dp))
                }
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
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

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
                    ProfileChart(
                        series = series,
                        xScale = xScale,
                        formatX = formatX,
                        formatY = formatters.ElevationAxis,
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
 */
@Composable
private fun SheetTitle(
    name: String,
    routeColor: Color,
    atFullHeight: Boolean,
    onStepHeight: () -> Unit,
    onClose: () -> Unit,
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
        // Back in the title, because it is the one row on screen at every height - and
        // everything it used to sit beside now folds away, which is no place for the
        // control that makes room for what is left.
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
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, stringResource(R.string.track_close))
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
