package dev.samuelq.gpx.ui.track

import android.content.Context
import android.content.Intent
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.core.content.FileProvider
import dev.samuelq.gpx.R
import dev.samuelq.gpx.data.gpx.GPX_MIME_TYPE
import dev.samuelq.gpx.data.db.TrackEntity
import dev.samuelq.gpx.data.safeFileName
import dev.samuelq.gpx.data.track.TrackFiles

/** Opens with the name selected, so the first keystroke replaces it. */
@Composable
fun TrackNameDialog(
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
        title = { Text(stringResource(R.string.library_rename)) },
        text = {
            OutlinedTextField(
                value = field,
                onValueChange = { field = it },
                singleLine = true,
                label = { Text(stringResource(R.string.library_rename_label)) },
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

/** Before a track's row arrives; see [TrackEntity.title] for after. */
fun trackTitle(trackName: String?, displayName: String): String =
    trackName?.takeIf(String::isNotBlank) ?: TrackFiles.recordedAt(displayName) ?: displayName

private fun String.dropGpxSuffix(): String =
    if (endsWith(GPX, ignoreCase = true)) dropLast(GPX.length) else this

/** The track's name, made safe as a filename; unnamed, the file it's stored under, whose stamp sorts. */
fun exportFileName(trackName: String?, displayName: String): String =
    safeFileName(trackName?.takeIf(String::isNotBlank) ?: displayName.dropGpxSuffix()).ensureGpxSuffix()

fun String.ensureGpxSuffix(): String =
    if (endsWith(GPX, ignoreCase = true)) this else "$this$GPX"

/** Shares the stored file via a [FileProvider] grant scoped to it. */
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
