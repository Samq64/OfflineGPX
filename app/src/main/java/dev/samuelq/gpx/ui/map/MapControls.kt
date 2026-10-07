package dev.samuelq.gpx.ui.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.samuelq.gpx.R
import dev.samuelq.gpx.core.model.Waypoint
import dev.samuelq.gpx.ui.PointTooltip
import dev.samuelq.gpx.ui.format.LocalFormatters
import kotlin.math.roundToInt

/**
 * A tapped waypoint's distance into its track, time, and note, beside its pin tip at [tipAt].
 * [distanceMeters] is null off a track.
 */
@Composable
internal fun WaypointTooltip(waypoint: Waypoint, distanceMeters: Double?, tipAt: () -> Offset) {
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
        // Announced as it opens, since a screen reader action can open it with focus elsewhere.
        Column(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
            val formatters = LocalFormatters.current
            val name = waypoint.name?.takeIf(String::isNotBlank)
            val distance = distanceMeters?.let(formatters::distance)
            val time = waypoint.point.time?.let { formatters.time(it) }
            val detail = when {
                distance != null && time != null -> stringResource(R.string.waypoint_distance_time, distance, time)
                else -> distance ?: time
            }
            // The placeholder dash only when there's nothing else to show.
            if (detail != null || name == null) {
                Text(
                    text = detail ?: formatters.time(null),
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalContentColor.current.copy(alpha = 0.7f),
                )
            }
            name?.let {
                Text(text = it, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Starts a recording; once running, it lives in the sheet. */
@Composable
internal fun RecordButton(onStart: () -> Unit) {
    val label = stringResource(R.string.record_start)
    ExtendedFloatingActionButton(
        onClick = onStart,
        // The label goes on the icon: this overload hides its text from accessibility.
        icon = { Icon(painterResource(R.drawable.ic_play_arrow), contentDescription = label) },
        text = { Text(label) },
    )
}

/**
 * Filled while [following], with the found-position icon once there's a fix; [waiting] until
 * then. Says what a tap does: follow, or stop showing the position, which a recording can't.
 */
@Composable
internal fun LocationButton(following: Boolean, waiting: Boolean, recording: Boolean, onClick: () -> Unit) {
    val label = stringResource(
        when {
            !following -> R.string.map_follow_location
            recording -> R.string.map_following_location
            else -> R.string.map_stop_location
        },
    )
    val waitingLabel = stringResource(R.string.record_waiting_for_fix)
    val icon = if (following && !waiting) R.drawable.ic_my_location else R.drawable.ic_location_searching
    Surface(
        onClick = onClick,
        modifier = Modifier.size(48.dp).semantics {
            contentDescription = label
            if (waiting) stateDescription = waitingLabel
        },
        shape = CircleShape,
        color = with(MaterialTheme.colorScheme) { if (following) primaryContainer else surfaceContainerHigh },
        shadowElevation = 2.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painterResource(icon),
                contentDescription = null,
            )
        }
    }
}
