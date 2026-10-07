package dev.samuelq.gpx.ui.record

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.samuelq.gpx.data.record.AbandonedRecording
import dev.samuelq.gpx.data.record.DiscardedRecording
import dev.samuelq.gpx.data.record.RecordingRecovery
import dev.samuelq.gpx.data.track.TrackLabel
import dev.samuelq.gpx.di.appContainer
import java.io.File
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Delivered once. */
sealed interface RecoveryEvent {
    /** [id] is the saved track's row. */
    data class Saved(val id: Long) : RecoveryEvent

    data object Failed : RecoveryEvent

    /** Undone with [RecoveryViewModel.restoreAbandoned], else [RecoveryViewModel.forgetAbandoned]. */
    class AbandonedDiscarded(val recording: AbandonedRecording, val label: TrackLabel) : RecoveryEvent
}

/** Recordings a crash left unsaved, and undoing Stop's discard. */
class RecoveryViewModel(private val recovery: RecordingRecovery) : ViewModel() {

    /** The oldest recording a crash left unsaved, until it is saved or discarded. */
    private val _abandoned = MutableStateFlow<AbandonedRecording?>(null)
    val abandoned: StateFlow<AbandonedRecording?> = _abandoned.asStateFlow()
    private val skipped = mutableSetOf<File>()

    /** A channel, not state, so a rotation can't re-announce something. */
    private val _events = Channel<RecoveryEvent>(Channel.BUFFERED)
    val events: Flow<RecoveryEvent> = _events.receiveAsFlow()

    init {
        viewModelScope.launch { nextAbandoned() }
    }

    /** Saves the abandoned recording on offer and opens it, as a clean stop would. */
    fun saveAbandoned(label: TrackLabel) {
        val recording = _abandoned.value ?: return
        _abandoned.value = null
        restoreAbandoned(recording, label)
    }

    /** Also what undoing its discard runs. */
    fun restoreAbandoned(recording: AbandonedRecording, label: TrackLabel) {
        viewModelScope.launch {
            recovery.save(recording, label).fold(
                onSuccess = { _events.trySend(RecoveryEvent.Saved(it)) },
                // Still on disk: asked about again next launch.
                onFailure = {
                    skipped += recording.file
                    _events.trySend(RecoveryEvent.Failed)
                },
            )
            nextAbandoned()
        }
    }

    /** Kept on disk, and skipped here, until the undo lapses. [label] is what an undo saves. */
    fun discardAbandoned(label: TrackLabel) {
        val recording = _abandoned.value ?: return
        _abandoned.value = null
        skipped += recording.file
        _events.trySend(RecoveryEvent.AbandonedDiscarded(recording, label))
        viewModelScope.launch { nextAbandoned() }
    }

    fun forgetAbandoned(recording: AbandonedRecording) = recovery.forget(recording)

    /** Undoes a Stop dialog's discard: saves the ride and opens it, as Save would have. */
    fun restoreDiscarded(recording: DiscardedRecording) {
        viewModelScope.launch {
            recovery.restore(recording).fold(
                onSuccess = { _events.trySend(RecoveryEvent.Saved(it)) },
                onFailure = { _events.trySend(RecoveryEvent.Failed) },
            )
        }
    }

    fun forgetDiscarded(recording: DiscardedRecording) = recovery.forget(recording)

    /** After a stop's save failed and its log was claimed. */
    fun refresh() {
        viewModelScope.launch { nextAbandoned() }
    }

    private suspend fun nextAbandoned() {
        // Skipping a failed save keeps the dialog from reopening on it.
        _abandoned.value = recovery.abandoned().firstOrNull { it.file !in skipped }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { RecoveryViewModel(recovery = appContainer.recordingRecovery) }
        }
    }
}
