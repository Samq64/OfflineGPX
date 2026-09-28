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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.samuelq.gpx.R
import dev.samuelq.gpx.data.record.AbandonedRecording
import dev.samuelq.gpx.data.record.RecordingRecovery
import dev.samuelq.gpx.data.record.RecordingState
import dev.samuelq.gpx.data.track.defaultTrackName
import dev.samuelq.gpx.ui.format.Formatters
import dev.samuelq.gpx.ui.track.StatRow
import java.time.Instant

/** Stop's question. Dismissing it keeps recording. */
@Composable
fun StopRecordingDialog(
    state: RecordingState.Active,
    onSave: (name: String) -> Unit,
    /** The name in the field, for an undo to save it as. */
    onDiscard: (name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // Once, so the name doesn't change under the user as the ride goes on.
    val defaultName = remember {
        val seconds = state.totalSeconds
        defaultTrackName(
            context,
            startedAt = Instant.now().minusMillis((seconds * 1000).toLong()),
            averageSpeedMps = if (seconds > 0) state.distanceMeters / seconds else 0.0,
        )
    }
    SaveRecordingDialog(
        title = stringResource(R.string.record_stop_title),
        // The distance says why Save is off, so no separate "too short" message.
        summary = { StatRow(recordingStats(state.distanceMeters, state.totalSeconds)) },
        defaultName = defaultName,
        canSave = RecordingRecovery.isSaveable(state.distanceMeters),
        onSave = onSave,
        onDiscard = onDiscard,
        onDismiss = onDismiss,
    )
}

/** For a recording a crash left unsaved. Only its buttons close it. */
@Composable
fun RecoveredRecordingDialog(
    recording: AbandonedRecording,
    onSave: (name: String) -> Unit,
    onDiscard: (name: String) -> Unit,
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
                    val date = Formatters.dateTime(stats.startedAt)
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
            defaultName = recording.defaultName,
            canSave = true,
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
    defaultName: String,
    canSave: Boolean,
    onSave: (name: String) -> Unit,
    onDiscard: (name: String) -> Unit,
    onDismiss: (() -> Unit)?,
) {
    // Not auto-focused: a keyboard would hide the map, and the default is usually kept.
    var name by remember { mutableStateOf(defaultName) }

    BasicAlertDialog(
        onDismissRequest = { onDismiss?.invoke() },
        properties = DialogProperties(
            dismissOnBackPress = onDismiss != null,
            dismissOnClickOutside = onDismiss != null,
        ),
    ) {
        Surface(
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            Column(Modifier.padding(bottom = 12.dp)) {
                Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall)
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
                }
                Spacer(Modifier.height(12.dp))
                // Less inset than the content: the buttons' own padding lines their text up with it.
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    TextButton(onClick = { onDiscard(name) }) {
                        Text(
                            text = stringResource(R.string.record_discard),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (onDismiss != null) {
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                    }
                    TextButton(onClick = { onSave(name) }, enabled = canSave) {
                        Text(stringResource(R.string.action_save))
                    }
                }
            }
        }
    }
}
