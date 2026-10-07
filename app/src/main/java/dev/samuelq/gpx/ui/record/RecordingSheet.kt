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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.samuelq.gpx.R
import dev.samuelq.gpx.core.analysis.TrackProfile
import dev.samuelq.gpx.data.record.RecordingState
import dev.samuelq.gpx.data.record.RecordingStatus
import dev.samuelq.gpx.ui.EdgePadding
import dev.samuelq.gpx.ui.format.Formatters
import dev.samuelq.gpx.ui.format.LocalFormatters
import dev.samuelq.gpx.ui.format.spokenDuration
import dev.samuelq.gpx.ui.theme.recordingColor
import dev.samuelq.gpx.ui.track.ProfileSheet
import dev.samuelq.gpx.ui.track.Stat
import dev.samuelq.gpx.ui.track.StatRow
import dev.samuelq.gpx.ui.track.distanceAndElapsed

/** The live recording in the sheet: status, numbers and controls in the peek, charts below. */
@Composable
fun RecordingSheet(
    state: RecordingState.Active,
    /** Null until the recording has moved; the sheet shows empty charts meanwhile. */
    profile: TrackProfile?,
    maxHeight: Dp,
    selectedIndex: Int?,
    onSelectedIndexChange: (Int?) -> Unit,
    useTimeAxis: Boolean,
    onAxisChange: (Boolean) -> Unit,
    onPeekHeightChange: (Dp) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    /** [name] may be blank. */
    onAddWaypoint: (name: String) -> Unit,
) {
    ProfileSheet(
        profile = profile,
        // One recording per sheet: a zoom survives the profile growing.
        viewKey = Unit,
        maxHeight = maxHeight,
        // The map's line can run ahead of a stale profile.
        selectedIndex = selectedIndex?.takeIf { it < (profile?.points?.size ?: 0) },
        onSelectedIndexChange = onSelectedIndexChange,
        useTimeAxis = useTimeAxis,
        onAxisChange = onAxisChange,
        onPeekHeightChange = onPeekHeightChange,
        pointCount = state.pointCount,
    ) {
        RecordingHeader(state, onPause, onResume, onStop, onAddWaypoint)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecordingHeader(
    state: RecordingState.Active,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onAddWaypoint: (name: String) -> Unit,
) {
    val formatters = LocalFormatters.current
    var addingWaypoint by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = EdgePadding, end = EdgePadding, top = 4.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Tells a cold start (settles soon) from being indoors (never will).
        val poorSignal = state.accuracyMeters?.takeIf { it > state.accuracyLimitMeters }

        val waiting = state.status == RecordingStatus.WAITING
        val status = when (state.status) {
            RecordingStatus.PAUSED -> stringResource(R.string.record_notification_paused)
            RecordingStatus.LOCATION_OFF -> stringResource(R.string.record_location_is_off)
            RecordingStatus.RECORDING -> stringResource(R.string.record_notification_active)
            RecordingStatus.WAITING ->
                poorSignal
                    ?.let { stringResource(R.string.record_weak_signal, formatters.meters(it)) }
                    ?: stringResource(R.string.record_waiting_for_fix)
        }
        // Announced on change, so without the accuracy, which changes with every fix.
        val spokenStatus = if (poorSignal != null && waiting) {
            stringResource(R.string.record_weak_signal_spoken)
        } else {
            status
        }
        val waypointCount = state.waypoints.size.takeIf { it > 0 }
            ?.let { pluralStringResource(R.plurals.record_waypoints_logged, it, it) }

        // Like a track's title: what's happening, then the numbers, then controls.
        Row(verticalAlignment = Alignment.CenterVertically) {
            RecordingDot(paused = state.paused)
            Spacer(Modifier.width(8.dp))
            Text(
                text = listOfNotNull(status, waypointCount).joinToString(Formatters.SEPARATOR),
                modifier = Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = listOfNotNull(spokenStatus, waypointCount).joinToString(", ")
                },
                style = MaterialTheme.typography.labelLarge,
                color = if ((poorSignal != null && waiting) || state.status == RecordingStatus.LOCATION_OFF) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        val speedLabel = stringResource(R.string.chart_speed)
        StatRow(
            distanceAndElapsed(state.distanceMeters, state.totalSeconds) + Stat(
                speedLabel,
                // Blank, not zero, until the first fix.
                state.currentSpeedMps?.let(formatters::speed) ?: Formatters.EMPTY,
            ),
        )

        // Weighted outlined < tonal < filled. Wraps, not clips, at large font scales.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Apart from pause and stop: it marks the ride rather than controlling it.
            // Disabled, not hidden, before the first fix so the layout doesn't shift.
            OutlinedButton(onClick = { addingWaypoint = true }, enabled = state.lastPoint != null) {
                Text(stringResource(R.string.record_add_waypoint))
            }

            Spacer(Modifier.weight(1f))

            // Icon only, so flipping between the two can't change its width.
            FilledTonalIconButton(onClick = if (state.paused) onResume else onPause) {
                Icon(
                    painterResource(if (state.paused) R.drawable.ic_play_arrow else R.drawable.ic_pause),
                    contentDescription = stringResource(
                        if (state.paused) R.string.record_resume else R.string.record_pause,
                    ),
                )
            }

            Button(onClick = onStop) { Text(stringResource(R.string.record_stop)) }
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
private fun WaypointDialog(number: Int, onDismiss: () -> Unit, onConfirm: (name: String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(focusRequester) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.record_waypoint_title, number)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.record_waypoint_label)) },
                modifier = Modifier.focusRequester(focusRequester),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }) {
                Text(stringResource(R.string.action_done))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Pulses while recording, unless animations are off. */
@Composable
private fun RecordingDot(paused: Boolean) {
    // Compose's own scale, which follows the setting; at zero an infinite pulse would stop dim.
    var animate by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        val scale = coroutineContext[MotionDurationScale] ?: return@LaunchedEffect
        snapshotFlow { scale.scaleFactor > 0f }.collect { animate = it }
    }
    val transition = rememberInfiniteTransition(label = "recording")
    val pulse = transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )

    Box(
        Modifier
            .size(10.dp)
            // Read at draw time, so the pulse doesn't recompose every frame.
            .graphicsLayer {
                alpha = if (paused) {
                    0.35f
                } else if (animate) {
                    pulse.value
                } else {
                    1f
                }
            }
            .clip(CircleShape)
            .background(recordingColor()),
    )
}
