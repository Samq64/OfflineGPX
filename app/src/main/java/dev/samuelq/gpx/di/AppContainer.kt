package dev.samuelq.gpx.di

import android.content.Context
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.CreationExtras
import dev.samuelq.gpx.GpxApplication
import dev.samuelq.gpx.data.db.GpxDatabase
import dev.samuelq.gpx.data.map.MapStore
import dev.samuelq.gpx.data.record.RecordingController
import dev.samuelq.gpx.data.record.RecordingRecovery
import dev.samuelq.gpx.data.settings.SettingsRepository
import dev.samuelq.gpx.data.track.TrackRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Manual dependency wiring: the graph is a handful of objects, too few for Hilt. */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    /** Outlives any screen: a recovery must finish even if the UI is gone. */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val database by lazy { GpxDatabase.create(appContext) }

    val trackRepository by lazy { TrackRepository(appContext, database.trackDao()) }

    /**
     * Shared by the recording service and the screens watching it. Held here because it
     * has to outlive any screen: leaving the app mid-ride is the normal case.
     */
    val recordingController = RecordingController(appContext)

    /** The live recording's log, and whatever a crash left of an earlier one. */
    val recordingRecovery by lazy { RecordingRecovery(appContext, trackRepository) }

    /** Read by the recorder when a recording starts, and by everything that shows a number. */
    val settingsRepository by lazy { SettingsRepository(appContext) }

    /**
     * The offline basemaps on this device. Shared rather than per-screen: settings manages
     * them and the map draws with them, and the two have to agree on which one is active.
     */
    val mapStore by lazy { MapStore(appContext, settingsRepository) }

    /**
     * Sets aside a ride whose process died before it was stopped, for the map to ask about.
     * Runs once per launch, off the main thread - but undispatched, so it holds the
     * recovery lock before any recording can start or the map can ask.
     */
    fun claimAbandonedRecording() {
        applicationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            recordingRecovery.claim()
        }
    }

    /**
     * Reads the maps directory once at launch, off the main thread. Eager, since the map
     * screen is shown first - lazy loading would flash an empty background before tiles appear.
     */
    fun loadOfflineMaps() {
        applicationScope.launch { mapStore.refresh() }
    }
}

/** Reaches the container from inside a `viewModelFactory { initializer { ... } }` block. */
val CreationExtras.appContainer: AppContainer
    get() = (this[APPLICATION_KEY] as GpxApplication).container
