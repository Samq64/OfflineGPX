package dev.samuelq.gpx.ui.map

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.samuelq.gpx.core.analysis.TrackAnalyzer
import dev.samuelq.gpx.core.analysis.TrackProfile
import dev.samuelq.gpx.core.model.TrackPoints
import dev.samuelq.gpx.data.db.TrackEntity
import dev.samuelq.gpx.data.map.MapStore
import dev.samuelq.gpx.data.map.OfflineMap
import dev.samuelq.gpx.data.settings.SettingsRepository
import dev.samuelq.gpx.data.record.RecordingController
import dev.samuelq.gpx.data.track.LoadedTrack
import dev.samuelq.gpx.data.track.TrackRepository
import dev.samuelq.gpx.di.appContainer
import dev.samuelq.gpx.ui.library.sortedFor
import dev.samuelq.gpx.ui.track.FocusedTrack
import dev.samuelq.gpx.ui.track.TrackRef
import dev.samuelq.gpx.ui.track.toTrackMessageRes
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One-shot messages; the screen resolves the words. */
sealed interface MapMessage {
    data object RenameFailed : MapMessage
    data object ImportFailed : MapMessage

    /** Undone with [MapViewModel.show]. */
    class Hidden(val id: Long) : MapMessage
}

/** Visible tracks, with their geometry once it has been read off disk. */
data class MapUiState(
    /** In the list's order, reversed so its top is drawn last, on top. */
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
    controller: RecordingController,
    mapStore: MapStore,
    settings: SettingsRepository,
) : ViewModel() {

    val basemaps: StateFlow<List<OfflineMap>> = mapStore.maps

    private val _state = MutableStateFlow(MapUiState())
    val state: StateFlow<MapUiState> = _state.asStateFlow()

    val trace: StateFlow<TrackPoints> = controller.trace

    private val liveChartsShown = MutableStateFlow(false)

    fun showLiveCharts(shown: Boolean) {
        liveChartsShown.value = shown
    }

    /**
     * The recording analysed like a saved track, redone only while its charts are shown.
     * Kept, stale, while they aren't, so the collapsed sheet still has them to open onto.
     * Null until it has moved: standing still has no distance axis to chart along.
     */
    val live: StateFlow<TrackProfile?> = combine(controller.trace, liveChartsShown, ::Pair)
        .runningFold(null as TrackProfile?) { last, (trace, shown) ->
            when {
                trace.size < 2 -> null
                shown || last == null ->
                    TrackAnalyzer.analyze(trace).takeIf { it.stats.distanceMeters > 0.0 }
                else -> last
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _focused = MutableStateFlow<FocusedTrack>(FocusedTrack.None)
    val focused: StateFlow<FocusedTrack> = _focused.asStateFlow()

    /** A channel, not state, so a rotation can't re-announce something. */
    private val _messages = Channel<MapMessage>(Channel.BUFFERED)
    val messages: Flow<MapMessage> = _messages.receiveAsFlow()


    private var requested: TrackRef? = null
    private var focusJob: Job? = null

    /** Outlives the map's composition, which doesn't survive navigating away. */
    var lastCamera: CameraSnapshot? = null
        private set

    fun rememberCamera(camera: CameraSnapshot) {
        lastCamera = camera
    }

    init {
        // `update` at every writer: a non-atomic read-modify-write lost renames during geometry loads.
        viewModelScope.launch {
            combine(repository.tracks, settings.trackSort) { all, sort ->
                all.filter(TrackEntity::visible).sortedFor(sort).asReversed()
            }.collect { entities ->
                _state.update { it.copy(entities = entities, loading = true) }
                loadMissing(entities)
            }
        }
        viewModelScope.launch {
            repository.tracks.collect { all ->
                val before = _state.value.all
                _state.update { it.copy(all = all) }
                // Close the sheet if its track was deleted, or just hidden, elsewhere. A track opened
                // while already hidden is meant to be shown.
                val focusedId = (requested as? TrackRef.Saved)?.id ?: return@collect
                val row = all.firstOrNull { it.id == focusedId }
                val wasVisible = before.firstOrNull { it.id == focusedId }?.visible == true
                if (row == null || (wasVisible && !row.visible)) focus(null)
            }
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
            repository.import(uri).fold(
                onSuccess = { focus(TrackRef.Saved(it)) },
                onFailure = { _messages.trySend(MapMessage.ImportFailed) },
            )
        }
    }

    /** The sheet reads the name off the row, so the row's update is all it needs. */
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
            val id = when (ref) {
                is TrackRef.Saved -> ref.id
                // Into the library, then opened like any saved track; a retry imports again.
                is TrackRef.Shared -> repository.import(ref.uri, reuseIdentical = true).getOrElse {
                    _focused.value = FocusedTrack.Failed(it.toTrackMessageRes())
                    return@launch
                }.also { requested = TrackRef.Saved(it) }
            }

            // A visible track is already parsed.
            val cached = _state.value.geometry[id]
            if (cached != null) {
                _focused.value = FocusedTrack.Ready(cached)
                repository.touch(id)
                return@launch
            }

            _focused.value = repository.open(id).fold(
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
            _messages.trySend(MapMessage.Hidden(id))
        }
    }

    fun show(id: Long) {
        viewModelScope.launch { repository.setVisible(id, true) }
    }

    /** Undoable until [commitDelete]; see [TrackRepository.deleteLater]. */
    fun delete(id: Long) = repository.deleteLater(listOf(id))

    fun undoDelete(id: Long) = repository.undoDelete(listOf(id))

    fun commitDelete(id: Long) = repository.commitDelete(listOf(id))

    companion object {
        val Factory = viewModelFactory {
            initializer {
                MapViewModel(
                    repository = appContainer.trackRepository,
                    controller = appContainer.recordingController,
                    mapStore = appContainer.mapStore,
                    settings = appContainer.settingsRepository,
                )
            }
        }
    }
}
