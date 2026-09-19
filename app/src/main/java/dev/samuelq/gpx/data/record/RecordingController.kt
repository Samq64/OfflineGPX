package dev.samuelq.gpx.data.record

import androidx.annotation.StringRes
import dev.samuelq.gpx.core.analysis.FixFilter
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
        /**
         * Accuracy of the last reading, believed or not.
         *
         * Shown while nothing has been recorded yet, because "waiting for a fix" and
         * "getting fixes, none of them good enough to be a position" look identical from
         * the outside and mean very different things - the first is a cold start, the
         * second is being indoors.
         */
        val accuracyMeters: Double? = null,
        /** The limit [accuracyMeters] is being judged against, since the user can move it. */
        val accuracyLimitMeters: Double = FixFilter.MAX_ACCURACY_METERS,
    ) : RecordingState
}

/**
 * The recording so far, in the shape the map draws it.
 *
 * Held apart from [RecordingState] because the two change at different rates: the numbers
 * are worth a redraw every fix, re-projecting every route on the map is not.
 */
class LiveTrace(
    val points: List<TrackPoint>,
    /** Index of each run's first point. A pause starts a new one, as in a GPX segment. */
    val segmentStartIndices: IntArray,
) {
    companion object {
        val Empty = LiveTrace(emptyList(), IntArray(0))
    }
}

/** Something the recorder finished doing, delivered once. */
sealed interface RecordingEvent {
    /** A recording was stopped and saved. [id] is its row in the library. */
    data class Saved(val id: Long) : RecordingEvent

    data object Discarded : RecordingEvent

    /**
     * A resource id rather than a message: these are shown to the user verbatim, and a
     * string assembled in the service is one the translators never see.
     */
    data class Failed(@StringRes val messageRes: Int) : RecordingEvent
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

    private val _trace = MutableStateFlow(LiveTrace.Empty)
    val trace: StateFlow<LiveTrace> = _trace.asStateFlow()

    private val _events = Channel<RecordingEvent>(Channel.BUFFERED)
    val events: Flow<RecordingEvent> = _events.receiveAsFlow()

    val isActive: Boolean get() = _state.value is RecordingState.Active

    internal fun update(state: RecordingState) {
        _state.value = state
    }

    internal fun updateTrace(trace: LiveTrace) {
        _trace.value = trace
    }

    /**
     * Deliberately not suspending. Half the events worth sending are sent on the way out -
     * the service refusing to start, or shutting down - and a `send` from a scope that is
     * about to be cancelled is an event the user never hears about. The channel is
     * buffered, so this only drops if nothing has collected for 64 events.
     */
    internal fun emit(event: RecordingEvent) {
        _events.trySend(event)
    }
}
