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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.samuelq.gpx.R
import dev.samuelq.gpx.data.db.TrackEntity
import dev.samuelq.gpx.data.db.TrackSource
import dev.samuelq.gpx.ui.format.Formatters
import dev.samuelq.gpx.ui.format.LocalFormatters
import dev.samuelq.gpx.ui.theme.routePalette
import dev.samuelq.gpx.ui.track.DeleteTrackDialog
import dev.samuelq.gpx.ui.track.TrackNameDialog
import dev.samuelq.gpx.ui.track.editableTrackName
import dev.samuelq.gpx.ui.track.exportFileName
import java.time.Instant


/**
 * Where tracks are managed rather than read.
 *
 * Import lives here instead of on the map because the app makes its own GPX files now -
 * bringing one in from elsewhere is the rarer thing, and the map's one action should be
 * the common one. Long-press starts a selection, which exists for deleting several
 * tracks at once - visibility is a switch on every row and an all-at-once pair in the menu.
 */
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
    // Separate from the query being blank: the field is open from the moment it is asked
    // for, and an empty one is how every search starts.
    var searching by rememberSaveable { mutableStateOf(false) }

    fun closeSearch() {
        searching = false
        viewModel.search("")
    }

    // Back closes the field before it leaves the screen: the filter is state the user put
    // there, and dropping them onto the map still holding it would be a step too far.
    BackHandler(enabled = searching) { closeSearch() }
    BackHandler(enabled = selection.isNotEmpty()) { viewModel.clearSelection() }

    val importFailed = stringResource(R.string.library_import_failed)
    val exportFailed = stringResource(R.string.library_export_failed)
    val exported = stringResource(R.string.library_exported)
    val renameFailed = stringResource(R.string.library_rename_failed)
    val resources = LocalContext.current.resources
    // A count, and a different sentence when it is not the count that was asked for.
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

    // CreateDocument rather than a share sheet: the user picks the destination through
    // SAF, so the file lands where they chose and the app needs no storage permission.
    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml")
    ) { destination -> viewModel.finishExport(destination) }

    // A folder for a batch, because there is no one file forty tracks could be. Still a
    // place the user pointed at by hand, and the grant is not persisted.
    val folderExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { folder -> viewModel.finishExportAll(folder) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is LibraryEvent.Open -> onOpenTrack(event.id)
                LibraryEvent.ImportFailed -> snackbarHostState.showSnackbar(importFailed)
                LibraryEvent.ExportFailed -> snackbarHostState.showSnackbar(exportFailed)
                LibraryEvent.Exported -> snackbarHostState.showSnackbar(exported)
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
                        // Same condition as the overflow beside it: there is nothing
                        // to search in an empty library, and no threshold beyond that -
                        // an icon that appears once you own eight tracks is a rule the
                        // user has to discover to trust.
                        if (loaded == null || tracks.isNotEmpty()) {
                            IconButton(onClick = { searching = true }) {
                                Icon(Icons.Default.Search, stringResource(R.string.library_search))
                            }
                        }
                        IconButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Default.Add, stringResource(R.string.library_import))
                        }
                        // Shown while still loading too. Deciding on an empty list would
                        // pop the icon into existence a frame later, during the slide.
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
            // Nothing yet. An empty surface for a frame or two beats telling the user they
            // have no tracks and then taking it back.
            loaded == null -> Unit

            // An empty list under a search is a fact about the search, not the library:
            // "No tracks yet" over forty hidden ones would be plainly wrong.
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
                        color = palette[track.colorIndex % palette.size],
                        selected = track.id in selection,
                        selectionActive = selection.isNotEmpty(),
                        onOpen = {
                            // Opening a hidden track shows it. It used to be drawn as a
                            // one-off that vanished when the sheet closed, which made
                            // "hidden" mean two different things depending on how you got
                            // there - and left the switch saying off about a line that
                            // was plainly on the map.
                            if (!track.visible) viewModel.setVisible(listOf(track.id), true)
                            onOpenTrack(track.id)
                        },
                        onToggleSelected = { viewModel.toggleSelected(track.id) },
                        onToggleVisible = { viewModel.setVisible(listOf(track.id), !track.visible) },
                        onExport = {
                            viewModel.beginExport(track)
                            exporter.launch(exportFileName(track.trackName, track.displayName))
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
            // What is actually being destroyed differs by source, and the difference is
            // the one the user cares about: a recording only exists here.
            deletesFiles = tracks.any { it.id in deleting && it.source == TrackSource.RECORDED },
            count = deleting.size,
            onDismiss = { deleting = emptySet() },
            onConfirm = {
                viewModel.delete(deleting)
                deleting = emptySet()
            },
        )
    }
}

/**
 * The bar that replaces the title while rows are ticked.
 *
 * Select-all is a tri-state checkbox rather than an icon, because there is no icon for it:
 * `material-icons-core` carries no `select_all` or `done_all`, and the nearest thing in it
 * is a bare tick, which in a bar next to a bin reads as "confirm" rather than "everything".
 * Pulling in `material-icons-extended` for one glyph is the trade the dependency note
 * already refuses.
 *
 * A checkbox is the better control anyway, and not as a consolation. It *shows* whether
 * everything is selected, which no icon can, and it toggles - so deselecting all stops
 * being a thing you can only do by leaving selection mode entirely. Indeterminate is the
 * honest state for a partial selection and the one people already know from every mail
 * client.
 *
 * Show and hide are not here at all. Every row already carries a switch with a full touch
 * target, and show-all and hide-all sit in the list's own menu; a third way to do it, only
 * reachable by first long-pressing something, was a row in a menu nobody needed to open.
 * What a selection is actually for is deleting several things at once.
 */
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
            // The selection already exists for deleting several at once; exporting them
            // is the same gesture with a destination instead of a confirmation.
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
    onExport: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val formatters = LocalFormatters.current

    // Built once per row, not once per composition. Three `String.format` calls each, and
    // the first use of a localized `DateTimeFormatter` loads its locale data - which is
    // exactly the kind of work that lands on the frames of an entry animation.
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
                // The swatch is the same hue the map draws this track in, so the two
                // surfaces can be read against each other without a legend.
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
                text = track.trackName?.takeIf(String::isNotBlank) ?: track.displayName,
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
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, stringResource(R.string.library_more))
                        }
                        // Only once it is wanted. Composed unconditionally this was a
                        // transition object and a popup's worth of setup per row, for a
                        // menu almost none of them will ever show.
                        if (menuOpen) DropdownMenu(
                            expanded = true,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.library_rename)) },
                                onClick = {
                                    menuOpen = false
                                    onRename()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.library_export)) },
                                onClick = {
                                    menuOpen = false
                                    onExport()
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = stringResource(R.string.library_delete),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    onDelete()
                                },
                            )
                        }
                    }
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

/** Quotes what was typed back, so a typo is visible without reopening the field. */
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

/**
 * The title replaced by a field, which is where a list searches itself.
 *
 * Not a Material `SearchBar`: that one is a surface that expands over the screen to offer
 * suggestions and recent queries, and there is nothing here to suggest - the whole library
 * is already on screen behind it, filtering as you type.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    // Opened by a tap on the icon, so the keyboard is what was asked for.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    TopAppBar(
        title = {
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text(stringResource(R.string.library_search_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                // Stripped back to just the text: a filled field with its own underline
                // inside an app bar is two containers deep for one line of input.
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

