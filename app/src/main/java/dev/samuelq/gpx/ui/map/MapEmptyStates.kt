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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.samuelq.gpx.R

@Composable
internal fun EmptyState(
    onImportMap: () -> Unit,
    onImportTrack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    EmptyPage(
        title = stringResource(R.string.map_empty_title),
        body = stringResource(R.string.map_empty_body),
        modifier = modifier,
    ) {
        OutlinedButton(onClick = onImportMap) {
            Text(stringResource(R.string.settings_maps_import))
        }
        Button(onClick = onImportTrack) {
            Text(stringResource(R.string.library_import))
        }
    }
}

/** In place of framing tracks so far apart they'd all be specks. */
@Composable
internal fun TooFarApartState(onOpenList: () -> Unit, modifier: Modifier = Modifier) {
    EmptyPage(
        title = stringResource(R.string.map_spread_title),
        body = stringResource(R.string.map_spread_body),
        // Over the map, so it mustn't take a drag meant for nothing.
        modifier = modifier.pointerInput(Unit) {},
    ) {
        Button(onClick = onOpenList) {
            Text(stringResource(R.string.map_spread_action))
        }
    }
}

@Composable
private fun EmptyPage(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    buttons: @Composable () -> Unit,
) {
    Column(
        // Opaque and page-coloured: with nothing to show, this is a page, not a map.
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { buttons() }
    }
}

/** Every track hidden on purpose and no basemap: just a way back to the list. */
@Composable
internal fun ShowTracksHint(onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier) {
        Text(stringResource(R.string.map_hidden_hint))
    }
}
