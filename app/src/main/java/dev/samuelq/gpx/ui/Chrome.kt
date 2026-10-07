package dev.samuelq.gpx.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import dev.samuelq.gpx.R

/** A top bar's way back; [label] says where to when it's not the previous screen. */
@Composable
internal fun BackButton(onClick: () -> Unit, label: String = stringResource(R.string.action_back)) {
    IconButton(onClick = onClick) { Icon(painterResource(R.drawable.ic_arrow_back), label) }
}

/** A heading, as Material's dialogs give their title only a pane title. */
@Composable
internal fun DialogTitle(text: String) {
    Text(text, Modifier.semantics { heading() })
}
