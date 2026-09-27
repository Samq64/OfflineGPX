package dev.samuelq.gpx.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.samuelq.gpx.R

@Composable
internal fun EmptyState(
    onImportMap: () -> Unit,
    onImportTrack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        // Opaque, over the canvas's own flat background, and the same colour as every
        // other screen's: with nothing to show, this is a page, not a map.
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.map_empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.map_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onImportMap) {
                Text(stringResource(R.string.map_empty_import_map))
            }
            Button(onClick = onImportTrack) {
                Text(stringResource(R.string.map_empty_import_track))
            }
        }
    }
}

/**
 * The one state with nothing at all on screen: every track hidden, no basemap. Not the
 * first-run card - the user did this on purpose from the list, and already knows what the
 * app is - just a way back that doesn't require remembering the list icon exists.
 */
@Composable
internal fun ShowTracksHint(onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier) {
        Text(stringResource(R.string.map_hidden_hint))
    }
}
