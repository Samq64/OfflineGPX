package dev.samuelq.gpx.data.record

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import dev.samuelq.gpx.core.model.TrackPoint
import dev.samuelq.gpx.core.model.Waypoint
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

sealed interface RecordingState {

    data object Idle : RecordingState

    data class Active(
        val paused: Boolean,
        val pointCount: Int,
        val distanceMeters: Double,
        /** Wall-clock time since the first point, pauses included. */
        val totalSeconds: Double,
        val lastPoint: TrackPoint?,
        val currentSpeedMps: Double?,
        /** Of the last reading, accepted or not, to tell a cold start from poor fixes. */
        val accuracyMeters: Double?,
        val accuracyLimitMeters: Double,
        /** Oldest first. */
        val waypoints: List<Waypoint>,
    ) : RecordingState
}

/** The live route for the map; apart from [RecordingState] since it updates less often. */
class LiveTrace(
    val points: List<TrackPoint>,
    /** A pause starts a new segment. */
    val segmentStartIndices: IntArray,
) {
    companion object {
        val Empty = LiveTrace(emptyList(), IntArray(0))
    }
}

/** Delivered once. */
sealed interface RecordingEvent {
    /** [id] is the saved track's row. */
    data class Saved(val id: Long) : RecordingEvent

    data object Discarded : RecordingEvent

    data class Failed(@StringRes val messageRes: Int) : RecordingEvent
}

/** Where the UI sends commands to [RecordingService] and observes what it publishes. */
class RecordingController(context: Context) {

    private val appContext = context.applicationContext

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _trace = MutableStateFlow(LiveTrace.Empty)
    val trace: StateFlow<LiveTrace> = _trace.asStateFlow()

    private val _events = Channel<RecordingEvent>(Channel.BUFFERED)
    val events: Flow<RecordingEvent> = _events.receiveAsFlow()

    internal fun update(state: RecordingState) {
        _state.value = state
    }

    internal fun updateTrace(trace: LiveTrace) {
        _trace.value = trace
    }

    /** Non-suspending, so events from a scope being cancelled aren't lost. */
    internal fun emit(event: RecordingEvent) {
        _events.trySend(event)
    }

    /** Not cached: location can be toggled at any time. */
    val isGpsEnabled: Boolean get() = LocationSource(appContext).isGpsEnabled

    fun start() = send(RecordingService.ACTION_START)
    fun pause() = send(RecordingService.ACTION_PAUSE)
    fun resume() = send(RecordingService.ACTION_RESUME)
    fun stop() = send(RecordingService.ACTION_STOP)
    fun discard() = send(RecordingService.ACTION_DISCARD)

    /** [description] may be blank. */
    fun addWaypoint(description: String) = send(RecordingService.ACTION_WAYPOINT) {
        putExtra(RecordingService.EXTRA_DESCRIPTION, description)
    }

    private fun send(action: String, extras: Intent.() -> Unit = {}) {
        val intent = Intent(appContext, RecordingService::class.java).setAction(action).apply(extras)
        if (action == RecordingService.ACTION_START) {
            appContext.startForegroundService(intent)
        } else {
            appContext.startService(intent)
        }
    }
}
