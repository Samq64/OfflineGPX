package dev.samuelq.gpx.ui.track

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.core.content.FileProvider
import dev.samuelq.gpx.R
import dev.samuelq.gpx.data.gpx.GPX_MIME_TYPE
import dev.samuelq.gpx.data.track.TrackFiles

/**
 * Names a track, wherever the naming happens. One dialog for library rename and the
 * post-recording prompt - the same act, differing only in [titleRes]. Opens focused with
 * the current name selected, so the first keystroke replaces it.
 */
@Composable
fun TrackNameDialog(
    @StringRes titleRes: Int,
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var field by remember(initialName) {
        mutableStateOf(
            TextFieldValue(initialName, selection = TextRange(0, initialName.length))
        )
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(focusRequester) { focusRequester.requestFocus() }

    val confirm = { onConfirm(field.text) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            OutlinedTextField(
                value = field,
                onValueChange = { field = it },
                singleLine = true,
                label = { Text(stringResource(R.string.library_rename_label)) },
                // Done rather than a newline: the field holds one line, and reaching for
                // a button after typing a name is a step the keyboard can absorb.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { confirm() }),
                modifier = Modifier.focusRequester(focusRequester),
            )
        },
        confirmButton = {
            TextButton(onClick = confirm) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * Confirms before a track (or forty) is gone for good - shown from the library's row menu,
 * selection bar, and the map's sheet, so one and forty go through the same question.
 */
@Composable
fun DeleteTrackDialog(
    count: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.library_delete_title, count, count)) },
        text = { Text(stringResource(R.string.library_delete_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.library_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** What a track is called on screen: its own name, or the file it arrived as. */
fun trackTitle(trackName: String?, displayName: String): String =
    trackName?.takeIf(String::isNotBlank) ?: displayName

/**
 * What to put in the field for editing: the track's own name, or the imported filename
 * without its extension - `.gpx` is how the file is stored, not what the track is called.
 */
fun editableTrackName(trackName: String?, displayName: String): String =
    trackTitle(trackName, displayName.dropGpxSuffix())

/** Case-insensitively, because a file picked off a desktop may well be `.GPX`. */
private fun String.dropGpxSuffix(): String =
    if (endsWith(GPX, ignoreCase = true)) dropLast(GPX.length) else this

/**
 * What to offer the export picker: the track's own name with the suffix restored, not the
 * sortable-timestamp filename it's stored under.
 */
fun exportFileName(trackName: String?, displayName: String): String =
    editableTrackName(trackName, displayName).ensureGpxSuffix()

/** The suffix belongs to the filename, and is put back at the one moment the two meet. */
fun String.ensureGpxSuffix(): String =
    if (endsWith(GPX, ignoreCase = true)) this else "$this$GPX"

/**
 * A chooser Intent for a track's own GPX file - straight off disk, through a [FileProvider]
 * grant scoped to that one file, since app-private storage isn't otherwise readable by
 * another app.
 */
fun shareTrackIntent(context: Context, location: String, trackName: String?, displayName: String): Intent {
    val uri = FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", TrackFiles.file(context, location),
    )
    val send = Intent(Intent.ACTION_SEND).apply {
        type = GPX_MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, exportFileName(trackName, displayName))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return Intent.createChooser(send, null)
}

private const val GPX = ".gpx"
