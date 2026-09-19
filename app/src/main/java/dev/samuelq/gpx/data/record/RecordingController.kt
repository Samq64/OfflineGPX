package dev.samuelq.gpx.data.record

import dev.samuelq.gpx.core.model.TrackPoint
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import java.time.Instant

/** What the recorder is doing, as the UI needs to see it. */
sealed interface RecordingState {

    data object Idle : RecordingState

    data class Active(
        val paused: Boolean,
        val startedAt: Instant,
        val pointCount: Int,
        val distanceMeters: Double,
        val movingSeconds: Double,
        val lastPoint: TrackPoint?,
        /** Null until the first fix lands - GPS takes a few seconds to settle outdoors. */
        val currentSpeedMps: Double?,
    ) : RecordingState
}

/** Something the recorder finished doing, delivered once. */
sealed interface RecordingEvent {
    /** A recording was stopped and saved. [id] is its row in the library. */
    data class Saved(val id: Long) : RecordingEvent

    data object Discarded : RecordingEvent

    data class Failed(val reason: String) : RecordingEvent
}

/**
 * The single place the service and the UI meet.
 *
 * The service owns the recording and cannot be bound to from a composable without a lot of
 * ceremony, so it publishes here instead and the screen just observes. Held by
 * [dev.samuelq.gpx.di.AppContainer], so it outlives any screen - the state has to survive
 * the user leaving the app mid-ride, which is the normal case rather than the edge one.
 */
class RecordingController {

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _events = Channel<RecordingEvent>(Channel.BUFFERED)
    val events: Flow<RecordingEvent> = _events.receiveAsFlow()

    val isActive: Boolean get() = _state.value is RecordingState.Active

    internal fun update(state: RecordingState) {
        _state.value = state
    }

    internal suspend fun emit(event: RecordingEvent) {
        _events.send(event)
    }
}
