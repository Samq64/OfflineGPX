package dev.samuelq.gpx.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.samuelq.gpx.data.db.TrackEntity
import dev.samuelq.gpx.data.track.TrackRepository
import dev.samuelq.gpx.di.appContainer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface LibraryEvent {
    data class Open(val id: Long) : LibraryEvent
    data object ImportFailed : LibraryEvent
    data object ExportFailed : LibraryEvent
    data object Exported : LibraryEvent
    data object RenameFailed : LibraryEvent
}

class LibraryViewModel(private val repository: TrackRepository) : ViewModel() {

    val tracks: StateFlow<List<TrackEntity>> = repository.tracks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Ids ticked for a batch action. Empty means the list is in its normal mode. */
    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = _selection.asStateFlow()

    /**
     * One-shot events, not state: a Channel so an id is delivered exactly once and a
     * rotation cannot re-trigger the navigation or re-show the error.
     */
    private val _events = Channel<LibraryEvent>(Channel.BUFFERED)
    val events: Flow<LibraryEvent> = _events.receiveAsFlow()

    /** The track a pending export will write, held across the document-picker round trip. */
    var exporting: TrackEntity? = null
        private set

    fun toggleSelected(id: Long) {
        _selection.value = _selection.value.let { if (id in it) it - id else it + id }
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    fun selectAll(ids: List<Long>) {
        _selection.value = ids.toSet()
    }

    fun import(uri: Uri) {
        viewModelScope.launch {
            repository.import(uri.toString()).fold(
                onSuccess = { _events.send(LibraryEvent.Open(it)) },
                onFailure = { _events.send(LibraryEvent.ImportFailed) },
            )
        }
    }

    fun beginExport(track: TrackEntity) {
        exporting = track
    }

    fun finishExport(destination: Uri?) {
        val track = exporting ?: return
        exporting = null
        if (destination == null) return
        viewModelScope.launch {
            repository.export(track.id, destination.toString()).fold(
                onSuccess = { _events.send(LibraryEvent.Exported) },
                onFailure = { _events.send(LibraryEvent.ExportFailed) },
            )
        }
    }

    fun rename(id: Long, name: String) {
        viewModelScope.launch {
            repository.rename(id, name).onFailure { _events.send(LibraryEvent.RenameFailed) }
        }
    }

    fun setVisible(ids: Collection<Long>, visible: Boolean) {
        viewModelScope.launch {
            repository.setVisible(ids.toList(), visible)
            clearSelection()
        }
    }

    fun setAllVisible(visible: Boolean) {
        viewModelScope.launch { repository.setAllVisible(visible) }
    }

    fun delete(ids: Collection<Long>) {
        viewModelScope.launch {
            repository.forgetAll(ids.toList())
            clearSelection()
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { LibraryViewModel(appContainer.trackRepository) }
        }
    }
}
