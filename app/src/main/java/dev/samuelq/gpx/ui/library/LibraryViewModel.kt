package dev.samuelq.gpx.ui.library

import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
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
    data object ExportFailed : LibraryEvent
    data object Exported : LibraryEvent

    /** [written] of [requested] tracks reached the folder. */
    data class ExportedAll(val written: Int, val requested: Int) : LibraryEvent
    data object RenameFailed : LibraryEvent
}

/**
 * `@Stable` so the lambdas a row captures can be memoised by the compiler. The one mutable
 * property here, [exporting], is never read during composition - it is handed to the
 * document picker and read back when it returns - so nothing in the UI can go stale.
 */
@Stable
class LibraryViewModel(private val repository: TrackRepository) : ViewModel() {

    /** What the user has typed into the search field. Blank means they have not. */
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /**
     * The rows to show: everything, filtered by [query].
     *
     * Null until the first read comes back, which is not the same as empty.
     *
     * Starting at `emptyList()` meant the screen rendered "No tracks yet" for the first
     * frames of its own entry animation and then replaced it with the list - a full
     * content swap mid-slide, which reads as the animation stuttering rather than as data
     * arriving. `WhileSubscribed` keeps this warm for five seconds, so only the first open
     * ever sees the null.
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
     * One-shot events, not state: a Channel so an id is delivered exactly once and a
     * rotation cannot re-trigger the navigation or re-show the error.
     */
    private val _events = Channel<LibraryEvent>(Channel.BUFFERED)
    val events: Flow<LibraryEvent> = _events.receiveAsFlow()

    /** The track a pending export will write, held across the document-picker round trip. */
    var exporting: TrackEntity? = null
        private set

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

    fun beginExport(track: TrackEntity) {
        exporting = track
    }

    fun finishExport(destination: Uri?) {
        val track = exporting ?: return
        exporting = null
        if (destination == null) return
        viewModelScope.launch {
            repository.export(track.id, destination.toString()).fold(
                onSuccess = { _events.send(LibraryEvent.Exported) },
                onFailure = { _events.send(LibraryEvent.ExportFailed) },
            )
        }
    }

    /** The filenames a pending batch will be written under, by row id. */
    private var exportingAll: Map<Long, String> = emptyMap()

    fun beginExportAll(names: Map<Long, String>) {
        exportingAll = names
    }

    fun finishExportAll(folder: Uri?) {
        val names = exportingAll
        exportingAll = emptyMap()
        if (folder == null || names.isEmpty()) return
        viewModelScope.launch {
            repository.exportAll(names, folder.toString()).fold(
                // Reported as a count because it is one: a folder the app could write some
                // of is a likelier outcome than one it could write none of, and "Exported"
                // over a batch that half-landed is the kind of lie that costs a ride.
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

    fun setVisible(ids: Collection<Long>, visible: Boolean) {
        viewModelScope.launch {
            repository.setVisible(ids.toList(), visible)
            clearSelection()
        }
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
        val Factory = viewModelFactory {
            initializer { LibraryViewModel(appContainer.trackRepository) }
        }
    }
}

/**
 * Whether a row answers to what was typed.
 *
 * Both names, not just the one on screen: an import keeps the filename it arrived under
 * even after it is renamed, and "the file I downloaded in March" is exactly the sort of
 * thing someone searches a list of rides for.
 */
private fun TrackEntity.matches(query: String): Boolean {
    val needle = query.trim()
    if (needle.isEmpty()) return true
    return trackName?.contains(needle, ignoreCase = true) == true ||
        displayName.contains(needle, ignoreCase = true)
}
