package dev.samuelq.gpx.ui

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.samuelq.gpx.GpxApplication
import dev.samuelq.gpx.ui.format.Formatters
import dev.samuelq.gpx.ui.format.LocalFormatters
import dev.samuelq.gpx.ui.library.LibraryScreen
import dev.samuelq.gpx.ui.map.MapScreen
import dev.samuelq.gpx.ui.nav.LibraryRoute
import dev.samuelq.gpx.ui.nav.MapRoute
import dev.samuelq.gpx.ui.nav.SettingsRoute
import dev.samuelq.gpx.ui.settings.SettingsScreen
import dev.samuelq.gpx.ui.track.TrackRef

/**
 * Three destinations, and that is the whole app.
 *
 * A track is not one of them any more - it is a selection on the map, shown in a sheet
 * there. What is left is the map, the list that curates it and the settings, which is few
 * enough that back means one thing: close what is open, then leave.
 */
@Composable
fun GpxApp(
    incomingTrack: Uri?,
    onIncomingTrackHandled: () -> Unit,
) {
    val navController = rememberNavController()
    val container = (LocalContext.current.applicationContext as GpxApplication).container
    val settings by container.settingsRepository.settings.collectAsStateWithLifecycle()

    // Remembered per unit system, not rebuilt per recomposition: the charts key their
    // layout on the identity of the axis lambdas this carries.
    val formatters = remember(settings.units) { Formatters(settings.units) }

    LaunchedEffect(incomingTrack) {
        val uri = incomingTrack ?: return@LaunchedEffect
        // Straight onto the map, wherever the user happened to be. A file handed over by
        // another app is a track to look at, not a reason to build a stack.
        navController.focusOnMap(FocusRequest.uri(uri.toString()))
        onIncomingTrackHandled()
    }

    CompositionLocalProvider(LocalFormatters provides formatters) {
        // No BackHandler here: NavHost owns the back stack, so back at the root exits and
        // predictive back works without the app intercepting the gesture. The map has one
        // of its own, for the sheet, which is a different question.
        //
        // Slide, in and out, rather than the cross-fade navigation-compose defaults to.
        // A fade says "something changed"; a slide says where it went and how to get back,
        // which is the whole job of the animation - and the predictive back gesture drags
        // `popExit` directly, so the peel-back under your thumb is this one.
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
                )
            }

            composable<LibraryRoute> {
                LibraryScreen(
                    onOpenTrack = { id -> navController.focusOnMap(FocusRequest.saved(id)) },
                    onBack = navController::popBackStack,
                )
            }

            composable<SettingsRoute> {
                SettingsScreen(onBack = navController::popBackStack)
            }
        }
    }
}

/** Long enough to read as movement, short enough not to be a wait. */
private val NavigationSpec = tween<IntOffset>(durationMillis = 300)

/**
 * A track handed to the map from somewhere else, as one bundle-safe value.
 *
 * Two separate keys for "a row" and "a URI" would have a third state - both set - that
 * means nothing, so the discriminator rides along with the value. It is parsed back with
 * a single [substringAfter], not split on a separator that could appear inside a URI; that
 * is the mistake the old space-separated back stack made.
 */
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

/**
 * Shows a track on the map, from wherever the caller is.
 *
 * The map is the start destination, so it is always on the stack and always the thing to
 * come back to - which makes this a pop rather than a navigate, and means opening a track
 * from the list never leaves a second copy of anything behind.
 */
private fun NavController.focusOnMap(request: String) {
    getBackStackEntry(MapRoute).savedStateHandle[FocusRequest.KEY] = request
    popBackStack(MapRoute, inclusive = false)
}

/**
 * Navigate, but never onto a copy of where we already are.
 *
 * Without this a double tap - or one gesture that fires the same callback twice - pushes
 * the same destination twice, and the back gesture then animates the screen you are on
 * peeling back to reveal itself. That reads as a broken gesture rather than as a duplicate
 * entry, which is why it took so long to spot.
 */
private fun NavController.open(route: Any) = navigate(route) { launchSingleTop = true }
