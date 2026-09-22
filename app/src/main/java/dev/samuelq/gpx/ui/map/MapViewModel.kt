package dev.samuelq.gpx.ui.map

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.samuelq.gpx.data.db.TrackEntity
import dev.samuelq.gpx.data.map.MapStore
import dev.samuelq.gpx.data.map.OfflineMap
import dev.samuelq.gpx.data.record.LiveTrace
import dev.samuelq.gpx.data.record.RecordingController
import dev.samuelq.gpx.data.track.LoadedTrack
import dev.samuelq.gpx.data.track.TrackRepository
import dev.samuelq.gpx.di.appContainer
import dev.samuelq.gpx.ui.track.FocusedTrack
import dev.samuelq.gpx.ui.track.TrackRef
import dev.samuelq.gpx.ui.track.toTrackMessageRes
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Something the map should say, once. The words are the screen's business. */
enum class MapMessage { RenameFailed, Hidden, ImportFailed }

/** Visible tracks, with their geometry once it has been read off disk. */
data class MapUiState(
    val entities: List<TrackEntity> = emptyList(),
    val geometry: Map<Long, LoadedTrack> = emptyMap(),
    val loading: Boolean = false,
    /**
     * Every row, visible or not - the sheet's rename/export/delete questions are about
     * the row, not the geometry.
     */
    val all: List<TrackEntity> = emptyList(),
) {
    /** Distinguishes "no tracks at all" from "all of them are hidden". */
    val totalCount: Int get() = all.size

    fun entity(id: Long): TrackEntity? = all.firstOrNull { it.id == id }
}

class MapViewModel(
    private val repository: TrackRepository,
    controller: RecordingController,
    mapStore: MapStore,
) : ViewModel() {

    /**
     * The basemaps to draw under the routes, empty when none is shown - a perfectly good
     * screen, since routes drew on a plain background before basemaps existed.
     */
    val basemaps: StateFlow<List<OfflineMap>> = mapStore.active
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _state = MutableStateFlow(MapUiState())
    val state: StateFlow<MapUiState> = _state.asStateFlow()

    /**
     * The in-progress recording's geometry, drawn alongside saved tracks - its numbers
     * belong to [dev.samuelq.gpx.ui.record.RecordViewModel], its shape to the map.
     */
    val trace: StateFlow<LiveTrace> = controller.trace

    private val _focused = MutableStateFlow<FocusedTrack>(FocusedTrack.None)
    val focused: StateFlow<FocusedTrack> = _focused.asStateFlow()

    /**
     * Things that happened and are worth a word, named rather than worded - the screen
     * resolves its own text at composition, so a locale change re-resolves it. A channel,
     * not state, so a rotation can't re-announce an export that already happened.
     */
    private val _messages = Channel<MapMessage>(Channel.BUFFERED)
    val messages: Flow<MapMessage> = _messages.receiveAsFlow()

    private var requested: TrackRef? = null
    private var focusJob: Job? = null

    /**
     * Where the camera was, last time this screen was on top of the stack. The map's own
     * composition doesn't survive navigating away and back - a plain var here does, since
     * this ViewModel is scoped to the route's back-stack entry, not the composition.
     */
    var lastCamera: CameraSnapshot? = null
        private set

    fun rememberCamera(camera: CameraSnapshot) {
        lastCamera = camera
    }

    init {
        // `update`, not `value = value.copy(...)`, at all four writers of this state: a
        // read-modify-write that isn't atomic drops one of two concurrent edits. Renaming
        // a track while its geometry loaded was enough to lose the new name.
        viewModelScope.launch {
            repository.visibleTracks.collect { entities ->
                _state.update { it.copy(entities = entities, loading = true) }
                loadMissing(entities)
            }
        }
        viewModelScope.launch {
            repository.tracks.collect { all ->
                _state.update { it.copy(all = all) }
                // The sheet has no other way to learn its track was deleted elsewhere (the
                // library, a batch delete) - without this it stays open over a gone row.
                val focusedId = (requested as? TrackRef.Saved)?.id
                if (focusedId != null && all.none { it.id == focusedId }) {
                    focus(null)
                }
            }
        }
    }

    /**
     * Shows a track's detail, or clears it with null. Idempotent for the track already
     * showing - a repeated tap on the same route shouldn't reload and blank the sheet.
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

    /**
     * Imports a `.gpx` file straight from the empty state and opens what it brought in -
     * the same [TrackRepository.import] the track list uses, but focused rather than
     * navigated to.
     */
    fun importTrack(uri: Uri) {
        viewModelScope.launch {
            repository.import(uri.toString()).fold(
                onSuccess = { focus(TrackRef.Saved(it)) },
                onFailure = { _messages.trySend(MapMessage.ImportFailed) },
            )
        }
    }

    /**
     * Renames a track and puts the new name on the sheet. Both caches are updated, not
     * just the sheet - stale geometry would make the rename silently undo itself next open.
     */
    fun rename(id: Long, name: String) {
        viewModelScope.launch {
            val trimmed = name.trim().takeIf(String::isNotEmpty)
            repository.rename(id, name).onFailure {
                _messages.trySend(MapMessage.RenameFailed)
            }.onSuccess {
                val focused = _focused.value
                if (focused is FocusedTrack.Ready && focused.track.id == id) {
                    _focused.value = FocusedTrack.Ready(focused.track.renamed(trimmed))
                }
                _state.update { current ->
                    val cached = current.geometry[id] ?: return@update current
                    current.copy(geometry = current.geometry + (id to cached.renamed(trimmed)))
                }
            }
        }
    }

    private fun reload() {
        val ref = requested ?: return
        focusJob?.cancel()
        _focused.value = FocusedTrack.Loading
        focusJob = viewModelScope.launch {
            // Already parsed and in hand for a visible track - the repository would just
            // re-read the file to reproduce what's on screen.
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
     * Parses only what is newly visible and drops what no longer is - a hide/show toggle
     * has to be cheap. The missing ones are read concurrently, since a file read plus
     * parse plus analysis of each has nothing to say to any of the others.
     */
    private suspend fun loadMissing(entities: List<TrackEntity>) {
        val wanted = entities.map { it.id }.toSet()
        val missing = wanted - _state.value.geometry.keys

        val loaded = coroutineScope {
            missing.map { id -> async { repository.geometry(id).getOrNull() } }.awaitAll()
        }
        val read = missing.zip(loaded).mapNotNull { (id, track) -> track?.let { id to it } }

        // Merged against what the state holds *now*, not the snapshot from before the
        // reads above suspended - a rename landing in between is only in the former.
        _state.update { current ->
            current.copy(
                geometry = current.geometry.filterKeys { it in wanted } + read,
                loading = false,
            )
        }
    }

    /**
     * Takes a track off the map. The caller drops focus too, so the sheet doesn't linger
     * over a line that's no longer drawn.
     */
    fun hide(id: Long) {
        viewModelScope.launch {
            repository.setVisible(listOf(id), false)
            _messages.trySend(MapMessage.Hidden)
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repository.forgetAll(listOf(id)) }
    }

    /**
     * The same track under a new name - geometry is untouched and shared, since a rename
     * doesn't move a single point.
     */
    private fun LoadedTrack.renamed(name: String?) = LoadedTrack(
        id = id,
        displayName = displayName,
        track = track.copy(name = name),
        profile = profile,
        colorIndex = colorIndex,
    )

    companion object {
        val Factory = viewModelFactory {
            initializer {
                MapViewModel(
                    repository = appContainer.trackRepository,
                    controller = appContainer.recordingController,
                    mapStore = appContainer.mapStore,
                )
            }
        }
    }
}
