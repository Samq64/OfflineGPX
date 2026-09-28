package dev.samuelq.gpx.ui.map

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.samuelq.gpx.R
import dev.samuelq.gpx.core.model.Waypoint
import dev.samuelq.gpx.data.record.RecordingController
import dev.samuelq.gpx.data.record.RecordingEvent
import dev.samuelq.gpx.data.record.RecordingState
import dev.samuelq.gpx.data.track.LoadedTrack
import dev.samuelq.gpx.ui.record.RecoveredRecordingDialog
import dev.samuelq.gpx.ui.showUndo
import dev.samuelq.gpx.ui.theme.recordingColor
import dev.samuelq.gpx.ui.theme.routePalette
import dev.samuelq.gpx.ui.theme.slot
import dev.samuelq.gpx.ui.track.FocusedTrack
import dev.samuelq.gpx.ui.track.TrackActions
import dev.samuelq.gpx.ui.track.TrackNameDialog
import dev.samuelq.gpx.ui.track.TrackRef
import dev.samuelq.gpx.ui.track.TrackSheetPeekHeight
import dev.samuelq.gpx.ui.track.editableTrackName
import dev.samuelq.gpx.ui.track.shareTrackIntent
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** Shown tracks overlaid, the focused one in a sheet or side panel, and the live recording. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    pendingFocus: TrackRef?,
    onFocusConsumed: () -> Unit,
    onOpenList: () -> Unit,
    onOpenSettings: () -> Unit,
    recorder: RecordingController,
    viewModel: MapViewModel = viewModel(factory = MapViewModel.Factory),
) {
    val context = LocalContext.current
    // Not context.getString: a long-lived collector would keep the old locale.
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val trace by viewModel.trace.collectAsStateWithLifecycle()
    val focused by viewModel.focused.collectAsStateWithLifecycle()
    val recording by recorder.state.collectAsStateWithLifecycle()
    val basemaps by viewModel.basemaps.collectAsStateWithLifecycle()

    val palette = routePalette()
    val liveColor = recordingColor()
    val density = LocalDensity.current

    val snackbarHostState = remember { SnackbarHostState() }
    // By id: the prompt waits for the track to load, by which time the sheet may show another.
    var renamingId by remember { mutableStateOf<Long?>(null) }
    // Never read here: that would recompose the screen every frame of a pan. ScaleBar reads it.
    val metersPerPixel = remember { mutableDoubleStateOf(0.0) }

    val trackImporter = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::importTrack) }

    // Distance by default: on a time axis a stop becomes a gap as wide as the stop.
    var preferTimeAxis by rememberSaveable { mutableStateOf(false) }

    val focusedTrack = (focused as? FocusedTrack.Ready)?.track
    var selectedIndex by remember(focusedTrack?.id) { mutableStateOf<Int?>(null) }
    var tappedWaypoint by remember(focusedTrack?.id) { mutableStateOf<Waypoint?>(null) }
    // Read only by the tooltip's layout, so panning doesn't recompose this screen.
    val tappedWaypointAt = remember { mutableStateOf(Offset.Zero) }

    val discarded = stringResource(R.string.record_discarded)
    val renameFailed = stringResource(R.string.library_rename_failed)
    val hidden = stringResource(R.string.track_hidden)
    val importFailed = stringResource(R.string.library_import_failed)
    val saveFailed = stringResource(R.string.record_save_failed)

    val undo = stringResource(R.string.action_undo)
    val deleted = pluralStringResource(R.plurals.library_deleted, 1, 1)

    // Replaces rather than queues: a stale answer to a tap is misleading. Replacing an undo
    // commits it.
    fun say(message: String) = scope.launch {
        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarHostState.showSnackbar(message)
    }

    fun offerUndo(message: String, onUndo: () -> Unit, onCommit: () -> Unit = {}) = scope.launch {
        snackbarHostState.showUndo(message, undo, onUndo, onCommit)
    }

    val startRecording = rememberStartRecording(recorder, ::say)


    // Tracks opened from the list or an intent are framed once loaded; map taps never move the camera.
    var framing by remember { mutableStateOf<TrackRef?>(null) }
    LaunchedEffect(focused) {
        if (focused is FocusedTrack.None || focused is FocusedTrack.Failed) framing = null
    }

    LaunchedEffect(pendingFocus) {
        pendingFocus?.let {
            framing = it
            viewModel.focus(it)
            onFocusConsumed()
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            when (message) {
                MapMessage.RenameFailed -> say(renameFailed)
                MapMessage.ImportFailed -> say(importFailed)
                MapMessage.RecoveryFailed -> say(saveFailed)
                is MapMessage.Hidden -> offerUndo(hidden, onUndo = { viewModel.show(message.id) })
                is MapMessage.AbandonedDiscarded -> offerUndo(
                    discarded,
                    onUndo = { viewModel.restoreAbandoned(message.recording, message.name) },
                    onCommit = { viewModel.forgetAbandoned(message.recording) },
                )
            }
        }
    }

    LaunchedEffect(recorder) {
        recorder.events.collect { event ->
            when (event) {
                is RecordingEvent.Saved -> viewModel.focus(TrackRef.Saved(event.id))
                is RecordingEvent.Discarded -> event.recording?.let { recording ->
                    offerUndo(
                        discarded,
                        onUndo = { viewModel.restoreDiscarded(recording) },
                        onCommit = { viewModel.forgetDiscarded(recording) },
                    )
                } ?: say(discarded)
                is RecordingEvent.Failed -> say(resources.getString(event.messageRes))
            }
        }
    }

    // --- The sheet -----------------------------------------------------------------

    // Not skipping Hidden, so the sheet can go away entirely.
    val sheetState = rememberStandardBottomSheetState(
        initialValue = SheetValue.Hidden,
        skipHiddenState = false,
    )
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)
    val hasFocus = focused != FocusedTrack.None

    // Landscape uses a side panel; the sheet stays composed but hidden so rotation keeps the track.
    val windowSize = LocalWindowInfo.current.containerSize
    val sidePanel = windowSize.width > windowSize.height
    val currentSidePanel by rememberUpdatedState(sidePanel)

    LaunchedEffect(hasFocus, sidePanel) {
        // Guarded: hiding before layout asks for an anchor that doesn't exist.
        if (hasFocus && !sidePanel) sheetState.partialExpand()
        else if (sheetState.currentValue != SheetValue.Hidden) sheetState.hide()
        // So a stale name prompt can't reappear when the track is reopened.
        if (!hasFocus) renamingId = null
    }

    // Swiping the sheet away clears focus. drop(1): the initial Hidden emission would clear a
    // focus just set from the list.
    LaunchedEffect(sheetState) {
        snapshotFlow { sheetState.currentValue }.drop(1).collect { value ->
            // Not when hidden for the side panel.
            if (value == SheetValue.Hidden && !currentSidePanel) viewModel.focus(null)
        }
    }

    // Back collapses an expanded sheet before closing it.
    BackHandler(enabled = hasFocus) {
        if (!sidePanel && sheetState.currentValue == SheetValue.Expanded) {
            scope.launch { sheetState.partialExpand() }
        } else {
            viewModel.focus(null)
        }
    }

    // --- What the canvas draws, and how much room it has ---------------------------

    // The recording is kept out: it grows every few seconds and would rebuild every track.
    // Keyed on the focused id, not the track: a rebuilt overlay loses its measured extent.
    val overlays = remember(state.entities, state.geometry, focusedTrack?.id, palette) {
        val drawable = state.entities.mapNotNull { state.geometry[it.id] }
        // Include the focused track even if hidden, so its readout has a line to go with.
        val unlisted = focusedTrack?.takeIf { focus -> drawable.none { it.id == focus.id } }
        // Colour from the track, not its position, so it's stable across taps.
        (drawable + listOfNotNull(unlisted)).map { it.toOverlay(palette.slot(it.colorIndex)) }
    }

    // Only the recording's and the focused track's waypoints.
    val mapWaypoints = ((recording as? RecordingState.Active)?.waypoints ?: emptyList()) +
        (focusedTrack?.track?.waypoints ?: emptyList())

    val liveOverlay = remember(trace, liveColor) {
        if (trace.points.isEmpty()) {
            null
        } else {
            RouteOverlay(
                trackId = LIVE_TRACK_ID,
                points = trace.points,
                segmentStartIndices = trace.segmentStartIndices,
                color = liveColor,
            )
        }
    }

    val windowHeight = with(density) { windowSize.height.toDp() }
    // The cutout is added on top of the minimum width.
    val panelInset = WindowInsets.safeDrawing.only(WindowInsetsSides.Start).asPaddingValues()
        .calculateStartPadding(LocalLayoutDirection.current)
    val panelWidth = maxOf(
        SidePanelMinWidth + panelInset,
        with(density) { windowSize.width.toDp() } * SidePanelWindowFraction,
    )
    // Animated, unlike sheetCover: the panel only moves on a tap, never a drag.
    val panelCover by animateDpAsState(
        targetValue = if (sidePanel && hasFocus) panelWidth else 0.dp,
        label = "panelCover",
    )
    // One expanded height: the content scrolls inside it.
    val sheetMaxHeight = windowHeight * SheetMaxHeightFraction

    val navigationBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Measured off the loaded sheet; the fixed height stands in until there is one.
    var peekContentHeight by remember { mutableStateOf(0.dp) }
    val peekHeight = if (focused is FocusedTrack.Ready && peekContentHeight > 0.dp) {
        maxOf(TrackSheetPeekHeight, DragHandleHeight + peekContentHeight + navigationBarInset)
    } else {
        TrackSheetPeekHeight
    }
    // From the live offset: the settled value only updates after a drag, so dependents lagged.
    var scaffoldHeight by remember { mutableIntStateOf(0) }
    val sheetCover by remember {
        derivedStateOf {
            val offset = runCatching { sheetState.requireOffset() }.getOrNull() ?: return@derivedStateOf 0.dp
            with(density) { (scaffoldHeight - offset).coerceAtLeast(0f).toDp() }
        }
    }
    // Controls ride the sheet up to its peek; an expanded sheet covers them.
    val sheetInset = sheetCover.coerceIn(navigationBarInset, maxOf(navigationBarInset, peekHeight))
    // Floating controls must be counted, or the fit puts part of a route behind them.
    var controlsHeight by remember { mutableStateOf(0.dp) }

    val coveredHeight = maxOf(sheetCover, sheetInset + controlsHeight)

    val canvasPadding = PaddingValues(
        start = MapEdgePadding + panelCover,
        end = MapEdgePadding,
        top = MapEdgePadding,
        bottom = MapEdgePadding + coveredHeight,
    )

    // Settled padding, so the frame isn't fitted to a screen the sheet is about to cover.
    val framePadding = PaddingValues(
        start = MapEdgePadding + if (sidePanel) panelWidth else 0.dp,
        end = MapEdgePadding,
        top = MapEdgePadding,
        bottom = MapEdgePadding + controlsHeight + if (sidePanel) navigationBarInset else peekHeight,
    )
    val frameTrackId = focusedTrack?.id
        ?.takeIf { id ->
            when (val ref = framing) {
                is TrackRef.Saved -> ref.id == id
                is TrackRef.Transient -> id == LoadedTrack.TRANSIENT_ID
                null -> false
            }
        }
        // The peek is measured off the loaded sheet.
        ?.takeIf { sidePanel || peekContentHeight > 0.dp }

    // Null for an intent-opened file: no row to act on, and sharing would hand it back to itself.
    val actions = focusedTrack?.let { state.entity(it.id) }?.let { entity ->
        remember(entity.id, entity.displayName, entity.location) {
            TrackActions(
                onRename = { renamingId = entity.id },
                onShare = {
                    context.startActivity(
                        shareTrackIntent(context, entity.location, entity.trackName, entity.displayName)
                    )
                },
                onHide = {
                    viewModel.hide(entity.id)
                    viewModel.focus(null)
                },
                onDelete = {
                    // Unfocus first so the sheet doesn't show a deleted row.
                    viewModel.focus(null)
                    viewModel.delete(entity.id)
                    offerUndo(
                        deleted,
                        onUndo = { viewModel.undoDelete(entity.id) },
                        onCommit = { viewModel.commitDelete(entity.id) },
                    )
                },
            )
        }
    }

    // Shared by the sheet and the side panel.
    val trackContent: @Composable (FocusedTrack, Dp, (() -> Unit)?, (Dp) -> Unit) -> Unit =
        { current, maxHeight, onClose, onPeekHeightChange ->
            FocusedTrackContent(
                focused = current,
                palette = palette,
                maxHeight = maxHeight,
                selectedIndex = selectedIndex,
                onSelectedIndexChange = { selectedIndex = it },
                preferTimeAxis = preferTimeAxis,
                onAxisChange = { preferTimeAxis = it },
                actions = actions,
                onRetry = viewModel::retryFocus,
                onDismiss = { viewModel.focus(null) },
                onClose = onClose,
                onPeekHeightChange = onPeekHeightChange,
            )
        }

    BottomSheetScaffold(
        modifier = Modifier.onSizeChanged { scaffoldHeight = it.height },
        scaffoldState = scaffoldState,
        sheetPeekHeight = peekHeight,
        sheetDragHandle = { CompactDragHandle() },
        // A step off the map's background so the sheet's edge stays visible.
        sheetContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            state.totalCount == 0 -> stringResource(R.string.app_name)
                            state.entities.isEmpty() -> stringResource(R.string.map_none_shown)
                            else -> pluralStringResource(
                                R.plurals.map_shown,
                                state.entities.size,
                                state.entities.size,
                            )
                        }
                    )
                },
                actions = {
                    IconButton(onClick = onOpenList) {
                        Icon(
                            Icons.AutoMirrored.Filled.List,
                            stringResource(R.string.map_open_list),
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, stringResource(R.string.settings_open))
                    }
                },
            )
        },
        sheetContent = {
            // Placeholder at peek height: shorter content leaves the scaffold nothing to anchor to.
            if (sidePanel || focused == FocusedTrack.None) {
                Spacer(Modifier.fillMaxWidth().height(TrackSheetPeekHeight))
            } else {
                trackContent(focused, sheetMaxHeight, null) { peekContentHeight = it }
            }
        },
    ) { padding ->
        // Top padding only: the map runs under the sheet so the sheet can hide fully.
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {

            // Always composed: a basemap is worth showing with no tracks.
            OfflineMapCanvas(
                routes = overlays,
                liveRoute = liveOverlay,
                basemaps = basemaps,
                // So the cold-start frame waits for tracks instead of settling on the bare basemap.
                tracksLoading = state.loading,
                contentDescription = stringResource(R.string.map_description),
                focusedTrackId = focusedTrack?.id,
                selectedIndex = selectedIndex,
                markerColor = focusedTrack
                    ?.let { palette.slot(it.colorIndex) }
                    ?: MaterialTheme.colorScheme.primary,
                showPuck = recording is RecordingState.Active,
                puckColor = liveColor,
                waypoints = mapWaypoints,
                onSelect = { trackId, index ->
                    // An open note takes the first tap, so closing it never moves the marker.
                    if (tappedWaypoint != null) tappedWaypoint = null
                    else when (trackId) {
                        // The recording has no row to open and no numbers to scrub.
                        LIVE_TRACK_ID -> Unit
                        focusedTrack?.id -> selectedIndex = index
                        else -> viewModel.focus(TrackRef.Saved(trackId))
                    }
                },
                onSelectNothing = {
                    if (tappedWaypoint != null) tappedWaypoint = null else viewModel.focus(null)
                },
                onSelectWaypoint = { waypoint ->
                    tappedWaypoint = waypoint
                    // Only the focused track's waypoints have a chart position.
                    focusedTrack
                        ?.takeIf { waypoint in it.track.waypoints }
                        ?.profile?.indexOf(waypoint.point)
                        ?.takeIf { it >= 0 }
                        ?.let { selectedIndex = it }
                },
                followedWaypoint = tappedWaypoint,
                onFollowedWaypointMove = { tappedWaypointAt.value = it },
                contentPadding = canvasPadding,
                frameTrackId = frameTrackId,
                framePadding = framePadding,
                onFramed = { framing = null },
                sheetHeight = sheetCover,
                panelWidth = panelCover,
                // Distinct from land so ground no file covers reads as empty.
                backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow,
                landColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                labelColor = MaterialTheme.colorScheme.onSurface,
                onScaleChange = { metersPerPixel.doubleValue = it },
                // Resume where the camera was left rather than re-fitting.
                initialCamera = viewModel.lastCamera,
                onCameraChange = viewModel::rememberCamera,
                modifier = Modifier.fillMaxSize(),
            )

            tappedWaypoint?.let { tapped ->
                WaypointTooltip(tapped, tipAt = { tappedWaypointAt.value })
            }

            // First run only; hidden-by-choice gets the hint below.
            val mapIsEmpty = overlays.isEmpty() && liveOverlay == null &&
                !state.loading && basemaps.isEmpty() && state.totalCount == 0

            val allHidden = overlays.isEmpty() && liveOverlay == null &&
                !state.loading && basemaps.isEmpty() && state.totalCount > 0

            // Nothing to measure against without a basemap or route.
            val hasContent = overlays.isNotEmpty() || liveOverlay != null || basemaps.isNotEmpty()

            when {
                overlays.isNotEmpty() || liveOverlay != null -> Unit

                state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    LinearProgressIndicator(Modifier.padding(32.dp))
                }

                mapIsEmpty -> EmptyState(
                    onImportMap = onOpenSettings,
                    onImportTrack = { trackImporter.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxSize(),
                )

                allHidden -> ShowTracksHint(
                    onClick = onOpenList,
                    modifier = Modifier.align(Alignment.Center),
                )

                else -> Unit
            }

            if (hasContent) {
                ScaleBar(
                    // The state, not its value: the bar re-reads it as the camera moves.
                    metersPerPixel = metersPerPixel,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = panelCover + 12.dp, bottom = sheetInset + 4.dp),
                )
            }

            // The bar pads the inset inside its own surface so the map can't show under it, and
            // it's excluded from controlsHeight.
            val barInset = if (recording is RecordingState.Active) sheetInset else 0.dp
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = panelCover, bottom = sheetInset - barInset)
                    .onSizeChanged { controlsHeight = with(density) { it.height.toDp() } - barInset },
            ) {
                RecordControls(
                    recording = recording,
                    onStart = startRecording,
                    onPause = recorder::pause,
                    onResume = recorder::resume,
                    onStop = recorder::requestStop,
                    onAddWaypoint = recorder::addWaypoint,
                    bottomInset = barInset,
                )
            }

            TrackSidePanel(
                visible = sidePanel && hasFocus,
                focused = focused,
                width = panelWidth,
                modifier = Modifier.align(Alignment.TopStart),
            ) { shown ->
                trackContent(shown, Dp.Unspecified, { viewModel.focus(null) }) {}
            }

            // Here, not the scaffold's slot, which pins it to the bottom edge over the record
            // controls. Sits on whichever reaches higher: the controls or the sheet.
            SnackbarHost(
                snackbarHostState,
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = panelCover, bottom = coveredHeight),
            )
        }
    }

    // Only once loaded, which is what knows the name to offer.
    focusedTrack?.takeIf { it.id == renamingId }?.let { track ->
        TrackNameDialog(
            initialName = editableTrackName(track.track.name, track.displayName),
            // Prefilled, not a hint: dismissing keeps what's shown.
            onDismiss = { renamingId = null },
            onConfirm = { name ->
                viewModel.rename(track.id, name)
                renamingId = null
            },
        )
    }

    val abandoned by viewModel.abandoned.collectAsStateWithLifecycle()
    abandoned?.let { recording ->
        RecoveredRecordingDialog(
            recording = recording,
            onSave = viewModel::saveAbandoned,
            onDiscard = viewModel::discardAbandoned,
        )
    }
}

/** Live recording layer id; it has no library row, so taps on it resolve to nothing. */
private const val LIVE_TRACK_ID = Long.MIN_VALUE

private val MapEdgePadding = 24.dp

/** Leaves the route visible above an expanded sheet. */
private const val SheetMaxHeightFraction = 0.72f

/** The landscape panel: at least this past any cutout, or this share of the window if wider. */
private val SidePanelMinWidth = 400.dp

private const val SidePanelWindowFraction = 1f / 3
