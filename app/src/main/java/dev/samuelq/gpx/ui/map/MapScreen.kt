package dev.samuelq.gpx.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.samuelq.gpx.R
import dev.samuelq.gpx.data.record.RecordingState
import dev.samuelq.gpx.ui.theme.routePalette
import dev.samuelq.gpx.ui.track.GeoBounds
import dev.samuelq.gpx.ui.track.RouteCanvas
import dev.samuelq.gpx.ui.track.RouteLayer
import dev.samuelq.gpx.ui.track.routePathOf

/**
 * The app's home: every track the user has chosen to show, overlaid.
 *
 * Which tracks those are is curated in the list, not here - dumping the whole library onto
 * one canvas is noise, and a screen that decides for you is worse than one you point at
 * what you want. Tapping a route opens it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    onOpenTrack: (Long) -> Unit,
    onOpenList: () -> Unit,
    onRecord: () -> Unit,
    viewModel: MapViewModel = viewModel(factory = MapViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recording by viewModel.recording.collectAsStateWithLifecycle()
    val palette = routePalette()

    // One projection across every drawn track, so two rides of the same road land on top
    // of one another instead of each filling the canvas on its own. The list arrives
    // oldest-interaction first, so painting in order puts the last-touched route on top.
    val layers = remember(state.entities, state.geometry, palette) {
        val drawable = state.entities.mapNotNull { entity ->
            state.geometry[entity.id]?.let { entity to it }
        }
        val bounds = GeoBounds.union(
            drawable.mapNotNull { (_, track) -> GeoBounds.of(track.profile.points) }
        ) ?: return@remember emptyList()

        drawable.mapNotNull { (entity, track) ->
            routePathOf(track.profile.points, track.profile.segmentStartIndices, bounds)
                ?.let {
                    // Colour comes from the track, never its position: a hue that changed
                    // when you tapped something would be worse than any stacking order.
                    RouteLayer(entity.id, it, palette[entity.colorIndex % palette.size])
                }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (state.entities.isEmpty()) {
                            stringResource(R.string.app_name)
                        } else {
                            pluralStringResource(
                                R.plurals.map_shown,
                                state.entities.size,
                                state.entities.size,
                            )
                        }
                    )
                },
                actions = {
                    IconButton(onClick = onOpenList) {
                        Icon(
                            Icons.AutoMirrored.Filled.List,
                            stringResource(R.string.map_open_list),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onRecord,
                icon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                text = {
                    Text(
                        stringResource(
                            if (recording is RecordingState.Active) {
                                R.string.map_recording
                            } else {
                                R.string.record_start
                            }
                        )
                    )
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                layers.isNotEmpty() -> RouteCanvas(
                    layers = layers,
                    contentDescription = stringResource(R.string.map_description),
                    onSelect = { trackId, _ -> onOpenTrack(trackId) },
                    modifier = Modifier.fillMaxSize(),
                )

                state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    LinearProgressIndicator(Modifier.padding(32.dp))
                }

                else -> EmptyState(
                    hasHiddenTracks = state.totalCount > 0,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun EmptyState(
    hasHiddenTracks: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.map_empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(
                if (hasHiddenTracks) R.string.map_empty_hidden else R.string.map_empty_body
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
