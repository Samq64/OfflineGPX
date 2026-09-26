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

/**
 * The live recording, docked under the map. Two rows and no more - the route drawing
 * itself above is the interesting part, and a recorder that ate the map would trade the
 * thing you want to look at for numbers you already know.
 */
@Composable
fun RecordingBar(
    state: RecordingState.Active,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onDiscard: () -> Unit,
    /** [description] may be blank - a waypoint with nothing typed is still one. */
    onAddWaypoint: (description: String) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Whatever sits under the bar - the nav bar, a peeked sheet. Padded inside the surface,
     * so the bar's own background reaches the bottom edge and the map can't show through.
     */
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
                    // Blank until the first fix rather than a zero, which would be a
                    // measurement the recorder has not made.
                    text = state.currentSpeedMps?.let(formatters::speed) ?: Formatters.EMPTY,
                    style = MaterialTheme.typography.titleMedium.tabularFigures(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Nothing recorded yet has two causes accuracy tells apart: a cold start
            // settles in seconds, a phone indoors never will.
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
            // The count only, never what any of them say - the bar is a glance, not a log.
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

                // Disabled rather than hidden before the first fix: there is nowhere yet
                // to put a waypoint, but the control staying in place means a hand that
                // already knows where it is doesn't have to go looking for it once there is.
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

/**
 * One button, one field, one word to close it - dropping a waypoint mid-ride has to cost
 * as little attention as possible. Title carries the running count so far rather than a
 * generic one, which is also the count kept visible in [RecordingBar] itself: what's asked
 * for elsewhere is the number, not a list of what's already been noted.
 */
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

/**
 * The one piece of decoration in the app: a pulse tells running and paused apart at a
 * glance, without a word of text.
 */
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
            .background(MaterialTheme.colorScheme.error)
    )
}
