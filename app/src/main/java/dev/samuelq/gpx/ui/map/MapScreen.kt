package dev.samuelq.gpx.ui.map

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
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
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.samuelq.gpx.R
import dev.samuelq.gpx.core.analysis.TrackProfile
import dev.samuelq.gpx.core.model.GeoBounds
import dev.samuelq.gpx.core.model.UnitSystem
import dev.samuelq.gpx.core.model.Waypoint
import dev.samuelq.gpx.data.map.OfflineMap
import dev.samuelq.gpx.data.record.RecordingState
import dev.samuelq.gpx.data.track.editableName
import dev.samuelq.gpx.data.track.title
import dev.samuelq.gpx.ui.format.LocalFormatters
import dev.samuelq.gpx.ui.makeWay
import dev.samuelq.gpx.ui.record.RecordingOutcomes
import dev.samuelq.gpx.ui.record.RecordingSheet
import dev.samuelq.gpx.ui.record.RecordingViewModel
import dev.samuelq.gpx.ui.screenSnackbars
import dev.samuelq.gpx.ui.theme.routePalette
import dev.samuelq.gpx.ui.theme.slot
import dev.samuelq.gpx.ui.track.FocusedTrack
import dev.samuelq.gpx.ui.track.TrackActions
import dev.samuelq.gpx.ui.track.TrackNameDialog
import dev.samuelq.gpx.ui.track.TrackRef
import dev.samuelq.gpx.ui.track.TrackSheet
import dev.samuelq.gpx.ui.track.TrackSheetError
import dev.samuelq.gpx.ui.track.TrackSheetLoading
import dev.samuelq.gpx.ui.track.TrackSheetPeekHeight
import dev.samuelq.gpx.ui.track.TrimControls
import dev.samuelq.gpx.ui.track.shareTrack
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** Shown tracks overlaid, the focused one in a sheet or side panel, and the live recording. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    pendingFocus: TrackRef?,
    onFocusConsumed: () -> Unit,
    /** A map's file name, from settings. */
    pendingMap: String?,
    onMapConsumed: () -> Unit,
    /** With the area the map last showed, for the list to filter to. */
    onOpenList: (area: GeoBounds?) -> Unit,
    onOpenSettings: () -> Unit,
    /** Straight to the file picker; the import itself, with its merge prompt, lives in settings. */
    onImportMap: () -> Unit,
    viewModel: MapViewModel = viewModel(factory = MapViewModel.Factory),
    recorder: RecordingViewModel = viewModel(factory = RecordingViewModel.Factory),
    location: LocationViewModel = viewModel(factory = LocationViewModel.Factory),
) {
    val mapController = remember { MapController() }
    val context = LocalContext.current
    // Not context.getString, and through state: the long-lived collectors below, and the
    // say and offerUndo they captured, read the current configuration's.
    val resources by rememberUpdatedState(LocalResources.current)
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
    val recordingSlot by viewModel.recordingSlot.collectAsStateWithLifecycle()
    val liveColor = palette.slot(recordingSlot)
    val density = LocalDensity.current

    val snackbars = screenSnackbars()
    val snackbarHostState = snackbars.host
    // Never read here: that would recompose the screen every frame of a pan. ScaleBar reads it.
    val metersPerPixel = remember { mutableDoubleStateOf(0.0) }

    val trackImporter = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importTrack) }

    // Distance by default: on a time axis a stop becomes a gap as wide as the stop.
    var preferTimeAxis by rememberSaveable { mutableStateOf(false) }

    val focusedTrack = (focused as? FocusedTrack.Ready)?.track
    // Name and colour are the row's, blank for the moment before a new import's row arrives.
    val openList = { onOpenList(viewModel.lastCamera?.area) }
    val focusedRow = focusedTrack?.let { state.entity(it.id) }
    val focusedColor = palette.slot(focusedRow?.colorIndex ?: 0)
    val focusedTitle = focusedRow?.title.orEmpty()
    // The recording takes the sheet over; other tracks wait until it stops.
    val subject = when {
        isRecording -> SheetSubject.Recording
        focused != FocusedTrack.None -> SheetSubject.Track(focused)
        else -> null
    }
    // A new subject drops the selection, and a new track the trim.
    val screen = rememberMapScreenState(focusedTrack?.id, isRecording, viewModel.screenKept) { viewModel.focus(it) }
    // Read only by the tooltip's layout, so panning doesn't recompose this screen.
    val tappedWaypointAt = remember { mutableStateOf(Offset.Zero) }

    fun say(message: String, openSettings: (() -> Unit)? = null) = scope.launch {
        snackbars.say(message, openSettings?.let { resources.getString(R.string.settings_title) }) {
            openSettings?.invoke()
        }
    }

    fun offerUndo(message: String, onUndo: () -> Unit, onCommit: () -> Unit = {}) =
        snackbars.offerUndo(context, message, onUndo, onCommit)

    val requestRecording = rememberLocationRequest(
        LocationUse.Record,
        isGpsEnabled = { recorder.isGpsEnabled },
        onGranted = { recorder.start() },
        say = ::say,
    )
    val showLocation = rememberLocationRequest(
        LocationUse.Show,
        isGpsEnabled = { location.isGpsEnabled },
        onGranted = {
            location.showLocation(true)
            screen.follow()
        },
        say = ::say,
    )
    LocationRationale(requestRecording)
    LocationRationale(showLocation)
    fun tapLocation() {
        when (screen.tapLocation(locating)) {
            LocationTap.Start -> showLocation()
            LocationTap.Stop -> location.showLocation(false)
            LocationTap.Follow, LocationTap.Unfollow -> Unit
        }
    }
    // Once per subject, so a recording's start and end are each seen once.
    LaunchedEffect(screen) {
        if (screen.recordingSeen()) location.showLocation(false)
    }

    // The puck while recording, else the dot. Each new one is centred; the first may zoom in.
    val followed = when {
        !screen.following -> null
        isRecording -> trace.lastOrNull()
        else -> position
    }
    LaunchedEffect(followed) {
        val at = followed ?: return@LaunchedEffect
        when (mapController.centreOn(at, zoomIn = screen.snapping)) {
            CentreResult.Centred -> screen.centred()
            CentreResult.OutOfBounds -> {
                screen.stopFollowing()
                say(resources.getString(R.string.map_location_outside))
            }
            CentreResult.NotLaidOut -> Unit
        }
    }
    val liveWaypoints by remember {
        derivedStateOf { (recording.value as? RecordingState.Active)?.waypoints.orEmpty() }
    }

    LaunchedEffect(focused) {
        if (focused is FocusedTrack.None || focused is FocusedTrack.Failed) screen.framing = null
    }

    LaunchedEffect(pendingFocus) {
        pendingFocus?.let {
            if (!screen.open(it)) say(resources.getString(R.string.record_stop_to_open))
            onFocusConsumed()
        }
    }

    var framingMap by remember { mutableStateOf<OfflineMap?>(null) }
    LaunchedEffect(pendingMap, basemaps) {
        val name = pendingMap ?: return@LaunchedEffect
        if (basemaps.isEmpty()) return@LaunchedEffect
        basemaps.firstOrNull { it.file.name == name }?.let {
            // Else the next fix pulls the camera back.
            screen.stopFollowing()
            framingMap = it
        }
        onMapConsumed()
    }

    LaunchedEffect(isRecording) {
        if (isRecording) viewModel.focus(null)
    }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            when (message) {
                is MapMessage.Say -> say(resources.getString(message.text))
                is MapMessage.Hidden -> offerUndo(
                    resources.getString(R.string.track_hidden, message.name),
                    onUndo = { viewModel.show(message.id) },
                )
                is MapMessage.Edited -> offerUndo(
                    resources.getString(R.string.track_trimmed, message.name),
                    onUndo = { viewModel.undoEdit(message.edit) },
                    onCommit = { viewModel.commitEdit(message.edit) },
                )
            }
        }
    }

    LaunchedEffect(location) {
        location.stopped.collect { reason ->
            screen.stopFollowing()
            when (reason) {
                LocationStopped.OFF -> say(resources.getString(R.string.map_location_off)) {
                    context.openLocationSettings()
                }
                LocationStopped.DENIED -> say(resources.getString(R.string.map_location_denied))
            }
        }
    }

    var showAllAfterRecording by remember { mutableStateOf(false) }
    val categories by recorder.categories.collectAsStateWithLifecycle()
    RecordingOutcomes(
        events = recorder.events,
        categories = categories,
        say = ::say,
        offerUndo = { message, onUndo, onCommit -> offerUndo(message, onUndo, onCommit) },
        onSaved = { viewModel.focus(TrackRef.Saved(it)) },
        onDiscarded = { showAllAfterRecording = true },
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
    val sheetSubject = rememberLastNonNull(subject)
        .takeIf { subject != null || sheetState.currentValue != SheetValue.Hidden }
    // Likewise the header, whose track is no longer focused.
    val lastTitle = rememberLastNonNull(focusedTitle.takeIf { focusedTrack != null })
    val lastColor = rememberLastNonNull(focusedColor.takeIf { focusedTrack != null })
    val sheetTitle = if (subject == null) lastTitle.orEmpty() else focusedTitle
    val sheetColor = if (subject == null) lastColor ?: focusedColor else focusedColor

    // Landscape, or any window Material calls expanded, uses a side panel; the sheet stays
    // composed but hidden so rotation keeps the track.
    val windowSize = LocalWindowInfo.current.containerSize
    val sidePanel = windowSize.width > windowSize.height ||
        with(density) { windowSize.width.toDp() } >= ExpandedWidth
    val currentSidePanel by rememberUpdatedState(sidePanel)

    // Saved, so a rotation, which hides the sheet for the panel and back, or a recreation
    // reopens it as it was. Not Hidden, which it passes through on the way.
    var sheetWasExpanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(sheetState) {
        snapshotFlow { sheetState.currentValue }.collect { value ->
            if (!currentSidePanel && value != SheetValue.Hidden) sheetWasExpanded = value == SheetValue.Expanded
        }
    }

    LaunchedEffect(hasSheet, sidePanel) {
        // Guarded: hiding before layout asks for an anchor that doesn't exist.
        if (hasSheet && !sidePanel) {
            if (sheetWasExpanded) sheetState.expand() else sheetState.partialExpand()
        } else if (sheetState.currentValue != SheetValue.Hidden) {
            sheetState.hide()
        }
        if (!hasSheet) sheetWasExpanded = false
        // So a stale name prompt can't reappear when the track is reopened.
        if (!hasFocus) screen.renamingId = null
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
    // Previewed as the gesture goes: the sheet or panel shrinks, and springs back if cancelled.
    val sheetExpanded = !sidePanel && sheetState.currentValue == SheetValue.Expanded
    val backProgress = remember { Animatable(0f) }
    val expandSheet: () -> Unit = { scope.launch { sheetState.expand() } }
    val collapseSheet: () -> Unit = { scope.launch { sheetState.partialExpand() } }
    PredictiveBackHandler(enabled = hasFocus || sheetExpanded) { events ->
        // Read now: the sheet may have been dragged since the gesture's composition.
        val collapsing = !currentSidePanel && sheetState.currentValue == SheetValue.Expanded
        try {
            events.collect { backProgress.snapTo(it.progress) }
        } catch (e: CancellationException) {
            scope.launch { backProgress.animateTo(0f) }
            throw e
        }
        // Released: the scale eases off as the sheet moves, so neither jumps.
        scope.launch { backProgress.animateTo(0f) }
        if (collapsing) sheetState.partialExpand() else viewModel.focus(null)
    }
    // Declared after, so it's asked first: back leaves a trim before anything else.
    BackHandler(enabled = screen.trimRange != null, onBack = screen::cancelTrim)

    // --- What the canvas draws, and how much room it has ---------------------------

    // The recording is kept out: it grows every few seconds and would rebuild every track.
    // Keyed on the focused id, not the track: a rebuilt overlay loses its measured extent.
    val trimRange = screen.trimRange
    val overlays = remember(state.entities, state.geometry, focusedTrack?.id, focusedColor, palette, trimRange) {
        // Colour from the row, not its position, so it's stable across taps.
        val drawable = state.entities.mapNotNull { row ->
            state.geometry[row.id]?.let { RouteOverlay(row.id, it, palette.slot(row.colorIndex), row.bounds) }
        }
        // Include the focused track even if hidden, so its readout has a line to go with.
        val unlisted = focusedTrack?.takeIf { focus -> drawable.none { it.trackId == focus.id } }
            ?.let { it.toOverlay(focusedColor, focusedRow?.bounds) }
        val all = drawable + listOfNotNull(unlisted)
        // While trimming, the kept part as the track and the whole of it dimmed beneath.
        val range = trimRange
        if (range == null || focusedTrack == null) {
            all
        } else {
            all.flatMap { route ->
                if (route.trackId != focusedTrack.id) {
                    listOf(route)
                } else {
                    listOf(
                        RouteOverlay(TRIM_CUT_ID, route.points, route.color, route.bounds),
                        RouteOverlay(route.trackId, route.points.slice(range), route.color, route.bounds),
                    )
                }
            }
        }
    }

    // What saving the trim would drop with the points.
    val cutWaypoints = remember(focusedTrack, trimRange) {
        val range = trimRange ?: return@remember emptySet()
        focusedTrack?.track?.waypoints?.filterIndexed { i, _ -> focusedTrack.waypointIndices[i] !in range }?.toSet()
            .orEmpty()
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
    var controlsWidth by remember { mutableStateOf(0.dp) }
    // Beside a side panel the map is short and wide, and a band across its whole width for
    // controls in one corner can leave a route a sliver. They take whichever edge costs less.
    val mapWidth = with(density) { windowSize.width.toDp() } - if (sidePanel) panelWidth else 0.dp
    val controlsAtEnd = sidePanel && controlsWidth / mapWidth < controlsHeight / windowHeight
    val controlsBottom = if (controlsAtEnd) 0.dp else controlsHeight
    val controlsEnd = if (controlsAtEnd) controlsWidth else 0.dp

    val coveredHeight = maxOf(sheetCover, sheetInset + controlsBottom)

    val canvasPadding = PaddingValues(
        start = MapEdgePadding + panelCover,
        end = MapEdgePadding + controlsEnd,
        top = MapEdgePadding,
        bottom = MapEdgePadding + coveredHeight,
    )

    // Else the camera stays where the discarded recording was, likely away from every track.
    // Once its line and sheet are gone: the line clears apart from the state, and the clamp
    // follows the sheet as it slides away, pulling a frame fitted any sooner off centre.
    val traceCleared = trace.size == 0
    val sheetGone = sheetCover == 0.dp && panelCover == 0.dp
    LaunchedEffect(showAllAfterRecording, isRecording, traceCleared, sheetGone) {
        if (showAllAfterRecording && !isRecording && traceCleared && sheetGone) {
            showAllAfterRecording = false
            mapController.showAllTracks()
        }
    }

    // Settled padding, so the frame isn't fitted to a screen the sheet is about to cover.
    val framePadding = PaddingValues(
        start = MapEdgePadding + if (sidePanel) panelWidth else 0.dp,
        end = MapEdgePadding + controlsEnd,
        top = MapEdgePadding,
        bottom = MapEdgePadding + controlsBottom + if (sidePanel) navigationBarInset else peekHeight,
    )
    val frameTrackId = focusedTrack?.id
        ?.takeIf(screen::frames)
        // The peek is measured off the loaded sheet.
        ?.takeIf { sidePanel || peekContentHeight > 0.dp }

    // Null until a new import's row arrives.
    val actions = focusedTrack?.let { state.entity(it.id) }?.let { entity ->
        remember(entity.id, entity.trackName, entity.startedAtEpochMillis, entity.category, entity.colorIndex, screen) {
            TrackActions(
                onRename = { screen.renamingId = entity.id },
                onShare = { scope.launch { context.shareTrack(entity, viewModel.fileToShare(entity)) } },
                onHide = {
                    viewModel.hide(entity.id)
                    viewModel.focus(null)
                },
                onDelete = {
                    // Unfocus first so the sheet doesn't show a deleted row.
                    viewModel.focus(null)
                    viewModel.delete(entity.id)
                    offerUndo(
                        resources.getString(R.string.deleted_named, entity.title),
                        onUndo = { viewModel.undoDelete(entity.id) },
                        onCommit = { viewModel.commitDelete(entity.id) },
                    )
                },
                onTrim = { screen.startTrim(focusedTrack.profile.points.size) },
                colorIndex = entity.colorIndex,
                onColor = { viewModel.setColor(entity.id, it) },
            )
        }
    }

    // Shared by the sheet and the side panel.
    // From a pin tap, or the sheet's screen reader actions.
    // A lambda, not a local fun: Compose keeps a `::` reference from the first composition,
    // still writing the state of a track no longer focused.
    // Only the charted route's waypoints have a chart position.
    val chartedFor: (Waypoint) -> TrackProfile? = { waypoint ->
        if (isRecording) {
            live?.takeIf { waypoint in liveWaypoints }
        } else {
            focusedTrack?.takeIf { waypoint in it.track.waypoints }?.profile
        }
    }
    val selectWaypoint: (Waypoint) -> Unit = { waypoint ->
        screen.selectWaypoint(waypoint, chartedFor(waypoint)?.indexOf(waypoint.point)?.takeIf { it >= 0 })
    }

    val sheetBody: @Composable (SheetSubject, Dp, (Dp) -> Unit) -> Unit =
        { current, maxHeight, onPeekHeightChange ->
            when (current) {
                is SheetSubject.Track -> when (val focused = current.focused) {
                    FocusedTrack.None -> Unit
                    FocusedTrack.Loading -> TrackSheetLoading()
                    is FocusedTrack.Failed -> TrackSheetError(
                        messageRes = focused.messageRes,
                        onRetry = viewModel::retryFocus,
                        onClose = { viewModel.focus(null) },
                    )
                    is FocusedTrack.Ready -> TrackSheet(
                        loaded = focused.track,
                        title = sheetTitle,
                        startedAtEpochMillis = state.entity(focused.track.id)?.startedAtEpochMillis,
                        routeColor = sheetColor,
                        maxHeight = maxHeight,
                        selectedIndex = screen.selectedIndex,
                        onSelectedIndexChange = { screen.selectedIndex = it },
                        useTimeAxis = preferTimeAxis && focused.track.profile.hasTime,
                        onAxisChange = { preferTimeAxis = it },
                        onPeekHeightChange = onPeekHeightChange,
                        // Closable without a drag, as the side panel is.
                        onClose = { viewModel.focus(null) },
                        actions = actions,
                        onSelectWaypoint = selectWaypoint,
                        trim = trimRange?.let { range ->
                            TrimControls(
                                range = range,
                                onRangeChange = screen::setTrim,
                                onCancel = screen::cancelTrim,
                                onSave = {
                                    val kept = screen.finishTrim()
                                    if (kept != null && focusedTrack != null) viewModel.trim(focusedTrack.id, kept)
                                },
                            )
                        },
                    )
                }
                // Idle while the side panel slides away after a stop.
                SheetSubject.Recording -> (recording.value as? RecordingState.Active)?.let { active ->
                    RecordingSheet(
                        state = active,
                        color = liveColor,
                        profile = live,
                        maxHeight = maxHeight,
                        selectedIndex = screen.selectedIndex,
                        onSelectedIndexChange = { screen.selectedIndex = it },
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
            // Drawn in sheetContent instead, so the back preview scales handle and surface as one.
            sheetDragHandle = null,
            sheetContainerColor = Color.Transparent,
            sheetShadowElevation = 0.dp,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            when {
                                state.all.isEmpty() -> stringResource(R.string.app_name)
                                state.entities.isEmpty() -> stringResource(R.string.map_none_shown)
                                else -> pluralStringResource(
                                    R.plurals.map_shown,
                                    state.entities.size,
                                    state.entities.size,
                                )
                            },
                        )
                    },
                    actions = {
                        IconButton(onClick = openList) {
                            Icon(
                                painterResource(R.drawable.ic_list),
                                stringResource(R.string.library_title),
                            )
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings_title))
                        }
                    },
                )
            },
            sheetContent = {
                Surface(
                    shape = BottomSheetDefaults.ExpandedShape,
                    // A step off the map's background so the sheet's edge stays visible.
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shadowElevation = BottomSheetDefaults.Elevation,
                    modifier = Modifier.predictiveBackScale(
                        { if (sidePanel) 0f else backProgress.value },
                        TransformOrigin(0.5f, 1f),
                    ),
                ) {
                    Column {
                        CompactDragHandle(
                            onExpand = expandSheet
                                .takeIf { sheetState.currentValue == SheetValue.PartiallyExpanded },
                            onCollapse = collapseSheet
                                .takeIf { sheetState.currentValue == SheetValue.Expanded },
                        )
                        // Placeholder at peek height: shorter content leaves the scaffold nothing to anchor to.
                        if (sidePanel || sheetSubject == null) {
                            Spacer(Modifier.fillMaxWidth().height(TrackSheetPeekHeight))
                        } else {
                            sheetBody(sheetSubject, sheetMaxHeight) { peekContentHeight = it }
                        }
                    }
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
                    selectedIndex = screen.selectedIndex,
                    markEnds = trimRange != null,
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
                    cutWaypoints = cutWaypoints,
                    onSelect = screen::tapLine,
                    onSelectNothing = screen::tapNothing,
                    onSelectWaypoint = selectWaypoint,
                    followedWaypoint = screen.tappedWaypoint,
                    onFollowedWaypointMove = { tappedWaypointAt.value = it },
                    contentPadding = canvasPadding,
                    frameTrackId = frameTrackId,
                    framePadding = framePadding,
                    onFramed = { screen.framing = null },
                    frameMap = framingMap,
                    onMapFramed = { framingMap = null },
                    sheetHeight = sheetCover,
                    panelWidth = panelCover,
                    // Distinct from land so ground no file covers reads as empty.
                    basemapStyle = BasemapStyle(
                        background = MaterialTheme.colorScheme.surfaceContainerLow,
                        land = MaterialTheme.colorScheme.surfaceContainerLowest,
                        label = MaterialTheme.colorScheme.onSurface,
                        contourLabels = LocalFormatters.current.units == UnitSystem.METRIC,
                    ),
                    onScaleChange = { metersPerPixel.doubleValue = it },
                    // Resume where the camera was left rather than re-fitting.
                    followed = followed,
                    onDrag = screen::stopFollowing,
                    initialCamera = viewModel.lastCamera,
                    onCameraChange = { viewModel.lastCamera = it },
                    modifier = Modifier.fillMaxSize(),
                )

                screen.tappedWaypoint?.let { tapped ->
                    // The live profile lags while its charts are hidden, and would put a new waypoint at its old end.
                    val charted = chartedFor(tapped)?.takeIf { !isRecording || it.points.size == trace.size }
                    val distance = remember(tapped, charted) { charted?.distanceTo(tapped.point) }
                    WaypointTooltip(tapped, distance, tipAt = { tappedWaypointAt.value })
                }

                val hasRoutes = overlays.isNotEmpty() || liveOverlay != null
                // Nothing to measure against without a basemap or route.
                val hasContent = hasRoutes || basemaps.isNotEmpty()

                when {
                    hasRoutes -> Unit

                    state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        val loading = stringResource(R.string.map_loading)
                        LinearProgressIndicator(Modifier.padding(32.dp).semantics { contentDescription = loading })
                    }

                    hasContent -> Unit

                    // First run only; hidden-by-choice gets the hint.
                    state.all.isEmpty() -> EmptyState(
                        onImportMap = onImportMap,
                        onImportTrack = { trackImporter.launch(arrayOf("*/*")) },
                        modifier = Modifier.fillMaxSize(),
                    )

                    else -> ShowTracksHint(
                        onClick = openList,
                        modifier = Modifier.align(Alignment.Center),
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
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .onSizeChanged { controlsWidth = with(density) { it.width.toDp() } }
                            .padding(16.dp),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        // Nothing to place the dot against without a track or a map, unless recording.
                        if (isRecording || hasContent) {
                            LocationButton(
                                following = screen.following,
                                // The puck is the position while recording.
                                waiting = if (isRecording) trace.size == 0 else locating && position == null,
                                recording = isRecording,
                                onClick = ::tapLocation,
                            )
                        }
                        if (!isRecording) RecordButton(onStart = requestRecording)
                    }
                }

                SidePanel(
                    subject = subject,
                    visible = sidePanel && hasSheet,
                    width = panelWidth,
                    modifier = Modifier.align(Alignment.TopStart),
                    backProgress = { if (sidePanel) backProgress.value else 0f },
                ) { shown ->
                    sheetBody(shown, Dp.Unspecified) {}
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
                    .background(MaterialTheme.colorScheme.surfaceContainer),
            )
        }
    }

    // Only once loaded, which is what knows the name to offer.
    focusedRow?.takeIf { it.id == screen.renamingId }?.let { track ->
        val categories by viewModel.categories.collectAsStateWithLifecycle()
        TrackNameDialog(
            initialName = track.editableName,
            initialCategory = track.category.orEmpty(),
            categories = categories,
            // Prefilled, not a hint: dismissing keeps what's shown.
            onDismiss = { screen.renamingId = null },
            onConfirm = { name, category ->
                viewModel.rename(track.id, name, category)
                screen.renamingId = null
            },
        )
    }
}

/** What the sheet or side panel is about. */
private sealed interface SheetSubject {
    data class Track(val focused: FocusedTrack) : SheetSubject

    data object Recording : SheetSubject
}

/** The whole of a track being trimmed, dimmed under the part kept. */
private const val TRIM_CUT_ID = Long.MIN_VALUE + 1

private val MapEdgePadding = 24.dp

/** Leaves the route visible above an expanded sheet. */
private const val SheetMaxHeightFraction = 0.72f

/** The landscape panel: at least this past any cutout, or this share of the window if wider. */
private val SidePanelMinWidth = 400.dp

private const val SidePanelWindowFraction = 1f / 3

/** Material's expanded window width class begins here. */
private val ExpandedWidth = 840.dp
