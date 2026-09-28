package dev.samuelq.gpx.ui.record

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.samuelq.gpx.R
import dev.samuelq.gpx.data.record.RecordingState
import dev.samuelq.gpx.ui.format.Formatters
import dev.samuelq.gpx.ui.format.LocalFormatters
import dev.samuelq.gpx.ui.format.tabularFigures
import dev.samuelq.gpx.ui.theme.recordingColor

/** Live recording controls, kept small so the map stays visible. */
@Composable
fun RecordingBar(
    state: RecordingState.Active,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onDiscard: () -> Unit,
    /** [description] may be blank. */
    onAddWaypoint: (description: String) -> Unit,
    modifier: Modifier = Modifier,
    /** Padded inside the surface so its background reaches the bottom edge. */
    bottomInset: Dp = 0.dp,
) {
    val formatters = LocalFormatters.current
    var addingWaypoint by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = bottomInset)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RecordingDot(paused = state.paused)
                Spacer(Modifier.width(12.dp))

                Text(
                    text = formatters.distance(state.distanceMeters),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.width(20.dp))
                Text(
                    text = Formatters.duration(state.totalSeconds),
                    style = MaterialTheme.typography.titleMedium.tabularFigures(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(20.dp))
                Text(
                    // Blank, not zero, until the first fix.
                    text = state.currentSpeedMps?.let(formatters::speed) ?: Formatters.EMPTY,
                    style = MaterialTheme.typography.titleMedium.tabularFigures(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Tells a cold start (settles soon) from being indoors (never will).
            val poorSignal = state.accuracyMeters?.takeIf { it > state.accuracyLimitMeters }

            val status = when {
                state.paused -> stringResource(R.string.record_notification_paused)
                state.lastPoint != null -> pluralStringResource(
                    R.plurals.record_points_logged,
                    state.pointCount,
                    Formatters.count(state.pointCount),
                )

                poorSignal != null -> stringResource(
                    R.string.record_weak_signal,
                    formatters.meters(poorSignal),
                )

                else -> stringResource(R.string.record_waiting_for_fix)
            }
            val waypointCount = if (state.waypoints.isNotEmpty()) {
                pluralStringResource(
                    R.plurals.record_waypoints_logged,
                    state.waypoints.size,
                    state.waypoints.size,
                )
            } else {
                null
            }

            Text(
                text = listOfNotNull(status, waypointCount).joinToString("  ·  "),
                style = MaterialTheme.typography.labelMedium,
                color = if (poorSignal != null && state.lastPoint == null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onStop) { Text(stringResource(R.string.record_stop)) }

                if (state.paused) {
                    IconButton(onClick = onResume) {
                        Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.record_resume))
                    }
                } else {
                    IconButton(onClick = onPause) {
                        Icon(
                            painterResource(R.drawable.ic_pause),
                            contentDescription = stringResource(R.string.record_pause),
                        )
                    }
                }

                // Disabled, not hidden, before the first fix so the layout doesn't shift.
                IconButton(onClick = { addingWaypoint = true }, enabled = state.lastPoint != null) {
                    Icon(Icons.Default.Place, contentDescription = stringResource(R.string.record_add_waypoint))
                }

                Spacer(Modifier.weight(1f))

                TextButton(onClick = onDiscard) {
                    Text(
                        text = stringResource(R.string.record_discard),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    if (addingWaypoint) {
        WaypointDialog(
            number = state.waypoints.size + 1,
            onDismiss = { addingWaypoint = false },
            onConfirm = {
                addingWaypoint = false
                onAddWaypoint(it)
            },
        )
    }
}

@Composable
private fun WaypointDialog(
    number: Int,
    onDismiss: () -> Unit,
    onConfirm: (description: String) -> Unit,
) {
    var description by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(focusRequester) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.record_waypoint_title, number)) },
        text = {
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text(stringResource(R.string.record_waypoint_label)) },
                modifier = Modifier.focusRequester(focusRequester),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(description) }) {
                Text(stringResource(R.string.action_done))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun RecordingDot(paused: Boolean) {
    val transition = rememberInfiniteTransition(label = "recording")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )

    Box(
        Modifier
            .size(10.dp)
            .alpha(if (paused) 0.35f else alpha)
            .clip(CircleShape)
            .background(recordingColor())
    )
}

@Composable
fun DiscardRecordingDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.record_discard_title)) },
        text = { Text(stringResource(R.string.record_discard_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.record_discard_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
