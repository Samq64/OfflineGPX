package dev.samuelq.gpx.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.samuelq.gpx.ui.library.LibraryScreen
import dev.samuelq.gpx.ui.map.MapScreen
import dev.samuelq.gpx.ui.nav.LibraryRoute
import dev.samuelq.gpx.ui.nav.MapRoute
import dev.samuelq.gpx.ui.nav.RecordRoute
import dev.samuelq.gpx.ui.nav.TrackRoute
import dev.samuelq.gpx.ui.nav.TransientTrackRoute
import dev.samuelq.gpx.ui.record.RecordScreen
import dev.samuelq.gpx.ui.track.TrackRef
import dev.samuelq.gpx.ui.track.TrackScreen

@Composable
fun GpxApp(
    incomingTrack: Uri?,
    onIncomingTrackHandled: () -> Unit,
) {
    val navController = rememberNavController()

    LaunchedEffect(incomingTrack) {
        val uri = incomingTrack ?: return@LaunchedEffect
        // The map stays underneath, so back from an externally opened file lands somewhere
        // sensible rather than closing the app.
        navController.navigate(TransientTrackRoute(uri.toString())) {
            popUpTo(MapRoute)
        }
        onIncomingTrackHandled()
    }

    // No BackHandler: NavHost owns the back stack, so back at the root exits and
    // predictive back works without the app intercepting the gesture.
    NavHost(navController = navController, startDestination = MapRoute) {

        composable<MapRoute> {
            MapScreen(
                onOpenTrack = { id -> navController.navigate(TrackRoute(id)) },
                onOpenList = { navController.navigate(LibraryRoute) },
                onRecord = { navController.navigate(RecordRoute) },
            )
        }

        composable<LibraryRoute> {
            LibraryScreen(
                onOpenTrack = { id -> navController.navigate(TrackRoute(id)) },
                onBack = navController::popBackStack,
            )
        }

        composable<TrackRoute> { entry ->
            TrackScreen(
                ref = TrackRef.Saved(entry.toRoute<TrackRoute>().id),
                onBack = navController::popBackStack,
            )
        }

        composable<TransientTrackRoute> { entry ->
            TrackScreen(
                ref = TrackRef.Transient(entry.toRoute<TransientTrackRoute>().uri),
                onBack = navController::popBackStack,
            )
        }

        composable<RecordRoute> {
            RecordScreen(
                onBack = navController::popBackStack,
                onSaved = { id ->
                    // Replace the recorder with the track it produced: going back from a
                    // finished ride should reach the map, not the recorder again.
                    navController.navigate(TrackRoute(id)) {
                        popUpTo(RecordRoute) { inclusive = true }
                    }
                },
            )
        }
    }
}
