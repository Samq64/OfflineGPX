package dev.samuelq.gpx.ui.map

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.samuelq.gpx.R
import dev.samuelq.gpx.core.analysis.TrackAnalyzer
import dev.samuelq.gpx.core.analysis.TrackProfile
import dev.samuelq.gpx.core.model.TrackPoints
import dev.samuelq.gpx.core.model.Waypoint
import dev.samuelq.gpx.data.db.TrackEntity
import dev.samuelq.gpx.data.map.MapImportResult
import dev.samuelq.gpx.data.map.MapStore
import dev.samuelq.gpx.data.map.OfflineMap
import dev.samuelq.gpx.data.record.RecordingController
import dev.samuelq.gpx.data.settings.SettingsRepository
import dev.samuelq.gpx.data.track.LoadedTrack
import dev.samuelq.gpx.data.track.ReceivedTrack
import dev.samuelq.gpx.data.track.TrackEdit
import dev.samuelq.gpx.data.track.TrackLoadException
import dev.samuelq.gpx.data.track.TrackRepository
import dev.samuelq.gpx.data.track.title
import dev.samuelq.gpx.di.appContainer
import dev.samuelq.gpx.ui.library.sortedFor
import dev.samuelq.gpx.ui.settings.message
import dev.samuelq.gpx.ui.track.FocusedTrack
import dev.samuelq.gpx.ui.track.TrackRef
import dev.samuelq.gpx.ui.track.toTrackMessageRes
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
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
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** One-shot messages; the screen resolves the words. */
internal sealed interface MapMessage {
    /** Words alone, with nothing to act on. */
    class Say(@param:StringRes val text: Int) : MapMessage

    /** Undone with [MapViewModel.show]. [name] is the track's, for the message. */
    class Hidden(val id: Long, val name: String) : MapMessage

    /**
     * A change to [name]'s file, as [text] says with the name; undone with [MapViewModel.undoEdit],
     * else [MapViewModel.commitEdit].
     */
    class Edited(val edit: TrackEdit, val name: String, @param:StringRes val text: Int) : MapMessage

    /**
     * [total] files imported, from another app or picked, of which these were added or already
     * saved; undone with [MapViewModel.undoImport]. [failure] says why, when a lone file wasn't.
     */
    class Imported(
        val tracks: List<ReceivedTrack>,
        val maps: List<OfflineMap>,
        val total: Int,
        @param:StringRes val failure: Int?,
    ) : MapMessage
}

/** Visible tracks, with their geometry once it has been read off disk. */
internal data class MapUiState(
    /** In the list's order, reversed so its top is drawn last, on top. */
    val entities: List<TrackEntity> = emptyList(),
    /** Just what's drawn, cut where analysis breaks it; a focused track's profile is read when focused. */
    val geometry: Map<Long, TrackPoints> = emptyMap(),
    // Starts true: a read is already in flight, and the map frames once on the first
    // not-loading state, so starting false latched the camera on the basemap.
    val loading: Boolean = true,
    /** Every row, visible or not; the sheet's actions are about the row. */
    val all: List<TrackEntity> = emptyList(),
) {
    fun entity(id: Long): TrackEntity? = all.firstOrNull { it.id == id }
}

class MapViewModel(
    private val repository: TrackRepository,
    controller: RecordingController,
    private val mapStore: MapStore,
    settings: SettingsRepository,
    /** For analysing the recording; a parameter so tests can substitute one. */
    analysis: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    val basemaps: StateFlow<List<OfflineMap>> = mapStore.maps

    private val _state = MutableStateFlow(MapUiState())
    internal val state: StateFlow<MapUiState> = _state.asStateFlow()

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
        .flowOn(analysis)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _focused = MutableStateFlow<FocusedTrack>(FocusedTrack.None)
    val focused: StateFlow<FocusedTrack> = _focused.asStateFlow()

    /** A channel, not state, so a rotation can't re-announce something. */
    private val _messages = Channel<MapMessage>(Channel.BUFFERED)
    internal val messages: Flow<MapMessage> = _messages.receiveAsFlow()

    private var requested: TrackRef? = null
    private var focusJob: Job? = null

    /**
     * The screen's state that outlives leaving it, as the map's composition doesn't survive
     * navigating away; see [rememberMapScreenState].
     */
    val screenKept = MapScreenState.Kept()

    /** For the rename dialog to suggest. */
    val categories: StateFlow<List<String>> = repository.categories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var lastCamera: CameraSnapshot? = null

    init {
        // `update` at every writer: a non-atomic read-modify-write lost renames during geometry loads.
        viewModelScope.launch {
            combine(repository.tracks, settings.trackOrder) { all, order ->
                all.filter(TrackEntity::visible).sortedFor(order).asReversed()
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
        if (ref == requested) return
        requested = ref
        reload()
    }

    /** Imports a `.gpx` file from the empty state, then opens and announces it. */
    fun importTrack(uri: Uri) {
        viewModelScope.launch {
            repository.import(uri).fold(
                onSuccess = ::announceImport,
                onFailure = { _messages.trySend(MapMessage.Say(R.string.library_import_failed)) },
            )
        }
    }

    /** Adds files from other apps, each a map by its first bytes or else a track, in turn. */
    fun receive(uris: List<Uri>) {
        viewModelScope.launch {
            val tracks = mutableListOf<ReceivedTrack>()
            val maps = mutableListOf<OfflineMap>()
            var failure: Int? = null
            for (uri in uris) {
                if (mapStore.holdsMap(uri)) {
                    when (val result = mapStore.import(uri)) {
                        is MapImportResult.Imported -> maps += result.map
                        is MapImportResult.Failed -> failure = result.error.message.text
                    }
                } else {
                    repository.receive(uri).fold(
                        onSuccess = { tracks += it },
                        onFailure = {
                            failure = if (it is TrackLoadException.Invalid) {
                                R.string.incoming_unsupported
                            } else {
                                it.toTrackMessageRes()
                            }
                        },
                    )
                }
            }
            _messages.trySend(MapMessage.Imported(tracks, maps, uris.size, failure))
        }
    }

    /** Opens a track just picked and imported, and says so as for a shared one. */
    fun announceImport(id: Long) {
        _messages.trySend(MapMessage.Imported(listOf(ReceivedTrack(id, isNew = true)), emptyList(), 1, null))
    }

    /** Removes what [imported] added; tracks already saved before stay. */
    internal fun undoImport(imported: MapMessage.Imported) {
        val added = imported.tracks.filter { it.isNew }.map { it.id }
        repository.deleteLater(added)
        repository.commitDelete(added)
        imported.maps.forEach {
            mapStore.deleteLater(it)
            mapStore.commitDelete(it)
        }
    }

    /** Null leaves either as it is. The sheet reads the name off the row, so the row's update is all it needs. */
    fun rename(id: Long, name: String?, category: String?) {
        viewModelScope.launch {
            repository.rename(id, name, category).onFailure {
                _messages.trySend(MapMessage.Say(R.string.library_rename_failed))
            }
        }
    }

    suspend fun fileToShare(track: TrackEntity): File = repository.fileToShare(track)

    fun setColor(id: Long, colorIndex: Int) {
        viewModelScope.launch { repository.setColor(id, colorIndex) }
    }

    private fun reload() {
        val ref = requested as? TrackRef.Saved ?: return
        focusJob?.cancel()
        _focused.value = FocusedTrack.Loading
        focusJob = viewModelScope.launch {
            _focused.value = repository.open(ref.id).fold(
                onSuccess = FocusedTrack::Ready,
                onFailure = {
                    requested = null
                    _messages.trySend(MapMessage.Say(it.toTrackMessageRes()))
                    FocusedTrack.None
                },
            )
        }
    }

    /**
     * Parses only newly visible tracks, a few at a time, and drops hidden ones, so toggles are cheap.
     * Hundreds of hour-long tracks fit in a phone's heap when only their points are kept.
     */
    private suspend fun loadMissing(entities: List<TrackEntity>) {
        val wanted = entities.map { it.id }.toSet()
        val missing = wanted - _state.value.geometry.keys

        val loads = Semaphore(LOAD_PARALLELISM)
        val loaded = coroutineScope {
            missing.map { id ->
                async { loads.withPermit { repository.geometry(id).getOrNull()?.profile?.points } }
            }.awaitAll()
        }
        val read = missing.zip(loaded).mapNotNull { (id, points) -> points?.let { id to it } }

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
        val name = nameOf(id)
        viewModelScope.launch {
            repository.setVisible(id, false)
            _messages.trySend(MapMessage.Hidden(id, name))
        }
    }

    private fun nameOf(id: Long): String = _state.value.entity(id)?.title.orEmpty()

    fun show(id: Long) {
        viewModelScope.launch { repository.setVisible(id, true) }
    }

    /** Undoable until [commitDelete]; see [TrackRepository.deleteLater]. */
    fun delete(id: Long) = repository.deleteLater(listOf(id))

    fun undoDelete(id: Long) = repository.undoDelete(listOf(id))

    fun commitDelete(id: Long) = repository.commitDelete(listOf(id))

    /** Sets the note of [id]'s waypoint [index]; [onDone] gets the waypoint as rewritten. */
    fun renameWaypoint(id: Long, index: Int, name: String, onDone: (Waypoint) -> Unit) {
        viewModelScope.launch {
            repository.renameWaypoint(id, index, name).fold(
                onSuccess = { loaded ->
                    // In place, not reloaded: the sheet keeps its selection.
                    if ((requested as? TrackRef.Saved)?.id == id) {
                        focusJob?.cancel()
                        _focused.value = FocusedTrack.Ready(loaded)
                    }
                    loaded.track.waypoints.getOrNull(index)?.let(onDone)
                },
                onFailure = { _messages.trySend(MapMessage.Say(R.string.track_edit_failed)) },
            )
        }
    }

    /** Keeps points [range], inclusive. */
    fun trim(id: Long, range: IntRange) = edit(id, R.string.track_trimmed) {
        repository.trim(id, range.first, range.last)
    }

    fun deleteWaypoint(id: Long, index: Int) = edit(id, R.string.waypoint_deleted) {
        repository.deleteWaypoint(id, index)
    }

    private fun edit(id: Long, @StringRes text: Int, run: suspend () -> Result<TrackEdit>) {
        val name = nameOf(id)
        viewModelScope.launch {
            run().fold(
                onSuccess = { edit ->
                    refresh(edit.id)
                    _messages.trySend(MapMessage.Edited(edit, name, text))
                },
                onFailure = { _messages.trySend(MapMessage.Say(R.string.track_edit_failed)) },
            )
        }
    }

    fun undoEdit(edit: TrackEdit) {
        viewModelScope.launch {
            repository.undoEdit(edit).onFailure { _messages.trySend(MapMessage.Say(R.string.track_edit_failed)) }
            refresh(edit.id)
        }
    }

    fun commitEdit(edit: TrackEdit) = repository.commitEdit(edit)

    /** Rereads [id] after its file changed: the cached geometry, and the sheet if it shows it. */
    private suspend fun refresh(id: Long) {
        _state.update { it.copy(geometry = it.geometry - id) }
        loadMissing(_state.value.entities)
        if ((requested as? TrackRef.Saved)?.id == id) reload()
    }

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

/** Enough to keep the disk busy without every track's buffers alive at once. */
private const val LOAD_PARALLELISM = 4
