package dev.samuelq.gpx.ui.settings

import android.icu.text.ListFormatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.samuelq.gpx.BuildConfig
import dev.samuelq.gpx.R
import dev.samuelq.gpx.core.model.UnitSystem
import dev.samuelq.gpx.data.map.OfflineMap
import dev.samuelq.gpx.data.settings.Settings
import dev.samuelq.gpx.ui.BackButton
import dev.samuelq.gpx.ui.BarInsets
import dev.samuelq.gpx.ui.DialogTitle
import dev.samuelq.gpx.ui.EdgePadding
import dev.samuelq.gpx.ui.format.LocalFormatters
import dev.samuelq.gpx.ui.format.tabularFigures
import dev.samuelq.gpx.ui.isLargeText
import dev.samuelq.gpx.ui.makeWay
import dev.samuelq.gpx.ui.readFirst
import dev.samuelq.gpx.ui.readableWidth
import dev.samuelq.gpx.ui.screenSnackbars
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Pause detection is deliberately not a setting: changing it would re-summarise the whole
 * library.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenLibraries: () -> Unit,
    /** Opens the map file picker on arrival. */
    importMapOnOpen: Boolean = false,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val maps by viewModel.maps.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val formatters = LocalFormatters.current
    val context = LocalContext.current
    val snackbars = screenSnackbars()
    val snackbarHostState = snackbars.host

    val importer = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> viewModel.importMap(uri) }
    // Once per visit: saved, so returning from the picker or rotating doesn't reopen it.
    var importAsked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (importMapOnOpen && !importAsked) {
            importAsked = true
            importer.launch(MAP_MIME_TYPES)
        }
    }

    // Not stringResource: a long-lived collector would keep the old locale.
    val resources = LocalResources.current

    // By filename, saved, so the answer still finds its map after a recreation.
    var exporting by rememberSaveable { mutableStateOf<String?>(null) }
    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val map = maps.firstOrNull { it.file.name == exporting }
        exporting = null
        map?.let { viewModel.exportMap(it, uri) }
    }
    fun exportMap(map: OfflineMap) {
        exporting = map.file.name
        exporter.launch(map.file.name)
    }

    // Undoable rather than confirmed: a mis-tap would cost re-fetching the file.
    fun deleteMap(map: OfflineMap) {
        viewModel.deleteMap(map)
        snackbars.offerUndo(
            context = context,
            message = resources.getString(R.string.deleted_named, map.displayName),
            onUndo = { viewModel.undoDeleteMap(map) },
            onCommit = { viewModel.commitDeleteMap(map) },
        )
    }

    fun openUrl(url: String) {
        if (!context.openUrl(url)) viewModel.reportNoBrowser()
    }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message -> launch { snackbars.say(resources.getString(message.text)) } }
    }

    Scaffold(
        // As in the library: the cutout sits beside the content in landscape.
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState, Modifier.readFirst()) },
        topBar = {
            TopAppBar(
                windowInsets = BarInsets,
                title = { Text(stringResource(R.string.settings_title), Modifier.semantics { heading() }) },
                navigationIcon = {
                    BackButton(onBack)
                },
            )
        },
    ) { padding ->
        val layoutDirection = LocalLayoutDirection.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                // Inside the scroll, so the margins of a large screen still scroll it.
                .readableWidth()
                // Inside the scroll, so the end scrolls clear of the navigation bar from behind it.
                .padding(
                    start = padding.calculateStartPadding(layoutDirection),
                    end = padding.calculateEndPadding(layoutDirection),
                    bottom = padding.calculateBottomPadding(),
                )
                .padding(vertical = 8.dp),
        ) {
            SectionHeading(stringResource(R.string.settings_section_maps))

            MapsSection(
                maps = maps,
                importing = importing,
                onImport = { importer.launch(MAP_MIME_TYPES) },
                onExport = ::exportMap,
                onDelete = ::deleteMap,
                onOpenHelp = { openUrl(MAP_HELP_URL) },
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Setting(
                title = stringResource(R.string.settings_units),
                explanation = stringResource(R.string.settings_units_explanation),
            ) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    UnitSystem.entries.forEachIndexed { index, system ->
                        SegmentedButton(
                            selected = settings.units == system,
                            onClick = { viewModel.setUnits(system) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = UnitSystem.entries.size,
                            ),
                        ) {
                            Text(
                                stringResource(
                                    when (system) {
                                        UnitSystem.METRIC -> R.string.settings_units_metric
                                        UnitSystem.IMPERIAL -> R.string.settings_units_imperial
                                    },
                                ),
                            )
                        }
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeading(stringResource(R.string.settings_section_about))

            AppHeader(onOpenCommit = { openUrl(COMMIT_URL + it) })
            LinkButtons(
                listOf(
                    stringResource(R.string.settings_about_source) to REPO_URL,
                    stringResource(R.string.settings_about_licence) to LICENCE_URL,
                    stringResource(R.string.settings_about_issues) to ISSUES_URL,
                ),
                onClick = ::openUrl,
            )
            PageRow(stringResource(R.string.settings_about_libraries), onClick = onOpenLibraries)
        }
    }
}

private const val REPO_URL = "https://github.com/Samq64/OTrace"
private const val COMMIT_URL = "$REPO_URL/commits/"
private const val LICENCE_URL = "$REPO_URL/blob/master/LICENSE"
private const val ISSUES_URL = "$REPO_URL/issues"

@Composable
private fun linkStyles() = TextLinkStyles(
    SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline),
)

/** The commit hash, when the build has one, links to the history up to it. */
@Composable
private fun AppHeader(onOpenCommit: (String) -> Unit) {
    val described = BuildConfig.GIT_HASH
    val hash = described.removeSuffix(DIRTY)
    val version = stringResource(R.string.settings_about_version, BuildConfig.VERSION_NAME) +
        if (described.isEmpty()) "" else " · $described"
    val link = linkStyles()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = EdgePadding, vertical = 8.dp),
    ) {
        AppIcon()
        Spacer(Modifier.width(16.dp))
        Column {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium)
            Text(
                text = buildAnnotatedString {
                    append(version)
                    if (hash.isNotEmpty()) {
                        val at = version.length - described.length
                        addLink(LinkAnnotation.Clickable(hash, link) { onOpenCommit(hash) }, at, at + hash.length)
                    }
                },
                style = MaterialTheme.typography.bodyMedium.tabularFigures(),
            )
            Text(
                text = stringResource(R.string.settings_about_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Compose can't paint an adaptive icon, so its layers are drawn here: the foreground's
 * 72 of 108 dp safe zone fills the shape.
 */
@Composable
private fun AppIcon() {
    Box(
        modifier = Modifier
            .size(IconSize)
            .clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        for (layer in listOf(R.drawable.ic_launcher_background, R.drawable.ic_launcher_foreground)) {
            Image(
                painter = painterResource(layer),
                contentDescription = null,
                modifier = Modifier.requiredSize(IconSize * 108 / 72),
            )
        }
    }
}

private val IconSize = 56.dp

/** Marks a build with uncommitted changes; not part of the commit link. */
private const val DIRTY = "-dirty"

/** Buttons rather than inline links, for full-size touch targets. */
@Composable
private fun LinkButtons(links: List<Pair<String, String>>, onClick: (String) -> Unit) {
    FlowRow(
        modifier = Modifier.padding(horizontal = EdgePadding - 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        links.forEach { (label, url) ->
            TextButton(onClick = { onClick(url) }) { Text(label) }
        }
    }
}

/** Opens a sub-page; the chevron marks it as one. */
@Composable
private fun PageRow(title: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = EdgePadding),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** No download button: the app has no network permission, so it links to how instead. */
@Composable
private fun MapsSection(
    maps: List<OfflineMap>,
    importing: Boolean,
    onImport: () -> Unit,
    onExport: (OfflineMap) -> Unit,
    onDelete: (OfflineMap) -> Unit,
    onOpenHelp: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.settings_maps_explanation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = EdgePadding),
        )

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.padding(horizontal = EdgePadding - 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(onClick = onImport, enabled = !importing) {
                Text(stringResource(R.string.settings_maps_import))
            }
            TextButton(onClick = onOpenHelp) {
                Text(stringResource(R.string.settings_maps_where))
            }
        }

        if (importing) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = EdgePadding, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(R.string.settings_maps_importing),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }

        if (maps.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_maps_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = EdgePadding, vertical = 8.dp),
            )
        } else {
            maps.forEach { map ->
                MapRow(map = map, onExport = { onExport(map) }, onDelete = { onDelete(map) })
            }
        }
    }
}

/** A row, not ListItem: that insets 16dp against this screen's 20. */
@Composable
private fun MapRow(map: OfflineMap, onExport: () -> Unit, onDelete: () -> Unit) {
    val size = android.text.format.Formatter.formatShortFileSize(LocalContext.current, map.sizeBytes)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = EdgePadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // One stop for name, credit and size.
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
            Text(
                text = map.displayName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = if (isLargeText()) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            map.attribution?.let { attribution ->
                Text(
                    text = attribution,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = size,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Box {
            var open by remember { mutableStateOf(false) }
            IconButton(onClick = { open = true }) {
                Icon(
                    painterResource(R.drawable.ic_more_vert),
                    contentDescription = stringResource(R.string.settings_maps_manage_named, map.displayName),
                )
            }
            DropdownMenu(open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.settings_maps_export)) },
                    onClick = {
                        open = false
                        onExport()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.library_delete), color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        open = false
                        onDelete()
                    },
                )
            }
        }
    }
}

/**
 * `.map` has no registered MIME type; the wildcard covers providers that mistype it. Import
 * validates the header regardless.
 */
private val MAP_MIME_TYPES = arrayOf("application/octet-stream", "*/*")

/** The published v5 files, by region down to states; the render theme expects their tags. */
private const val MAP_HELP_URL = "https://download.mapsforge.org/maps/v5/"

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(horizontal = EdgePadding, vertical = 12.dp)
            .semantics { heading() },
    )
}

@Composable
private fun Setting(title: String, explanation: String, value: String? = null, control: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = EdgePadding, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (value != null) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium.tabularFigures(),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Text(
            text = explanation,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        control()
    }
}
