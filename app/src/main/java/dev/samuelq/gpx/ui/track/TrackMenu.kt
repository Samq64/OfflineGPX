package dev.samuelq.gpx.ui.track

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import dev.samuelq.gpx.R

/** [onHide] only where hiding closes something. [trackTitle] names the button in a list of them. */
@Composable
fun TrackMenu(
    onRename: () -> Unit,
    onShare: () -> Unit,
    onHide: (() -> Unit)?,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    trackTitle: String? = null,
    /** On the map only, where the charts to trim against are. */
    onTrim: (() -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }

    @Composable
    fun Item(@StringRes text: Int, action: () -> Unit, error: Boolean = false) = DropdownMenuItem(
        text = {
            Text(
                text = stringResource(text),
                color = if (error) MaterialTheme.colorScheme.error else Color.Unspecified,
            )
        },
        onClick = {
            open = false
            action()
        },
    )

    Box(modifier) {
        IconButton(onClick = { open = true }) {
            Icon(
                painterResource(R.drawable.ic_more_vert),
                trackTitle?.let { stringResource(R.string.track_manage_named, it) }
                    ?: stringResource(R.string.track_manage),
            )
        }
        // Composed only when open; per-row menu setup adds up in a list.
        if (open) {
            DropdownMenu(expanded = true, onDismissRequest = { open = false }) {
                // The map's own first, apart from what the list's menu has too.
                onHide?.let { Item(R.string.track_hide, it) }
                onTrim?.let { Item(R.string.track_trim, it) }
                if (onHide != null || onTrim != null) HorizontalDivider()
                Item(R.string.library_rename, onRename)
                Item(R.string.library_share, onShare)
                HorizontalDivider()
                Item(R.string.library_delete, onDelete, error = true)
            }
        }
    }
}
