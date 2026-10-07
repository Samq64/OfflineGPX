package dev.samuelq.gpx.ui.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.samuelq.gpx.data.record.RecordingController
import dev.samuelq.gpx.data.record.RecordingEvent
import dev.samuelq.gpx.data.record.RecordingState
import dev.samuelq.gpx.data.track.TrackLabel
import dev.samuelq.gpx.data.track.TrackRepository
import dev.samuelq.gpx.di.appContainer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** The recording as the UI sees it: its state, its outcomes and what the user can do to it. */
class RecordingViewModel(private val controller: RecordingController, tracks: TrackRepository) : ViewModel() {

    val state: StateFlow<RecordingState> = controller.state

    /** Stop was asked for, from the sheet or the notification, and awaits an answer. */
    val stopRequested: StateFlow<Boolean> = controller.stopRequested

    /** Delivered once, so only [RecordingOutcomes] collects them. */
    val events: Flow<RecordingEvent> = controller.events

    /** For naming a recording as it's saved. */
    val categories: StateFlow<CategoryChoice> =
        combine(tracks.categories, tracks.lastRecordingCategory) { all, last -> CategoryChoice(all, last.orEmpty()) }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(SHARE_GRACE_MILLIS),
                CategoryChoice(emptyList(), ""),
            )

    /** Not cached: location can be toggled at any time. */
    val isGpsEnabled: Boolean get() = controller.isGpsEnabled

    fun start() = controller.start()
    fun pause() = controller.pause()
    fun resume() = controller.resume()
    fun requestStop() = controller.requestStop()
    fun cancelStop() = controller.cancelStop()
    fun stop(label: TrackLabel) = controller.stop(label)
    fun discard(label: TrackLabel) = controller.discard(label)
    fun addWaypoint(name: String) = controller.addWaypoint(name)

    companion object {
        private const val SHARE_GRACE_MILLIS = 5_000L

        val Factory = viewModelFactory {
            initializer {
                RecordingViewModel(appContainer.recordingController, appContainer.trackRepository)
            }
        }
    }
}
