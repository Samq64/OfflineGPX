package dev.samuelq.gpx.ui.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.samuelq.gpx.R
import dev.samuelq.gpx.data.record.RecordingEvent
import dev.samuelq.gpx.data.record.RecordingState
import dev.samuelq.gpx.ui.record.RecordViewModel
import dev.samuelq.gpx.ui.record.RecordingBar
import dev.samuelq.gpx.ui.theme.routePalette
import dev.samuelq.gpx.ui.track.DeleteTrackDialog
import dev.samuelq.gpx.ui.track.FocusedTrack
import dev.samuelq.gpx.ui.track.TrackActions
import dev.samuelq.gpx.ui.track.TrackNameDialog
import dev.samuelq.gpx.ui.track.TrackRef
import dev.samuelq.gpx.ui.track.TrackSheet
import dev.samuelq.gpx.ui.track.TrackSheetError
import dev.samuelq.gpx.ui.track.TrackSheetLoading
import dev.samuelq.gpx.ui.track.TrackSheetPeekHeight
import dev.samuelq.gpx.ui.track.editableTrackName
import dev.samuelq.gpx.ui.track.shareTrackIntent
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * The whole app, near enough: every track chosen to show, overlaid; the one being looked
 * at in a sheet over it; the one being recorded drawing itself among them. No dedicated
 * track screen - a tapped route is a selection, and the sheet and the selection are one fact.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    pendingFocus: TrackRef?,
    onFocusConsumed: () -> Unit,
    onOpenList: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: MapViewModel = viewModel(factory = MapViewModel.Factory),
    recorder: RecordViewModel = viewModel(factory = RecordViewModel.Factory),
) {
    val context = LocalContext.current
    // Not `context.getString`: the context a long-lived collector captured is the one it
    // started with, so a locale change would leave it saying the old language.
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val trace by viewModel.trace.collectAsStateWithLifecycle()
    val focused by viewModel.focused.collectAsStateWithLifecycle()
    val recording by recorder.state.collectAsStateWithLifecycle()
    // Empty until someone imports one, which is how the app ships. Settings is the only
    // place maps are managed, and nothing here ever goes looking for one.
    val basemaps by viewModel.basemaps.collectAsStateWithLifecycle()

    val palette = routePalette()
    val liveColor = MaterialTheme.colorScheme.error
    val density = LocalDensity.current

    val snackbarHostState = remember { SnackbarHostState() }
    var confirmDiscard by remember { mutableStateOf(false) }
    // The recording waiting to be named, if the user has just finished one. Held as an id
    // rather than a flag: the prompt cannot open until the track it names has been read
    // back, and by then any number of other things could have taken the sheet.
    var namingId by remember { mutableStateOf<Long?>(null) }
    // The same prompt reached on purpose from the sheet's menu, which differs from the one
    // above in its title and in nothing else.
    var renamingId by remember { mutableStateOf<Long?>(null) }
    var deletingId by remember { mutableStateOf<Long?>(null) }
    // Metres to a screen pixel, republished by the map every frame of a pan or pinch. Held
    // as a state object and never read here - reading it in this scope would recompose the
    // whole screen sixty times a second; `ScaleBar` reads it where it draws.
    val metersPerPixel = remember { mutableDoubleStateOf(0.0) }

    // Same picker the track list offers, reachable from the empty state too: a first-run
    // map has nothing worth tapping into a list for yet.
    val trackImporter = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::importTrack) }

    // The camera belongs to the map view. This file only keeps the policy: frame once at a
    // cold start, never move on a selection - a map that rearranges on a tap is one you
    // have to re-read.
    //
    // Distance by default: on a time axis a stop is a hole as wide as the stop was, most
    // of the chart on a long lunch; on a distance axis it's no width at all.
    var preferTimeAxis by rememberSaveable { mutableStateOf(false) }

    val focusedTrack = (focused as? FocusedTrack.Ready)?.track
    // An index into a different track is meaningless, so it goes when the track does.
    var selectedIndex by remember(focusedTrack?.id) { mutableStateOf<Int?>(null) }

    val locationOff = stringResource(R.string.record_location_off)
    val locationDenied = stringResource(R.string.record_location_denied)
    val preciseRequired = stringResource(R.string.record_precise_required)
    val discarded = stringResource(R.string.record_discarded)
    val renameFailed = stringResource(R.string.library_rename_failed)
    val hidden = stringResource(R.string.track_hidden)
    val importFailed = stringResource(R.string.library_import_failed)

    // Replaces whatever is on screen rather than queueing behind it: these are answers to
    // a tap that just happened, and a stale one arriving four seconds later is a lie.
    fun say(message: String) = scope.launch {
        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarHostState.showSnackbar(message)
    }

    fun startRecording() {
        // Checked here and again in the service: this is the one that can explain itself,
        // and the service's is for location being switched off between the two.
        if (recorder.isGpsEnabled) recorder.start() else say(locationOff)
    }

    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        when {
            // Precise only. Approximate is wifi- and cell-derived and accurate to hundreds
            // of metres at best; a route from it is noise and a speed from it is a wrong
            // number presented as a real one. Refusing beats recording garbage.
            granted[Manifest.permission.ACCESS_FINE_LOCATION] == true -> startRecording()
            granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true -> say(preciseRequired)
            else -> say(locationDenied)
        }
    }

    LaunchedEffect(pendingFocus) {
        pendingFocus?.let {
            viewModel.focus(it)
            onFocusConsumed()
        }
    }

    // Resolved here rather than where they are sent, like every other line this screen
    // says: a string read at composition is re-read when the locale changes, and one read
    // inside a collector is whatever it was when the collector started.
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            say(
                when (message) {
                    MapMessage.RenameFailed -> renameFailed
                    MapMessage.Hidden -> hidden
                    MapMessage.ImportFailed -> importFailed
                }
            )
        }
    }

    LaunchedEffect(recorder) {
        recorder.events.collect { event ->
            when (event) {
                // Straight into the sheet: the ride you just finished is the one you want
                // to look at, and it is already on the map. And straight into naming it,
                // while you still remember where you went - the app's guess at a name is
                // the hour and the pace, which is a placeholder and reads like one. Later
                // means never: the rename is three taps down a menu on another screen.
                is RecordingEvent.Saved -> {
                    namingId = event.id
                    viewModel.focus(TrackRef.Saved(event.id))
                }
                RecordingEvent.Discarded -> say(discarded)
                is RecordingEvent.Failed -> say(resources.getString(event.messageRes))
            }
        }
    }

    // --- The sheet -----------------------------------------------------------------

    // Two heights: peek ("what is this") and expanded ("what happened", route still
    // visible, charts one scroll away). Material's sheet has a third value, Hidden, which
    // is what lets it go away entirely and give the map back.
    val sheetState = rememberStandardBottomSheetState(
        initialValue = SheetValue.Hidden,
        // The point of the redesign: it can go away entirely and give the map back.
        skipHiddenState = false,
    )
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)
    val hasFocus = focused != FocusedTrack.None

    // Wider than tall, the track goes in a panel down the side instead: a bottom sheet
    // there covers most of a map that is already short. The sheet stays composed but
    // hidden, so turning the phone moves the track between the two without losing it.
    val windowSize = LocalWindowInfo.current.containerSize
    val sidePanel = windowSize.width > windowSize.height
    val currentSidePanel by rememberUpdatedState(sidePanel)

    LaunchedEffect(hasFocus, sidePanel) {
        // Guarded, not unconditional: the sheet starts hidden, and hiding it before layout
        // asks for an anchor that doesn't exist.
        if (hasFocus && !sidePanel) sheetState.partialExpand()
        else if (sheetState.currentValue != SheetValue.Hidden) sheetState.hide()
        // Letting go of the track lets go of the offer to name it, so a stale prompt can't
        // ambush the next time that track is opened.
        if (!hasFocus) namingId = null
    }

    // Swiped away by hand: the selection follows the sheet rather than lingering as
    // invisible state with a marker still on the route.
    //
    // `drop(1)`: `snapshotFlow` opens by reporting where the sheet already is (Hidden on
    // the composing frame), which undropped reads as a dismissal and clears a focus the
    // effect above had just set from the list.
    LaunchedEffect(sheetState) {
        snapshotFlow { sheetState.currentValue }.drop(1).collect { value ->
            // Not when it was hidden to make way for the panel.
            if (value == SheetValue.Hidden && !currentSidePanel) viewModel.focus(null)
        }
    }

    // Back steps down before it closes: dropping an expanded sheet straight to nothing
    // throws away a gesture's worth of intent in one press.
    BackHandler(enabled = hasFocus) {
        if (!sidePanel && sheetState.currentValue == SheetValue.Expanded) {
            scope.launch { sheetState.partialExpand() }
        } else {
            viewModel.focus(null)
        }
    }

    // --- What the canvas draws, and how much room it has ---------------------------

    // Positions, not shapes - the map projects. The recording is deliberately not in this
    // list: it grows every few seconds and the saved tracks don't, so keeping them
    // together rebuilt every track to add a few metres to one. See `liveRoute`.
    //
    // Keyed on the focused track's *id*, not the track: a rebuilt overlay is a new object
    // that discards the extent it measured. The id matters only for `unlisted` below; the
    // focused width is the canvas's to draw.
    val overlays = remember(state.entities, state.geometry, focusedTrack?.id, palette) {
        val drawable = state.entities.mapNotNull { entity ->
            state.geometry[entity.id]?.let { entity to it }
        }
        // A track opened from the list may be hidden. Showing its numbers without its
        // line would be a readout for something that is not on screen.
        val unlisted = focusedTrack?.takeIf { focus -> drawable.none { it.first.id == focus.id } }

        buildList {
            drawable.forEach { (entity, track) ->
                // Colour comes from the track, never its position: a hue that changed when
                // you tapped something would be worse than any stacking order.
                add(
                    RouteOverlay(
                        trackId = entity.id,
                        points = track.profile.points,
                        segmentStartIndices = track.profile.segmentStartIndices,
                        color = palette[entity.colorIndex % palette.size],
                    )
                )
            }
            unlisted?.let { track ->
                add(
                    RouteOverlay(
                        trackId = track.id,
                        points = track.profile.points,
                        segmentStartIndices = track.profile.segmentStartIndices,
                        color = palette[track.colorIndex % palette.size],
                    )
                )
            }
        }
    }

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
    // The cutout comes out of the panel's own padding, so it's added on rather than eating
    // into the 400. Wider still on a big screen: a longer chart is a finer one.
    val panelInset = WindowInsets.safeDrawing.only(WindowInsetsSides.Start).asPaddingValues()
        .calculateStartPadding(LocalLayoutDirection.current)
    val panelWidth = maxOf(
        SidePanelMinWidth + panelInset,
        with(density) { windowSize.width.toDp() } * SidePanelWindowFraction,
    )
    // Animated, unlike the sheet's cover: the panel only moves on a tap, never a drag.
    val panelCover by animateDpAsState(
        targetValue = if (sidePanel && hasFocus) panelWidth else 0.dp,
        label = "panelCover",
    )
    // The sheet's one expanded height. The content column scrolls inside it, so the charts
    // are always reachable without needing a taller anchor to grow into.
    val sheetMaxHeight = windowHeight * SheetMaxHeightFraction

    val navigationBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Measured off the loaded sheet; the fixed height stands in until there is one.
    var peekContentHeight by remember { mutableStateOf(0.dp) }
    val peekHeight = if (focused is FocusedTrack.Ready && peekContentHeight > 0.dp) {
        maxOf(TrackSheetPeekHeight, DragHandleHeight + peekContentHeight + navigationBarInset)
    } else {
        TrackSheetPeekHeight
    }
    // How much of the screen the sheet covers right now, read off its live position. Not
    // animated from its settled state: that only changes once a drag has finished, so
    // everything riding on the sheet lagged a dismissing swipe and then caught up.
    var scaffoldHeight by remember { mutableIntStateOf(0) }
    val sheetCover by remember {
        derivedStateOf {
            val offset = runCatching { sheetState.requireOffset() }.getOrNull() ?: return@derivedStateOf 0.dp
            with(density) { (scaffoldHeight - offset).coerceAtLeast(0f).toDp() }
        }
    }
    // The controls sit on the sheet, up to its peek - an expanded sheet covers them rather
    // than lifting them halfway up the map.
    val sheetInset = sheetCover.coerceIn(navigationBarInset, maxOf(navigationBarInset, peekHeight))
    // The controls float over the map, so the fit has to be told about them or half a
    // route ends up behind the recording bar.
    var controlsHeight by remember { mutableStateOf(0.dp) }

    // How much of the map is actually covered right now: the sheet, or the controls
    // standing on it, whichever reaches higher.
    val coveredHeight = maxOf(sheetCover, sheetInset + controlsHeight)

    val canvasPadding = PaddingValues(
        start = MapEdgePadding + panelCover,
        end = MapEdgePadding,
        top = MapEdgePadding,
        bottom = MapEdgePadding + coveredHeight,
    )

    // The track's details, the same in the sheet and in the side panel. Nothing for no
    // track: each caller decides what stands in for it.
    val trackContent: @Composable (FocusedTrack, Dp, (() -> Unit)?, (Dp) -> Unit) -> Unit =
        { current, maxHeight, onClose, onPeekHeightChange ->
            when (current) {
                FocusedTrack.None -> Unit
                FocusedTrack.Loading -> TrackSheetLoading()
                is FocusedTrack.Failed -> TrackSheetError(
                    messageRes = current.messageRes,
                    onRetry = viewModel::retryFocus,
                    onClose = { viewModel.focus(null) },
                )
                is FocusedTrack.Ready -> TrackSheet(
                    loaded = current.track,
                    routeColor = palette[current.track.colorIndex % palette.size],
                    maxHeight = maxHeight,
                    selectedIndex = selectedIndex,
                    onSelectedIndexChange = { selectedIndex = it },
                    useTimeAxis = preferTimeAxis && current.track.profile.hasTime,
                    onAxisChange = { preferTimeAxis = it },
                    onPeekHeightChange = onPeekHeightChange,
                    onClose = onClose,
                    // Null for a file opened from an intent: it has no row to rename,
                    // hide or delete, and sharing it would just hand the file back to
                    // itself.
                    actions = state.entity(current.track.id)?.let { entity ->
                        remember(entity.id, entity.displayName, entity.location) {
                            TrackActions(
                                onRename = { renamingId = entity.id },
                                onShare = {
                                    context.startActivity(
                                        shareTrackIntent(
                                            context, entity.location, entity.trackName, entity.displayName,
                                        )
                                    )
                                },
                                onHide = {
                                    viewModel.hide(entity.id)
                                    viewModel.focus(null)
                                },
                                onDelete = { deletingId = entity.id },
                            )
                        }
                    },
                )
            }
        }


    BottomSheetScaffold(
        modifier = Modifier.onSizeChanged { scaffoldHeight = it.height },
        scaffoldState = scaffoldState,
        sheetPeekHeight = peekHeight,
        sheetDragHandle = { CompactDragHandle() },
        // A step off the map's own background, so the edge of the maps doesn't run
        // straight into the sheet.
        sheetContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            // The welcome screen, not "shown": there is nothing imported
                            // yet to be counted, so the app's own name goes there instead.
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
            // Hidden, but still the peek's height: a sheet whose content is shorter than its
            // own peek leaves the scaffold with nonsense to anchor to.
            if (sidePanel || focused == FocusedTrack.None) {
                Spacer(Modifier.fillMaxWidth().height(TrackSheetPeekHeight))
            } else {
                trackContent(focused, sheetMaxHeight, null) { peekContentHeight = it }
            }
        },
    ) { padding ->
        // Only the top padding is taken. The sheet's peek is deliberately *not* carved out
        // of the map: the map runs to the bottom edge and the sheet floats over it, which
        // is both what a map should look like and the only way the sheet can be gone
        // entirely without leaving a strip of nothing behind.
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {

            // Always composed, even with nothing to draw. The basemap is worth looking at
            // on its own - someone who has imported one and not yet recorded anything
            // should see where they are, not an empty state telling them the app is empty.
            OfflineMapCanvas(
                routes = overlays,
                liveRoute = liveOverlay,
                basemaps = basemaps,
                // So the cold-start frame waits for tracks rather than settling on the
                // basemap the moment before they arrive and never getting a second look.
                tracksLoading = state.loading,
                contentDescription = stringResource(R.string.map_description),
                focusedTrackId = focusedTrack?.id,
                selectedIndex = selectedIndex,
                markerColor = focusedTrack
                    ?.let { palette[it.colorIndex % palette.size] }
                    ?: MaterialTheme.colorScheme.primary,
                puckTrackId = LIVE_TRACK_ID.takeIf { recording is RecordingState.Active },
                puckColor = liveColor,
                onSelect = { trackId, index ->
                    when (trackId) {
                        // The recording has no row to open and no numbers to scrub.
                        LIVE_TRACK_ID -> Unit
                        // A tap on the route already showing moves its marker; a tap
                        // on any other line is a request to look at that one instead.
                        focusedTrack?.id -> selectedIndex = index
                        else -> viewModel.focus(TrackRef.Saved(trackId))
                    }
                },
                // Tapping the bare map puts it away, which is the gesture people try
                // first and the only one that does not involve aiming at anything.
                onSelectNothing = { viewModel.focus(null) },
                contentPadding = canvasPadding,
                sheetHeight = sheetCover,
                panelWidth = panelCover,
                // A step apart, not the same colour twice: ground no imported file covers
                // has to read as empty rather than as land, and the dashed outline alone is
                // a thin thing to carry that.
                backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow,
                landColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                labelColor = MaterialTheme.colorScheme.onSurface,
                onScaleChange = { metersPerPixel.doubleValue = it },
                // The ViewModel outlives this composition, so the camera picks up exactly
                // where it was left rather than re-fitting from nothing on every return to
                // this screen.
                initialCamera = viewModel.lastCamera,
                onCameraChange = viewModel::rememberCamera,
                modifier = Modifier.fillMaxSize(),
            )

            // Only the true first-run case, not "every track happens to be hidden" - that's
            // a state the user chose on purpose, and gets the bare map back, not a card.
            val mapIsEmpty = overlays.isEmpty() && liveOverlay == null &&
                !state.loading && basemaps.isEmpty() && state.totalCount == 0

            // Tracks exist, none are shown, and there's no basemap - a blank canvas with
            // nothing to explain why. Chosen on purpose (from the list), so this earns a
            // small hint rather than the first-run card re-explaining the whole app.
            val allHidden = overlays.isEmpty() && liveOverlay == null &&
                !state.loading && basemaps.isEmpty() && state.totalCount > 0

            // There has to be something to measure against - a basemap or a drawn route -
            // or the bar is reading a scale off a blank rectangle.
            val hasContent = overlays.isNotEmpty() || liveOverlay != null || basemaps.isNotEmpty()

            when {
                // A recording in progress counts as something to look at too - it just
                // lives in its own overlay.
                overlays.isNotEmpty() || liveOverlay != null -> Unit

                state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    LinearProgressIndicator(Modifier.padding(32.dp))
                }

                // Only over a blank map. With a basemap imported there is something to
                // look at, and a card explaining the app is empty would be covering it.
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
                MapChrome(
                    // The state, not its value - the bar re-reads it as the camera moves.
                    metersPerPixel = metersPerPixel,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = panelCover, bottom = sheetInset + 4.dp),
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = panelCover, bottom = sheetInset)
                    .onSizeChanged { controlsHeight = with(density) { it.height.toDp() } },
            ) {
                // Still no reset button. A real map has a whole world to be lost in rather
                // than a unit square to pinch back out of - worth adding as a "frame
                // everything" control, but currently a missing feature, not a choice.
                if (recording !is RecordingState.Active) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        ExtendedFloatingActionButton(
                            onClick = { permissions.requestThenStart(context) { startRecording() } },
                            icon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                            text = { Text(stringResource(R.string.record_start)) },
                        )
                    }
                }

                (recording as? RecordingState.Active)?.let { active ->
                    RecordingBar(
                        state = active,
                        onPause = recorder::pause,
                        onResume = recorder::resume,
                        onStop = recorder::stop,
                        onDiscard = { confirmDiscard = true },
                        // The enclosing Column already pads its bottom edge by sheetInset,
                        // which covers the nav bar when the sheet isn't focused - padding
                        // again here left an empty strip with the map showing through it.
                        applyNavigationBarPadding = false,
                    )
                }
            }

            // Over the map and the controls, down the start edge. Keeps showing the last
            // track while it slides away, rather than emptying before it has gone.
            var panelTrack by remember { mutableStateOf<FocusedTrack>(FocusedTrack.None) }
            LaunchedEffect(focused) { if (focused != FocusedTrack.None) panelTrack = focused }
            val fromStart = if (LocalLayoutDirection.current == LayoutDirection.Ltr) -1 else 1
            AnimatedVisibility(
                visible = sidePanel && hasFocus,
                enter = slideInHorizontally { fromStart * it },
                exit = slideOutHorizontally { fromStart * it },
                modifier = Modifier.align(Alignment.TopStart).fillMaxHeight().width(panelWidth),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(topEnd = SidePanelCornerRadius),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(
                        Modifier
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start))
                            .padding(top = 16.dp),
                    ) {
                        trackContent(
                            if (focused != FocusedTrack.None) focused else panelTrack,
                            Dp.Unspecified,
                            // No drag handle to swipe it away by, unlike the sheet.
                            { viewModel.focus(null) },
                        ) {}
                    }
                }
            }
        }
    }

    // Only once the track has been read back, which is what knows the name to offer. The
    // post-recording prompt and the menu's rename share this dialog; only the title differs.
    focusedTrack?.takeIf { it.id == namingId || it.id == renamingId }?.let { track ->
        val justRecorded = track.id == namingId
        TrackNameDialog(
            titleRes = if (justRecorded) R.string.record_name_title else R.string.library_rename,
            initialName = editableTrackName(track.track.name, track.displayName),
            // Dismissing keeps the name that is already there, which is why it is in the
            // field rather than behind it as a hint: what you are leaving is what you see.
            onDismiss = {
                namingId = null
                renamingId = null
            },
            onConfirm = { name ->
                viewModel.rename(track.id, name)
                namingId = null
                renamingId = null
            },
        )
    }

    deletingId?.let { id ->
        DeleteTrackDialog(
            count = 1,
            onDismiss = { deletingId = null },
            onConfirm = {
                deletingId = null
                // The sheet first: it is about to be a readout for a row that does not
                // exist, and the map behind it has one less line to draw.
                viewModel.focus(null)
                viewModel.delete(id)
            },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.record_discard_title)) },
            text = { Text(stringResource(R.string.record_discard_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    recorder.discard()
                }) { Text(stringResource(R.string.record_discard_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/**
 * The recording's own layer id. Not a row in the library - it has no row until it is
 * saved - so taps on it resolve to nothing rather than to some other track.
 */
private const val LIVE_TRACK_ID = Long.MIN_VALUE

/** Breathing room between the routes and whatever is at the edge of the canvas. */
private val MapEdgePadding = 24.dp

/**
 * The sheet's expanded height. A sheet that covers the map the moment it opens is a screen
 * wearing a slide animation, not a sheet - the route stays visible above it.
 */
private const val SheetMaxHeightFraction = 0.58f

/** The landscape panel: at least this past any cutout, or this share of the window if wider. */
private val SidePanelMinWidth = 400.dp
private const val SidePanelWindowFraction = 1f / 3
private val SidePanelCornerRadius = 28.dp

/** The handle's own height, counted into the measured peek. */
private val DragHandleHeight = 20.dp

/**
 * Half the height of the Material handle, which spends 44 of its 48dp on padding. Every
 * one of those is a dp of map, and the sheet is dragged by its whole surface anyway.
 */
@Composable
private fun CompactDragHandle() {
    Box(
        Modifier.fillMaxWidth().height(DragHandleHeight),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 32.dp, height = 4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
    }
}

@Composable
private fun EmptyState(
    onImportMap: () -> Unit,
    onImportTrack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        // Opaque, over the canvas's own flat background, and the same colour as every
        // other screen's: with nothing to show, this is a page, not a map.
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.map_empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.map_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onImportMap) {
                Text(stringResource(R.string.map_empty_import_map))
            }
            Button(onClick = onImportTrack) {
                Text(stringResource(R.string.map_empty_import_track))
            }
        }
    }
}

/**
 * The one state with nothing at all on screen: every track hidden, no basemap. Not the
 * first-run card - the user did this on purpose from the list, and already knows what the
 * app is - just a way back that doesn't require remembering the list icon exists.
 */
@Composable
private fun ShowTracksHint(onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier) {
        Text(stringResource(R.string.map_hidden_hint))
    }
}

/**
 * Asks for what is missing on the tap that starts a recording, nothing before it - no
 * screen explaining itself first. Coarse is listed alongside fine because Android 12+
 * ignores a fine request without it; notifications are requested but not required.
 */
private fun androidx.activity.compose.ManagedActivityResultLauncher<Array<String>, Map<String, Boolean>>.requestThenStart(
    context: Context,
    onAlreadyGranted: () -> Unit,
) {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
    if (fine == PackageManager.PERMISSION_GRANTED) {
        onAlreadyGranted()
        return
    }

    val wanted = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    launch(wanted.toTypedArray())
}
