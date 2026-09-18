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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(private val repository: TrackRepository) : ViewModel() {

    val tracks: StateFlow<List<TrackEntity>> = repository.tracks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * One-shot events, not state: a Channel so an id is delivered exactly once and a
     * rotation cannot re-trigger the navigation or re-show the error.
     */
    private val _events = Channel<LibraryEvent>(Channel.BUFFERED)
    val events: Flow<LibraryEvent> = _events.receiveAsFlow()

    /** Indexes the picked file, then asks the UI to open the row it produced. */
    fun import(uri: Uri) {
        viewModelScope.launch {
            repository.import(uri.toString()).fold(
                onSuccess = { _events.send(LibraryEvent.Open(it)) },
                onFailure = { _events.send(LibraryEvent.ImportFailed) },
            )
        }
    }

    fun forget(id: Long) {
        viewModelScope.launch { repository.forget(id) }
    }

    fun clearAll() {
        viewModelScope.launch { repository.clearAll() }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { LibraryViewModel(appContainer.trackRepository) }
        }
    }
}

sealed interface LibraryEvent {
    data class Open(val id: Long) : LibraryEvent
    data object ImportFailed : LibraryEvent
}
