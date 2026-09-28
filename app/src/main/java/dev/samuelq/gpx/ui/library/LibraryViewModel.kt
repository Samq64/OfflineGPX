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
import dev.samuelq.gpx.data.track.TrackRepository
import dev.samuelq.gpx.di.appContainer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface LibraryEvent {
    data class Open(val id: Long) : LibraryEvent
    data object ImportFailed : LibraryEvent
    /** The batch export to a folder failed outright - see [ExportedAll] for a partial one. */
    data object ExportFailed : LibraryEvent

    /** [written] of [requested] tracks reached the folder. */
    data class ExportedAll(val written: Int, val requested: Int) : LibraryEvent
    data object RenameFailed : LibraryEvent
}

/** `@Stable` so a row's captured lambdas can be memoised. */
@Stable
class LibraryViewModel(
    private val repository: TrackRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    /** What the user has typed into the search field. Blank means they have not. */
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /**
     * The rows to show, filtered by [query]. Null until the first read comes back, which
     * is not the same as empty - starting at `emptyList()` flashed "No tracks yet" during
     * the entry animation before the real list swapped in.
     */
    val tracks: StateFlow<List<TrackEntity>?> =
        combine(repository.tracks, _query) { tracks, query ->
            tracks.filter { it.matches(query) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun search(query: String) {
        _query.value = query
    }

    /** Ids ticked for a batch action. Empty means the list is in its normal mode. */
    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = _selection.asStateFlow()

    /**
     * One-shot events, not state - a Channel delivers each exactly once, so a rotation
     * can't re-trigger navigation or re-show an error.
     */
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

    fun import(uri: Uri) {
        viewModelScope.launch {
            repository.import(uri.toString()).fold(
                onSuccess = { _events.send(LibraryEvent.Open(it)) },
                onFailure = { _events.send(LibraryEvent.ImportFailed) },
            )
        }
    }

    /**
     * The filenames a pending batch will be written under, by row id. Saved state because
     * the folder picker can outlive this process, and the result would otherwise land on
     * an empty batch and silently write nothing.
     */
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
                // A count, not a boolean: a half-written folder is likelier than all-or-nothing.
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

    fun delete(ids: Collection<Long>) {
        viewModelScope.launch {
            repository.forgetAll(ids.toList())
            clearSelection()
        }
    }

    companion object {
        private const val EXPORT_IDS = "export_ids"
        private const val EXPORT_NAMES = "export_names"

        val Factory = viewModelFactory {
            initializer { LibraryViewModel(appContainer.trackRepository, createSavedStateHandle()) }
        }
    }
}

/**
 * Whether a row answers to what was typed. Checks both names, not just the one on screen -
 * an import keeps the filename it arrived under even after a rename.
 */
private fun TrackEntity.matches(query: String): Boolean {
    val needle = query.trim()
    if (needle.isEmpty()) return true
    return trackName?.contains(needle, ignoreCase = true) == true ||
        displayName.contains(needle, ignoreCase = true)
}
