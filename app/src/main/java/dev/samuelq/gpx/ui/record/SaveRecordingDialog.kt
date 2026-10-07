package dev.samuelq.gpx.ui.record

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.samuelq.gpx.R
import dev.samuelq.gpx.data.record.AbandonedRecording
import dev.samuelq.gpx.data.record.RecordingRecovery
import dev.samuelq.gpx.data.record.RecordingState
import dev.samuelq.gpx.data.track.TrackLabel
import dev.samuelq.gpx.data.track.TrackRepository
import dev.samuelq.gpx.ui.format.LocalFormatters
import dev.samuelq.gpx.ui.track.CategoryField
import dev.samuelq.gpx.ui.track.StatRow

/** The categories in use, and the one a new recording gets unless changed: the last one's. */
class CategoryChoice(val all: List<String>, val default: String)

@Composable
fun rememberCategoryChoice(tracks: TrackRepository): CategoryChoice {
    val all by tracks.categories.collectAsStateWithLifecycle(emptyList())
    val last by tracks.lastRecordingCategory.collectAsStateWithLifecycle(null)
    return remember(all, last) { CategoryChoice(all, last.orEmpty()) }
}

/** Stop's question. Dismissing it keeps recording. */
@Composable
fun StopRecordingDialog(
    state: RecordingState.Active,
    categories: CategoryChoice,
    onSave: (TrackLabel) -> Unit,
    /** What's in the fields, for an undo to save it as. */
    onDiscard: (TrackLabel) -> Unit,
    onDismiss: () -> Unit,
) {
    SaveRecordingDialog(
        title = stringResource(R.string.record_stop_title),
        // The distance says why Save is off, so no separate "too short" message.
        summary = { StatRow(recordingStats(state.distanceMeters, state.totalSeconds)) },
        canSave = RecordingRecovery.isSaveable(state.distanceMeters),
        categories = categories,
        onSave = onSave,
        onDiscard = onDiscard,
        onDismiss = onDismiss,
    )
}

/** For a recording a crash left unsaved. Only its buttons close it. */
@Composable
fun RecoveredRecordingDialog(
    recording: AbandonedRecording,
    categories: CategoryChoice,
    onSave: (TrackLabel) -> Unit,
    onDiscard: (TrackLabel) -> Unit,
) {
    val stats = recording.profile.stats
    // Keyed, so the next recording on offer doesn't inherit this one's edits.
    key(recording) {
        SaveRecordingDialog(
            title = stringResource(R.string.record_recovered_title),
            // Laid out like Stop's, plus when it started and why it's being asked about.
            summary = {
                Column {
                    StatRow(recordingStats(stats.distanceMeters, stats.totalDurationSeconds))
                    val date = LocalFormatters.current.dateTime(stats.startedAt)
                    val body = stringResource(R.string.record_recovered_body, date)
                    Text(
                        buildAnnotatedString {
                            append(body)
                            // Found, not split around: translations may move the date.
                            val at = body.indexOf(date)
                            if (at >= 0) addStyle(SpanStyle(fontWeight = FontWeight.Bold), at, at + date.length)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            canSave = true,
            categories = categories,
            onSave = onSave,
            onDiscard = onDiscard,
            onDismiss = null,
        )
    }
}

/**
 * Discard alone at the start, Cancel and Save at the end, as far from Discard as they get.
 * A null [onDismiss] leaves only Save and Discard to close it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SaveRecordingDialog(
    title: String,
    summary: @Composable () -> Unit,
    canSave: Boolean,
    categories: CategoryChoice,
    onSave: (TrackLabel) -> Unit,
    onDiscard: (TrackLabel) -> Unit,
    onDismiss: (() -> Unit)?,
) {
    // Optional, so not auto-focused: a keyboard would hide the map.
    var name by remember { mutableStateOf("") }
    // Typed, else the default, which may arrive after the dialog opens.
    var category by remember { mutableStateOf<String?>(null) }
    val label = { TrackLabel(name, category ?: categories.default) }

    BasicAlertDialog(
        onDismissRequest = { onDismiss?.invoke() },
        properties = DialogProperties(
            dismissOnBackPress = onDismiss != null,
            dismissOnClickOutside = onDismiss != null,
        ),
    ) {
        Surface(
            // A custom dialog isn't announced by name otherwise.
            modifier = Modifier.semantics { paneTitle = title },
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            Column(Modifier.padding(bottom = 12.dp)) {
                Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                    Spacer(Modifier.height(16.dp))
                    summary()
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.library_rename_label)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    CategoryField(
                        initial = categories.default,
                        onChange = { category = it },
                        suggestions = categories.all,
                    )
                }
                Spacer(Modifier.height(12.dp))
                // Less inset than the content: the buttons' own padding lines their text up with it.
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    TextButton(onClick = { onDiscard(label()) }) {
                        Text(
                            text = stringResource(R.string.record_discard),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (onDismiss != null) {
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                    }
                    // The distance says why to a sighted user; a screen reader hears only "disabled".
                    val tooShort = stringResource(R.string.record_too_short)
                    TextButton(
                        onClick = { onSave(label()) },
                        enabled = canSave,
                        modifier = Modifier.semantics { if (!canSave) stateDescription = tooShort },
                    ) {
                        Text(stringResource(R.string.action_save))
                    }
                }
            }
        }
    }
}
