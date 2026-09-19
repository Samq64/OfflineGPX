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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.samuelq.gpx.R
import dev.samuelq.gpx.data.record.RecordingState
import dev.samuelq.gpx.ui.format.Formatters
import dev.samuelq.gpx.ui.format.LocalFormatters
import dev.samuelq.gpx.ui.format.tabularFigures

/**
 * The live recording, docked under the map.
 *
 * Two rows and no more: the route drawing itself on the canvas above is the interesting
 * part, and a recorder that ate the map to show three numbers would be trading the thing
 * you want to look at for the thing you already know. Everything here is read at arm's
 * length, so the numbers stay large even though the bar is short.
 */
@Composable
fun RecordingBar(
    state: RecordingState.Active,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
    /** False when something below the bar is already clearing the system bars. */
    applyNavigationBarPadding: Boolean = true,
) {
    val formatters = LocalFormatters.current

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (applyNavigationBarPadding) Modifier.navigationBarsPadding() else Modifier)
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
                    text = Formatters.duration(state.movingSeconds),
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

            // Nothing recorded yet has two very different causes, and the accuracy is the
            // only thing that tells them apart: a cold start settles in seconds, a phone
            // indoors never will. Saying which saves the user waiting for the wrong one.
            val poorSignal = state.accuracyMeters?.takeIf { it > state.accuracyLimitMeters }

            Text(
                text = when {
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
                },
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
                if (state.paused) {
                    Button(onClick = onResume) { Text(stringResource(R.string.record_resume)) }
                } else {
                    OutlinedButton(onClick = onPause) { Text(stringResource(R.string.record_pause)) }
                }
                Button(onClick = onStop) { Text(stringResource(R.string.record_stop)) }

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
}

/**
 * The one piece of decoration in the app, and it earns its place: a recorder that is
 * running and one that is paused have to be told apart at a glance, and a pulse says it
 * without a word of text.
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
