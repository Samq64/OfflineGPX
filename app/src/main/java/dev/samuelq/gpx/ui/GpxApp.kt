package dev.samuelq.gpx.ui

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.samuelq.gpx.data.record.RecordingState
import dev.samuelq.gpx.ui.format.LocalFormatters
import dev.samuelq.gpx.ui.format.rememberSystemFormatters
import dev.samuelq.gpx.ui.library.LibraryScreen
import dev.samuelq.gpx.ui.map.MapScreen
import dev.samuelq.gpx.ui.nav.LibrariesRoute
import dev.samuelq.gpx.ui.nav.LibraryRoute
import dev.samuelq.gpx.ui.nav.MapRoute
import dev.samuelq.gpx.ui.nav.SettingsRoute
import dev.samuelq.gpx.ui.record.RecordingViewModel
import dev.samuelq.gpx.ui.record.StopRecordingDialog
import dev.samuelq.gpx.ui.settings.LibrariesScreen
import dev.samuelq.gpx.ui.settings.SettingsScreen
import dev.samuelq.gpx.ui.settings.SettingsViewModel
import dev.samuelq.gpx.ui.track.TrackRef

/** A track is a selection on the map, not a destination. */
@Composable
fun GpxApp(incomingFiles: List<Uri>, onIncomingFilesHandled: () -> Unit) {
    val navController = rememberNavController()
    val settings by viewModel<SettingsViewModel>(factory = SettingsViewModel.Factory).settings
        .collectAsStateWithLifecycle()

    // Remembered: the charts key their labels on this instance.
    val formatters = rememberSystemFormatters(settings.units)

    LaunchedEffect(incomingFiles) {
        if (incomingFiles.isEmpty()) return@LaunchedEffect
        navController.backToMap(INCOMING_KEY, ArrayList(incomingFiles))
        onIncomingFilesHandled()
    }

    CompositionLocalProvider(LocalFormatters provides formatters) {
        // No BackHandler here: NavHost owns the back stack, so back at the root exits and
        // predictive back isn't intercepted.
        NavHost(
            navController = navController,
            startDestination = MapRoute,
            enterTransition = { slideIntoContainer(SlideDirection.Start, NavigationSpec) },
            exitTransition = { slideOutOfContainer(SlideDirection.Start, NavigationSpec) },
            popEnterTransition = { slideIntoContainer(SlideDirection.End, NavigationSpec) },
            popExitTransition = { slideOutOfContainer(SlideDirection.End, NavigationSpec) },
        ) {
            composable<MapRoute> { entry ->
                val pending by entry.savedStateHandle
                    .getStateFlow<Long?>(FOCUS_KEY, null)
                    .collectAsStateWithLifecycle()
                val imported by entry.savedStateHandle
                    .getStateFlow<Long?>(IMPORTED_KEY, null)
                    .collectAsStateWithLifecycle()
                val incoming by entry.savedStateHandle
                    .getStateFlow<ArrayList<Uri>?>(INCOMING_KEY, null)
                    .collectAsStateWithLifecycle()
                val pendingMap by entry.savedStateHandle
                    .getStateFlow<String?>(SHOW_MAP_KEY, null)
                    .collectAsStateWithLifecycle()

                MapScreen(
                    pendingFocus = pending?.let(TrackRef::Saved),
                    onFocusConsumed = { entry.savedStateHandle[FOCUS_KEY] = null },
                    pendingImported = imported,
                    onImportedConsumed = { entry.savedStateHandle[IMPORTED_KEY] = null },
                    incomingFiles = incoming.orEmpty(),
                    onIncomingConsumed = { entry.savedStateHandle[INCOMING_KEY] = null },
                    pendingMap = pendingMap,
                    onMapConsumed = { entry.savedStateHandle[SHOW_MAP_KEY] = null },
                    onOpenList = { area -> navController.open(LibraryRoute(area)) },
                    onOpenSettings = { navController.open(SettingsRoute()) },
                    onImportMap = { navController.open(SettingsRoute(importMap = true)) },
                )
            }

            composable<LibraryRoute> {
                LibraryScreen(
                    onOpenTrack = { id -> navController.backToMap(FOCUS_KEY, id) },
                    onImportedTrack = { id -> navController.backToMap(IMPORTED_KEY, id) },
                    // A second tap mid-transition would otherwise pop the map too.
                    onBack = dropUnlessResumed { navController.popBackStack() },
                )
            }

            composable<SettingsRoute> { entry ->
                SettingsScreen(
                    onBack = dropUnlessResumed { navController.popBackStack() },
                    onOpenLibraries = { navController.open(LibrariesRoute) },
                    onShowMap = { name -> navController.backToMap(SHOW_MAP_KEY, name) },
                    importMapOnOpen = entry.toRoute<SettingsRoute>().importMap,
                )
            }

            composable<LibrariesRoute> {
                LibrariesScreen(onBack = dropUnlessResumed { navController.popBackStack() })
            }
        }

        // Here, not on the map, so the notification's Stop is answered over any screen.
        StopRecordingPrompt()
    }
}

/** Its own scope: the recording's state changes every second and would redo the whole app. */
@Composable
private fun StopRecordingPrompt(recorder: RecordingViewModel = viewModel(factory = RecordingViewModel.Factory)) {
    val stopRequested by recorder.stopRequested.collectAsStateWithLifecycle()
    if (!stopRequested) return
    val recording by recorder.state.collectAsStateWithLifecycle()
    val categories by recorder.categories.collectAsStateWithLifecycle()
    (recording as? RecordingState.Active)?.let { active ->
        StopRecordingDialog(
            state = active,
            categories = categories,
            onSave = recorder::stop,
            onDiscard = recorder::discard,
            onDismiss = recorder::cancelStop,
        )
    }
}

private val NavigationSpec = tween<IntOffset>(durationMillis = 300)

/** A saved track's id to open. */
private const val FOCUS_KEY = "focus"

/** A track just imported from the list, to open and announce. */
private const val IMPORTED_KEY = "imported"

/** Files from other apps, to add. */
private const val INCOMING_KEY = "incoming"

/** A map's file name to frame. */
private const val SHOW_MAP_KEY = "showMap"

/** A pop, not a navigate: the map is always on the stack. */
private fun NavController.backToMap(key: String, value: Any) {
    getBackStackEntry(MapRoute).savedStateHandle[key] = value
    popBackStack(MapRoute, inclusive = false)
}

/** Single-top, so a double tap doesn't push a duplicate. */
private fun NavController.open(route: Any) = navigate(route) { launchSingleTop = true }
