package dev.samuelq.gpx.ui.library

import android.content.res.Resources
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.samuelq.gpx.R
import dev.samuelq.gpx.data.track.editableName
import dev.samuelq.gpx.data.track.isTitledByStart
import dev.samuelq.gpx.data.track.title
import dev.samuelq.gpx.data.db.TrackEntity
import dev.samuelq.gpx.data.settings.TrackOrder
import dev.samuelq.gpx.data.settings.TrackSort
import dev.samuelq.gpx.ui.format.Formatters
import dev.samuelq.gpx.ui.format.LocalFormatters
import dev.samuelq.gpx.ui.format.spokenDuration
import dev.samuelq.gpx.ui.format.spokenKilobytes
import dev.samuelq.gpx.ui.isLargeText
import dev.samuelq.gpx.ui.readFirst
import dev.samuelq.gpx.ui.rememberSnackbars
import dev.samuelq.gpx.ui.track.ColorDot
import dev.samuelq.gpx.ui.track.TrackMenu
import dev.samuelq.gpx.ui.track.TrackNameDialog
import dev.samuelq.gpx.ui.track.exportFileName
import dev.samuelq.gpx.ui.track.shareTrackIntent
import java.text.Collator
import java.time.Instant


/** Track management. Long-press starts a multi-selection. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    onOpenTrack: (Long) -> Unit,
    onBack: () -> Unit,
    viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val order by viewModel.order.collectAsStateWithLifecycle()
    val loaded by viewModel.listing.collectAsStateWithLifecycle()
    val sizes by viewModel.sizes.collectAsStateWithLifecycle()
    val tracks = loaded?.tracks.orEmpty()
    val sections = remember(tracks) { tracks.sections() }
    // Not for a library no one has sorted yet.
    val headed = sections.any { it.category != null }
    // Where each track's row is, counting the headers before it.
    val rowIndex = remember(sections, headed) {
        buildMap {
            var index = 0
            sections.forEach { section ->
                if (headed) index++
                section.tracks.forEach { put(it.id, index++) }
            }
        }
    }
    val inArea by viewModel.inArea.collectAsStateWithLifecycle()
    val shownOnly by viewModel.shownOnly.collectAsStateWithLifecycle()
    val filtered = query.isNotBlank() || inArea || shownOnly
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val snackbars = rememberSnackbars()
    val snackbarHostState = snackbars.host
    // Separate from a blank query: an open field starts empty.
    var searching by rememberSaveable { mutableStateOf(false) }

    fun closeSearch() {
        searching = false
        viewModel.search("")
    }

    BackHandler(enabled = searching) { closeSearch() }
    BackHandler(enabled = selection.isNotEmpty()) { viewModel.clearSelection() }

    val context = LocalContext.current
    // Not stringResource: a long-lived collector would keep the old locale.
    val resources = LocalResources.current
    val layoutDirection = LocalLayoutDirection.current
    // Plural on the total: "1 of 3 tracks".
    fun allOrSome(done: Int, requested: Int, all: Int, some: Int) =
        if (done == requested) resources.getQuantityString(all, done, done)
        else resources.getQuantityString(some, requested, done, requested)

    var renaming by remember { mutableStateOf<TrackEntity?>(null) }

    // A track just changed or brought back, scrolled to once its row is in the list.
    var reveal by remember { mutableStateOf<Long?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(reveal, rowIndex) {
        val id = reveal ?: return@LaunchedEffect
        val index = rowIndex[id] ?: return@LaunchedEffect
        // Laid out isn't seen: the padding puts rows under the bar.
        val info = listState.layoutInfo
        val shown = info.visibleItemsInfo.firstOrNull { it.key == id }
            ?.let { it.offset >= 0 && it.offset + it.size <= info.viewportEndOffset - info.afterContentPadding } == true
        if (!shown) listState.animateScrollToItem(index)
        // Only after: clearing it restarts this effect, which would cancel the scroll.
        reveal = null
    }

    fun delete(ids: Set<Long>) {
        viewModel.delete(ids)
        snackbars.offerUndo(
            context = context,
            // Named when it's one; a count says enough for several.
            message = ids.singleOrNull()?.let { id -> tracks.firstOrNull { it.id == id } }
                ?.let { resources.getString(R.string.deleted_named, it.title) }
                ?: resources.getQuantityString(R.plurals.library_deleted, ids.size, ids.size),
            undoLabel = resources.getString(R.string.action_undo),
            onUndo = {
                viewModel.undoDelete(ids)
                reveal = ids.first()
            },
            onCommit = { viewModel.commitDelete(ids) },
        )
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> viewModel.import(uris) }

    // The folder grant is not persisted.
    val folderExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { folder -> viewModel.finishExportAll(folder) }

    LaunchedEffect(viewModel) {
        suspend fun say(message: String) = snackbarHostState.showSnackbar(message)
        viewModel.events.collect { event ->
            when (event) {
                is LibraryEvent.Open -> onOpenTrack(event.id)
                is LibraryEvent.Duplicated -> {
                    reveal = event.id
                    say(resources.getString(R.string.library_duplicated))
                }
                LibraryEvent.ImportFailed -> say(resources.getString(R.string.library_import_failed))
                is LibraryEvent.ImportedAll -> say(
                    allOrSome(event.imported, event.requested, R.plurals.library_imported_all, R.plurals.library_imported_some)
                )
                LibraryEvent.ExportFailed -> say(resources.getString(R.string.library_export_failed))
                is LibraryEvent.ExportedAll -> say(
                    allOrSome(event.written, event.requested, R.plurals.library_exported_all, R.plurals.library_exported_some)
                )
                LibraryEvent.RenameFailed -> say(resources.getString(R.string.library_rename_failed))
                LibraryEvent.DuplicateFailed -> say(resources.getString(R.string.library_duplicate_failed))
                is LibraryEvent.VisibilityChanged -> snackbars.offerUndo(
                    context = context,
                    message = visibilityMessage(resources, event),
                    undoLabel = resources.getString(R.string.action_undo),
                    onUndo = { viewModel.restoreVisibility(event.before) },
                    onCommit = {},
                )
            }
        }
    }

    Scaffold(
        // With the cutout: in landscape it sits beside the list.
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState, Modifier.readFirst()) },
        topBar = {
            if (selection.isNotEmpty()) {
                val chosen = tracks.filter { it.id in selection }
                SelectionBar(
                    count = selection.size,
                    total = tracks.size,
                    onClose = viewModel::clearSelection,
                    onSelectAll = { viewModel.selectAll(tracks.map(TrackEntity::id)) },
                    onShow = { viewModel.changeVisibility(selection, BulkVisibility.SHOW) },
                    onHide = { viewModel.changeVisibility(selection, BulkVisibility.HIDE) },
                    onShowOnly = { viewModel.changeVisibility(selection, BulkVisibility.SHOW_ONLY) },
                    onExport = {
                        viewModel.beginExportAll(
                            chosen.associate { it.id to exportFileName(it.trackName, it.displayName) }
                        )
                        folderExporter.launch(null)
                    },
                    onDelete = { delete(selection) },
                )
            } else if (searching) {
                SearchBar(
                    query = query,
                    onQueryChange = viewModel::search,
                    onClose = ::closeSearch,
                )
            } else {
                TopAppBar(
                    windowInsets = BarInsets,
                    title = {
                        Text(stringResource(R.string.library_title), Modifier.semantics { heading() })
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                painterResource(R.drawable.ic_arrow_back),
                                stringResource(R.string.action_back),
                            )
                        }
                    },
                    actions = {
                        // Shown while loading so icons don't pop in during the slide.
                        if (loaded.let { it == null || it.total > 0 }) {
                            IconButton(onClick = { searching = true }) {
                                Icon(painterResource(R.drawable.ic_search), stringResource(R.string.library_search))
                            }
                            SortMenu(order, onSort = viewModel::setSort, onDescending = viewModel::setSortDescending)
                            IconButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                                Icon(painterResource(R.drawable.ic_add), stringResource(R.string.library_import))
                            }
                        }
                        // Empty, the page itself offers the import.
                    },
                )
            }
        },
    ) { padding ->
        val listing = loaded
        when {
            // Blank rather than flashing the empty state.
            listing == null -> Unit

            listing.total == 0 -> EmptyState(
                onImport = { picker.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxSize().padding(padding),
            )

            else -> Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
                val sides = Modifier.padding(
                    start = padding.calculateStartPadding(layoutDirection),
                    end = padding.calculateEndPadding(layoutDirection),
                )
                Filters(
                    inArea = inArea,
                    hasArea = viewModel.area != null,
                    shownOnly = shownOnly,
                    onInArea = viewModel::setInArea,
                    onShownOnly = viewModel::setShownOnly,
                    modifier = sides,
                )
                // Filtering is silent otherwise.
                if (filtered && tracks.isNotEmpty()) {
                    val found = pluralStringResource(R.plurals.library_search_found, tracks.size, tracks.size)
                    Box(Modifier.semantics { liveRegion = LiveRegionMode.Polite; contentDescription = found })
                }
                if (tracks.isEmpty()) {
                    NoMatches(
                        query = query,
                        modifier = Modifier.fillMaxSize().then(sides).padding(bottom = padding.calculateBottomPadding()),
                    )
                } else LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = padding.calculateStartPadding(layoutDirection),
                        end = padding.calculateEndPadding(layoutDirection),
                        bottom = padding.calculateBottomPadding() + 24.dp,
                    ),
                ) {
                    sections.forEachIndexed { sectionIndex, section ->
                        if (headed) item(key = "category:${section.category.orEmpty()}", contentType = "header") {
                            val ids = section.tracks.map(TrackEntity::id)
                            val picked = ids.count { it in selection }
                            CategoryHeader(
                                title = section.category ?: stringResource(R.string.library_uncategorised),
                                // The pills end the first group's top already.
                                divider = sectionIndex > 0,
                                selected = when {
                                    selection.isEmpty() -> null
                                    picked == ids.size -> ToggleableState.On
                                    picked == 0 -> ToggleableState.Off
                                    else -> ToggleableState.Indeterminate
                                },
                                onToggle = { viewModel.setSelected(ids, picked < ids.size) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                        itemsIndexed(section.tracks, key = { _, track -> track.id }, contentType = { _, _ -> "track" }) { index, track ->
                            TrackRow(
                                modifier = Modifier.animateItem(),
                                // Under the header's checkbox, as what it selects.
                                indent = if (headed && selection.isNotEmpty()) CategoryIndent else 0.dp,
                                // The next header's line ends the group.
                                divider = index < section.tracks.lastIndex,
                                track = track,
                                sizeBytes = sizes[track.id],
                                selected = track.id in selection,
                                selectionActive = selection.isNotEmpty(),
                                onOpen = {
                                    // Otherwise the switch would say off for a line on the map.
                                    if (!track.visible) viewModel.setVisible(track.id, true)
                                    onOpenTrack(track.id)
                                },
                                onToggleSelected = { viewModel.toggleSelected(track.id) },
                                onToggleVisible = { viewModel.setVisible(track.id, !track.visible) },
                                onColor = { viewModel.setColor(track.id, it) },
                                onShare = {
                                    context.startActivity(
                                        shareTrackIntent(context, track.location, track.trackName, track.displayName)
                                    )
                                },
                                onRename = { renaming = track },
                                onDuplicate = { viewModel.duplicate(track.id) },
                                onDelete = { delete(setOf(track.id)) },
                            )
                        }
                    }
                }
            }
        }
    }

    renaming?.let { track ->
        val categories by viewModel.categories.collectAsStateWithLifecycle()
        TrackNameDialog(
            initialName = track.editableName,
            initialCategory = track.category.orEmpty(),
            categories = categories,
            onDismiss = { renaming = null },
            onConfirm = { name, category ->
                viewModel.rename(track.id, name, category)
                // A new name or category can move it.
                reveal = track.id
                renaming = null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(
    count: Int,
    total: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onShow: () -> Unit,
    onHide: () -> Unit,
    onShowOnly: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    TopAppBar(
        windowInsets = BarInsets,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        title = {
            Text(
                pluralStringResource(R.plurals.library_selected, count, count),
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.library_clear_selection))
            }
        },
        actions = {
            IconButton(onClick = onExport) {
                Icon(painterResource(R.drawable.ic_share), stringResource(R.string.library_export))
            }
            IconButton(onClick = onDelete) {
                Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.library_delete))
            }
            SelectionMenu(
                count = count,
                // Gone once there's nothing left to add.
                onSelectAll = onSelectAll.takeIf { count < total },
                onShow = onShow,
                onHide = onHide,
                onShowOnly = onShowOnly,
            )
        },
    )
}

/** Visibility after select all, as what selecting a category is mostly for; each choice says what it does. */
@Composable
private fun SelectionMenu(
    count: Int,
    onSelectAll: (() -> Unit)?,
    onShow: () -> Unit,
    onHide: () -> Unit,
    onShowOnly: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.library_more))
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            @Composable
            fun Item(label: String, action: () -> Unit) = DropdownMenuItem(
                text = { Text(label) },
                onClick = {
                    open = false
                    action()
                },
            )
            onSelectAll?.let {
                Item(stringResource(R.string.library_select_all), it)
                HorizontalDivider()
            }
            Item(stringResource(R.string.library_show_selected), onShow)
            Item(stringResource(R.string.library_hide_selected), onHide)
            Item(pluralStringResource(R.plurals.library_show_only_selected, count), onShowOnly)
        }
    }
}

/** In this area is left out when the map showed nothing to filter to, as when tracks are too far apart; last, so the rest don't move. */
@Composable
private fun Filters(
    inArea: Boolean,
    hasArea: Boolean,
    shownOnly: Boolean,
    onInArea: (Boolean) -> Unit,
    onShownOnly: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    @Composable
    fun Filter(label: Int, on: Boolean, onChange: (Boolean) -> Unit) = FilterChip(
        selected = on,
        onClick = { onChange(!on) },
        label = { Text(stringResource(label)) },
        leadingIcon = if (on) {
            { Icon(painterResource(R.drawable.ic_check), null, Modifier.size(FilterChipDefaults.IconSize)) }
        } else null,
    )

    Row(modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Filter(R.string.library_filter_shown, shownOnly, onShownOnly)
        if (hasArea) Filter(R.string.library_filter_area, inArea, onInArea)
    }
}

/** [selected] is null outside selection, where the header is only a label. */
@Composable
private fun CategoryHeader(
    title: String,
    /** Above it, ending the previous group. */
    divider: Boolean,
    selected: ToggleableState?,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        if (divider) HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (selected != null) {
                        Modifier.triStateToggleable(state = selected, onClick = onToggle, role = Role.Checkbox)
                    } else Modifier
                )
                .semantics { heading() }
                // Nearer its rows than the group before.
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected != null) {
                // The rows' slot and gap, so they indent to the title; the row toggles, so the box doesn't.
                Box(Modifier.width(CategoryIndent)) {
                    Box(Modifier.width(LeadingSlot), contentAlignment = Alignment.Center) {
                        TriStateCheckbox(state = selected, onClick = null)
                    }
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackRow(
    modifier: Modifier,
    indent: Dp,
    divider: Boolean,
    track: TrackEntity,
    sizeBytes: Long?,
    selected: Boolean,
    selectionActive: Boolean,
    onOpen: () -> Unit,
    onToggleSelected: () -> Unit,
    onToggleVisible: () -> Unit,
    onColor: (Int) -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    val formatters = LocalFormatters.current
    val title = track.title
    val showOnMap = stringResource(R.string.library_show_on_map, title)
    val openLabel = stringResource(R.string.library_open_on_map)
    val selectLabel = stringResource(R.string.library_select)
    val renameLabel = stringResource(R.string.library_rename)
    val shareLabel = stringResource(R.string.library_share)
    val duplicateLabel = stringResource(R.string.library_duplicate)
    val deleteLabel = stringResource(R.string.library_delete)

    // Remembered: a DateTimeFormatter's first use loads locale data, janking the entry animation.
    // Null for an unnamed recording: its title is already the date.
    val date = remember(track, formatters) {
        val recorded = track.startedAtEpochMillis ?: track.lastOpenedAtEpochMillis
        formatters.dateTime(Instant.ofEpochMilli(recorded)).takeUnless { track.isTitledByStart }
    }
    val summary = remember(track, sizeBytes, formatters) {
        listOfNotNull(
            track.totalSeconds.takeIf { it > 0 }?.let { Formatters.duration(it) },
            formatters.distance(track.distanceMeters),
            sizeBytes?.let { Formatters.kilobytes(it) },
        ).joinToString("  ·  ")
    }
    // Labelled and in words, as the sheet's stats are: "3:57" alone reads as a time of day.
    val resources = LocalResources.current
    val spokenSummary = remember(track, sizeBytes, formatters, resources) {
        fun stat(label: Int, value: String) = resources.getString(R.string.stat_spoken, resources.getString(label), value)
        listOfNotNull(
            track.totalSeconds.takeIf { it > 0 }?.let { stat(R.string.stat_elapsed, resources.spokenDuration(it)) },
            stat(R.string.axis_distance, formatters.distance(track.distanceMeters)),
            sizeBytes?.let { stat(R.string.library_size, resources.spokenKilobytes(it)) },
        ).joinToString(", ")
    }

    // Under the divider too, so its inset doesn't notch a run of selected rows.
    Column(
        modifier.background(
            if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
        )
    ) {
        // A row, not ListItem: with three lines it pins the dot and the controls to the top
        // padding, out of line with each other and the text.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClickLabel = if (selectionActive) null else openLabel,
                    onLongClickLabel = if (selectionActive) null else selectLabel,
                    role = if (selectionActive) Role.Checkbox else null,
                    onClick = { if (selectionActive) onToggleSelected() else onOpen() },
                    onLongClick = onToggleSelected,
                )
                .semantics {
                    if (selectionActive) {
                        toggleableState = ToggleableState(selected)
                    } else {
                        // Long-press and the menu, without finding either.
                        customActions = listOf(
                            CustomAccessibilityAction(selectLabel) { onToggleSelected(); true },
                            CustomAccessibilityAction(renameLabel) { onRename(); true },
                            CustomAccessibilityAction(shareLabel) { onShare(); true },
                            CustomAccessibilityAction(duplicateLabel) { onDuplicate(); true },
                            CustomAccessibilityAction(deleteLabel) { onDelete(); true },
                        )
                    }
                }
                .padding(start = 16.dp + indent, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // One width for both, so the title doesn't shift entering selection.
            Box(Modifier.width(LeadingSlot), contentAlignment = Alignment.Center) {
                if (selectionActive) {
                    // The row toggles, so it's one stop that says what's selected.
                    Checkbox(checked = selected, onCheckedChange = null)
                } else {
                    ColorDot(track.colorIndex, onColor)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = if (isLargeText()) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Wrapping, not cut: at large text sizes the ends are the time and size.
                date?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { contentDescription = spokenSummary },
                )
            }
            if (!selectionActive) {
                Switch(
                    checked = track.visible,
                    onCheckedChange = { onToggleVisible() },
                    modifier = Modifier.padding(start = 8.dp).semantics { contentDescription = showOnMap },
                )
                TrackMenu(onRename, onShare, onHide = null, onDelete, trackTitle = title, onDuplicate = onDuplicate)
            }
        }
        // Inset to the title: the same group, next item.
        if (divider) HorizontalDivider(Modifier.padding(start = 16.dp + indent + LeadingSlot + 16.dp))
    }
}

@Composable
private fun SortMenu(order: TrackOrder, onSort: (TrackSort) -> Unit, onDescending: (Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_sort), stringResource(R.string.library_sort))
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            val resources = LocalResources.current
            val options = remember(resources) {
                val collator = Collator.getInstance()
                TrackSort.entries.sortedWith(compareBy(collator) { resources.getString(it.label) })
            }
            @Composable
            fun Choice(label: String, isSelected: Boolean, onClick: () -> Unit) = DropdownMenuItem(
                text = { Text(label) },
                onClick = {
                    open = false
                    onClick()
                },
                // Blank space when unchecked keeps the labels aligned.
                leadingIcon = {
                    if (isSelected) Icon(painterResource(R.drawable.ic_check), null) else Spacer(Modifier.size(24.dp))
                },
                modifier = Modifier.semantics { selected = isSelected },
            )
            options.forEach { option ->
                Choice(stringResource(option.label), option == order.sort) { onSort(option) }
            }
            HorizontalDivider()
            Choice(stringResource(R.string.library_sort_ascending), !order.descending) { onDescending(false) }
            Choice(stringResource(R.string.library_sort_descending), order.descending) { onDescending(true) }
        }
    }
}

private val TrackSort.label: Int
    get() = when (this) {
        TrackSort.RECENT -> R.string.library_sort_recent
        TrackSort.DATE -> R.string.library_sort_date
        TrackSort.LENGTH -> R.string.library_sort_length
        TrackSort.NAME -> R.string.library_sort_name
    }

/** Named when it's one, as delete's is. */
private fun visibilityMessage(resources: Resources, event: LibraryEvent.VisibilityChanged): String {
    val (named, counted) = when (event.change) {
        BulkVisibility.SHOW -> R.string.library_shown_named to R.plurals.library_shown
        BulkVisibility.HIDE -> R.string.track_hidden to R.plurals.library_hidden
        BulkVisibility.SHOW_ONLY -> R.string.library_shown_only_named to R.plurals.library_shown_only
    }
    return event.name?.let { resources.getString(named, it) }
        ?: resources.getQuantityString(counted, event.count, event.count)
}

@Composable
private fun EmptyState(onImport: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.library_empty_title),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.library_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onImport) { Text(stringResource(R.string.library_import)) }
    }
}

/** Names the search if there is one; else it's the filters that left nothing. */
@Composable
private fun NoMatches(query: String, modifier: Modifier = Modifier) {
    Box(modifier.padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text = if (query.isNotBlank()) stringResource(R.string.library_search_empty, query.trim())
            else stringResource(R.string.library_filter_empty),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Material's input field in a plain bar: `SearchBar` itself expands for suggestions there are none of. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val keyboard = LocalSoftwareKeyboardController.current
    // The placeholder goes once there's text, and with it the field's only name.
    val hint = stringResource(R.string.library_search)

    TopAppBar(
        windowInsets = BarInsets,
        title = {
            SearchBarDefaults.InputField(
                query = query,
                onQueryChange = onQueryChange,
                onSearch = { keyboard?.hide() },
                expanded = false,
                onExpandedChange = {},
                placeholder = { Text(hint) },
                colors = SearchBarDefaults.inputFieldColors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                ),
                modifier = Modifier
                    .focusRequester(focusRequester)
                    .semantics { contentDescription = hint },
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    painterResource(R.drawable.ic_arrow_back),
                    stringResource(R.string.library_search_close),
                )
            }
        },
        actions = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.library_search_clear))
                }
            }
        },
    )
}

/** A row's dot or checkbox. */
private val LeadingSlot = 24.dp

/** A header's checkbox and the gap after it, which its rows are indented by. */
private val CategoryIndent = LeadingSlot + 16.dp

/** The bars' own insets, plus the cutout, which they leave out and landscape puts beside them. */
private val BarInsets: WindowInsets
    @Composable get() = TopAppBarDefaults.windowInsets.union(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))

