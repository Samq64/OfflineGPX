package dev.samuelq.gpx.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.samuelq.gpx.data.db.TrackEntity
import dev.samuelq.gpx.data.record.RecordingController
import dev.samuelq.gpx.data.record.RecordingState
import dev.samuelq.gpx.data.track.LoadedTrack
import dev.samuelq.gpx.data.track.TrackRepository
import dev.samuelq.gpx.di.appContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Visible tracks, with their geometry once it has been read off disk. */
data class MapUiState(
    val entities: List<TrackEntity> = emptyList(),
    val geometry: Map<Long, LoadedTrack> = emptyMap(),
    val loading: Boolean = false,
    /** Distinguishes "no tracks at all" from "all of them are hidden". */
    val totalCount: Int = 0,
)

class MapViewModel(
    private val repository: TrackRepository,
    controller: RecordingController,
) : ViewModel() {

    private val _state = MutableStateFlow(MapUiState())
    val state: StateFlow<MapUiState> = _state.asStateFlow()

    val recording: StateFlow<RecordingState> = controller.state

    init {
        viewModelScope.launch {
            repository.visibleTracks.collect { entities ->
                _state.value = _state.value.copy(entities = entities, loading = true)
                loadMissing(entities)
            }
        }
        viewModelScope.launch {
            repository.tracks.collect { all ->
                _state.value = _state.value.copy(totalCount = all.size)
            }
        }
    }

    /**
     * Parses only what is newly visible and drops what no longer is.
     *
     * Hiding and re-showing a track is a toggle in a list, so it has to be cheap; without
     * this cache each toggle would reparse every visible track's points.
     */
    private suspend fun loadMissing(entities: List<TrackEntity>) {
        val wanted = entities.map { it.id }.toSet()
        var geometry = _state.value.geometry.filterKeys { it in wanted }

        for (id in wanted - geometry.keys) {
            repository.geometry(id).onSuccess { geometry = geometry + (id to it) }
        }
        _state.value = _state.value.copy(geometry = geometry, loading = false)
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                MapViewModel(appContainer.trackRepository, appContainer.recordingController)
            }
        }
    }
}
