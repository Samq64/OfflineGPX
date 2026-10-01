package dev.samuelq.gpx.ui

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.samuelq.gpx.GpxApplication
import dev.samuelq.gpx.data.record.RecordingController
import dev.samuelq.gpx.data.record.RecordingState
import dev.samuelq.gpx.ui.format.rememberSystemFormatters
import dev.samuelq.gpx.ui.format.LocalFormatters
import dev.samuelq.gpx.ui.library.LibraryScreen
import dev.samuelq.gpx.ui.map.MapScreen
import dev.samuelq.gpx.ui.nav.LibraryRoute
import dev.samuelq.gpx.ui.nav.MapRoute
import dev.samuelq.gpx.ui.nav.SettingsRoute
import dev.samuelq.gpx.ui.record.StopRecordingDialog
import dev.samuelq.gpx.ui.settings.SettingsScreen
import dev.samuelq.gpx.ui.track.TrackRef

/** A track is a selection on the map, not a destination. */
@Composable
fun GpxApp(
    incomingTrack: Uri?,
    onIncomingTrackHandled: () -> Unit,
) {
    val navController = rememberNavController()
    val container = (LocalContext.current.applicationContext as GpxApplication).container
    val settings by container.settingsRepository.settings.collectAsStateWithLifecycle()

    // Remembered: the charts key their labels on this instance.
    val formatters = rememberSystemFormatters(settings.units)

    LaunchedEffect(incomingTrack) {
        val uri = incomingTrack ?: return@LaunchedEffect
        navController.focusOnMap(FocusRequest.uri(uri.toString()))
        onIncomingTrackHandled()
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
                    .getStateFlow(FocusRequest.KEY, null as String?)
                    .collectAsStateWithLifecycle()

                MapScreen(
                    pendingFocus = pending?.let(FocusRequest::decode),
                    onFocusConsumed = { entry.savedStateHandle[FocusRequest.KEY] = null },
                    onOpenList = { navController.open(LibraryRoute) },
                    onOpenSettings = { navController.open(SettingsRoute) },
                    recorder = container.recordingController,
                    showZoomButtons = settings.showZoomButtons,
                )
            }

            composable<LibraryRoute> {
                LibraryScreen(
                    onOpenTrack = { id -> navController.focusOnMap(FocusRequest.saved(id)) },
                    // A second tap mid-transition would otherwise pop the map too.
                    onBack = dropUnlessResumed { navController.popBackStack() },
                )
            }

            composable<SettingsRoute> {
                SettingsScreen(onBack = dropUnlessResumed { navController.popBackStack() })
            }
        }

        // Here, not on the map, so the notification's Stop is answered over any screen.
        StopRecordingPrompt(container.recordingController)
    }
}

/** Its own scope: the recording's state changes every second and would redo the whole app. */
@Composable
private fun StopRecordingPrompt(recorder: RecordingController) {
    val stopRequested by recorder.stopRequested.collectAsStateWithLifecycle()
    if (!stopRequested) return
    val recording by recorder.state.collectAsStateWithLifecycle()
    (recording as? RecordingState.Active)?.let { active ->
        StopRecordingDialog(
            state = active,
            onSave = recorder::stop,
            onDiscard = recorder::discard,
            onDismiss = recorder::cancelStop,
        )
    }
}

private val NavigationSpec = tween<IntOffset>(durationMillis = 300)

/** One prefixed string rather than two keys, which could both be set. */
private object FocusRequest {
    const val KEY = "focus"

    private const val SAVED = "saved:"
    private const val URI = "uri:"

    fun saved(id: Long): String = "$SAVED$id"
    fun uri(value: String): String = "$URI$value"

    fun decode(raw: String): TrackRef? = when {
        raw.startsWith(SAVED) -> raw.removePrefix(SAVED).toLongOrNull()?.let(TrackRef::Saved)
        raw.startsWith(URI) -> TrackRef.Transient(raw.removePrefix(URI))
        else -> null
    }
}

/** A pop, not a navigate: the map is always on the stack. */
private fun NavController.focusOnMap(request: String) {
    getBackStackEntry(MapRoute).savedStateHandle[FocusRequest.KEY] = request
    popBackStack(MapRoute, inclusive = false)
}

/** Single-top, so a double tap doesn't push a duplicate. */
private fun NavController.open(route: Any) = navigate(route) { launchSingleTop = true }
