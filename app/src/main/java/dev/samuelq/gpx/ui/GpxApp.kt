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
import androidx.lifecycle.compose.dropUnlessResumed
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
 * Three destinations. A track isn't one - it's a selection on the map, shown in a sheet
 * there - so back means one thing: close what's open, then leave.
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
    // scales and tick labels on this instance.
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
                )
            }

            composable<LibraryRoute> {
                LibraryScreen(
                    onOpenTrack = { id -> navController.focusOnMap(FocusRequest.saved(id)) },
                    // Only while resumed: a second tap during the exit transition would
                    // otherwise pop the map too, leaving an empty NavHost.
                    onBack = dropUnlessResumed { navController.popBackStack() },
                )
            }

            composable<SettingsRoute> {
                SettingsScreen(onBack = dropUnlessResumed { navController.popBackStack() })
            }
        }
    }
}

/** Long enough to read as movement, short enough not to be a wait. */
private val NavigationSpec = tween<IntOffset>(durationMillis = 300)

/**
 * A track handed to the map from somewhere else, as one bundle-safe value. The
 * discriminator rides along with the value rather than using two separate keys, which would
 * have a meaningless third state where both are set.
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
 * Shows a track on the map, from wherever the caller is. A pop rather than a navigate,
 * since the map is always on the stack.
 */
private fun NavController.focusOnMap(request: String) {
    getBackStackEntry(MapRoute).savedStateHandle[FocusRequest.KEY] = request
    popBackStack(MapRoute, inclusive = false)
}

/**
 * Navigate, but never onto a copy of where we already are - a double tap otherwise pushes
 * a duplicate entry that back then peels away to reveal itself.
 */
private fun NavController.open(route: Any) = navigate(route) { launchSingleTop = true }
