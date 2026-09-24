package dev.samuelq.gpx.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.samuelq.gpx.R
import dev.samuelq.gpx.core.model.UnitSystem
import dev.samuelq.gpx.data.map.OfflineMap
import dev.samuelq.gpx.data.settings.Settings
import dev.samuelq.gpx.ui.format.LocalFormatters
import dev.samuelq.gpx.ui.format.tabularFigures
import kotlin.math.roundToInt

private val ScreenPadding = 20.dp

/**
 * The three things worth changing, each said in full - two are signal-processing
 * thresholds, so both carry a sentence about what moving them costs. Deliberately absent:
 * sampling rate (a fixed "worse route" vs "worse battery" trade) and the pause-detection
 * threshold (a file property that would re-summarise the whole library on change).
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
    val formatters = LocalFormatters.current
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    // Held rather than deleted straight from the row: a mis-tap on a big map costs a
    // real trip back to wherever the file came from.
    var deletingMap by remember { mutableStateOf<OfflineMap?>(null) }

    // OpenDocument rather than GetContent: it grants read access to exactly the file
    // picked, which is all a copy needs. No storage permission is involved either way.
    val importer = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> viewModel.importMap(uri, uri?.let { context.fileNameOf(it) }) }

    val imported = stringResource(R.string.settings_maps_imported)
    val deleted = stringResource(R.string.settings_maps_deleted)
    val unreadable = stringResource(R.string.settings_maps_failed_unreadable)
    val wrongFormat = stringResource(R.string.settings_maps_failed_format)
    val noSpace = stringResource(R.string.settings_maps_failed_space)
    val noBrowser = stringResource(R.string.settings_maps_no_browser)

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(
                when (message) {
                    SettingsMessage.MapImported -> imported
                    SettingsMessage.MapDeleted -> deleted
                    SettingsMessage.MapUnreadable -> unreadable
                    SettingsMessage.MapWrongFormat -> wrongFormat
                    SettingsMessage.MapNoSpace -> noSpace
                    SettingsMessage.NoBrowser -> noBrowser
                }
            )
        }
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
                onDelete = { deletingMap = it },
                onOpenHelp = {
                    // Needs no INTERNET permission: handing a URL to whatever handles web
                    // pages is an intent, and the browser fetches it in its own process.
                    val opened = runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, MAP_HELP_URL.toUri())
                        )
                    }.isSuccess
                    if (!opened) viewModel.reportNoBrowser()
                },
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

            // Committed when the thumb is let go, not while it moves - a write per drag
            // frame would be sixty disk writes a second.
            var accuracy by remember(settings.maxAccuracyMeters) {
                mutableFloatStateOf(settings.maxAccuracyMeters.toFloat())
            }
            Setting(
                title = stringResource(R.string.settings_accuracy),
                explanation = stringResource(R.string.settings_accuracy_explanation),
                value = formatters.meters(accuracy.toDouble()),
            ) {
                Slider(
                    value = accuracy,
                    onValueChange = { accuracy = it },
                    onValueChangeFinished = {
                        viewModel.setMaxAccuracy(accuracy.roundToInt().toDouble())
                    },
                    valueRange = Settings.ACCURACY_RANGE.toFloatRange(),
                )
            }

            var displacement by remember(settings.minDisplacementMeters) {
                mutableFloatStateOf(settings.minDisplacementMeters.toFloat())
            }
            Setting(
                title = stringResource(R.string.settings_displacement),
                explanation = stringResource(R.string.settings_displacement_explanation),
                value = if (displacement < 0.5f) {
                    stringResource(R.string.settings_displacement_off)
                } else {
                    formatters.meters(displacement.toDouble())
                },
            ) {
                Slider(
                    value = displacement,
                    onValueChange = { displacement = it },
                    onValueChangeFinished = {
                        viewModel.setMinDisplacement(displacement.roundToInt().toDouble())
                    },
                    valueRange = Settings.DISPLACEMENT_RANGE.toFloatRange(),
                )
            }

            // Said once, here, rather than on each: they are read when a recording
            // starts, so changing one mid-ride would otherwise look broken.
            Text(
                text = stringResource(R.string.settings_recording_applies_next),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = ScreenPadding),
            )

            Spacer(Modifier.height(16.dp))

            TextButton(
                onClick = viewModel::resetRecording,
                modifier = Modifier.padding(horizontal = ScreenPadding - 12.dp),
            ) {
                Text(stringResource(R.string.settings_reset))
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    deletingMap?.let { map ->
        AlertDialog(
            onDismissRequest = { deletingMap = null },
            title = { Text(stringResource(R.string.settings_maps_delete_title)) },
            text = { Text(stringResource(R.string.settings_maps_delete_body, map.displayName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteMap(map)
                        deletingMap = null
                    },
                ) {
                    Text(
                        text = stringResource(R.string.library_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingMap = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/**
 * Where maps come from - explained, not offered. No download button, since the app holds
 * no network permission: this says plainly that maps arrive from elsewhere, links to a
 * page on how, and opens the file picker.
 */
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

/** One imported map: its name, who the data is from, and its size. */
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
            // Only when the file says so itself - the app has no source of its own to
            // credit.
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
 * The name the provider gives a document, for naming the copy after it. Null when the
 * provider declines to say; the store then falls back to its own name.
 */
private fun Context.fileNameOf(uri: Uri): String? = runCatching {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
}.getOrNull()

/**
 * What the picker will accept. A mapsforge map file has no registered MIME type, so
 * providers hand it over as `application/octet-stream`; the wildcard covers a file manager
 * that types it as something else. Import validates the header regardless of what the
 * picker claimed.
 */
private val MAP_MIME_TYPES = arrayOf("application/octet-stream", "*/*")

/**
 * Where to go to get a map file. Provisional - a tool that cuts an area straight to a
 * download, since every other source of .map files ships whole countries. Extracts are cut
 * from download.mapsforge.org, whose tag vocabulary [dev.samuelq.gpx.ui.map.MapRenderTheme]
 * expects. Opened through a browser intent; nothing is fetched on the app's behalf.
 */
private const val MAP_HELP_URL = "https://mapcut.samruff.dev/"

// No `steps`: a discrete slider over ~95 metre-steps draws a dotted line, not a scale. The
// track is continuous; the value rounds to a whole metre only when the thumb is released.

private fun ClosedFloatingPointRange<Double>.toFloatRange(): ClosedFloatingPointRange<Float> =
    start.toFloat()..endInclusive.toFloat()

@Composable
private fun SectionHeading(text: String) {
    // Material 3's list subheader: title small in primary. A title role, not a label -
    // labels are for text inside components - and a heading to TalkBack, so it can skip
    // between sections.
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(horizontal = ScreenPadding, vertical = 12.dp)
            .semantics { heading() },
    )
}

/**
 * A setting: what it is, what it costs, and the control. The current value sits on the
 * title's line rather than under the control, so it can be read without following a thumb.
 */
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
