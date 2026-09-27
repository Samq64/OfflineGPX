package dev.samuelq.gpx.ui.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.samuelq.gpx.data.record.RecordingController
import dev.samuelq.gpx.data.record.RecordingEvent
import dev.samuelq.gpx.data.record.RecordingState
import dev.samuelq.gpx.di.appContainer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The recorder's controls, for whoever is showing them. That is the map screen: a
 * recording is something you watch happen on top of your routes, not a place you go.
 */
class RecordViewModel(private val controller: RecordingController) : ViewModel() {

    val state: StateFlow<RecordingState> = controller.state
    val events: Flow<RecordingEvent> = controller.events

    val isGpsEnabled: Boolean get() = controller.isGpsEnabled

    fun start() = controller.start()
    fun pause() = controller.pause()
    fun resume() = controller.resume()
    fun stop() = controller.stop()
    fun discard() = controller.discard()
    fun addWaypoint(description: String) = controller.addWaypoint(description)

    companion object {
        val Factory = viewModelFactory {
            initializer { RecordViewModel(appContainer.recordingController) }
        }
    }
}
