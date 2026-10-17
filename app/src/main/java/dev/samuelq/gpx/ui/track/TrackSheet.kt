package dev.samuelq.gpx.ui.track

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.samuelq.gpx.R
import dev.samuelq.gpx.core.analysis.TrackProfile
import dev.samuelq.gpx.core.model.Waypoint
import dev.samuelq.gpx.data.track.LoadedTrack
import dev.samuelq.gpx.ui.EdgePadding
import dev.samuelq.gpx.ui.chart.BreakLabel
import dev.samuelq.gpx.ui.chart.ChartAxisGroup
import dev.samuelq.gpx.ui.chart.ChartSeries
import dev.samuelq.gpx.ui.chart.EmptyChart
import dev.samuelq.gpx.ui.chart.ProfileChart
import dev.samuelq.gpx.ui.chart.axisScale
import dev.samuelq.gpx.ui.chart.scrollView
import dev.samuelq.gpx.ui.chart.timeAxisScale
import dev.samuelq.gpx.ui.chart.yScale
import dev.samuelq.gpx.ui.chart.zoomView
import dev.samuelq.gpx.ui.format.Formatters
import dev.samuelq.gpx.ui.format.LocalFormatters
import dev.samuelq.gpx.ui.format.spokenDuration
import dev.samuelq.gpx.ui.format.tabularFigures
import dev.samuelq.gpx.ui.isLargeText
import dev.samuelq.gpx.ui.theme.LocalChartColors
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.delay

/** Peek height before a track loads, and the minimum after. */
val TrackSheetPeekHeight = 128.dp

/** Null until a new import's row arrives. */
@Immutable
class TrackActions(
    val onRename: () -> Unit,
    val onShare: () -> Unit,
    val onHide: () -> Unit,
    val onDelete: () -> Unit,
    val onTrim: () -> Unit,
    val colorIndex: Int,
    val onColor: (Int) -> Unit,
)

/** A trim being set up on the sheet: [range] is the points kept, inclusive. */
class TrimControls(
    val range: IntRange,
    /** Whether either end has left the track's own. */
    val moved: Boolean,
    /** The track's waypoints the trim would drop. */
    val waypointsCut: Int,
    val onRangeChange: (IntRange) -> Unit,
    val onCancel: () -> Unit,
    val onSave: () -> Unit,
)

/** Stats and charts for the focused track; dragging the sheet is the only disclosure. */
@Composable
fun TrackSheet(
    loaded: LoadedTrack,
    title: String,
    /** The row's, like [title]; null before a new import's row arrives. */
    startedAtEpochMillis: Long?,
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
    onSelectWaypoint: (Waypoint) -> Unit = {},
    /** Replaces the title and stats while a trim is set up. */
    trim: TrimControls? = null,
) {
    val profile = loaded.profile
    val waypointActions = waypointActions(loaded.track.waypoints, profile.stats.startedAt, onSelectWaypoint)
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
        keptRange = trim?.range,
        onKeptRangeChange = trim?.onRangeChange,
    ) {
        if (trim != null) {
            TrimHeader(
                title = title,
                profile = profile,
                trim = trim,
                waypoints = loaded.track.waypoints.size,
                waypointsKept = loaded.track.waypoints.size - trim.waypointsCut,
                routeColor = routeColor,
                useTimeAxis = useTimeAxis,
                onAxisChange = onAxisChange,
                selectedIndex = selectedIndex,
                onSelectedIndexChange = onSelectedIndexChange,
            )
            return@ProfileSheet
        }
        SheetTitle(
            name = title,
            routeColor = routeColor,
            actions = actions,
            onClose = onClose,
            // Waypoints are otherwise reached only by their pins, and the sheet has no room to list them.
            modifier = Modifier
                .padding(start = EdgePadding, end = 4.dp)
                .semantics { if (waypointActions.isNotEmpty()) customActions = waypointActions },
        )

        StatRow(
            stats = trackHeadline(profile.stats, profile.hasTime, profile.hasElevation),
            modifier = Modifier.padding(start = EdgePadding, end = 8.dp),
        )

        startedAtEpochMillis?.let {
            Text(
                text = LocalFormatters.current.longDateTime(Instant.ofEpochMilli(it)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = EdgePadding, vertical = 8.dp),
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
    /** While trimming: the points kept, the rest greyed on the charts. */
    keptRange: IntRange? = null,
    /** Makes [keptRange]'s ends draggable on the charts. */
    onKeptRangeChange: ((IntRange) -> Unit)? = null,
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
            },
    ) {
        // Measured, since a larger font would push the date under the gesture bar.
        val measuredHeader = @Composable {
            Column(Modifier.onSizeChanged { onPeekHeightChange(with(density) { it.height.toDp() }) }) {
                header()
            }
        }
        // Scrolls with the rest at large text sizes, or a tall header could push controls out of a short panel.
        val scrollHeader = isLargeText()
        if (!scrollHeader) measuredHeader()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                // fill = false keeps a short sheet short.
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
        ) {
            if (scrollHeader) measuredHeader()
            // Just above the gesture bar at peek: a second hint, beside the handle, that there's more.
            HorizontalDivider(Modifier.padding(horizontal = EdgePadding, vertical = 8.dp))
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
                    keptRange = keptRange,
                    onKeptRangeChange = onKeptRangeChange,
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
    keptRange: IntRange?,
    onKeptRangeChange: ((IntRange) -> Unit)?,
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
    val onZoom: (Float, Float) -> Unit = remember(profile, useTimeAxis) {
        { anchor, zoom ->
            val view = zoomView(zoomed ?: xDomain, xDomain, anchor, zoom)
            zoomed = view.takeIf { it.endInclusive - it.start < xDomain.endInclusive - xDomain.start }
        }
    }
    val onScroll: (Float) -> Unit = remember(profile, useTimeAxis) {
        { by -> zoomed?.let { zoomed = scrollView(it, xDomain, by) } }
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
            { seconds -> Formatters.duration(seconds.toDouble()) }
        } else {
            formatters.distanceAxisFor(xScale.step)
        }
    }

    // Remembered: the chart keys its measured layout on these lambdas' identity.
    val speedValue: (Float) -> String =
        remember(formatters) { { formatters.speed(it.toDouble()) } }
    val elevationValue: (Float) -> String =
        remember(formatters) { { formatters.meters(it.toDouble()) } }
    val resources = LocalResources.current
    // The same on either axis: distance, then the time since the start.
    val positionValue: (Int) -> String = remember(formatters, profile, resources) {
        { i ->
            val distance = formatters.distance(profile.distanceMeters[i].toDouble())
            if (profile.hasTime) {
                val elapsed = Formatters.duration(profile.elapsedSeconds[i].toDouble())
                resources.getString(R.string.chart_position_elapsed, distance, elapsed)
            } else {
                distance
            }
        }
    }

    // Time axis only: on a distance axis a stop is zero wide. The wash says it's a gap.
    val breakLabel = remember(useTimeAxis, resources) {
        BreakLabel(
            shown = { Formatters.duration(it.toDouble()) },
            spoken = { resources.spokenDuration(it.toDouble()) },
        ).takeIf { useTimeAxis }
    }

    // While trimming, the whole track's details would read as the trimmed one's.
    if (keptRange == null) {
        description?.takeIf(String::isNotBlank)?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = EdgePadding, vertical = 4.dp),
            )
        }
        TrackDetails(
            stats = profile.stats,
            hasTime = profile.hasTime,
            hasElevation = profile.hasElevation,
            modifier = Modifier.padding(horizontal = EdgePadding, vertical = 4.dp),
            // Only a recording overrides the count.
            complete = pointCount != null,
            pointCount = pointCount,
        )
    }

    // Less while trimming: the selector this leaves room for is in the header.
    Spacer(Modifier.height(if (keptRange == null) 20.dp else 8.dp))

    // A trim's header has it, beside the ends it sets the units of.
    if (profile.hasTime && keptRange == null) {
        AxisSelector(
            useTimeAxis = useTimeAxis,
            onChange = onAxisChange,
            modifier = Modifier.padding(horizontal = EdgePadding),
        )
        Spacer(Modifier.height(16.dp))
    }

    @Composable
    fun Profile(
        @StringRes title: Int,
        /** Shown instead when the track lacks the series. */
        @StringRes unavailable: Int?,
        y: FloatArray,
        color: Color,
        perUnit: Float,
        fromZero: Boolean,
        axisFor: (step: Float) -> (Float) -> String,
        formatValue: (Float) -> String,
    ) {
        ChartSection(
            title = stringResource(title),
            modifier = Modifier.padding(horizontal = EdgePadding),
        ) {
            if (unavailable != null) {
                Unavailable(stringResource(unavailable))
                return@ChartSection
            }
            val series = remember(profile, useTimeAxis, color) {
                ChartSeries(
                    x = xValues,
                    y = y,
                    segmentStartIndices = profile.points.segmentStarts(),
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
                // Undoing a pinch otherwise takes another pinch.
                zoomed = zoomed != null,
                onScroll = onScroll,
                onResetZoom = { zoomed = null },
                axisGroup = axisGroup,
                contentDescription = stringResource(title),
                breakLabel = breakLabel,
                formatValue = formatValue,
                formatPosition = positionValue,
                keptRange = keptRange,
                onKeptRangeChange = onKeptRangeChange,
            )
        }
    }

    Profile(
        title = R.string.chart_speed,
        unavailable = R.string.chart_speed_empty.takeUnless { profile.hasTime },
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
        unavailable = R.string.chart_elevation_empty.takeUnless { profile.hasElevation },
        y = profile.elevationMeters,
        color = chartColors.elevation,
        perUnit = formatters.elevationPerMeter,
        fromZero = false,
        axisFor = formatters::elevationAxisFor,
        formatValue = elevationValue,
    )

    Spacer(Modifier.height(32.dp))
}

/** What a trim keeps: dragged on the charts, or stepped a point at a time here. */
@Composable
private fun TrimHeader(
    title: String,
    profile: TrackProfile,
    trim: TrimControls,
    /** The track's, and of them those the trim keeps. */
    waypoints: Int,
    waypointsKept: Int,
    /** The map's marks for the ends are in it, so the legend is too. */
    routeColor: Color,
    useTimeAxis: Boolean,
    onAxisChange: (Boolean) -> Unit,
    /** An end selected was last moved, by a step or on the charts, and is the one shown there. */
    selectedIndex: Int?,
    onSelectedIndexChange: (Int?) -> Unit,
) {
    val formatters = LocalFormatters.current
    val timed = useTimeAxis && profile.hasTime
    val x = if (timed) profile.elapsedSeconds else profile.distanceMeters
    val last = x.size - 1
    val first = trim.range.first
    val end = trim.range.last
    val resources = LocalResources.current

    // How much is cut, to the point: the usual two decimals of a km hid single steps. Hours always,
    // as for any time not labelled as one.
    val format: (Float) -> String =
        if (timed) {
            { seconds -> Formatters.duration(seconds.toDouble()) }
        } else {
            { meters -> formatters.preciseDistance(meters.toDouble()) }
        }
    val speak: (Float) -> String = if (timed) { seconds -> resources.spokenDuration(seconds.toDouble()) } else format

    // Selected too, so the map follows the end and the charts show where it is.
    fun move(range: IntRange, moved: Int) {
        trim.onRangeChange(range)
        onSelectedIndexChange(moved)
    }

    // From the cumulative series: distance is flat across a break and elapsed is wall-clock, so a
    // difference is exact.
    val distance = formatters.distance((profile.distanceMeters[end] - profile.distanceMeters[first]).toDouble())
    val elapsed = (profile.elapsedSeconds[end] - profile.elapsedSeconds[first]).toDouble().takeIf { profile.hasTime }
    // Kept of all, once any would go: the map's dimmed pins are easy to miss.
    val waypointsPart = when {
        waypoints == 0 -> null
        waypointsKept == waypoints -> pluralStringResource(R.plurals.trim_waypoints, waypoints, waypoints)
        else -> pluralStringResource(R.plurals.trim_waypoints_some, waypoints, waypointsKept, waypoints)
    }
    val join = stringResource(R.string.trim_kept_join)
    fun keptLine(time: String?) =
        listOfNotNull(distance, time, waypointsPart).reduce { a, b -> String.format(join, a, b) }
    // Bare under the title, which says it's a trim; spoken after the word, with no title beside it.
    val kept = keptLine(elapsed?.let { Formatters.duration(it) })
    val keptSpoken = stringResource(R.string.trim_kept, keptLine(elapsed?.let { resources.spokenDuration(it) }))
    // Large text leaves the title no room beside the actions, so they take a row of their own.
    val stacked = isLargeText()

    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(Modifier.padding(horizontal = EdgePadding), verticalAlignment = Alignment.CenterVertically) {
            // Together, so the actions centre on both rather than leaving the second adrift below them.
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.trim_title, title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = if (stacked) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    text = kept,
                    style = MaterialTheme.typography.bodyMedium.tabularFigures(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { contentDescription = keptSpoken },
                )
            }
            if (!stacked) TrimActions(trim)
        }
        if (stacked) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = EdgePadding).padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) { TrimActions(trim) }
        }

        if (profile.hasTime) {
            AxisSelector(
                useTimeAxis = timed,
                onChange = onAxisChange,
                modifier = Modifier.padding(start = EdgePadding, top = 12.dp, end = EdgePadding),
            )
        }

        // At least two points are kept, or there's no track left. The arrows on the margin, their
        // touch targets past it, as Material places edge icon buttons.
        Row(
            Modifier.padding(horizontal = EdgePadding - 12.dp).padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TrimEnd(
                label = stringResource(R.string.trim_start),
                mark = TrimMark.Play,
                markColor = routeColor,
                // What's cut from each end, so both read alike.
                cut = format(x[first]),
                spoken = speak(x[first]),
                active = selectedIndex == first,
                earlier = stringResource(R.string.trim_start_earlier),
                later = stringResource(R.string.trim_start_later),
                onEarlier = { n: Int -> (first - n).coerceAtLeast(0).let { move(it..end, it) } }.takeIf { first > 0 },
                onLater = { n: Int -> (first + n).coerceAtMost(end - 1).let { move(it..end, it) } }
                    .takeIf { first + 1 < end },
                valueInset = PaddingValues(start = 12.dp),
                modifier = Modifier.weight(1f),
            )
            val cutFromEnd = x[last] - x[end]
            TrimEnd(
                label = stringResource(R.string.trim_end),
                mark = TrimMark.Stop,
                markColor = routeColor,
                cut = if (end < last) stringResource(R.string.trim_end_cut, format(cutFromEnd)) else format(0f),
                spoken = stringResource(R.string.trim_end_cut_spoken, speak(cutFromEnd)),
                active = selectedIndex == end,
                earlier = stringResource(R.string.trim_end_earlier),
                later = stringResource(R.string.trim_end_later),
                onEarlier = { n: Int -> (end - n).coerceAtLeast(first + 1).let { move(first..it, it) } }
                    .takeIf { end - 1 > first },
                onLater = { n: Int -> (end + n).coerceAtMost(last).let { move(first..it, it) } }.takeIf { end < last },
                valueInset = PaddingValues(end = 12.dp),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TrimActions(trim: TrimControls) {
    TextButton(onClick = trim.onCancel) { Text(stringResource(R.string.action_cancel)) }
    Spacer(Modifier.width(8.dp))
    // Nothing to save until something is cut.
    Button(onClick = trim.onSave, enabled = trim.moved) { Text(stringResource(R.string.action_save)) }
}

/** One end of a trim, stepped a point per tap, and faster the longer it's held. */
@Composable
private fun TrimEnd(
    label: String,
    /** This end's shape on the map, beside [label] as its legend. */
    mark: TrimMark,
    markColor: Color,
    /** How much is cut from this end. */
    cut: String,
    spoken: String,
    /** Tinted when it's the end the charts and map are showing. */
    active: Boolean,
    earlier: String,
    later: String,
    /** Each moves by the given number of points. */
    onEarlier: ((Int) -> Unit)?,
    onLater: ((Int) -> Unit)?,
    /** On the side whose button reaches past the margin, so the value centres on the selector's segment. */
    valueInset: PaddingValues,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        RepeatingIconButton(R.drawable.ic_chevron_left, earlier, onEarlier)
        Column(
            Modifier.weight(1f).padding(valueInset),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TrimMarkGlyph(mark, markColor)
                Spacer(Modifier.width(4.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Shrunk to fit, not clipped: a value cut short, unit and all, read as another number.
            // Over an unseen full-size line, so a shrunk value leaves the two ends level. Without its
            // line height, which non-linear font scaling grows at the smaller size, past the full line's.
            val style = MaterialTheme.typography.titleMedium.tabularFigures()
            Box(contentAlignment = Alignment.Center) {
                Text("", style = style, modifier = Modifier.alpha(0f).clearAndSetSemantics {})
                Text(
                    text = cut,
                    style = style.copy(lineHeight = TextUnit.Unspecified),
                    color = if (active) MaterialTheme.colorScheme.primary else Color.Unspecified,
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(minFontSize = MinValueSize, maxFontSize = style.fontSize),
                    modifier = Modifier.semantics { contentDescription = spoken },
                )
            }
        }
        RepeatingIconButton(R.drawable.ic_chevron_right, later, onLater)
    }
}

/** The shapes the map marks a trim's moved ends with: play and stop. */
private enum class TrimMark { Play, Stop }

/** [mark] at the label's size, hollow as the map draws it. Decorative; the label says which end. */
@Composable
private fun TrimMarkGlyph(mark: TrimMark, color: Color) {
    val size = with(LocalDensity.current) { MaterialTheme.typography.labelSmall.fontSize.toDp() }
    Canvas(Modifier.size(size)) {
        val stroke = Stroke(width = MarkGlyphStroke.toPx(), join = StrokeJoin.Round)
        // The edge's middle, so its outside meets the box.
        val inset = stroke.width / 2
        val side = this.size.minDimension - stroke.width
        when (mark) {
            // Its flat side in, so its length matches its height across, as an equilateral triangle's.
            TrimMark.Play -> drawPath(
                Path().apply {
                    moveTo(inset + side * PlayInset, inset)
                    lineTo(inset + side, inset + side / 2)
                    lineTo(inset + side * PlayInset, inset + side)
                    close()
                },
                color,
                style = stroke,
            )
            TrimMark.Stop -> drawRoundRect(
                color = color,
                topLeft = Offset(inset, inset),
                size = Size(side, side),
                cornerRadius = CornerRadius(side * MarkGlyphCorner),
                style = stroke,
            )
        }
    }
}

private val MarkGlyphStroke = 2.dp
private const val PlayInset = 0.13f

// As the map's square.
private const val MarkGlyphCorner = 0.2f

/** Steps once on a tap; held, steps again, more at a time the longer, until let go. Null disables it. */
@Composable
private fun RepeatingIconButton(@DrawableRes icon: Int, description: String, onStep: ((Int) -> Unit)?) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val step by rememberUpdatedState(onStep)
    // The release still clicks; skipped once a hold has repeated.
    var repeated by remember { mutableStateOf(false) }
    LaunchedEffect(pressed) {
        if (!pressed) return@LaunchedEffect
        repeated = false
        delay(RepeatDelayMillis)
        var held = 0L
        while (true) {
            step?.invoke(holdSteps(held)) ?: break
            repeated = true
            delay(RepeatIntervalMillis)
            held += RepeatIntervalMillis
        }
    }
    IconButton(
        onClick = { if (!repeated) step?.invoke(1) },
        enabled = onStep != null,
        interactionSource = interactions,
    ) {
        Icon(painterResource(icon), contentDescription = description)
    }
}

/**
 * Points per repeat after a hold of [heldMillis]: one for a second, so a few metres stay easy,
 * then doubling every half second, so a long drive to the start is seconds away rather than minutes.
 */
internal fun holdSteps(heldMillis: Long): Int {
    if (heldMillis < HoldRampAfterMillis) return 1
    val doublings = (heldMillis - HoldRampAfterMillis) / HoldDoublingMillis + 1
    return 1 shl doublings.coerceAtMost(MaxHoldDoublings).toInt()
}

private const val RepeatDelayMillis = 400L
private const val RepeatIntervalMillis = 50L
private const val HoldRampAfterMillis = 1000L
private const val HoldDoublingMillis = 500L
private const val MaxHoldDoublings = 6L
private val MinValueSize = 10.sp

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
        actions?.let { ColorDot(it.colorIndex, it.onColor) }
            ?: Box(Modifier.size(ColorDotSize).clip(CircleShape).background(routeColor))
        Spacer(Modifier.width(12.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = if (isLargeText()) 2 else 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        actions?.let {
            TrackMenu(
                it.onRename,
                it.onShare,
                it.onHide,
                it.onDelete,
                onTrim = it.onTrim,
            )
        }
        onClose?.let {
            IconButton(onClick = it) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.track_close))
            }
        }
    }
}

/** Sized like the peek so the sheet does not jump open. */
@Composable
internal fun TrackSheetLoading(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = TrackSheetPeekHeight)
            .padding(horizontal = EdgePadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CircularProgressIndicator(Modifier.size(20.dp))
        Text(stringResource(R.string.track_loading), style = MaterialTheme.typography.bodyMedium)
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
        listOf(R.string.axis_distance, R.string.axis_time).forEachIndexed { index, label ->
            val time = index == 1
            SegmentedButton(
                selected = useTimeAxis == time,
                onClick = { onChange(time) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(label)) }
        }
    }
}

@Composable
private fun ChartSection(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
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
        modifier = Modifier.padding(horizontal = EdgePadding, vertical = 4.dp),
    )
    Spacer(Modifier.height(20.dp))
    // Disabled, not hidden, so nothing shifts once the charts fill in.
    AxisSelector(
        useTimeAxis = useTimeAxis,
        onChange = {},
        enabled = false,
        modifier = Modifier.padding(horizontal = EdgePadding),
    )
    Spacer(Modifier.height(16.dp))
    val message = stringResource(R.string.chart_waiting)
    for (title in listOf(R.string.chart_speed, R.string.chart_elevation)) {
        ChartSection(
            title = stringResource(title),
            modifier = Modifier.padding(horizontal = EdgePadding),
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

/**
 * A screen reader action per waypoint, in time order: "Waypoint 3, 10:42 AM: bridge closed".
 * The date joins the time off [trackStart]'s day; a long note is cut, as the tooltip has it whole.
 */
@Composable
private fun waypointActions(
    waypoints: List<Waypoint>,
    trackStart: Instant?,
    onSelect: (Waypoint) -> Unit,
): List<CustomAccessibilityAction> {
    val formatters = LocalFormatters.current
    val resources = LocalResources.current
    val select by rememberUpdatedState(onSelect)
    return remember(waypoints, trackStart, formatters, resources) {
        val zone = ZoneId.systemDefault()
        val startDay = trackStart?.atZone(zone)?.toLocalDate()
        // Stable, so undated ones keep file order after the dated.
        waypoints.sortedWith(compareBy(nullsLast()) { it.point.time }).mapIndexed { i, waypoint ->
            val time = waypoint.point.time?.let {
                if (it.atZone(zone).toLocalDate() == startDay) formatters.time(it) else formatters.dateTime(it)
            }
            val note = waypoint.name?.trim()?.takeIf(String::isNotEmpty)?.let {
                if (it.length > NOTE_LABEL_LENGTH) it.take(NOTE_LABEL_LENGTH).trimEnd() + "…" else it
            }
            val number = i + 1
            val label = when {
                time != null && note != null -> resources.getString(
                    R.string.waypoint_action_time_note,
                    number,
                    time,
                    note,
                )
                time != null -> resources.getString(R.string.waypoint_action_time, number, time)
                note != null -> resources.getString(R.string.waypoint_action_note, number, note)
                else -> resources.getString(R.string.record_waypoint_title, number)
            }
            CustomAccessibilityAction(label) {
                select(waypoint)
                true
            }
        }
    }
}

private const val NOTE_LABEL_LENGTH = 60
