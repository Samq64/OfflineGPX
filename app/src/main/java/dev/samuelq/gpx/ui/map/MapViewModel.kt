package dev.samuelq.gpx.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.samuelq.gpx.data.db.TrackEntity
import dev.samuelq.gpx.data.record.LiveTrace
import dev.samuelq.gpx.data.record.RecordingController
import dev.samuelq.gpx.data.track.LoadedTrack
import dev.samuelq.gpx.data.track.TrackRepository
import dev.samuelq.gpx.di.appContainer
import dev.samuelq.gpx.ui.track.FocusedTrack
import dev.samuelq.gpx.ui.track.TrackRef
import dev.samuelq.gpx.ui.track.toTrackMessageRes
import kotlinx.coroutines.Job
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

    /**
     * The in-progress recording's geometry, drawn alongside the saved tracks. The
     * recorder's *numbers* are not this screen's business - [dev.samuelq.gpx.ui.record.RecordViewModel]
     * carries those - but its shape is, because it shares the map's projection.
     */
    val trace: StateFlow<LiveTrace> = controller.trace

    private val _focused = MutableStateFlow<FocusedTrack>(FocusedTrack.None)
    val focused: StateFlow<FocusedTrack> = _focused.asStateFlow()

    private var requested: TrackRef? = null
    private var focusJob: Job? = null

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
     * Shows a track's detail, or clears it with null.
     *
     * Idempotent for the track already showing: this is called from a tap on the route,
     * which can arrive again for the same line, and reloading would blank the sheet the
     * reader is looking at.
     */
    fun focus(ref: TrackRef?) {
        if (ref == null) {
            requested = null
            focusJob?.cancel()
            _focused.value = FocusedTrack.None
            return
        }
        if (ref == requested && _focused.value !is FocusedTrack.Failed) return
        requested = ref
        reload()
    }

    fun retryFocus() {
        if (requested != null) reload()
    }

    private fun reload() {
        val ref = requested ?: return
        focusJob?.cancel()
        _focused.value = FocusedTrack.Loading
        focusJob = viewModelScope.launch {
            // A visible track's geometry is already parsed and in hand; going back to the
            // repository for it would re-read the file to produce what is on screen.
            val cached = (ref as? TrackRef.Saved)?.let { _state.value.geometry[it.id] }
            if (cached != null) {
                _focused.value = FocusedTrack.Ready(cached)
                repository.touch(cached.id)
                return@launch
            }

            val result = when (ref) {
                is TrackRef.Saved -> repository.open(ref.id)
                is TrackRef.Transient -> repository.openTransient(ref.uri)
            }
            _focused.value = result.fold(
                onSuccess = FocusedTrack::Ready,
                onFailure = { FocusedTrack.Failed(it.toTrackMessageRes()) },
            )
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
