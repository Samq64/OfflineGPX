package dev.samuelq.gpx.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.samuelq.gpx.core.model.TrackPoint
import dev.samuelq.gpx.data.record.LocationSource
import dev.samuelq.gpx.data.record.RecordingController
import dev.samuelq.gpx.data.record.RecordingState
import dev.samuelq.gpx.di.appContainer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn

/** Why showing the position stopped. */
enum class LocationStopped { OFF, DENIED }

/** The user's position on the map, outside a recording. */
class LocationViewModel(
    private val locationSource: LocationSource,
    controller: RecordingController,
) : ViewModel() {

    private val _locating = MutableStateFlow(false)
    /** Kept across navigation, so returning to the map shows the position again. */
    val locating: StateFlow<Boolean> = _locating.asStateFlow()

    val isGpsEnabled: Boolean get() = locationSource.isGpsEnabled

    private val _stopped = Channel<LocationStopped>(Channel.BUFFERED)
    val stopped: Flow<LocationStopped> = _stopped.receiveAsFlow()

    fun showLocation(shown: Boolean) {
        _locating.value = shown
    }

    /**
     * The latest fix while [locating], null while waiting for one. Stops off screen, and while
     * recording, whose puck is the position then.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val position: StateFlow<TrackPoint?> = combine(
        _locating,
        controller.state.map { it is RecordingState.Active },
    ) { locating, recording -> locating && !recording }
        .distinctUntilChanged()
        .flatMapLatest { on ->
            if (!on) {
                flowOf(null)
            } else {
                flow<TrackPoint?> {
                    emit(null)
                    emitAll(locationSource.fixes(onAvailable = { if (!it) stop(LocationStopped.OFF) }))
                }.catch { if (it is SecurityException) stop(LocationStopped.DENIED) else throw it }
            }
        }
        // No replay: a fix from before the screen went away would show a stale position.
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), null)

    private fun stop(reason: LocationStopped) {
        _locating.value = false
        _stopped.trySend(reason)
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                LocationViewModel(
                    locationSource = appContainer.locationSource,
                    controller = appContainer.recordingController,
                )
            }
        }
    }
}
