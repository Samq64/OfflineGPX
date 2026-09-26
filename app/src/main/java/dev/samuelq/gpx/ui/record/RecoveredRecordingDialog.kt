package dev.samuelq.gpx.ui.record

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.samuelq.gpx.R
import dev.samuelq.gpx.data.track.AbandonedRecording
import dev.samuelq.gpx.ui.format.Formatters
import dev.samuelq.gpx.ui.format.LocalFormatters

/**
 * Asks what to do with a recording a crash left unsaved. Only its buttons close it: the
 * ride is on disk either way, and a tap outside is not a decision about it.
 */
@Composable
fun RecoveredRecordingDialog(
    recording: AbandonedRecording,
    onSave: (name: String) -> Unit,
    onDiscard: () -> Unit,
) {
    val formatters = LocalFormatters.current
    // Not focused on open, unlike the other naming dialogs: this one appears at launch,
    // and a keyboard nobody asked for would hide the map behind it.
    var name by remember(recording) { mutableStateOf(recording.defaultName) }
    val stats = recording.profile.stats

    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.record_recovered_title)) },
        text = {
            Column {
                Text(
                    stringResource(
                        R.string.record_recovered_body,
                        Formatters.dateTime(stats.startedAt),
                        formatters.distance(stats.distanceMeters),
                    )
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.library_rename_label)) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDiscard) {
                Text(
                    text = stringResource(R.string.record_discard_confirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
    )
}
