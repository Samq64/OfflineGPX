package dev.samuelq.gpx.di

import android.content.Context
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.CreationExtras
import dev.samuelq.gpx.GpxApplication
import dev.samuelq.gpx.data.db.GpxDatabase
import dev.samuelq.gpx.data.track.GpxTrackRepository
import dev.samuelq.gpx.data.track.TrackRepository

/**
 * Manual dependency wiring, for a graph of two objects.
 *
 * Hilt becomes worth its code generation when the recording service lands - a Service has
 * no CreationExtras to reach through - and not before.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    private val database by lazy { GpxDatabase.create(appContext) }

    val trackRepository: TrackRepository by lazy {
        GpxTrackRepository(context = appContext, dao = database.trackDao())
    }
}

/** Reaches the container from inside a `viewModelFactory { initializer { ... } }` block. */
val CreationExtras.appContainer: AppContainer
    get() = (this[APPLICATION_KEY] as GpxApplication).container
