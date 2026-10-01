package dev.samuelq.gpx.ui.track

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import dev.samuelq.gpx.R

/** [onHide] only where hiding closes something. [trackTitle] names the button in a list of them. */
@Composable
fun TrackMenu(
    onRename: () -> Unit,
    onShare: () -> Unit,
    onHide: (() -> Unit)?,
    onDelete: () -> Unit,
    trackTitle: String? = null,
    /** On the map only, where the charts to trim against are. */
    onTrim: (() -> Unit)? = null,
    /** Listed when [showSplit], greyed out while null: it splits at the selected point. */
    onSplit: (() -> Unit)? = null,
    showSplit: Boolean = false,
    onDuplicate: (() -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }

    @Composable
    fun Item(@StringRes text: Int, action: () -> Unit, error: Boolean = false, enabled: Boolean = true) = DropdownMenuItem(
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
        enabled = enabled,
    )

    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                Icons.Default.MoreVert,
                trackTitle?.let { stringResource(R.string.track_manage_named, it) }
                    ?: stringResource(R.string.track_manage),
            )
        }
        // Composed only when open; per-row menu setup adds up in a list.
        if (open) DropdownMenu(expanded = true, onDismissRequest = { open = false }) {
            // The map's own first, apart from what the list's menu has too.
            onHide?.let { Item(R.string.track_hide, it) }
            onTrim?.let { Item(R.string.track_trim, it) }
            if (showSplit) Item(R.string.track_split, onSplit ?: {}, enabled = onSplit != null)
            if (onHide != null || onTrim != null || showSplit) HorizontalDivider()
            Item(R.string.library_rename, onRename)
            Item(R.string.library_share, onShare)
            onDuplicate?.let { Item(R.string.library_duplicate, it) }
            HorizontalDivider()
            Item(R.string.library_delete, onDelete, error = true)
        }
    }
}
