package dev.samuelq.gpx.ui.record

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.samuelq.gpx.R
import dev.samuelq.gpx.data.record.AbandonedRecording
import dev.samuelq.gpx.data.record.RecordingRecovery
import dev.samuelq.gpx.data.record.RecordingState
import dev.samuelq.gpx.data.track.TrackLabel
import dev.samuelq.gpx.ui.format.LocalFormatters
import dev.samuelq.gpx.ui.track.CategoryField
import dev.samuelq.gpx.ui.track.StatRow

/** The categories in use, and the one a new recording gets unless changed: the last one's. */
internal class CategoryChoice(val all: List<String>, val default: String)

/** Stop's question. Dismissing it keeps recording. */
@Composable
internal fun StopRecordingDialog(
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
internal fun RecoveredRecordingDialog(
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
                    val formatters = LocalFormatters.current
                    val date = formatters.date(stats.startedAt)
                    val time = formatters.time(stats.startedAt)
                    val body = stringResource(R.string.record_recovered_body, date, time)
                    Text(
                        buildAnnotatedString {
                            append(body)
                            // Found, not split around: translations may move them.
                            for (part in listOf(date, time)) {
                                val at = body.indexOf(part)
                                if (at >= 0) addStyle(SpanStyle(fontWeight = FontWeight.Bold), at, at + part.length)
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    // Here, where a stopped recording has just shown why it matters.
                    BatteryOptimisationHint(Modifier.padding(top = 8.dp))
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
 * Discard, Cancel and Save, with Save at the end. A null [onDismiss] leaves only Save and
 * Discard to close it.
 */
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

    AlertDialog(
        onDismissRequest = { onDismiss?.invoke() },
        properties = DialogProperties(
            dismissOnBackPress = onDismiss != null,
            dismissOnClickOutside = onDismiss != null,
        ),
        title = { Text(title, Modifier.semantics { heading() }) },
        text = {
            // Scrolls when the keyboard or large text leaves too little room.
            Column(Modifier.verticalScroll(rememberScrollState())) {
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
        },
        dismissButton = {
            TextButton(onClick = { onDiscard(label()) }) {
                Text(stringResource(R.string.record_discard), color = MaterialTheme.colorScheme.error)
            }
            if (onDismiss != null) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
        confirmButton = {
            // The distance says why to a sighted user; a screen reader hears only "disabled".
            val tooShort = stringResource(R.string.record_too_short)
            TextButton(
                onClick = { onSave(label()) },
                enabled = canSave,
                modifier = Modifier.semantics { if (!canSave) stateDescription = tooShort },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
    )
}
