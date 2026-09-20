package dev.samuelq.gpx.ui.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.samuelq.gpx.R
import dev.samuelq.gpx.data.db.TrackSource
import dev.samuelq.gpx.data.record.RecordingEvent
import dev.samuelq.gpx.data.record.RecordingState
import dev.samuelq.gpx.ui.record.RecordViewModel
import dev.samuelq.gpx.ui.record.RecordingBar
import dev.samuelq.gpx.ui.theme.routePalette
import dev.samuelq.gpx.ui.track.DeleteTrackDialog
import dev.samuelq.gpx.ui.track.FocusedTrack
import dev.samuelq.gpx.ui.track.TrackActions
import dev.samuelq.gpx.ui.track.TrackRef
import dev.samuelq.gpx.ui.track.TrackNameDialog
import dev.samuelq.gpx.ui.track.TrackSheet
import dev.samuelq.gpx.ui.track.TrackSheetError
import dev.samuelq.gpx.ui.track.TrackSheetLoading
import dev.samuelq.gpx.ui.track.TrackSheetPeekHeight
import dev.samuelq.gpx.ui.track.editableTrackName
import dev.samuelq.gpx.ui.track.exportFileName
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * The whole app, near enough: every track the user has chosen to show, overlaid; the one
 * they are looking at in a sheet over it; and the one being recorded drawing itself among
 * them.
 *
 * There is no dedicated track screen any more. A second screen meant redrawing the same
 * route on a second canvas, losing every other track off the side of it, and a back stack
 * entry for what is really a selection. Tapping a route selects it, the sheet says what it
 * is, and swiping the sheet away deselects it - the sheet and the selection are one fact.
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
    // Metres to a screen pixel, republished by the map as the camera moves.
    var metersPerPixel by remember { mutableDoubleStateOf(0.0) }

    // CreateDocument rather than a share sheet: the destination is picked through SAF, so
    // the file lands where the user chose and the app needs no storage permission.
    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml")
    ) { destination -> viewModel.finishExport(destination?.toString()) }

    // The camera belongs to MapLibre now - pinch, fling, the pan clamp and the projection
    // were all this file's problem and are none of its business any more. What is kept is
    // the policy: frame everything once at a cold start, and never move on a selection,
    // because a map that rearranges itself when you tap something is a map you have to
    // re-read rather than one you were already looking at.
    //
    // Distance by default. On a time axis every stop is a hole as wide as the stop was,
    // which on a ride with a long lunch is most of the chart; on a distance axis a stop
    // takes no width at all, because no distance passed during it. Time is one tap away
    // for when the stops are the thing you came to look at.
    var preferTimeAxis by rememberSaveable { mutableStateOf(false) }

    val focusedTrack = (focused as? FocusedTrack.Ready)?.track
    // An index into a different track is meaningless, so it goes when the track does.
    var selectedIndex by remember(focusedTrack?.id) { mutableStateOf<Int?>(null) }

    val locationOff = stringResource(R.string.record_location_off)
    val locationDenied = stringResource(R.string.record_location_denied)
    val preciseRequired = stringResource(R.string.record_precise_required)
    val discarded = stringResource(R.string.record_discarded)
    val exported = stringResource(R.string.library_exported)
    val exportFailed = stringResource(R.string.library_export_failed)
    val renameFailed = stringResource(R.string.library_rename_failed)
    val hidden = stringResource(R.string.track_hidden)

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
                    MapMessage.Exported -> exported
                    MapMessage.ExportFailed -> exportFailed
                    MapMessage.RenameFailed -> renameFailed
                    MapMessage.Hidden -> hidden
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

    // Three heights, not two. Peek answers "what is this"; the middle one answers "what
    // happened" while keeping the route in view beside the numbers; and full is for
    // reading the charts themselves, where the map has stopped being the point.
    //
    // Material's sheet only has three values and one of them is Hidden, so the third
    // detent is not an anchor - it is the *content's* max height changing, which moves the
    // Expanded anchor the sheet is already settled at. Animating the height makes that a
    // slide rather than a jump, because the anchor is recomputed on each frame's measure.
    var sheetAtFullHeight by rememberSaveable { mutableStateOf(false) }

    val sheetState = rememberStandardBottomSheetState(
        initialValue = SheetValue.Hidden,
        // The point of the redesign: it can go away entirely and give the map back.
        skipHiddenState = false,
    )
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)
    val hasFocus = focused != FocusedTrack.None

    LaunchedEffect(hasFocus) {
        // Guarded rather than unconditional: the sheet starts hidden, and asking it to
        // hide before it has been laid out is asking for an anchor that does not exist.
        if (hasFocus) sheetState.partialExpand()
        else if (sheetState.currentValue != SheetValue.Hidden) sheetState.hide()
        // Letting go of the track lets go of the offer to name it. Otherwise a prompt that
        // never got the chance to open would be waiting to ambush the next time that same
        // track was opened, hours later, from the list.
        if (!hasFocus) namingId = null
    }

    // Swiped away by hand: the selection follows the sheet rather than lingering as an
    // invisible bit of state with a marker still on the route.
    //
    // `drop(1)`, because a `snapshotFlow` opens by reporting where it already is, and where
    // the sheet already is on the frame the map is composed is Hidden. Undropped, that reads
    // as a dismissal and clears the focus - including the one the effect above it has just
    // set from the list. Opening a track from the library therefore worked only when a sheet
    // happened to be open when you left the map, and did nothing at all when one wasn't.
    LaunchedEffect(sheetState) {
        snapshotFlow { sheetState.currentValue }.drop(1).collect { value ->
            if (value == SheetValue.Hidden) viewModel.focus(null)
            // Dragging the sheet down past the middle gives the map back for good: the
            // next expand starts from the middle again rather than leaping to full.
            if (value != SheetValue.Expanded) sheetAtFullHeight = false
        }
    }

    /**
     * One step taller, and from the top back to the bottom.
     *
     * A button rather than a fourth drag anchor, which the platform sheet cannot express.
     * Dragging still does what it did - peek, middle, or down and away - and this is the
     * only way to reach the third height, so it has to be visible rather than inferred.
     */
    fun stepSheetHeight() {
        scope.launch {
            when {
                sheetState.currentValue != SheetValue.Expanded -> sheetState.expand()
                !sheetAtFullHeight -> sheetAtFullHeight = true
                else -> {
                    sheetAtFullHeight = false
                    sheetState.partialExpand()
                }
            }
        }
    }

    // Back steps down before it closes. Dropping from a full-height sheet straight to
    // nothing throws away two gestures' worth of intent in one press; from the peek, where
    // there is nothing left to give back, it closes.
    BackHandler(enabled = hasFocus) {
        if (sheetState.currentValue == SheetValue.Expanded) {
            // `sheetAtFullHeight` is cleared by the collector once the sheet settles, so
            // the height and the anchor never animate against each other.
            scope.launch { sheetState.partialExpand() }
        } else {
            viewModel.focus(null)
        }
    }

    // --- What the canvas draws, and how much room it has ---------------------------

    // Positions, not shapes. There is no shared projection to normalise into any more -
    // the map has one, it is Web Mercator, and it agrees with the tiles underneath.
    val overlays = remember(state.entities, state.geometry, trace, focusedTrack, palette, liveColor) {
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
            if (trace.points.isNotEmpty()) {
                add(
                    RouteOverlay(
                        trackId = LIVE_TRACK_ID,
                        points = trace.points,
                        segmentStartIndices = trace.segmentStartIndices,
                        color = liveColor,
                    )
                )
            }
        }
    }

    val windowHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val expandedHeight = windowHeight * SheetMaxHeightFraction
    // Up to just under the top bar. Taller and the sheet slides behind chrome it cannot
    // scroll out of the way.
    val fullHeight = windowHeight -
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding() -
        TopAppBarHeight

    val sheetMaxHeight by animateDpAsState(
        targetValue = if (sheetAtFullHeight) fullHeight else expandedHeight,
        label = "sheetMaxHeight",
    )

    val navigationBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val sheetInset by animateDpAsState(
        targetValue = if (hasFocus) TrackSheetPeekHeight else navigationBarInset,
        label = "sheetInset",
    )
    // The controls float over the map, so the fit has to be told about them or half a
    // route ends up behind the recording bar.
    var controlsHeight by remember { mutableStateOf(0.dp) }

    // How much of the map is actually covered right now, which is not the same as the
    // peek: opening the sheet to read the charts hides well over half the canvas, and a
    // route fitted to the peek would be sitting mostly behind it by then.
    val coveredHeight by animateDpAsState(
        targetValue = when {
            !hasFocus -> maxOf(navigationBarInset, controlsHeight + navigationBarInset)
            // Deliberately the middle height even when the sheet is full: at full there
            // is no map left to fit a route into, and freezing it here means coming back
            // down does not have to re-fit anything.
            sheetState.currentValue == SheetValue.Expanded -> expandedHeight
            else -> maxOf(TrackSheetPeekHeight, sheetInset + controlsHeight)
        },
        label = "coveredHeight",
    )

    val canvasPadding = PaddingValues(
        start = MapEdgePadding,
        end = MapEdgePadding,
        top = MapEdgePadding,
        bottom = MapEdgePadding + coveredHeight,
    )
    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetPeekHeight = TrackSheetPeekHeight,
        sheetDragHandle = { CompactDragHandle() },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (state.entities.isEmpty()) {
                            stringResource(R.string.app_name)
                        } else {
                            pluralStringResource(
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
            when (val current = focused) {
                // Hidden, but still the peek's height: a sheet whose content is shorter
                // than its own peek leaves the scaffold with nonsense to anchor to.
                FocusedTrack.None -> Spacer(
                    Modifier.fillMaxWidth().height(TrackSheetPeekHeight)
                )

                FocusedTrack.Loading -> TrackSheetLoading()
                is FocusedTrack.Failed -> TrackSheetError(
                    messageRes = current.messageRes,
                    onRetry = viewModel::retryFocus,
                    onClose = { viewModel.focus(null) },
                )

                is FocusedTrack.Ready -> TrackSheet(
                    loaded = current.track,
                    routeColor = palette[current.track.colorIndex % palette.size],
                    maxHeight = sheetMaxHeight,
                    atFullHeight = sheetAtFullHeight,
                    onStepHeight = ::stepSheetHeight,
                    selectedIndex = selectedIndex,
                    onSelectedIndexChange = { selectedIndex = it },
                    useTimeAxis = preferTimeAxis && current.track.profile.hasTime,
                    onAxisChange = { preferTimeAxis = it },
                    // Null for a file opened from an intent: it has no row to rename,
                    // hide or delete, and the export it could offer is a copy of a file
                    // the user already has.
                    actions = state.entity(current.track.id)?.let { entity ->
                        remember(entity.id, entity.displayName) {
                            TrackActions(
                                onRename = { renamingId = entity.id },
                                onExport = {
                                    viewModel.beginExport(entity.id)
                                    exporter.launch(
                                        exportFileName(entity.trackName, entity.displayName)
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
                basemaps = basemaps,
                contentDescription = stringResource(R.string.map_description),
                focusedTrackId = focusedTrack?.id,
                selectedIndex = selectedIndex,
                markerColor = focusedTrack
                    ?.let { palette[it.colorIndex % palette.size] }
                    ?: MaterialTheme.colorScheme.primary,
                markerRingColor = MaterialTheme.colorScheme.surface,
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
                backgroundColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                landColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                labelColor = MaterialTheme.colorScheme.onSurface,
                gridColor = MaterialTheme.colorScheme.outlineVariant,
                onScaleChange = { metersPerPixel = it },
                modifier = Modifier.fillMaxSize(),
            )

            when {
                overlays.isNotEmpty() -> Unit

                state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    LinearProgressIndicator(Modifier.padding(32.dp))
                }

                // Only over a blank map. With a basemap imported there is something to
                // look at, and a card explaining the app is empty would be covering it.
                basemaps.isEmpty() -> EmptyState(
                    hasHiddenTracks = state.totalCount > 0,
                    modifier = Modifier.fillMaxSize(),
                )

                else -> Unit
            }

            MapChrome(
                metersPerPixel = metersPerPixel,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(bottom = sheetInset + 4.dp),
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = sheetInset)
                    .onSizeChanged { controlsHeight = with(density) { it.height.toDp() } },
            ) {
                // Still no reset button, though the reason has changed: a real map has a
                // whole world to be lost in rather than a unit square to pinch back out
                // of, so "show me everything again" is no longer the same gesture that
                // left. It is now a missing feature rather than a deliberate omission -
                // worth adding as a control that frames the visible tracks.
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
                        // The sheet owns the bottom edge when it is up, so the bar only
                        // needs to clear the system bars when it is not.
                        applyNavigationBarPadding = !hasFocus,
                    )
                }
            }
        }
    }

    // Only once the track has been read back, which is what knows the name to offer. The
    // prompt that follows a recording and the one reached from the menu are the same
    // dialog; only the title tells the reader which of the two brought it up.
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
            // What is actually destroyed differs by source, and that is the difference
            // the user cares about: a recording only exists here.
            deletesFiles = state.entity(id)?.source == TrackSource.RECORDED,
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
 * The middle height: the most of the window the sheet takes when simply expanded. A sheet
 * that covers the map the moment you open it is not a sheet, it is a screen that arrived
 * by sliding - so covering it all is a third state you ask for, not the one you land on.
 */
private const val SheetMaxHeightFraction = 0.58f

/** Material's default, and what the scaffold's own top bar occupies above the sheet. */
private val TopAppBarHeight = 64.dp

/**
 * Half the height of the Material handle, which spends 44 of its 48dp on padding. Every
 * one of those is a dp of map, and the sheet is dragged by its whole surface anyway.
 */
@Composable
private fun CompactDragHandle() {
    Box(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
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
    hasHiddenTracks: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.map_empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(
                if (hasHiddenTracks) R.string.map_empty_hidden else R.string.map_empty_body
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Asks for what is missing on the tap that starts a recording, and nothing before it.
 *
 * No screen in between explaining itself first: the system dialog already names what is
 * being asked for, a page of reassurance ahead of it is one more tap between the user and
 * the thing they pressed a button to do, and refusal is answered where it happens.
 *
 * Android 12+ ignores a fine-location request that does not also name coarse, so both are
 * listed - the pairing is the platform's, not a second capability this app wants.
 * Notifications are requested alongside because a foreground service the user cannot see
 * is worse than one they can, but recording works without that grant.
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
