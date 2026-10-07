package dev.samuelq.gpx.ui.library

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.runtime.Stable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.toRoute
import dev.samuelq.gpx.R
import dev.samuelq.gpx.core.model.GeoBounds
import dev.samuelq.gpx.data.db.TrackEntity
import dev.samuelq.gpx.data.settings.SettingsRepository
import dev.samuelq.gpx.data.settings.TrackOrder
import dev.samuelq.gpx.data.settings.TrackSort
import dev.samuelq.gpx.data.track.TrackRepository
import dev.samuelq.gpx.data.track.title
import dev.samuelq.gpx.di.appContainer
import dev.samuelq.gpx.ui.nav.LibraryRoute
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface LibraryEvent {
    data class Open(val id: Long) : LibraryEvent

    /** Words alone, with nothing to act on. */
    data class Say(@param:StringRes val text: Int) : LibraryEvent

    /** A multi-file import; a single file opens instead. */
    data class ImportedAll(val imported: Int, val requested: Int) : LibraryEvent

    data class ExportedAll(val written: Int, val requested: Int) : LibraryEvent
    data class Duplicated(val id: Long) : LibraryEvent

    /** [count] tracks, [name] if it was one; undone by restoring [before]. */
    data class VisibilityChanged(
        val change: BulkVisibility,
        val count: Int,
        val name: String?,
        val before: Map<Long, Boolean>,
    ) : LibraryEvent
}

enum class BulkVisibility { SHOW, HIDE, SHOW_ONLY }

/** What the list shows, and how many tracks there are before filtering. */
class Listing(val tracks: List<TrackEntity>, val total: Int)

/** Tracks under one category, null for the uncategorised. */
class Section(val category: String?, val tracks: List<TrackEntity>)

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

    val order: StateFlow<TrackOrder> = settings.trackOrder

    /** What the map showed on the way here; null if it showed nothing yet. */
    val area: GeoBounds? = savedState.toRoute<LibraryRoute>().area

    /** For the rename dialog to suggest. */
    val categories: StateFlow<List<String>> = repository.categories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val inArea: StateFlow<Boolean> = savedState.getStateFlow(IN_AREA, false)

    val shownOnly: StateFlow<Boolean> = savedState.getStateFlow(SHOWN_ONLY, false)

    fun setInArea(on: Boolean) {
        savedState[IN_AREA] = on
    }

    fun setShownOnly(on: Boolean) {
        savedState[SHOWN_ONLY] = on
    }

    /** Read apart from the rows, so the list shows before every file is statted. */
    val sizes: StateFlow<Map<Long, Long>> = repository.tracks
        .mapLatest { repository.fileSizes(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Null until loaded, so the empty state doesn't flash during the entry animation. */
    val listing: StateFlow<Listing?> =
        combine(repository.tracks, _query, order, inArea, shownOnly) { tracks, query, order, inArea, shownOnly ->
            val area = area.takeIf { inArea }
            val shown = tracks.filter {
                it.matches(query) && (!shownOnly || it.visible) && (area == null || it.bounds?.overlaps(area) == true)
            }
            Listing(shown.sortedFor(order).groupedByCategory(), tracks.size)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setSort(sort: TrackSort) = settings.setTrackSort(sort)

    fun setSortDescending(descending: Boolean) = settings.setTrackSortDescending(descending)

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
        _selection.update { if (id in it) it - id else it + id }
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    fun selectAll(ids: List<Long>) {
        _selection.value = ids.toSet()
    }

    /** Adds or removes [ids], as a category's checkbox does. */
    fun setSelected(ids: Collection<Long>, selected: Boolean) {
        _selection.update { if (selected) it + ids else it - ids.toSet() }
    }

    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            if (uris.size == 1) {
                repository.import(uris.single()).fold(
                    onSuccess = { _events.send(LibraryEvent.Open(it)) },
                    onFailure = { _events.send(LibraryEvent.Say(R.string.library_import_failed)) },
                )
                return@launch
            }
            val imported = uris.count { repository.import(it).isSuccess }
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
            repository.exportAll(names, folder).fold(
                onSuccess = { _events.send(LibraryEvent.ExportedAll(it, names.size)) },
                onFailure = { _events.send(LibraryEvent.Say(R.string.library_export_failed)) },
            )
            clearSelection()
        }
    }

    /** Null leaves either as it is. */
    fun rename(id: Long, name: String?, category: String?) {
        viewModelScope.launch {
            repository.rename(id, name, category).onFailure {
                _events.send(LibraryEvent.Say(R.string.library_rename_failed))
            }
        }
    }

    fun setVisible(id: Long, visible: Boolean) {
        viewModelScope.launch { repository.setVisible(id, visible) }
    }

    /** The copy lists first, as the last viewed. */
    fun duplicate(id: Long) {
        viewModelScope.launch {
            repository.duplicate(id).fold(
                onSuccess = { _events.send(LibraryEvent.Duplicated(it)) },
                onFailure = { _events.send(LibraryEvent.Say(R.string.library_duplicate_failed)) },
            )
        }
    }

    fun setColor(id: Long, colorIndex: Int) {
        viewModelScope.launch { repository.setColor(id, colorIndex) }
    }

    /**
     * Ends the selection, as the action it was made for: the rows' switches then show the
     * result. Show only hides tracks filtered out of the list too.
     */
    fun changeVisibility(ids: Set<Long>, change: BulkVisibility) {
        clearSelection()
        viewModelScope.launch {
            val all = repository.tracks.first()
            // Every track for show only, which may hide any of them.
            val before = all.filter { change == BulkVisibility.SHOW_ONLY || it.id in ids }.associate {
                it.id to
                    it.visible
            }
            when (change) {
                BulkVisibility.SHOW -> repository.setVisible(ids, true)
                BulkVisibility.HIDE -> repository.setVisible(ids, false)
                BulkVisibility.SHOW_ONLY -> repository.showOnly(ids)
            }
            val name = ids.singleOrNull()?.let { id -> all.firstOrNull { it.id == id }?.title }
            _events.send(LibraryEvent.VisibilityChanged(change, ids.size, name, before))
        }
    }

    fun restoreVisibility(before: Map<Long, Boolean>) {
        viewModelScope.launch { repository.restoreVisibility(before) }
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
        private const val IN_AREA = "in_area"
        private const val SHOWN_ONLY = "shown_only"

        val Factory = viewModelFactory {
            initializer {
                LibraryViewModel(
                    appContainer.trackRepository,
                    appContainer.settingsRepository,
                    createSavedStateHandle(),
                )
            }
        }
    }
}

/** Checks the title, the filename and the category: a renamed import still answers to its filename. */
private fun TrackEntity.matches(query: String): Boolean {
    val needle = query.trim()
    if (needle.isEmpty()) return true
    return title.contains(needle, ignoreCase = true) ||
        displayName.contains(needle, ignoreCase = true) ||
        category?.contains(needle, ignoreCase = true) == true
}

/**
 * Whether [this] and [area] share any ground. [area]'s longitudes may run past ±180, as a view
 * across the antimeridian's do, so it's tried a world either side too.
 */
internal fun GeoBounds.overlaps(area: GeoBounds): Boolean {
    if (southLatitude > area.northLatitude || northLatitude < area.southLatitude) return false
    if (area.eastLongitude - area.westLongitude >= 360.0) return true
    return (-1..1).any { world ->
        val shift = world * 360.0
        westLongitude + shift <= area.eastLongitude && eastLongitude + shift >= area.westLongitude
    }
}

/**
 * Categories alphabetically, ignoring case, the uncategorised last; stable, so each keeps the
 * sort within.
 */
internal fun List<TrackEntity>.groupedByCategory(): List<TrackEntity> {
    val collator = Collator.getInstance()
    return sortedWith(
        compareBy<TrackEntity> { it.category == null }
            .thenBy(collator) { it.category.orEmpty().lowercase(Locale.ROOT) },
    )
}

/** [groupedByCategory]'s runs, each titled by its first track's spelling. */
internal fun List<TrackEntity>.sections(): List<Section> = buildList {
    var start = 0
    for (i in 1..this@sections.size) {
        if (i == this@sections.size ||
            !this@sections[i].category.equals(this@sections[start].category, ignoreCase = true)
        ) {
            add(Section(this@sections[start].category, this@sections.subList(start, i)))
            start = i
        }
    }
}

/** Stable, so ties keep the repository's last-viewed order, turned round with the rest. */
internal fun List<TrackEntity>.sortedFor(order: TrackOrder): List<TrackEntity> {
    val natural = when (order.sort) {
        TrackSort.RECENT -> this
        TrackSort.DATE -> sortedByDescending { it.startedAtEpochMillis ?: it.lastOpenedAtEpochMillis }
        TrackSort.LENGTH -> sortedByDescending { it.distanceMeters }
        TrackSort.NAME -> {
            val collator = Collator.getInstance()
            sortedWith(compareBy(collator) { it.title })
        }
    }
    return if (order.descending == order.sort.naturallyDescending) natural else natural.asReversed()
}
