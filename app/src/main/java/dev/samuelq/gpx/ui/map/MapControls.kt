package dev.samuelq.gpx.ui.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.samuelq.gpx.R
import dev.samuelq.gpx.core.model.Waypoint
import dev.samuelq.gpx.data.record.RecordingState
import dev.samuelq.gpx.ui.PointTooltip
import dev.samuelq.gpx.ui.format.Formatters
import dev.samuelq.gpx.ui.record.RecordingBar
import kotlin.math.roundToInt

/** A tapped waypoint's time and note, beside its pin. [tipAt] is where the pin's tip is. */
@Composable
internal fun WaypointTooltip(waypoint: Waypoint, tipAt: () -> Offset) {
    val density = LocalDensity.current
    val headRadius = with(density) { WaypointPinHeadRadius.toPx() }
    val height = with(density) { (WaypointPinHeadRadius + PIN_TIP_LENGTH_DP.dp).toPx() }
    PointTooltip(
        anchorAt = {
            val tip = tipAt()
            IntOffset((tip.x - headRadius).roundToInt(), (tip.y - height).roundToInt())
        },
        anchorSize = DpSize(WaypointPinHeadRadius * 2, WaypointPinHeadRadius + PIN_TIP_LENGTH_DP.dp),
    ) {
        Column {
            Text(
                text = Formatters.time(waypoint.point.time),
                style = MaterialTheme.typography.labelMedium,
                color = LocalContentColor.current.copy(alpha = 0.7f),
            )
            waypoint.description?.takeIf(String::isNotBlank)?.let {
                Text(text = it, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * The record button, or the live recording's bar once one is running.
 *
 * Still no reset button. A real map has a whole world to be lost in rather than a unit
 * square to pinch back out of - worth adding as a "frame everything" control, but currently
 * a missing feature, not a choice.
 */
@Composable
internal fun RecordControls(
    recording: RecordingState,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onDiscard: () -> Unit,
    onAddWaypoint: (String) -> Unit,
    bottomInset: Dp,
) {
    when (recording) {
        RecordingState.Idle -> Box(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            ExtendedFloatingActionButton(
                onClick = onStart,
                icon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                text = { Text(stringResource(R.string.record_start)) },
            )
        }
        is RecordingState.Active -> RecordingBar(
            state = recording,
            onPause = onPause,
            onResume = onResume,
            onStop = onStop,
            onDiscard = onDiscard,
            onAddWaypoint = onAddWaypoint,
            bottomInset = bottomInset,
        )
    }
}
