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
import kotlinx.coroutines.launch

/** Something the map should say, once. The words are the screen's business. */
enum class MapMessage { Exported, ExportFailed, RenameFailed, Hidden, ImportFailed }

/** Visible tracks, with their geometry once it has been read off disk. */
data class MapUiState(
    val entities: List<TrackEntity> = emptyList(),
    val geometry: Map<Long, LoadedTrack> = emptyMap(),
    val loading: Boolean = false,
    /**
     * Every row, visible or not. Carried because the sheet manages tracks now and the
     * questions it has to answer - is this a recording whose file goes with it, what
     * filename should the export offer - are about the row, not the geometry.
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
     * The basemaps to draw under the routes, empty when none is shown.
     *
     * Empty is the shipped state and stays a perfectly good screen: the routes were drawn
     * on a plain background before there was a basemap at all, and an app that refuses to
     * show a ride until someone has sideloaded a 90 MB file would be a worse app.
     */
    val basemaps: StateFlow<List<OfflineMap>> = mapStore.active
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    /**
     * Things that happened and are worth a word, named rather than worded.
     *
     * Not a string and not a resource id: the screen resolves its own text at composition,
     * where a change of locale re-resolves it, and this side of the line has no business
     * knowing the sentence. A channel rather than state, so a rotation cannot re-announce
     * an export that already happened.
     */
    private val _messages = Channel<MapMessage>(Channel.BUFFERED)
    val messages: Flow<MapMessage> = _messages.receiveAsFlow()

    private var requested: TrackRef? = null
    private var focusJob: Job? = null

    /** The track a pending export will write, held across the document-picker round trip. */
    private var exporting: Long? = null

    init {
        viewModelScope.launch {
            repository.visibleTracks.collect { entities ->
                _state.value = _state.value.copy(entities = entities, loading = true)
                loadMissing(entities)
            }
        }
        viewModelScope.launch {
            repository.tracks.collect { all ->
                _state.value = _state.value.copy(all = all)
                // The sheet has no other way to find out that the track it is showing, or
                // still loading, was deleted from somewhere that is not this screen - the
                // library, a batch delete, another device entirely. Without this it stays
                // open over a row that no longer exists, a stale readout of a track that is
                // already gone.
                val focusedId = (requested as? TrackRef.Saved)?.id
                if (focusedId != null && all.none { it.id == focusedId }) {
                    focus(null)
                }
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

    /**
     * Imports a `.gpx` file straight from the empty state, and opens what it brought in.
     *
     * The same [TrackRepository.import] the track list uses - there is only one way into
     * the library - but focused here instead of navigated to, since a first-run map has
     * nowhere else worth sending the user to look at it.
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
     * Renames a track and puts the new name straight onto the sheet showing it.
     *
     * Both caches have to be told, not just the sheet: the parsed geometry is what a later
     * tap on the same line reopens from, so leaving the old name in it means the rename
     * appears to stick and then quietly undoes itself the next time the track is opened.
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
                _state.value.geometry[id]?.let { cached ->
                    _state.value = _state.value.copy(
                        geometry = _state.value.geometry + (id to cached.renamed(trimmed)),
                    )
                }
            }
        }
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
     *
     * The missing ones are read at the same time rather than one after another. Each is a
     * file read, an XML parse and an analysis of tens of thousands of points, and they have
     * nothing to say to each other - waiting for the first before starting the second only
     * made the first map of the day take as long as the sum of every track on it.
     */
    private suspend fun loadMissing(entities: List<TrackEntity>) {
        val wanted = entities.map { it.id }.toSet()
        val kept = _state.value.geometry.filterKeys { it in wanted }
        val missing = wanted - kept.keys

        val loaded = coroutineScope {
            missing.map { id -> async { repository.geometry(id).getOrNull() } }.awaitAll()
        }

        val geometry = kept + missing.zip(loaded).mapNotNull { (id, track) ->
            track?.let { id to it }
        }
        _state.value = _state.value.copy(geometry = geometry, loading = false)
    }

    /** Remembers what a document picker is about to be opened for. */
    fun beginExport(id: Long) {
        exporting = id
    }

    fun finishExport(destination: String?) {
        val id = exporting ?: return
        exporting = null
        if (destination == null) return
        viewModelScope.launch {
            repository.export(id, destination).fold(
                onSuccess = { _messages.trySend(MapMessage.Exported) },
                onFailure = { _messages.trySend(MapMessage.ExportFailed) },
            )
        }
    }

    /**
     * Takes a track off the map. The sheet goes with it, which the caller does by dropping
     * the focus - a sheet of numbers about a line that is no longer drawn is the state this
     * is avoiding, not one to leave behind.
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
     * The same track under a new name. The geometry is untouched and deliberately shared -
     * a rename does not move a single point, and reprojecting a long ride to relabel it
     * would be the most expensive way to change a string.
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
