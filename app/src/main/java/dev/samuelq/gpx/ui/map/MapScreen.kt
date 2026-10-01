package dev.samuelq.gpx.ui.map

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.tappableElement
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
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.samuelq.gpx.R
import dev.samuelq.gpx.core.model.Waypoint
import dev.samuelq.gpx.data.record.RecordingController
import dev.samuelq.gpx.data.record.RecordingState
import dev.samuelq.gpx.ui.record.RecordingSheet
import dev.samuelq.gpx.ui.record.RecordingOutcomes
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
import dev.samuelq.gpx.ui.track.trackTitle
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
    showZoomButtons: Boolean,
    viewModel: MapViewModel = viewModel(factory = MapViewModel.Factory),
    location: LocationViewModel = viewModel(factory = LocationViewModel.Factory),
) {
    val mapController = remember { MapController() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val trace by viewModel.trace.collectAsStateWithLifecycle()
    val live by viewModel.live.collectAsStateWithLifecycle()
    val focused by viewModel.focused.collectAsStateWithLifecycle()
    // Not read here but where it's shown: it changes every second, and this screen is large.
    val recording = recorder.state.collectAsStateWithLifecycle()
    val isRecording by remember { derivedStateOf { recording.value is RecordingState.Active } }
    val basemaps by viewModel.basemaps.collectAsStateWithLifecycle()
    val locating by location.locating.collectAsStateWithLifecycle()
    val position by location.position.collectAsStateWithLifecycle()

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
    // Name and colour are the row's; the file's name stands in until a new import's row arrives.
    val focusedRow = focusedTrack?.let { state.entity(it.id) }
    val focusedColor = palette.slot(focusedRow?.colorIndex ?: 0)
    val focusedTitle = when {
        focusedRow != null -> trackTitle(focusedRow.trackName, focusedRow.displayName)
        focusedTrack != null -> trackTitle(focusedTrack.track.name, focusedTrack.displayName)
        else -> ""
    }
    // The recording takes the sheet over; other tracks wait until it stops.
    val subject = when {
        isRecording -> SheetSubject.Recording
        focused != FocusedTrack.None -> SheetSubject.Track(focused)
        else -> null
    }
    var selectedIndex by remember(focusedTrack?.id, isRecording) { mutableStateOf<Int?>(null) }
    var tappedWaypoint by remember(focusedTrack?.id, isRecording) { mutableStateOf<Waypoint?>(null) }
    // Read only by the tooltip's layout, so panning doesn't recompose this screen.
    val tappedWaypointAt = remember { mutableStateOf(Offset.Zero) }

    val renameFailed = stringResource(R.string.library_rename_failed)
    val hidden = stringResource(R.string.track_hidden)
    val importFailed = stringResource(R.string.library_import_failed)
    val stopToOpen = stringResource(R.string.record_stop_to_open)
    val locationOff = stringResource(R.string.map_location_off)
    val locationDenied = stringResource(R.string.map_location_denied)

    val undo = stringResource(R.string.action_undo)
    val deleted = pluralStringResource(R.plurals.library_deleted, 1, 1)

    // Replaces rather than queues: a stale answer to a tap is misleading. Replacing an undo
    // commits it.
    fun say(message: String) = scope.launch {
        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarHostState.showSnackbar(message)
    }

    fun offerUndo(message: String, onUndo: () -> Unit, onCommit: () -> Unit = {}) = scope.launch {
        snackbarHostState.showUndo(context, message, undo, onUndo, onCommit)
    }

    // A ride starts where the user is, which may be nowhere near the view; centred on its first point.
    var centreOnRecording by remember { mutableStateOf(false) }
    val requestRecording = rememberStartRecording(recorder, ::say)
    val startRecording = {
        centreOnRecording = true
        requestRecording()
    }
    val firstRecorded = trace.takeIf { isRecording && it.size > 0 }?.first()
    LaunchedEffect(firstRecorded, centreOnRecording) {
        val at = firstRecorded?.takeIf { centreOnRecording } ?: return@LaunchedEffect
        if (mapController.centreOn(at) != CentreResult.NotLaidOut) centreOnRecording = false
    }

    // On the first fix after a tap; later ones move the dot, not the map.
    var centreOnFix by remember { mutableStateOf(false) }
    val showLocation = rememberShowLocation(
        isGpsEnabled = { location.isGpsEnabled },
        onShow = {
            location.showLocation(true)
            centreOnFix = true
        },
        say = ::say,
    )
    val outside = stringResource(R.string.map_location_outside)
    LaunchedEffect(position, centreOnFix) {
        val at = position?.takeIf { centreOnFix } ?: return@LaunchedEffect
        when (mapController.centreOn(at)) {
            CentreResult.Centred -> centreOnFix = false
            CentreResult.OutOfBounds -> {
                centreOnFix = false
                say(outside)
            }
            CentreResult.NotLaidOut -> Unit
        }
    }
    val liveWaypoints by remember {
        derivedStateOf { (recording.value as? RecordingState.Active)?.waypoints.orEmpty() }
    }


    // Tracks opened from the list or an intent are framed once loaded; map taps never move the camera.
    var framing by remember { mutableStateOf<TrackRef?>(null) }
    LaunchedEffect(focused) {
        if (focused is FocusedTrack.None || focused is FocusedTrack.Failed) framing = null
    }

    LaunchedEffect(pendingFocus) {
        pendingFocus?.let {
            if (isRecording) {
                say(stopToOpen)
            } else {
                framing = it
                viewModel.focus(it)
            }
            onFocusConsumed()
        }
    }

    LaunchedEffect(isRecording) {
        if (isRecording) viewModel.focus(null)
    }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            when (message) {
                MapMessage.RenameFailed -> say(renameFailed)
                MapMessage.ImportFailed -> say(importFailed)
                is MapMessage.Hidden -> offerUndo(hidden, onUndo = { viewModel.show(message.id) })
            }
        }
    }

    LaunchedEffect(location) {
        location.stopped.collect { reason ->
            when (reason) {
                LocationStopped.OFF -> say(locationOff)
                LocationStopped.DENIED -> say(locationDenied)
            }
        }
    }

    RecordingOutcomes(
        recorder = recorder,
        say = ::say,
        offerUndo = { message, onUndo, onCommit -> offerUndo(message, onUndo, onCommit) },
        onSaved = { viewModel.focus(TrackRef.Saved(it)) },
    )

    // --- The sheet -----------------------------------------------------------------

    val currentIsRecording by rememberUpdatedState(isRecording)
    // Not skipping Hidden, so the sheet can go away entirely, except when it holds the
    // recording's controls.
    val sheetState = rememberStandardBottomSheetState(
        initialValue = SheetValue.Hidden,
        confirmValueChange = { it != SheetValue.Hidden || !currentIsRecording },
        skipHiddenState = false,
    )
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)
    val hasFocus = focused != FocusedTrack.None
    val hasSheet = subject != null
    // Kept while the sheet slides away, so it doesn't go blank first.
    var lastSubject by remember { mutableStateOf<SheetSubject?>(null) }
    LaunchedEffect(subject) { if (subject != null) lastSubject = subject }
    val sheetSubject = subject ?: lastSubject.takeIf { sheetState.currentValue != SheetValue.Hidden }

    // Landscape uses a side panel; the sheet stays composed but hidden so rotation keeps the track.
    val windowSize = LocalWindowInfo.current.containerSize
    val sidePanel = windowSize.width > windowSize.height
    val currentSidePanel by rememberUpdatedState(sidePanel)

    LaunchedEffect(hasSheet, sidePanel) {
        // Guarded: hiding before layout asks for an anchor that doesn't exist.
        if (hasSheet && !sidePanel) sheetState.partialExpand()
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

    // Back collapses an expanded sheet before closing it. The recording's only collapses.
    val sheetExpanded = !sidePanel && sheetState.currentValue == SheetValue.Expanded
    BackHandler(enabled = hasFocus || sheetExpanded) {
        if (sheetExpanded) {
            scope.launch { sheetState.partialExpand() }
        } else {
            viewModel.focus(null)
        }
    }

    // --- What the canvas draws, and how much room it has ---------------------------

    // The recording is kept out: it grows every few seconds and would rebuild every track.
    // Keyed on the focused id, not the track: a rebuilt overlay loses its measured extent.
    val overlays = remember(state.entities, state.geometry, focusedTrack?.id, focusedColor, palette) {
        // Colour from the row, not its position, so it's stable across taps.
        val drawable = state.entities.mapNotNull { row ->
            state.geometry[row.id]?.toOverlay(palette.slot(row.colorIndex), row.bounds)
        }
        // Include the focused track even if hidden, so its readout has a line to go with.
        val unlisted = focusedTrack?.takeIf { focus -> drawable.none { it.trackId == focus.id } }
            ?.let { it.toOverlay(focusedColor, focusedRow?.bounds) }
        drawable + listOfNotNull(unlisted)
    }


    // Indexed like the analysis, so a chart index is a point on this line.
    val liveOverlay = remember(trace, liveColor) {
        if (trace.size == 0) null else RouteOverlay(trackId = LIVE_TRACK_ID, points = trace, color = liveColor)
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
        targetValue = if (sidePanel && hasSheet) panelWidth else 0.dp,
        label = "panelCover",
    )
    // One expanded height: the content scrolls inside it.
    val sheetMaxHeight = windowHeight * SheetMaxHeightFraction

    val navigationBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Measured off the loaded sheet; the fixed height stands in until there is one.
    var peekContentHeight by remember { mutableStateOf(0.dp) }
    val peekMeasured = isRecording || focused is FocusedTrack.Ready
    val peekHeight = if (peekMeasured && peekContentHeight > 0.dp) {
        maxOf(TrackSheetPeekHeight, DragHandleHeight + peekContentHeight + navigationBarInset)
    } else {
        TrackSheetPeekHeight
    }
    // A collapsed sheet otherwise stays at the placeholder peek it settled on. Not once the
    // subject is gone: that would cancel the hide.
    LaunchedEffect(peekHeight) {
        if (hasSheet && sheetState.currentValue == SheetValue.PartiallyExpanded) sheetState.partialExpand()
    }
    // From the live offset: the settled value only updates after a drag, so dependents lagged.
    var scaffoldHeight by remember { mutableIntStateOf(0) }
    val sheetCover by remember {
        derivedStateOf {
            val offset = runCatching { sheetState.requireOffset() }.getOrNull() ?: return@derivedStateOf 0.dp
            with(density) { (scaffoldHeight - offset).coerceAtLeast(0f).toDp() }
        }
    }
    // Only then is the recording re-analysed. The panel has no collapsed state.
    val currentPeekHeight by rememberUpdatedState(peekHeight)
    val sheetOpened by remember { derivedStateOf { sheetCover > currentPeekHeight + 1.dp } }
    LaunchedEffect(isRecording, sidePanel, sheetOpened) {
        viewModel.showLiveCharts(isRecording && (sidePanel || sheetOpened))
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
                // Imported as it opened, so its id wasn't known when asked for.
                is TrackRef.Shared -> true
                null -> false
            }
        }
        // The peek is measured off the loaded sheet.
        ?.takeIf { sidePanel || peekContentHeight > 0.dp }

    // Null until a new import's row arrives.
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
    // From a pin tap, or the sheet's screen reader actions.
    fun selectWaypoint(waypoint: Waypoint) {
        tappedWaypoint = waypoint
        // Only the charted route's waypoints have a chart position.
        val charted = if (isRecording) {
            live?.takeIf { waypoint in liveWaypoints }
        } else {
            focusedTrack?.takeIf { waypoint in it.track.waypoints }?.profile
        }
        charted?.indexOf(waypoint.point)?.takeIf { it >= 0 }?.let { selectedIndex = it }
    }

    val sheetBody: @Composable (SheetSubject, Dp, (() -> Unit)?, (Dp) -> Unit) -> Unit =
        { current, maxHeight, onClose, onPeekHeightChange ->
            when (current) {
                is SheetSubject.Track -> FocusedTrackContent(
                    focused = current.focused,
                    title = focusedTitle,
                    routeColor = focusedColor,
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
                    onSelectWaypoint = ::selectWaypoint,
                )
                // Idle while the side panel slides away after a stop.
                SheetSubject.Recording -> (recording.value as? RecordingState.Active)?.let { active ->
                    RecordingSheet(
                        state = active,
                        profile = live,
                        maxHeight = maxHeight,
                        selectedIndex = selectedIndex,
                        onSelectedIndexChange = { selectedIndex = it },
                        useTimeAxis = preferTimeAxis,
                        onAxisChange = { preferTimeAxis = it },
                        onPeekHeightChange = onPeekHeightChange,
                        onPause = recorder::pause,
                        onResume = recorder::resume,
                        onStop = recorder::requestStop,
                        onAddWaypoint = recorder::addWaypoint,
                    )
                }
            }
        }

    Box {
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
                if (sidePanel || sheetSubject == null) {
                    Spacer(Modifier.fillMaxWidth().height(TrackSheetPeekHeight))
                } else {
                    // Closable without a drag, as the side panel is.
                    sheetBody(sheetSubject, sheetMaxHeight, { viewModel.focus(null) }) { peekContentHeight = it }
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
                    controller = mapController,
                    focusedTrackId = if (isRecording) LIVE_TRACK_ID else focusedTrack?.id,
                    selectedIndex = selectedIndex,
                    markerColor = when {
                        isRecording -> liveColor
                        focusedTrack != null -> focusedColor
                        else -> MaterialTheme.colorScheme.primary
                    },
                    showPuck = isRecording,
                    puckColor = liveColor,
                    position = position,
                    // Only the focused track's and the recording's.
                    trackWaypoints = focusedTrack?.track?.waypoints.orEmpty(),
                    liveWaypoints = liveWaypoints,
                    onSelect = { trackId, index ->
                        // An open note takes the first tap, so closing it never moves the marker.
                        if (tappedWaypoint != null) tappedWaypoint = null
                        else when (trackId) {
                            LIVE_TRACK_ID, focusedTrack?.id -> selectedIndex = index
                            // Not while recording, which holds the sheet.
                            else -> if (!isRecording) viewModel.focus(TrackRef.Saved(trackId))
                        }
                    },
                    onSelectNothing = {
                        when {
                            tappedWaypoint != null -> tappedWaypoint = null
                            isRecording -> selectedIndex = null
                            else -> viewModel.focus(null)
                        }
                    },
                    onSelectWaypoint = ::selectWaypoint,
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
                        val loading = stringResource(R.string.map_loading)
                        LinearProgressIndicator(Modifier.padding(32.dp).semantics { contentDescription = loading })
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

                if (hasContent && showZoomButtons) {
                    MapZoomControls(
                        controller = mapController,
                        // Halfway down the start edge: clear of the sheet, the record button and the panel.
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = panelCover + 8.dp),
                    )
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

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(start = panelCover, bottom = sheetInset)
                        .onSizeChanged { controlsHeight = with(density) { it.height.toDp() } },
                ) {
                    // While recording, the puck shows the position.
                    if (!isRecording) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            // Nothing to place the dot against without a track or a map.
                            if (hasContent) {
                                LocationButton(
                                    shown = locating,
                                    waiting = locating && position == null,
                                    onToggle = { show ->
                                        if (show) {
                                            showLocation()
                                        } else {
                                            location.showLocation(false)
                                            centreOnFix = false
                                        }
                                    },
                                )
                            }
                            RecordButton(onStart = startRecording)
                        }
                    }
                }

                SidePanel(
                    subject = subject,
                    visible = sidePanel && hasSheet,
                    width = panelWidth,
                    modifier = Modifier.align(Alignment.TopStart),
                ) { shown ->
                    sheetBody(shown, Dp.Unspecified, { viewModel.focus(null) }) {}
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

        // Behind three see-through buttons, in the sheet's colour, so its next lines don't show
        // through at peek; drawn over it rather than spaced into it. Zero with gesture navigation.
        if (hasSheet && !sidePanel) {
            val buttonsHeight = with(density) { WindowInsets.tappableElement.getBottom(this).toDp() }
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(buttonsHeight)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
            )
        }
    }

    // Only once loaded, which is what knows the name to offer.
    focusedRow?.takeIf { it.id == renamingId }?.let { track ->
        TrackNameDialog(
            initialName = editableTrackName(track.trackName, track.displayName),
            // Prefilled, not a hint: dismissing keeps what's shown.
            onDismiss = { renamingId = null },
            onConfirm = { name ->
                viewModel.rename(track.id, name)
                renamingId = null
            },
        )
    }
}

/** What the sheet or side panel is about. */
private sealed interface SheetSubject {
    data class Track(val focused: FocusedTrack) : SheetSubject

    data object Recording : SheetSubject
}

/** Live recording layer id; it has no library row. */
private const val LIVE_TRACK_ID = Long.MIN_VALUE

private val MapEdgePadding = 24.dp

/** Leaves the route visible above an expanded sheet. */
private const val SheetMaxHeightFraction = 0.72f

/** The landscape panel: at least this past any cutout, or this share of the window if wider. */
private val SidePanelMinWidth = 400.dp

private const val SidePanelWindowFraction = 1f / 3
