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
import dev.samuelq.gpx.core.analysis.TrackProfile
import dev.samuelq.gpx.data.track.LoadedTrack
import dev.samuelq.gpx.ui.chart.ChartAxisGroup
import dev.samuelq.gpx.ui.chart.ChartSeries
import dev.samuelq.gpx.ui.chart.EmptyChart
import dev.samuelq.gpx.ui.chart.ProfileChart
import dev.samuelq.gpx.ui.chart.axisScale
import dev.samuelq.gpx.ui.chart.timeAxisScale
import dev.samuelq.gpx.ui.chart.yScale
import dev.samuelq.gpx.ui.chart.zoomView
import dev.samuelq.gpx.ui.format.Formatters
import dev.samuelq.gpx.ui.format.LocalFormatters
import dev.samuelq.gpx.ui.theme.LocalChartColors

internal val SheetPadding = 20.dp

/** Peek height before a track loads, and the minimum after. */
val TrackSheetPeekHeight = 128.dp

/** Null for a track opened via intent, which has no library row to act on. */
@Immutable
class TrackActions(
    val onRename: () -> Unit,
    val onShare: () -> Unit,
    val onHide: () -> Unit,
    val onDelete: () -> Unit,
)

/** Stats and charts for the focused track; dragging the sheet is the only disclosure. */
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
    /** Measured height of the collapsed content, so the peek fits it. */
    onPeekHeightChange: (Dp) -> Unit,
    /** Close button, for the landscape panel. */
    onClose: (() -> Unit)? = null,
) {
    val profile = loaded.profile
    ProfileSheet(
        profile = profile,
        viewKey = profile,
        maxHeight = maxHeight,
        selectedIndex = selectedIndex,
        onSelectedIndexChange = onSelectedIndexChange,
        useTimeAxis = useTimeAxis,
        onAxisChange = onAxisChange,
        onPeekHeightChange = onPeekHeightChange,
        // The file's own <desc>; the app never writes one.
        description = loaded.track.description,
    ) {
        SheetTitle(
            name = trackTitle(loaded.track.name, loaded.displayName),
            routeColor = routeColor,
            actions = actions,
            onClose = onClose,
            modifier = Modifier.padding(start = SheetPadding, end = 4.dp),
        )

        StatRow(
            stats = trackHeadline(profile.stats, profile.hasTime),
            modifier = Modifier.padding(start = SheetPadding, end = 8.dp),
        )

        profile.stats.startedAt?.let {
            Text(
                text = Formatters.dateTime(it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = SheetPadding, vertical = 8.dp),
            )
        }
    }
}

/**
 * [header] in the peek, then details and charts of [profile] below it; null shows empty
 * charts. A zoom lasts while [viewKey] does, and an unzoomed chart follows a growing profile.
 */
@Composable
fun ProfileSheet(
    profile: TrackProfile?,
    viewKey: Any,
    maxHeight: Dp,
    selectedIndex: Int?,
    onSelectedIndexChange: (Int?) -> Unit,
    useTimeAxis: Boolean,
    onAxisChange: (Boolean) -> Unit,
    onPeekHeightChange: (Dp) -> Unit,
    description: String? = null,
    /** Overrides the profile's, for a recording's count that moves with every fix. */
    pointCount: Int? = null,
    header: @Composable () -> Unit,
) {
    val density = LocalDensity.current

    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            // Taps not consumed by charts or buttons clear the selection.
            .pointerInput(Unit) {
                detectTapGestures { onSelectedIndexChange(null) }
            }
    ) {
        // Measured, since a larger font would push the date under the gesture bar.
        Column(Modifier.onSizeChanged { onPeekHeightChange(with(density) { it.height.toDp() }) }) {
            header()
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                // fill = false keeps a short sheet short.
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
        ) {
            // Just above the gesture bar at peek: a second hint, beside the handle, that there's more.
            HorizontalDivider(Modifier.padding(horizontal = SheetPadding, vertical = 8.dp))
            if (profile != null) {
                ProfileDetails(
                    profile = profile,
                    viewKey = viewKey,
                    selectedIndex = selectedIndex,
                    onSelectedIndexChange = onSelectedIndexChange,
                    useTimeAxis = useTimeAxis && profile.hasTime,
                    onAxisChange = onAxisChange,
                    description = description,
                    pointCount = pointCount,
                )
            } else {
                EmptyProfile(pointCount, useTimeAxis)
            }
        }
    }
}

@Composable
private fun ProfileDetails(
    profile: TrackProfile,
    viewKey: Any,
    selectedIndex: Int?,
    onSelectedIndexChange: (Int?) -> Unit,
    useTimeAxis: Boolean,
    onAxisChange: (Boolean) -> Unit,
    description: String?,
    pointCount: Int?,
) {
    val chartColors = LocalChartColors.current
    val formatters = LocalFormatters.current

    val xValues = if (useTimeAxis) profile.elapsedSeconds else profile.distanceMeters
    val xDomain = (xValues.firstOrNull() ?: 0f)..(xValues.lastOrNull() ?: 1f)
    // Null is the whole domain, which a live profile widens.
    var zoomed by remember(viewKey, useTimeAxis) { mutableStateOf<ClosedFloatingPointRange<Float>?>(null) }
    val xView = zoomed ?: xDomain
    // Reset per track and units, since the gutter only ever grows.
    val axisGroup = remember(viewKey, formatters) { ChartAxisGroup() }
    val onZoom: (Float, Float, Float) -> Unit = remember(profile, useTimeAxis) {
        { anchor, zoom, pan ->
            val view = zoomView(zoomed ?: xDomain, xDomain, anchor, zoom, pan)
            zoomed = view.takeIf { it.endInclusive - it.start < xDomain.endInclusive - xDomain.start }
        }
    }
    // Shared by both charts so the scrubber lines up.
    val xScale = remember(xView, useTimeAxis, formatters) {
        if (useTimeAxis) {
            timeAxisScale(xView.start, xView.endInclusive)
        } else {
            axisScale(xView.start, xView.endInclusive, perUnit = formatters.distancePerMeter)
        }
    }
    // Keyed on the scale: label precision follows the step.
    val formatX: (Float) -> String = remember(formatters, useTimeAxis, xScale) {
        if (useTimeAxis) {
            Formatters.durationAxisFor(xScale.max)
        } else {
            formatters.distanceAxisFor(xScale.step)
        }
    }

    // Remembered: the chart keys its measured layout on these lambdas' identity.
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

    // Time axis only: on a distance axis a stop is zero wide.
    val gapFormat = stringResource(R.string.chart_gap)
    val breakLabel: ((Float) -> String)? = remember(useTimeAxis, gapFormat) {
        if (!useTimeAxis) null else { seconds -> gapFormat.format(Formatters.durationAxis(seconds)) }
    }

    description?.takeIf(String::isNotBlank)?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = SheetPadding, vertical = 4.dp),
        )
    }

    Column(Modifier.padding(horizontal = SheetPadding, vertical = 4.dp)) {
        TrackDetails(
            stats = profile.stats,
            hasTime = profile.hasTime,
            hasElevation = profile.hasElevation,
            // Only a recording overrides the count.
            complete = pointCount != null,
            pointCount = pointCount,
        )
    }

    Spacer(Modifier.height(20.dp))

    if (profile.hasTime) {
        AxisSelector(
            useTimeAxis = useTimeAxis,
            onChange = onAxisChange,
            modifier = Modifier.padding(horizontal = SheetPadding),
        )
        Spacer(Modifier.height(16.dp))
    }

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

/** Name beside the route swatch, tying the sheet to one of several overlaid routes. */
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

/** Sized like the peek so the sheet does not jump open. */
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
    enabled: Boolean = true,
) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = !useTimeAxis,
            onClick = { onChange(false) },
            enabled = enabled,
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.axis_distance)) }

        SegmentedButton(
            selected = useTimeAxis,
            onClick = { onChange(true) },
            enabled = enabled,
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.axis_time)) }
    }
}

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

/** Details and chart outlines awaiting data, so a new recording's sheet still opens onto something. */
@Composable
private fun EmptyProfile(pointCount: Int?, useTimeAxis: Boolean) {
    TrackDetails(
        stats = null,
        hasTime = false,
        hasElevation = false,
        complete = true,
        pointCount = pointCount,
        modifier = Modifier.padding(horizontal = SheetPadding, vertical = 4.dp),
    )
    Spacer(Modifier.height(20.dp))
    // Disabled, not hidden, so nothing shifts once the charts fill in.
    AxisSelector(
        useTimeAxis = useTimeAxis,
        onChange = {},
        enabled = false,
        modifier = Modifier.padding(horizontal = SheetPadding),
    )
    Spacer(Modifier.height(16.dp))
    val message = stringResource(R.string.chart_waiting)
    for (title in listOf(R.string.chart_speed, R.string.chart_elevation)) {
        ChartSection(
            title = stringResource(title),
            modifier = Modifier.padding(horizontal = SheetPadding),
        ) {
            EmptyChart(message)
        }
        Spacer(Modifier.height(12.dp))
    }
    Spacer(Modifier.height(20.dp))
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
