package dev.samuelq.gpx.ui.settings

import android.content.Context
import android.content.Intent
import android.icu.text.ListFormatter
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.samuelq.gpx.BuildConfig
import dev.samuelq.gpx.R
import dev.samuelq.gpx.core.model.UnitSystem
import dev.samuelq.gpx.data.map.OfflineMap
import dev.samuelq.gpx.data.settings.Settings
import dev.samuelq.gpx.ui.format.LocalFormatters
import dev.samuelq.gpx.ui.format.tabularFigures
import dev.samuelq.gpx.ui.isLargeText
import dev.samuelq.gpx.ui.readFirst
import dev.samuelq.gpx.ui.makeWay
import dev.samuelq.gpx.ui.rememberSnackbars
import kotlin.math.roundToInt

private val ScreenPadding = 20.dp

/**
 * Pause detection is deliberately not a setting: changing it would re-summarise the whole
 * library.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    /** Opens the map file picker on arrival. */
    importMapOnOpen: Boolean = false,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val maps by viewModel.maps.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val overlapping by viewModel.overlapping.collectAsStateWithLifecycle()
    val formatters = LocalFormatters.current
    val context = LocalContext.current
    val snackbars = rememberSnackbars()
    val snackbarHostState = snackbars.host

    val importer = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
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
        ActivityResultContracts.CreateDocument("application/octet-stream")
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
            undoLabel = resources.getString(R.string.action_undo),
            onUndo = { viewModel.undoDeleteMap(map) },
            onCommit = { viewModel.commitDeleteMap(map) },
        )
    }

    // The browser fetches it, so no INTERNET permission is needed.
    fun openUrl(url: String) {
        val opened = runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        }.isSuccess
        if (!opened) viewModel.reportNoBrowser()
    }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbarHostState.makeWay()
            snackbarHostState.showSnackbar(
                resources.getString(
                    when (message) {
                        SettingsMessage.MapImported -> R.string.settings_maps_imported
                        SettingsMessage.MapUnreadable -> R.string.settings_maps_failed_unreadable
                        SettingsMessage.MapWrongFormat -> R.string.settings_maps_failed_format
                        SettingsMessage.MapNoSpace -> R.string.settings_maps_failed_space
                        SettingsMessage.MapExported -> R.string.settings_maps_exported
                        SettingsMessage.MapExportFailed -> R.string.settings_maps_export_failed
                        SettingsMessage.NoBrowser -> R.string.settings_maps_no_browser
                    }
                )
            )
        }
    }

    overlapping?.let { overlaps ->
        MergeMapsDialog(
            newMap = overlaps.staged.displayName,
            existing = overlaps.existing.map { it.displayName },
            onMerge = viewModel::mergeOverlapping,
            onCancel = viewModel::cancelImport,
        )
    }

    // Rechecked on resume, since the user changes it in system settings.
    var batteryRestricted by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        batteryRestricted = !context.isIgnoringBatteryOptimizations()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState, Modifier.readFirst()) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title), Modifier.semantics { heading() }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
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
            SectionHeading(stringResource(R.string.settings_section_display))

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
                                    }
                                )
                            )
                        }
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeading(stringResource(R.string.settings_section_recording))

            // Committed on release, not per drag frame, to avoid a disk write each frame.
            // No `steps`: ~95 discrete steps would draw a dotted track.
            var accuracy by remember(settings.maxAccuracyMeters) {
                mutableFloatStateOf(settings.maxAccuracyMeters.toFloat())
            }
            val accuracyTitle = stringResource(R.string.settings_accuracy)
            // The slider alone reads as a bare percentage, and the dot not at all.
            val accuracyState = stringResource(
                R.string.settings_accuracy_state,
                formatters.meters(accuracy.toDouble()),
                formatters.meters(Settings.Defaults.maxAccuracyMeters),
            )
            Setting(
                title = accuracyTitle,
                explanation = stringResource(R.string.settings_accuracy_explanation),
                value = formatters.meters(accuracy.toDouble()),
            ) {
                MarkedSlider(
                    modifier = Modifier.semantics {
                        contentDescription = accuracyTitle
                        stateDescription = accuracyState
                    },
                    value = accuracy,
                    onValueChange = { accuracy = it },
                    onValueChangeFinished = {
                        viewModel.setMaxAccuracy(accuracy.roundToInt().toDouble())
                    },
                    valueRange = Settings.ACCURACY_RANGE.toFloatRange(),
                    marker = Settings.Defaults.maxAccuracyMeters.toFloat(),
                )
            }

            if (batteryRestricted) {
                Setting(
                    title = stringResource(R.string.settings_battery),
                    explanation = stringResource(R.string.settings_battery_explanation),
                ) {
                    TextButton(
                        onClick = { context.openBatterySettings() },
                        // Aligns the label, not the ripple, with the text above.
                        modifier = Modifier.offset(x = (-12).dp),
                    ) {
                        Text(stringResource(R.string.settings_battery_open))
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeading(stringResource(R.string.settings_section_about))

            VersionLine(onOpenCommit = { openUrl(COMMIT_URL + it) })
            Text(
                text = stringResource(R.string.settings_about_libraries),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .padding(horizontal = ScreenPadding, vertical = 4.dp)
                    .semantics { heading() },
            )
            LIBRARIES.forEach { library ->
                LibraryLine(library, onClick = { openUrl(library.url) })
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MergeMapsDialog(
    newMap: String,
    existing: List<String>,
    onMerge: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(pluralStringResource(R.plurals.settings_maps_merge_title, existing.size))
        },
        text = {
            Text(
                pluralStringResource(
                    R.plurals.settings_maps_merge_body,
                    existing.size,
                    newMap,
                    ListFormatter.getInstance().format(existing.map { "“$it”" }),
                )
            )
        },
        confirmButton = {
            TextButton(onClick = onMerge) { Text(stringResource(R.string.settings_maps_merge)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** A [Slider] with a dot at [marker], drawn in the colours of a step tick. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MarkedSlider(
    modifier: Modifier = Modifier,
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    marker: Float,
) {
    val colors = SliderDefaults.colors()
    val fraction = (marker - valueRange.start) / (valueRange.endInclusive - valueRange.start)
    val markerColor = if (value >= marker) colors.activeTickColor else colors.inactiveTickColor
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        colors = colors,
        modifier = modifier,
        track = { state ->
            // The track spans the thumb's travel, so a fraction of its width lines up.
            Box {
                SliderDefaults.Track(sliderState = state, colors = colors)
                Canvas(Modifier.matchParentSize()) {
                    drawCircle(
                        color = markerColor,
                        radius = MarkerRadius.toPx(),
                        center = Offset(size.width * fraction, center.y),
                    )
                }
            }
        },
    )
}

private val MarkerRadius = 2.dp

private fun Context.isIgnoringBatteryOptimizations(): Boolean =
    getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

/**
 * The system list rather than a direct exemption request, which needs a permission Play
 * restricts. App info is the fallback where an OEM drops the list.
 */
private fun Context.openBatterySettings() {
    val opened = runCatching {
        startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }.isSuccess
    if (!opened) {
        runCatching {
            startActivity(
                Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())
            )
        }
    }
}

private class Library(val name: String, val licence: String, val url: String)

private val LIBRARIES = listOf(
    Library("VTM", "LGPL-3.0", "https://github.com/mapsforge/vtm"),
    Library("JTS", "EDL-1.0", "https://github.com/locationtech/jts"),
    Library("AndroidX", "Apache-2.0", "https://developer.android.com/jetpack/androidx"),
    Library("Kotlin", "Apache-2.0", "https://kotlinlang.org"),
)

private const val COMMIT_URL = "https://github.com/Samq64/offline-gpx-android/commit/"

@Composable
private fun linkStyles() = TextLinkStyles(
    SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)
)

/** The commit hash, when the build has one, links to its source. */
@Composable
private fun VersionLine(onOpenCommit: (String) -> Unit) {
    val hash = BuildConfig.GIT_HASH
    val version = BuildConfig.VERSION_NAME + if (hash.isEmpty()) "" else " ($hash)"
    val text = stringResource(R.string.settings_about_version, stringResource(R.string.app_name), version)
    val link = linkStyles()
    Text(
        text = buildAnnotatedString {
            append(text)
            val at = if (hash.isEmpty()) -1 else text.indexOf(hash)
            if (at >= 0) addLink(LinkAnnotation.Clickable(hash, link) { onOpenCommit(hash) }, at, at + hash.length)
        },
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp),
    )
}

/** The name links to the project; the licence follows in brackets. */
@Composable
private fun LibraryLine(library: Library, onClick: () -> Unit) {
    val link = linkStyles()
    Text(
        text = buildAnnotatedString {
            withLink(LinkAnnotation.Clickable(library.name, link) { onClick() }) {
                append(library.name)
            }
            append(" (${library.licence})")
        },
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 2.dp),
    )
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
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.padding(horizontal = ScreenPadding - 12.dp),
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
                modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 8.dp),
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
                modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp),
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
private fun MapRow(
    map: OfflineMap,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val size = android.text.format.Formatter.formatShortFileSize(LocalContext.current, map.sizeBytes)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding, vertical = 10.dp),
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
                    Icons.Default.MoreVert,
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

private fun ClosedFloatingPointRange<Double>.toFloatRange(): ClosedFloatingPointRange<Float> =
    start.toFloat()..endInclusive.toFloat()

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(horizontal = ScreenPadding, vertical = 12.dp)
            .semantics { heading() },
    )
}

@Composable
private fun Setting(
    title: String,
    explanation: String,
    value: String? = null,
    control: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 8.dp)) {
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
