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
import dev.samuelq.gpx.data.record.AbandonedRecording
import dev.samuelq.gpx.data.record.RecordingController
import dev.samuelq.gpx.data.record.RecordingRecovery
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

/** One-shot messages; the screen resolves the words. */
enum class MapMessage { RenameFailed, Hidden, ImportFailed, RecoveryFailed }

/** Visible tracks, with their geometry once it has been read off disk. */
data class MapUiState(
    val entities: List<TrackEntity> = emptyList(),
    val geometry: Map<Long, LoadedTrack> = emptyMap(),
    // Starts true: a read is already in flight, and the map frames once on the first
    // not-loading state, so starting false latched the camera on the basemap.
    val loading: Boolean = true,
    /** Every row, visible or not; the sheet's actions are about the row. */
    val all: List<TrackEntity> = emptyList(),
) {
    /** Distinguishes "no tracks at all" from "all of them are hidden". */
    val totalCount: Int get() = all.size

    fun entity(id: Long): TrackEntity? = all.firstOrNull { it.id == id }
}

class MapViewModel(
    private val repository: TrackRepository,
    private val recovery: RecordingRecovery,
    controller: RecordingController,
    mapStore: MapStore,
) : ViewModel() {

    val basemaps: StateFlow<List<OfflineMap>> = mapStore.active
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _state = MutableStateFlow(MapUiState())
    val state: StateFlow<MapUiState> = _state.asStateFlow()

    val trace: StateFlow<LiveTrace> = controller.trace

    private val _focused = MutableStateFlow<FocusedTrack>(FocusedTrack.None)
    val focused: StateFlow<FocusedTrack> = _focused.asStateFlow()

    /** A channel, not state, so a rotation can't re-announce something. */
    private val _messages = Channel<MapMessage>(Channel.BUFFERED)
    val messages: Flow<MapMessage> = _messages.receiveAsFlow()

    /** The oldest recording a crash left unsaved, until it is saved or discarded. */
    private val _abandoned = MutableStateFlow<AbandonedRecording?>(null)
    val abandoned: StateFlow<AbandonedRecording?> = _abandoned.asStateFlow()
    private val skipped = mutableSetOf<java.io.File>()

    private var requested: TrackRef? = null
    private var focusJob: Job? = null

    /** Outlives the map's composition, which doesn't survive navigating away. */
    var lastCamera: CameraSnapshot? = null
        private set

    fun rememberCamera(camera: CameraSnapshot) {
        lastCamera = camera
    }

    init {
        viewModelScope.launch { nextAbandoned() }
        // `update` at every writer: a non-atomic read-modify-write lost renames during geometry loads.
        viewModelScope.launch {
            repository.visibleTracks.collect { entities ->
                _state.update { it.copy(entities = entities, loading = true) }
                loadMissing(entities)
            }
        }
        viewModelScope.launch {
            repository.tracks.collect { all ->
                val before = _state.value.all
                _state.update { it.copy(all = all) }
                // The row is the name's source of truth, including renames made from the library.
                resyncNames(all)
                // Close the sheet if its track was deleted, or just hidden, elsewhere. A track opened
                // while already hidden is meant to be shown.
                val focusedId = (requested as? TrackRef.Saved)?.id ?: return@collect
                val row = all.firstOrNull { it.id == focusedId }
                val wasVisible = before.firstOrNull { it.id == focusedId }?.visible == true
                if (row == null || (wasVisible && !row.visible)) focus(null)
            }
        }
    }

    /** Brings the geometry cache and the open sheet, if any, in line with the rows' names. */
    private fun resyncNames(entities: List<TrackEntity>) {
        _state.update { current ->
            var geometry = current.geometry
            for (entity in entities) {
                val cached = geometry[entity.id] ?: continue
                if (cached.track.name != entity.trackName) {
                    geometry = geometry + (entity.id to cached.renamed(entity.trackName))
                }
            }
            current.copy(geometry = geometry)
        }
        _focused.update { focused ->
            if (focused !is FocusedTrack.Ready) return@update focused
            val entity = entities.firstOrNull { it.id == focused.track.id } ?: return@update focused
            if (entity.trackName == focused.track.track.name) return@update focused
            FocusedTrack.Ready(focused.track.renamed(entity.trackName))
        }
    }

    /** Shows a track's detail, or clears it with null. A repeat for the same track doesn't reload. */
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

    /** Imports a `.gpx` file from the empty state and focuses it. */
    fun importTrack(uri: Uri) {
        viewModelScope.launch {
            repository.import(uri.toString()).fold(
                onSuccess = { focus(TrackRef.Saved(it)) },
                onFailure = { _messages.trySend(MapMessage.ImportFailed) },
            )
        }
    }

    /** Saves the abandoned recording on offer and opens it, as a clean stop would. */
    fun saveAbandoned(name: String) {
        val recording = _abandoned.value ?: return
        _abandoned.value = null
        viewModelScope.launch {
            recovery.save(recording, name).fold(
                onSuccess = { focus(TrackRef.Saved(it)) },
                // Still on disk: asked about again next launch.
                onFailure = {
                    skipped += recording.file
                    _messages.trySend(MapMessage.RecoveryFailed)
                },
            )
            nextAbandoned()
        }
    }

    fun discardAbandoned() {
        val recording = _abandoned.value ?: return
        _abandoned.value = null
        viewModelScope.launch {
            recovery.discard(recording)
            nextAbandoned()
        }
    }

    private suspend fun nextAbandoned() {
        // Skipping a failed save keeps the dialog from reopening on it.
        _abandoned.value = recovery.abandoned().firstOrNull { it.file !in skipped }
    }

    /** The row's update brings the new name to the sheet via [resyncNames]. */
    fun rename(id: Long, name: String) {
        viewModelScope.launch {
            repository.rename(id, name).onFailure { _messages.trySend(MapMessage.RenameFailed) }
        }
    }

    private fun reload() {
        val ref = requested ?: return
        focusJob?.cancel()
        _focused.value = FocusedTrack.Loading
        focusJob = viewModelScope.launch {
            // A visible track is already parsed.
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

    /** Parses only newly visible tracks, concurrently, and drops hidden ones, so toggles are cheap. */
    private suspend fun loadMissing(entities: List<TrackEntity>) {
        val wanted = entities.map { it.id }.toSet()
        val missing = wanted - _state.value.geometry.keys

        val loaded = coroutineScope {
            missing.map { id -> async { repository.geometry(id).getOrNull() } }.awaitAll()
        }
        val read = missing.zip(loaded).mapNotNull { (id, track) -> track?.let { id to it } }

        // Merged against the current state, not the pre-suspend one, so a concurrent rename survives.
        _state.update { current ->
            current.copy(
                geometry = current.geometry.filterKeys { it in wanted } + read,
                loading = false,
            )
        }
    }

    /** The caller drops focus too. */
    fun hide(id: Long) {
        viewModelScope.launch {
            repository.setVisible(id, false)
            _messages.trySend(MapMessage.Hidden)
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repository.forgetAll(listOf(id)) }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                MapViewModel(
                    repository = appContainer.trackRepository,
                    recovery = appContainer.recordingRecovery,
                    controller = appContainer.recordingController,
                    mapStore = appContainer.mapStore,
                )
            }
        }
    }
}
