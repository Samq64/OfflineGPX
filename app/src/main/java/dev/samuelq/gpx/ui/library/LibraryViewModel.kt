package dev.samuelq.gpx.ui.library

import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.samuelq.gpx.data.db.TrackEntity
import dev.samuelq.gpx.data.settings.SettingsRepository
import dev.samuelq.gpx.data.settings.TrackSort
import dev.samuelq.gpx.data.track.TrackRepository
import dev.samuelq.gpx.di.appContainer
import dev.samuelq.gpx.ui.track.trackTitle
import java.text.Collator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface LibraryEvent {
    data class Open(val id: Long) : LibraryEvent
    data object ImportFailed : LibraryEvent
    /** A multi-file import; a single file opens instead. */
    data class ImportedAll(val imported: Int, val requested: Int) : LibraryEvent
    /** Failed outright; see [ExportedAll] for a partial one. */
    data object ExportFailed : LibraryEvent

    data class ExportedAll(val written: Int, val requested: Int) : LibraryEvent
    data object RenameFailed : LibraryEvent
}

/** `@Stable` so a row's captured lambdas can be memoised. */
@OptIn(ExperimentalCoroutinesApi::class)
@Stable
class LibraryViewModel(
    private val repository: TrackRepository,
    private val settings: SettingsRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val sort: StateFlow<TrackSort> = settings.trackSort

    /** Read apart from the rows, so the list shows before every file is statted. */
    val sizes: StateFlow<Map<Long, Long>> = repository.tracks
        .mapLatest { repository.fileSizes(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Null until loaded, so the empty state doesn't flash during the entry animation. */
    val tracks: StateFlow<List<TrackEntity>?> =
        combine(repository.tracks, _query, sort) { tracks, query, sort ->
            tracks.filter { it.matches(query) }.sortedFor(sort)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setSort(sort: TrackSort) = settings.setTrackSort(sort)

    fun search(query: String) {
        _query.value = query
    }

    /** Empty means not in selection mode. */
    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = _selection.asStateFlow()

    // A Channel delivers each once, so a rotation can't replay one.
    private val _events = Channel<LibraryEvent>(Channel.BUFFERED)
    val events: Flow<LibraryEvent> = _events.receiveAsFlow()

    fun toggleSelected(id: Long) {
        _selection.value = _selection.value.let { if (id in it) it - id else it + id }
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    fun selectAll(ids: List<Long>) {
        _selection.value = ids.toSet()
    }

    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            if (uris.size == 1) {
                repository.import(uris.single().toString()).fold(
                    onSuccess = { _events.send(LibraryEvent.Open(it)) },
                    onFailure = { _events.send(LibraryEvent.ImportFailed) },
                )
                return@launch
            }
            val imported = uris.count { repository.import(it.toString()).isSuccess }
            _events.send(LibraryEvent.ImportedAll(imported, uris.size))
        }
    }

    /** Saved state, since the folder picker can outlive this process. */
    fun beginExportAll(names: Map<Long, String>) {
        savedState[EXPORT_IDS] = names.keys.toLongArray()
        savedState[EXPORT_NAMES] = ArrayList(names.values)
    }

    fun finishExportAll(folder: Uri?) {
        val ids = savedState.remove<LongArray>(EXPORT_IDS) ?: LongArray(0)
        val names = ids.zip(savedState.remove<ArrayList<String>>(EXPORT_NAMES).orEmpty()).toMap()
        if (folder == null || names.isEmpty()) return
        viewModelScope.launch {
            repository.exportAll(names, folder.toString()).fold(
                onSuccess = { _events.send(LibraryEvent.ExportedAll(it, names.size)) },
                onFailure = { _events.send(LibraryEvent.ExportFailed) },
            )
            clearSelection()
        }
    }

    fun rename(id: Long, name: String) {
        viewModelScope.launch {
            repository.rename(id, name).onFailure { _events.send(LibraryEvent.RenameFailed) }
        }
    }

    fun setVisible(id: Long, visible: Boolean) {
        viewModelScope.launch { repository.setVisible(id, visible) }
    }

    fun setAllVisible(visible: Boolean) {
        viewModelScope.launch { repository.setAllVisible(visible) }
    }

    /** Undoable until [commitDelete]; see [TrackRepository.deleteLater]. */
    fun delete(ids: Collection<Long>) {
        repository.deleteLater(ids)
        clearSelection()
    }

    fun undoDelete(ids: Collection<Long>) = repository.undoDelete(ids)

    fun commitDelete(ids: Collection<Long>) = repository.commitDelete(ids)

    companion object {
        private const val EXPORT_IDS = "export_ids"
        private const val EXPORT_NAMES = "export_names"

        val Factory = viewModelFactory {
            initializer { LibraryViewModel(
                    appContainer.trackRepository,
                    appContainer.settingsRepository,
                    createSavedStateHandle(),
                ) }
        }
    }
}

/** Checks both names: a renamed import still answers to its filename. */
private fun TrackEntity.matches(query: String): Boolean {
    val needle = query.trim()
    if (needle.isEmpty()) return true
    return trackName?.contains(needle, ignoreCase = true) == true ||
        displayName.contains(needle, ignoreCase = true)
}

/** Stable, so ties keep the repository's last-viewed order. */
internal fun List<TrackEntity>.sortedFor(sort: TrackSort): List<TrackEntity> =
    when (sort) {
        TrackSort.RECENT -> this
        TrackSort.DATE -> sortedByDescending { it.startedAtEpochMillis ?: it.lastOpenedAtEpochMillis }
        TrackSort.LENGTH -> sortedByDescending { it.distanceMeters }
        TrackSort.NAME -> {
            val collator = Collator.getInstance()
            sortedWith(compareBy(collator) { trackTitle(it.trackName, it.displayName) })
        }
    }
