package dev.samuelq.gpx.ui.library

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.samuelq.gpx.R
import dev.samuelq.gpx.data.db.TrackEntity
import dev.samuelq.gpx.ui.format.Formatters
import dev.samuelq.gpx.ui.format.LocalFormatters
import dev.samuelq.gpx.ui.theme.routePalette
import dev.samuelq.gpx.ui.theme.slot
import dev.samuelq.gpx.ui.track.DeleteTrackDialog
import dev.samuelq.gpx.ui.track.TrackMenu
import dev.samuelq.gpx.ui.track.TrackNameDialog
import dev.samuelq.gpx.ui.track.editableTrackName
import dev.samuelq.gpx.ui.track.exportFileName
import dev.samuelq.gpx.ui.track.shareTrackIntent
import dev.samuelq.gpx.ui.track.trackTitle
import java.time.Instant


/** Track management. Long-press starts a multi-selection. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    onOpenTrack: (Long) -> Unit,
    onBack: () -> Unit,
    viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory),
) {
    val loaded by viewModel.tracks.collectAsStateWithLifecycle()
    val tracks = loaded.orEmpty()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
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

    val importFailed = stringResource(R.string.library_import_failed)
    val exportFailed = stringResource(R.string.library_export_failed)
    val renameFailed = stringResource(R.string.library_rename_failed)
    val context = LocalContext.current
    // Not `context.resources`, which misses a locale change while the screen is up.
    val resources = LocalResources.current
    val exportedAll: (Int, Int) -> String = { written, requested ->
        if (written == requested) {
            resources.getQuantityString(R.plurals.library_exported_all, written, written)
        } else {
            resources.getString(R.string.library_exported_some, written, requested)
        }
    }
    var renaming by remember { mutableStateOf<TrackEntity?>(null) }
    var deleting by remember { mutableStateOf<Set<Long>>(emptySet()) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::import)
    }

    // The folder grant is not persisted.
    val folderExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { folder -> viewModel.finishExportAll(folder) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is LibraryEvent.Open -> onOpenTrack(event.id)
                LibraryEvent.ImportFailed -> snackbarHostState.showSnackbar(importFailed)
                LibraryEvent.ExportFailed -> snackbarHostState.showSnackbar(exportFailed)
                is LibraryEvent.ExportedAll -> snackbarHostState.showSnackbar(
                    exportedAll(event.written, event.requested)
                )
                LibraryEvent.RenameFailed -> snackbarHostState.showSnackbar(renameFailed)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                    onDelete = { deleting = selection },
                )
            } else if (searching) {
                SearchBar(
                    query = query,
                    onQueryChange = viewModel::search,
                    onClose = ::closeSearch,
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.library_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                stringResource(R.string.action_back),
                            )
                        }
                    },
                    actions = {
                        if (loaded == null || tracks.isNotEmpty()) {
                            IconButton(onClick = { searching = true }) {
                                Icon(Icons.Default.Search, stringResource(R.string.library_search))
                            }
                        }
                        IconButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Default.Add, stringResource(R.string.library_import))
                        }
                        // Shown while loading so icons don't pop in during the slide.
                        if (loaded == null || tracks.isNotEmpty()) {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Default.MoreVert, stringResource(R.string.library_more))
                            }
                            DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.library_show_all)) },
                                    onClick = {
                                        menuOpen = false
                                        viewModel.setAllVisible(true)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.library_hide_all)) },
                                    onClick = {
                                        menuOpen = false
                                        viewModel.setAllVisible(false)
                                    },
                                )
                            }
                        }
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
                modifier = Modifier.fillMaxSize().padding(padding),
            )

            else -> {
            val palette = routePalette()
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
            ) {
                items(tracks, key = TrackEntity::id) { track ->
                    TrackRow(
                        track = track,
                        color = palette.slot(track.colorIndex),
                        selected = track.id in selection,
                        selectionActive = selection.isNotEmpty(),
                        onOpen = {
                            // Otherwise the switch would say off for a line on the map.
                            if (!track.visible) viewModel.setVisible(track.id, true)
                            onOpenTrack(track.id)
                        },
                        onToggleSelected = { viewModel.toggleSelected(track.id) },
                        onToggleVisible = { viewModel.setVisible(track.id, !track.visible) },
                        onShare = {
                            context.startActivity(
                                shareTrackIntent(context, track.location, track.trackName, track.displayName)
                            )
                        },
                        onRename = { renaming = track },
                        onDelete = { deleting = setOf(track.id) },
                    )
                    HorizontalDivider()
                }
            }
            }
        }
    }

    renaming?.let { track ->
        TrackNameDialog(
            titleRes = R.string.library_rename,
            initialName = editableTrackName(track.trackName, track.displayName),
            onDismiss = { renaming = null },
            onConfirm = { name ->
                viewModel.rename(track.id, name)
                renaming = null
            },
        )
    }

    if (deleting.isNotEmpty()) {
        DeleteTrackDialog(
            count = deleting.size,
            onDismiss = { deleting = emptySet() },
            onConfirm = {
                viewModel.delete(deleting)
                deleting = emptySet()
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
    val selectAll = stringResource(R.string.library_select_all)

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        title = { Text(stringResource(R.string.library_selected, count)) },
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
                Icon(Icons.Default.Delete, stringResource(R.string.library_delete))
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun TrackRow(
    track: TrackEntity,
    color: androidx.compose.ui.graphics.Color,
    selected: Boolean,
    selectionActive: Boolean,
    onOpen: () -> Unit,
    onToggleSelected: () -> Unit,
    onToggleVisible: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val formatters = LocalFormatters.current

    // Remembered: a DateTimeFormatter's first use loads locale data, janking the entry animation.
    val summary = remember(track, formatters) {
        buildString {
            append(formatters.distance(track.distanceMeters))
            if (track.totalSeconds > 0) {
                append("  ·  ")
                append(Formatters.duration(track.totalSeconds))
            }
            val recorded = track.startedAtEpochMillis ?: track.lastOpenedAtEpochMillis
            if (recorded > 0) {
                append("  ·  ")
                append(Formatters.dateTime(Instant.ofEpochMilli(recorded)))
            }
        }
    }

    ListItem(
        colors = if (selected) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            ListItemDefaults.colors()
        },
        leadingContent = {
            if (selectionActive) {
                Checkbox(checked = selected, onCheckedChange = { onToggleSelected() })
            } else {
                // Same hue as the map line.
                Box(
                    Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(if (track.visible) color else MaterialTheme.colorScheme.outlineVariant)
                )
            }
        },
        headlineContent = {
            Text(
                text = trackTitle(track.trackName, track.displayName),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = summary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
        },
        trailingContent = {
            if (!selectionActive) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = track.visible,
                        onCheckedChange = { onToggleVisible() },
                    )
                    TrackMenu(onRename, onShare, onHide = null, onDelete)
                }
            }
        },
        modifier = Modifier.combinedClickable(
            onClick = { if (selectionActive) onToggleSelected() else onOpen() },
            onLongClick = onToggleSelected,
        ),
    )
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.library_empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.library_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NoMatches(query: String, modifier: Modifier = Modifier) {
    Box(modifier.padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.library_search_empty, query.trim()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Not Material's `SearchBar`, which expands for suggestions there are none of. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    TopAppBar(
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text(stringResource(R.string.library_search_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
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

