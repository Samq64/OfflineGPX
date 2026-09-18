package dev.samuelq.gpx.ui.track

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.samuelq.gpx.R
import dev.samuelq.gpx.data.track.LoadedTrack
import dev.samuelq.gpx.data.track.TrackLoadException
import dev.samuelq.gpx.data.track.TrackRepository
import dev.samuelq.gpx.di.appContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Which track a screen is showing, and therefore how the repository should fetch it. */
sealed interface TrackRef {
    /** A row in the library. */
    data class Saved(val id: Long) : TrackRef

    /** A one-shot URI from a VIEW or SEND intent, deliberately not indexed. */
    data class Transient(val uri: String) : TrackRef
}

sealed interface TrackUiState {
    data object Loading : TrackUiState
    data class Ready(val track: LoadedTrack) : TrackUiState
    data class Failed(@StringRes val messageRes: Int) : TrackUiState
}

/**
 * Owns one track: the read, the parse and the analysis, held across configuration changes
 * so a rotation does not re-read the file.
 *
 * Scoped to its NavBackStackEntry, so two tracks in the same back stack never share state.
 */
class TrackViewModel(private val repository: TrackRepository) : ViewModel() {

    private val _state = MutableStateFlow<TrackUiState>(TrackUiState.Loading)
    val state: StateFlow<TrackUiState> = _state.asStateFlow()

    private var requested: TrackRef? = null

    fun load(ref: TrackRef) {
        // Called from a LaunchedEffect on every entry to the screen; only the first for a
        // given ref should do any work.
        if (requested == ref && _state.value !is TrackUiState.Failed) return
        requested = ref
        start()
    }

    fun retry() {
        if (requested != null) start()
    }

    private fun start() {
        val ref = requested ?: return
        _state.value = TrackUiState.Loading
        viewModelScope.launch {
            val result = when (ref) {
                is TrackRef.Saved -> repository.open(ref.id)
                is TrackRef.Transient -> repository.openTransient(ref.uri)
            }
            _state.value = result.fold(
                onSuccess = TrackUiState::Ready,
                onFailure = { TrackUiState.Failed(it.toMessageRes()) },
            )
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer { TrackViewModel(appContainer.trackRepository) }
        }

        @StringRes
        private fun Throwable.toMessageRes(): Int = when (this) {
            is TrackLoadException.Invalid -> R.string.track_error_invalid
            is TrackLoadException.Empty -> R.string.track_error_empty
            else -> R.string.track_error_unreadable
        }
    }
}
