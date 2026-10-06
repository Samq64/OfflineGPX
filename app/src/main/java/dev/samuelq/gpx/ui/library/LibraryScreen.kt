package dev.samuelq.gpx.ui.library

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.samuelq.gpx.R
import dev.samuelq.gpx.data.db.TrackEntity
import dev.samuelq.gpx.data.settings.TrackOrder
import dev.samuelq.gpx.data.settings.TrackSort
import dev.samuelq.gpx.ui.format.Formatters
import dev.samuelq.gpx.ui.format.LocalFormatters
import dev.samuelq.gpx.ui.isLargeText
import dev.samuelq.gpx.ui.readFirst
import dev.samuelq.gpx.ui.showUndo
import dev.samuelq.gpx.ui.theme.routePalette
import dev.samuelq.gpx.ui.theme.RouteColorNames
import dev.samuelq.gpx.ui.theme.RoutePickerOrder
import dev.samuelq.gpx.ui.theme.slot
import dev.samuelq.gpx.ui.track.TrackMenu
import dev.samuelq.gpx.ui.track.TrackNameDialog
import dev.samuelq.gpx.ui.track.editableTrackName
import dev.samuelq.gpx.ui.track.exportFileName
import dev.samuelq.gpx.ui.track.shareTrackIntent
import dev.samuelq.gpx.ui.track.trackTitle
import java.text.Collator
import java.time.Instant
import kotlinx.coroutines.launch


/** Track management. Long-press starts a multi-selection. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    onOpenTrack: (Long) -> Unit,
    onBack: () -> Unit,
    viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory),
) {
    val loaded by viewModel.tracks.collectAsStateWithLifecycle()
    val sizes by viewModel.sizes.collectAsStateWithLifecycle()
    val tracks = loaded.orEmpty()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val order by viewModel.order.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
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
    val scope = rememberCoroutineScope()

    // A track just changed or brought back, scrolled to once its row is in the list.
    var reveal by remember { mutableStateOf<Long?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(reveal, tracks) {
        val id = reveal ?: return@LaunchedEffect
        val index = tracks.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return@LaunchedEffect
        reveal = null
        if (listState.layoutInfo.visibleItemsInfo.none { it.key == id }) listState.animateScrollToItem(index)
    }

    fun delete(ids: Set<Long>) {
        viewModel.delete(ids)
        scope.launch {
            snackbarHostState.showUndo(
                context = context,
                // Named when it's one; a count says enough for several.
                message = ids.singleOrNull()?.let { id -> tracks.firstOrNull { it.id == id } }
                    ?.let { resources.getString(R.string.deleted_named, trackTitle(it.trackName, it.displayName)) }
                    ?: resources.getQuantityString(R.plurals.library_deleted, ids.size, ids.size),
                undoLabel = resources.getString(R.string.action_undo),
                onUndo = {
                    viewModel.undoDelete(ids)
                    reveal = ids.first()
                },
                onCommit = { viewModel.commitDelete(ids) },
            )
        }
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
                is LibraryEvent.Duplicated -> reveal = event.id
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
            }
        }
    }

    Scaffold(
        // With the cutout: in landscape it sits beside the list.
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState, Modifier.readFirst()) },
        topBar = {
            if (selection.isNotEmpty()) {
                SelectionBar(
                    count = selection.size,
                    total = tracks.size,
                    onClose = viewModel::clearSelection,
                    onSelectAll = { viewModel.selectAll(tracks.map(TrackEntity::id)) },
                    onExport = {
                        val chosen = tracks.filter { it.id in selection }
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
                                Icons.AutoMirrored.Filled.ArrowBack,
                                stringResource(R.string.action_back),
                            )
                        }
                    },
                    actions = {
                        // Shown while loading so icons don't pop in during the slide. Import is in
                        // the menu then: four icons wrapped the title on narrow screens.
                        if (loaded == null || tracks.isNotEmpty()) {
                            IconButton(onClick = { searching = true }) {
                                Icon(Icons.Default.Search, stringResource(R.string.library_search))
                            }
                            SortMenu(order, onSort = viewModel::setSort, onDescending = viewModel::setSortDescending)
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Default.MoreVert, stringResource(R.string.library_more))
                            }
                            DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                                @Composable
                                fun Item(label: Int, onClick: () -> Unit) = DropdownMenuItem(
                                    text = { Text(stringResource(label)) },
                                    onClick = {
                                        menuOpen = false
                                        onClick()
                                    },
                                )
                                Item(R.string.library_import) { picker.launch(arrayOf("*/*")) }
                                Item(R.string.library_show_all) { viewModel.setAllVisible(true) }
                                Item(R.string.library_hide_all) { viewModel.setAllVisible(false) }
                            }
                        }
                        // Empty, the page itself offers the import.
                    },
                )
            }
        },
    ) { padding ->
        when {
            // Blank rather than flashing the empty state.
            loaded == null -> Unit

            tracks.isEmpty() && query.isNotBlank() -> NoMatches(
                query = query,
                modifier = Modifier.fillMaxSize().padding(padding),
            )

            tracks.isEmpty() -> EmptyState(
                onImport = { picker.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxSize().padding(padding),
            )

            else -> {
            // Filtering is silent otherwise.
            if (query.isNotBlank()) {
                val found = pluralStringResource(R.plurals.library_search_found, tracks.size, tracks.size)
                Box(Modifier.semantics { liveRegion = LiveRegionMode.Polite; contentDescription = found })
            }
            val palette = routePalette()
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = padding.calculateStartPadding(layoutDirection),
                    top = padding.calculateTopPadding(),
                    end = padding.calculateEndPadding(layoutDirection),
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
            ) {
                items(tracks, key = TrackEntity::id) { track ->
                    TrackRow(
                        modifier = Modifier.animateItem(),
                        track = track,
                        sizeBytes = sizes[track.id],
                        palette = palette,
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

    renaming?.let { track ->
        TrackNameDialog(
            initialName = editableTrackName(track.trackName, track.displayName),
            onDismiss = { renaming = null },
            onConfirm = { name ->
                viewModel.rename(track.id, name)
                // A new name can move it, sorted by name.
                reveal = track.id
                renaming = null
            },
        )
    }
}

/** Select-all is a tri-state checkbox since `material-icons-core` has no `select_all`. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(
    count: Int,
    total: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val allSelected = count >= total
    // It clears once everything is selected, so it says so.
    val selectAll = stringResource(if (allSelected) R.string.library_clear_selection else R.string.library_select_all)

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
                Icon(Icons.Default.Clear, stringResource(R.string.library_clear_selection))
            }
        },
        actions = {
            TriStateCheckbox(
                state = if (allSelected) ToggleableState.On else ToggleableState.Indeterminate,
                onClick = { if (allSelected) onClose() else onSelectAll() },
                modifier = Modifier.semantics { contentDescription = selectAll },
            )
            IconButton(onClick = onExport) {
                Icon(Icons.Default.Share, stringResource(R.string.library_export_all))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, stringResource(R.string.library_delete_selected))
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackRow(
    modifier: Modifier,
    track: TrackEntity,
    sizeBytes: Long?,
    palette: List<Color>,
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
    val title = trackTitle(track.trackName, track.displayName)
    val showOnMap = stringResource(R.string.library_show_on_map, title)
    val openLabel = stringResource(R.string.library_open_on_map)
    val selectLabel = stringResource(R.string.library_select)
    val renameLabel = stringResource(R.string.library_rename)
    val shareLabel = stringResource(R.string.library_share)
    val duplicateLabel = stringResource(R.string.library_duplicate)
    val deleteLabel = stringResource(R.string.library_delete)

    // Remembered: a DateTimeFormatter's first use loads locale data, janking the entry animation.
    val date = remember(track, formatters) {
        val recorded = track.startedAtEpochMillis ?: track.lastOpenedAtEpochMillis
        formatters.dateTime(Instant.ofEpochMilli(recorded))
    }
    val summary = remember(track, sizeBytes, formatters) {
        listOfNotNull(
            track.totalSeconds.takeIf { it > 0 }?.let { Formatters.duration(it) },
            formatters.distance(track.distanceMeters),
            sizeBytes?.let { Formatters.kilobytes(it) },
        ).joinToString("  ·  ")
    }

    Column(modifier) {
        // A row, not ListItem: with three lines it pins the dot and the controls to the top
        // padding, out of line with each other and the text.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
                )
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionActive) {
                // The row toggles, so it's one stop that says what's selected.
                Checkbox(checked = selected, onCheckedChange = null)
            } else {
                ColorDot(track.colorIndex, palette, onColor = onColor)
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
                Text(
                    text = date,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        HorizontalDivider()
    }
}

/** The map line's hue; a tap picks another from the palette. Its touch target reaches 48dp. */
@Composable
private fun ColorDot(colorIndex: Int, palette: List<Color>, onColor: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = stringResource(
        R.string.library_color,
        stringResource(RouteColorNames[colorIndex.mod(RouteColorNames.size)]),
    )
    Box {
        Box(
            Modifier
                .size(DotSize)
                .clip(CircleShape)
                // Its colour even when hidden: it's the picker, and the switch says hidden.
                .background(palette.slot(colorIndex))
                .clickable(onClickLabel = stringResource(R.string.library_color_change)) { open = true }
                .semantics { contentDescription = label }
        )
        DropdownMenu(open, onDismissRequest = { open = false }) {
            // Seven 48dp targets need about 380dp; narrower windows get two even rows.
            val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
            FlowRow(
                modifier = Modifier.padding(horizontal = 8.dp),
                maxItemsInEachRow = if (windowWidth >= 380.dp) RoutePickerOrder.size else 4,
            ) {
                RoutePickerOrder.forEach { index ->
                    val color = palette[index]
                    val isSelected = index == colorIndex.mod(palette.size)
                    val label = stringResource(RouteColorNames[index])
                    Box(
                        Modifier
                            .size(48.dp)
                            .selectable(selected = isSelected, role = Role.RadioButton) {
                                open = false
                                if (!isSelected) onColor(index)
                            }
                            .semantics { contentDescription = label },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.size(32.dp).clip(CircleShape).background(color),
                            contentAlignment = Alignment.Center,
                        ) {
                            // Dark gold and pink are too pale for white.
                            val tick = if (color.luminance() > 0.4f) Color.Black else Color.White
                            if (isSelected) Icon(Icons.Default.Check, null, tint = tick)
                        }
                    }
                }
            }
        }
    }
}

private val DotSize = 20.dp

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
                    if (isSelected) Icon(Icons.Default.Check, null) else Spacer(Modifier.size(24.dp))
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

@Composable
private fun NoMatches(query: String, modifier: Modifier = Modifier) {
    Box(modifier.padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.library_search_empty, query.trim()),
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
                    Icons.AutoMirrored.Filled.ArrowBack,
                    stringResource(R.string.library_search_close),
                )
            }
        },
        actions = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, stringResource(R.string.library_search_clear))
                }
            }
        },
    )
}

/** The bars' own insets, plus the cutout, which they leave out and landscape puts beside them. */
private val BarInsets: WindowInsets
    @Composable get() = TopAppBarDefaults.windowInsets.union(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))

