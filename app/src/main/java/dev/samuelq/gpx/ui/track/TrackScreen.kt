package dev.samuelq.gpx.ui.track

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.samuelq.gpx.R
import dev.samuelq.gpx.data.track.LoadedTrack
import dev.samuelq.gpx.ui.chart.ChartSeries
import dev.samuelq.gpx.ui.chart.ProfileChart
import dev.samuelq.gpx.ui.chart.axisScale
import dev.samuelq.gpx.ui.format.Formatters
import dev.samuelq.gpx.ui.theme.LocalChartColors

private val ScreenPadding = 20.dp

/**
 * Enough of the sheet to keep the headline stats on screen at its lowest detent. The sheet
 * is deliberately not dismissible: the route is context, the numbers are the point, and a
 * state where the numbers are gone entirely is not worth reaching.
 */
private val SheetPeekHeight = 168.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackScreen(
    ref: TrackRef,
    onBack: () -> Unit,
) {
    // No key: the ViewModel is scoped to this NavBackStackEntry, which is already one per
    // destination on the stack.
    val viewModel: TrackViewModel = viewModel(factory = TrackViewModel.Factory)
    LaunchedEffect(ref) { viewModel.load(ref) }

    val state by viewModel.state.collectAsStateWithLifecycle()

    when (val current = state) {
        is TrackUiState.Ready -> TrackContent(loaded = current.track, onBack = onBack)

        TrackUiState.Loading -> Plain(title = null, onBack = onBack) { padding ->
            CenteredMessage(
                text = stringResource(R.string.track_loading),
                modifier = Modifier.padding(padding),
                showSpinner = true,
            )
        }

        is TrackUiState.Failed -> Plain(title = null, onBack = onBack) { padding ->
            ErrorState(
                messageRes = current.messageRes,
                onRetry = viewModel::retry,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

/**
 * Route behind, numbers in front.
 *
 * The route is the canvas and the analysis rides over it in a sheet, rather than both
 * competing for room in one scroll. That also settles the gesture question: the sheet's
 * handle owns vertical drag, and everything above it belongs to the route.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackContent(
    loaded: LoadedTrack,
    onBack: () -> Unit,
) {
    val profile = loaded.profile
    val chartColors = LocalChartColors.current

    // Reset when the track changes: an index into a different track is meaningless.
    var selectedIndex by remember(profile) { mutableStateOf<Int?>(null) }
    var preferTimeAxis by rememberSaveable { mutableStateOf(true) }
    val useTimeAxis = preferTimeAxis && profile.hasTime

    // One track, so its own extent is the extent: no union to take.
    val layers = remember(profile, chartColors) {
        val bounds = GeoBounds.of(profile.points) ?: return@remember emptyList()
        val path = routePathOf(profile.points, profile.segmentStartIndices, bounds)
            ?: return@remember emptyList()
        listOf(RouteLayer(trackId = loaded.id, path = path, color = chartColors.speed))
    }

    val scaffoldState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(
            initialValue = SheetValue.PartiallyExpanded,
            // The one state this sheet must never reach. Without it a downward fling
            // hides the summary and leaves a screen that answers nothing.
            skipHiddenState = true,
        ),
    )

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = SheetPeekHeight,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = loaded.track.name?.takeIf(String::isNotBlank) ?: loaded.displayName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
            )
        },
        sheetContent = {
            SheetContent(
                loaded = loaded,
                selectedIndex = selectedIndex,
                onSelectedIndexChange = { selectedIndex = it },
                useTimeAxis = useTimeAxis,
                onAxisChange = { preferTimeAxis = it },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            if (layers.isEmpty()) {
                Unavailable(stringResource(R.string.route_empty))
            } else {
                RouteCanvas(
                    layers = layers,
                    contentDescription = stringResource(R.string.route_description),
                    selectedIndex = selectedIndex,
                    markerLayerId = loaded.id,
                    markerColor = chartColors.speed,
                    markerRingColor = MaterialTheme.colorScheme.surface,
                    onSelect = { _, index -> selectedIndex = index },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetContent(
    loaded: LoadedTrack,
    selectedIndex: Int?,
    onSelectedIndexChange: (Int?) -> Unit,
    useTimeAxis: Boolean,
    onAxisChange: (Boolean) -> Unit,
) {
    val profile = loaded.profile
    val stats = profile.stats
    val chartColors = LocalChartColors.current

    val xValues = if (useTimeAxis) profile.elapsedSeconds else profile.distanceMeters
    // One domain, shared by both charts, so the same pixel column is the same moment in
    // each and the scrubber means the same thing in both - and in the route above.
    val xScale = remember(profile, useTimeAxis) {
        axisScale(xValues.firstOrNull() ?: 0f, xValues.lastOrNull() ?: 1f)
    }
    val formatX: (Float) -> String = if (useTimeAxis) Formatters.DurationAxis else Formatters.DistanceAxis

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        TrackSummary(
            stats = stats,
            hasTime = profile.hasTime,
            hasElevation = profile.hasElevation,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )

        Spacer(Modifier.height(20.dp))

        if (profile.hasTime) {
            AxisSelector(
                useTimeAxis = useTimeAxis,
                onChange = onAxisChange,
                modifier = Modifier.padding(horizontal = ScreenPadding),
            )
            Spacer(Modifier.height(4.dp))
        }

        ScrubReadout(
            profile = profile,
            selectedIndex = selectedIndex,
            useTimeAxis = useTimeAxis,
            onClear = { onSelectedIndexChange(null) },
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )

        Spacer(Modifier.height(12.dp))

        ChartSection(
            title = stringResource(R.string.chart_speed),
            unit = "km/h",
            modifier = Modifier.padding(horizontal = ScreenPadding),
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
                    formatY = Formatters.SpeedAxis,
                    selectedIndex = selectedIndex,
                    onSelectedIndexChange = onSelectedIndexChange,
                    contentDescription = stringResource(R.string.chart_speed),
                    highlightIndex = stats.maxSpeedIndex,
                    highlightLabel = Formatters.speed(stats.maxSpeedMps),
                )
            }
        }

        Spacer(Modifier.height(28.dp))

        ChartSection(
            title = stringResource(R.string.chart_elevation),
            unit = "m",
            modifier = Modifier.padding(horizontal = ScreenPadding),
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
                        // An elevation profile sits on a baseline near the low point, as
                        // every mapping tool draws it. The y axis labels that baseline
                        // explicitly so it can't be mistaken for sea level.
                        zeroBased = false,
                    )
                }
                ProfileChart(
                    series = series,
                    xScale = xScale,
                    formatX = formatX,
                    formatY = Formatters.ElevationAxis,
                    selectedIndex = selectedIndex,
                    onSelectedIndexChange = onSelectedIndexChange,
                    contentDescription = stringResource(R.string.chart_elevation),
                    highlightIndex = stats.maxElevationIndex,
                    highlightLabel = stats.maxElevationMeters?.let { Formatters.elevation(it) },
                )
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

/** The plain scaffold the loading and error states use, with the same chrome. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Plain(
    title: String?,
    onBack: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title ?: stringResource(R.string.app_name), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
            )
        },
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
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
        modifier = Modifier.padding(vertical = 24.dp, horizontal = ScreenPadding),
    )
}

@Composable
private fun CenteredMessage(
    text: String,
    modifier: Modifier = Modifier,
    showSpinner: Boolean = false,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (showSpinner) CircularProgressIndicator()
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ErrorState(
    messageRes: Int,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().padding(ScreenPadding), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
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
            TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        }
    }
}
