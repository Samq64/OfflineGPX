package dev.samuelq.gpx.di

import android.content.Context
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.CreationExtras
import dev.samuelq.gpx.GpxApplication
import dev.samuelq.gpx.data.db.GpxDatabase
import dev.samuelq.gpx.data.map.MapStore
import dev.samuelq.gpx.data.record.RecordingController
import dev.samuelq.gpx.data.settings.SettingsRepository
import dev.samuelq.gpx.data.track.GpxTrackRepository
import dev.samuelq.gpx.data.track.TrackRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manual dependency wiring, for a graph of three objects.
 *
 * Hilt becomes worth its code generation when this stops fitting on one screen - the
 * recording service reaches it through the Application rather than being injected, which
 * is the one place the lack of a container shows.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    /** Outlives any screen: a recovery must finish even if the UI is gone. */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val database by lazy { GpxDatabase.create(appContext) }

    val trackRepository: TrackRepository by lazy {
        GpxTrackRepository(context = appContext, dao = database.trackDao())
    }

    /**
     * Shared by the recording service and the screens watching it. Held here because it
     * has to outlive any screen: leaving the app mid-ride is the normal case.
     */
    val recordingController = RecordingController()

    /** Read by the recorder when a recording starts, and by everything that shows a number. */
    val settingsRepository by lazy { SettingsRepository(appContext) }

    /**
     * The offline basemaps on this device. Shared rather than per-screen: settings manages
     * them and the map draws with them, and the two have to agree on which one is active.
     */
    val mapStore by lazy { MapStore(appContext, settingsRepository) }

    /**
     * Rescues a ride whose process died before it was stopped.
     *
     * Runs once per launch, off the main thread. The write-ahead log is only worth writing
     * if something reads it back, and this is that something - without it a crash mid-ride
     * leaves a complete log that nothing ever turns into a track.
     */
    fun recoverAbandonedRecording() {
        applicationScope.launch { trackRepository.recoverAbandonedRecording() }
    }

    /**
     * Reads the maps directory once at launch, off the main thread.
     *
     * Eager because the map screen is the first thing shown and its basemap comes from
     * here: doing it lazily would mean a frame of routes on an empty background before the
     * tiles appear, every launch.
     */
    fun loadOfflineMaps() {
        applicationScope.launch { mapStore.refresh() }
    }
}

/** Reaches the container from inside a `viewModelFactory { initializer { ... } }` block. */
val CreationExtras.appContainer: AppContainer
    get() = (this[APPLICATION_KEY] as GpxApplication).container
