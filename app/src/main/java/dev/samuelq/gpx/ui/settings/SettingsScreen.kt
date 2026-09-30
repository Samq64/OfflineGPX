package dev.samuelq.gpx.ui.settings

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
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
import dev.samuelq.gpx.ui.showUndo
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private val ScreenPadding = 20.dp

/**
 * Pause detection is deliberately not a setting: changing it would re-summarise the whole
 * library.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val maps by viewModel.maps.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val overlapping by viewModel.overlapping.collectAsStateWithLifecycle()
    val formatters = LocalFormatters.current
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val importer = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> viewModel.importMap(uri) }

    val imported = stringResource(R.string.settings_maps_imported)
    val deleted = stringResource(R.string.settings_maps_deleted)
    val undo = stringResource(R.string.action_undo)

    // Undoable rather than confirmed: a mis-tap would cost re-fetching the file.
    fun deleteMap(map: OfflineMap) {
        viewModel.deleteMap(map)
        scope.launch {
            snackbarHostState.showUndo(
                message = deleted,
                undoLabel = undo,
                onUndo = { viewModel.undoDeleteMap(map) },
                onCommit = { viewModel.commitDeleteMap(map) },
            )
        }
    }
    val unreadable = stringResource(R.string.settings_maps_failed_unreadable)
    val wrongFormat = stringResource(R.string.settings_maps_failed_format)
    val noSpace = stringResource(R.string.settings_maps_failed_space)
    val noBrowser = stringResource(R.string.settings_maps_no_browser)

    // The browser fetches it, so no INTERNET permission is needed.
    fun openUrl(url: String) {
        val opened = runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        }.isSuccess
        if (!opened) viewModel.reportNoBrowser()
    }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(
                when (message) {
                    SettingsMessage.MapImported -> imported
                    SettingsMessage.MapUnreadable -> unreadable
                    SettingsMessage.MapWrongFormat -> wrongFormat
                    SettingsMessage.MapNoSpace -> noSpace
                    SettingsMessage.NoBrowser -> noBrowser
                }
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
            Setting(
                title = stringResource(R.string.settings_accuracy),
                explanation = stringResource(R.string.settings_accuracy_explanation),
                value = formatters.meters(accuracy.toDouble()),
            ) {
                MarkedSlider(
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

            Text(
                text = stringResource(R.string.settings_about_version, VERSION),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp),
            )
            LIBRARIES.forEach { library ->
                LibraryRow(library, onClick = { openUrl(library.url) })
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
                    existing.joinToString { "“$it”" },
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

private val VERSION = BuildConfig.VERSION_NAME +
    BuildConfig.GIT_HASH.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty()

@Composable
private fun LibraryRow(library: Library, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = ScreenPadding),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = library.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = library.licence,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** No download button: the app has no network permission, so it links to how instead. */
@Composable
private fun MapsSection(
    maps: List<OfflineMap>,
    importing: Boolean,
    onImport: () -> Unit,
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
                MapRow(map = map, onDelete = { onDelete(map) })
            }
        }
    }
}

@Composable
private fun MapRow(
    map: OfflineMap,
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
        Column(Modifier.weight(1f)) {
            Text(
                text = map.displayName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
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

        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.settings_maps_delete),
            )
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
