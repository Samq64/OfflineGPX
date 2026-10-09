package dev.samuelq.gpx.ui.track

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
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
import androidx.compose.ui.unit.dp
import dev.samuelq.gpx.R
import dev.samuelq.gpx.ui.DialogTitle

/** Opens with the name selected, so the first keystroke replaces it. */
@Composable
fun TrackNameDialog(
    initialName: String,
    initialCategory: String,
    /** Offered as the category is typed. */
    categories: List<String>,
    onDismiss: () -> Unit,
    /** Null for what's unchanged. */
    onConfirm: (name: String?, category: String?) -> Unit,
) {
    var field by remember(initialName) {
        mutableStateOf(
            TextFieldValue(initialName, selection = TextRange(0, initialName.length)),
        )
    }
    var category by remember(initialCategory) { mutableStateOf(initialCategory) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(focusRequester) { focusRequester.requestFocus() }

    val confirm = {
        onConfirm(field.text.takeIf { it != initialName }, category.takeIf { it != initialCategory })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { DialogTitle(stringResource(R.string.action_rename)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = field,
                    onValueChange = { field = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.library_rename_label)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )
                CategoryField(
                    initial = initialCategory,
                    onChange = { category = it },
                    suggestions = categories,
                    onDone = confirm,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = confirm) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Free text, with the categories in use that contain what's typed offered below it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryField(
    initial: String,
    onChange: (String) -> Unit,
    suggestions: List<String>,
    modifier: Modifier = Modifier,
    /** Null leaves the keyboard's action as Next. */
    onDone: (() -> Unit)? = null,
) {
    var field by remember(initial) { mutableStateOf(TextFieldValue(initial, selection = TextRange(initial.length))) }
    var expanded by remember { mutableStateOf(false) }
    val typed = field.text.trim()
    val matches = remember(typed, suggestions) {
        suggestions.filter { !it.equals(typed, ignoreCase = true) && it.contains(typed, ignoreCase = true) }
    }
    val open = expanded && matches.isNotEmpty()

    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = field,
            onValueChange = {
                field = it
                expanded = true
                onChange(it.text)
            },
            singleLine = true,
            label = { Text(stringResource(R.string.track_category)) },
            keyboardOptions = KeyboardOptions(imeAction = if (onDone != null) ImeAction.Done else ImeAction.Next),
            keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { expanded = false }) {
            matches.forEach { suggestion ->
                DropdownMenuItem(
                    text = { Text(suggestion) },
                    onClick = {
                        field = TextFieldValue(suggestion, selection = TextRange(suggestion.length))
                        expanded = false
                        onChange(suggestion)
                    },
                )
            }
        }
    }
}
