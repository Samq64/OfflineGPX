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
 * The three things worth changing, each said in full.
 *
 * Two of these are signal-processing thresholds, which is not a thing a settings screen can
 * assume anyone knows. Both carry a sentence about what moving them actually costs, because
 * a number you can change without knowing what it does is a number you will change once and
 * never understand again.
 *
 * What is deliberately *not* here: the sampling rate, whose two ends are "worse route" and
 * "worse battery"; and the pause-detection threshold, which is about the file rather than
 * this device and would have to re-summarise the whole library every time it moved.
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
    // Held rather than deleted straight from the row: a big archive is exactly the kind of
    // thing a mis-tap in a list shouldn't cost, and re-importing one is a real trip back to
    // wherever the file came from, not a rename undone in a second tap.
    var deletingMap by remember { mutableStateOf<OfflineMap?>(null) }

    // OpenDocument rather than GetContent: this takes a persistable read grant on exactly
    // the file picked, which is all the access a copy needs and less than GetContent hands
    // over. No storage permission is involved either way.
    val importer = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> viewModel.importMap(uri, uri?.let { context.fileNameOf(it) }) }

    val imported = stringResource(R.string.settings_maps_imported)
    val deleted = stringResource(R.string.settings_maps_deleted)
    val unreadable = stringResource(R.string.settings_maps_failed_unreadable)
    val wrongFormat = stringResource(R.string.settings_maps_failed_format)
    val noSpace = stringResource(R.string.settings_maps_failed_space)
    val noBrowser = stringResource(R.string.settings_maps_no_browser)
    val overlaps = stringResource(R.string.settings_maps_failed_overlap)

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
                    SettingsMessage.MapOverlaps -> overlaps
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
                onImport = { importer.launch(PMTILES_MIME_TYPES) },
                onDelete = { deletingMap = it },
                onOpenHelp = {
                    // The app has no INTERNET permission and does not need one to do
                    // this: handing a URL to whatever handles web pages is an intent,
                    // and the browser does the fetching in its own process.
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

            // Committed when the thumb is let go, not while it moves. A write per drag
            // frame would be sixty disk writes a second, and every one of them republishes
            // the settings the whole tree is reading.
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
                onClick = viewModel::resetToDefaults,
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
 * Where maps come from, which is the one thing this screen has to explain rather than
 * offer.
 *
 * There is no download button and there is not going to be one: the app holds no network
 * permission, which is the promise the whole thing is built on. So this says plainly that
 * maps arrive from elsewhere, links out to a page explaining how to get one, and opens the
 * system file picker. The link is a single constant because the right destination is still
 * an open question - nothing here endorses a source, and no map ships with the app.
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

/**
 * One imported map: what it is called, and the two facts that decide whether it is the one
 * you want - how big it is, and how far it zooms.
 *
 * Both come from the archive's own header rather than from the filename, because the
 * filename is whatever the download was called and says nothing about coverage. Zoom is
 * the more useful of the two: a map that stops at z12 is a map with no streets on it, and
 * that is not visible any other way until you are standing outside in the wrong place.
 */
@Composable
private fun MapRow(
    map: OfflineMap,
    onDelete: () -> Unit,
) {
    val detail = stringResource(
        R.string.settings_maps_detail,
        android.text.format.Formatter.formatShortFileSize(LocalContext.current, map.sizeBytes),
        if (map.header.isVector) {
            stringResource(R.string.settings_maps_zoom_to, map.header.maxZoom)
        } else {
            stringResource(R.string.settings_maps_raster)
        },
    )

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
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Only when the archive says so itself. The app fetches nothing and ships no
            // maps, so it has no source of its own to credit and nothing worth guessing at
            // for a file that does not declare one.
            map.attribution?.let { attribution ->
                Text(
                    text = stringResource(R.string.settings_maps_attribution, attribution),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
 * The name the provider gives a document, for naming the copy after it.
 *
 * Null when the provider declines to say, which is normal for some file managers - the
 * store falls back to its own name then rather than failing an import over a label.
 */
private fun Context.fileNameOf(uri: Uri): String? = runCatching {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
}.getOrNull()

/**
 * What the picker will accept.
 *
 * PMTiles has no registered MIME type, so providers hand these over as
 * `application/octet-stream` and nothing else will match. The wildcard is there because a
 * file manager that types it as something else would otherwise be unable to offer the file
 * at all; the import validates the header regardless of what the picker claimed.
 */
private val PMTILES_MIME_TYPES = arrayOf("application/octet-stream", "*/*")

/**
 * Where to go to get a map file.
 *
 * Provisional, and the one line to change when a page written here replaces it. It points
 * at a third-party tool that cuts an area of any size straight to a download - which is
 * the workflow this app needs and which nothing official provides - rather than at
 * documentation the reader would then have to act on.
 *
 * Worth knowing about what it points at: extracts are cut from Protomaps daily builds, so
 * the schema matches the style in [dev.samuelq.gpx.ui.map.MapStyle] and an imported file
 * will render rather than come up blank. It is also one person's instance with per-client
 * rate limits, which is the reason this is a constant and not a promise.
 *
 * Nothing is fetched on the user's behalf either way: this opens a browser through an
 * intent, and the app holds no network permission.
 */
private const val MAP_HELP_URL = "https://pmtiles.samruff.dev/"

// No `steps`: a discrete slider draws a tick per step, and a metre-per-step range of
// ninety-five of them is a dotted line, not a scale. The track is continuous and the
// value is rounded to a whole metre when the thumb is let go - which is the only place
// the roundness was ever visible.

private fun ClosedFloatingPointRange<Double>.toFloatRange(): ClosedFloatingPointRange<Float> =
    start.toFloat()..endInclusive.toFloat()

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 12.dp),
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
