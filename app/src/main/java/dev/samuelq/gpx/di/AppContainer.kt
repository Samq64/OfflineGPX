package dev.samuelq.gpx.di

import android.content.Context
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.CreationExtras
import dev.samuelq.gpx.GpxApplication
import dev.samuelq.gpx.data.db.GpxDatabase
import dev.samuelq.gpx.data.map.MapStore
import dev.samuelq.gpx.data.record.LocationSource
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

    /** Held here so state outlives any screen during a ride. */
    val recordingController = RecordingController(appContext)

    val recordingRecovery by lazy { RecordingRecovery(appContext, trackRepository) }

    val settingsRepository by lazy { SettingsRepository(appContext) }

    val mapStore by lazy { MapStore(appContext) }

    val locationSource by lazy { LocationSource(appContext) }

    /** Undispatched, so it holds the recovery lock before any recording can start. */
    fun claimAbandonedRecording() {
        applicationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            recordingRecovery.claim()
            recordingRecovery.purgeDiscarded()
        }
    }

    /** Eager, since the map screen is shown first. */
    fun loadOfflineMaps() {
        applicationScope.launch { mapStore.refresh() }
    }
}

/** Reaches the container from inside a `viewModelFactory { initializer { ... } }` block. */
val CreationExtras.appContainer: AppContainer
    get() = (this[APPLICATION_KEY] as GpxApplication).container
