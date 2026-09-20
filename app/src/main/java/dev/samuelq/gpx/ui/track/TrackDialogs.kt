package dev.samuelq.gpx.ui.track

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
import dev.samuelq.gpx.R

/**
 * Names a track, wherever the naming happens.
 *
 * One dialog for the rename in the library and the prompt that follows a recording, because
 * they are the same act: the difference is only what brought the user here, which is what
 * [titleRes] says. Shared mostly so the rule about suffixes lives in one place - see
 * [editableTrackName].
 *
 * Opens focused with the current name selected, so the first keystroke replaces it. The
 * default the app guessed is worth showing - it is often right, and it says what will be
 * kept if the dialog is dismissed - but it is not worth clearing by hand before typing.
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
 * The one confirmation in the app, for the one action nothing can undo.
 *
 * Shown from the library's row menu, its selection bar and the map's sheet, so one track
 * and forty go through the same question wherever it is asked. What is actually destroyed
 * differs by source, and that difference is the part the user cares about: a recording only
 * exists here.
 */
@Composable
fun DeleteTrackDialog(
    /** True when at least one of them is a recording, whose file goes with the row. */
    deletesFiles: Boolean,
    count: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.library_delete_title, count, count)) },
        text = {
            Text(
                stringResource(
                    if (deletesFiles) {
                        R.string.library_delete_body_recorded
                    } else {
                        R.string.library_delete_body_imported
                    }
                )
            )
        },
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

/**
 * What to put in the field for editing.
 *
 * The track's own name if it has one, and otherwise the filename it was imported under -
 * without the extension. `.gpx` is how the file is stored, not what the track is called,
 * and offering it as the starting point for a name invites every track in the library to
 * be called something dot gpx.
 */
fun editableTrackName(trackName: String?, displayName: String): String =
    trackName?.takeIf(String::isNotBlank) ?: displayName.dropGpxSuffix()

/** Case-insensitively, because a file picked off a desktop may well be `.GPX`. */
private fun String.dropGpxSuffix(): String =
    if (endsWith(GPX, ignoreCase = true)) dropLast(GPX.length) else this

/**
 * What to offer the export picker.
 *
 * The track's own name with the suffix put back on, not the name it is filed under here: a
 * recording is stored as a sortable timestamp because nothing reads a list of those, but
 * the file the user is about to put in their own Documents folder should be called what
 * they called the ride.
 */
fun exportFileName(trackName: String?, displayName: String): String =
    editableTrackName(trackName, displayName).ensureGpxSuffix()

/** The suffix belongs to the filename, and is put back at the one moment the two meet. */
fun String.ensureGpxSuffix(): String =
    if (endsWith(GPX, ignoreCase = true)) this else "$this$GPX"

private const val GPX = ".gpx"
