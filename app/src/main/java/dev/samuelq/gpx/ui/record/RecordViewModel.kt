package dev.samuelq.gpx.ui.record

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.samuelq.gpx.GpxApplication
import dev.samuelq.gpx.data.record.RecordingController
import dev.samuelq.gpx.data.record.RecordingEvent
import dev.samuelq.gpx.data.record.RecordingService
import dev.samuelq.gpx.data.record.RecordingState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

class RecordViewModel(
    application: Application,
    private val controller: RecordingController,
) : AndroidViewModel(application) {

    val state: StateFlow<RecordingState> = controller.state
    val events: Flow<RecordingEvent> = controller.events

    /** True when the device can actually produce a fix, checked before offering to start. */
    val isGpsEnabled: Boolean
        get() = dev.samuelq.gpx.data.record.LocationSource(getApplication()).isGpsEnabled

    fun start() = send(RecordingService.ACTION_START)
    fun pause() = send(RecordingService.ACTION_PAUSE)
    fun resume() = send(RecordingService.ACTION_RESUME)
    fun stop() = send(RecordingService.ACTION_STOP)
    fun discard() = send(RecordingService.ACTION_DISCARD)

    private fun send(action: String) =
        RecordingService.send(getApplication(), action)

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as GpxApplication
                RecordViewModel(app, app.container.recordingController)
            }
        }
    }
}
