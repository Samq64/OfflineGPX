package dev.samuelq.gpx.ui.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.samuelq.gpx.R
import dev.samuelq.gpx.core.model.Waypoint
import dev.samuelq.gpx.ui.PointTooltip
import dev.samuelq.gpx.ui.format.Formatters
import dev.samuelq.gpx.ui.format.LocalFormatters
import kotlin.math.roundToInt

/** A tapped waypoint's time and note, beside its pin tip at [tipAt]. */
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
        // Announced as it opens, since a screen reader action can open it with focus elsewhere.
        Column(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
            Text(
                text = LocalFormatters.current.time(waypoint.point.time),
                style = MaterialTheme.typography.labelMedium,
                color = LocalContentColor.current.copy(alpha = 0.7f),
            )
            waypoint.description?.takeIf(String::isNotBlank)?.let {
                Text(text = it, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Starts a recording; once running, it lives in the sheet. */
@Composable
internal fun RecordButton(onStart: () -> Unit) {
    ExtendedFloatingActionButton(
        onClick = onStart,
        icon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
        text = { Text(stringResource(R.string.record_start)) },
    )
}

/** Toggles the position dot; [waiting] until its first fix. Styled like the zoom controls. */
@Composable
internal fun LocationButton(shown: Boolean, waiting: Boolean, onToggle: (Boolean) -> Unit) {
    val label = stringResource(R.string.map_my_location)
    val waitingLabel = stringResource(R.string.record_waiting_for_fix)
    Surface(
        checked = shown,
        onCheckedChange = onToggle,
        modifier = Modifier.size(48.dp).semantics {
            contentDescription = label
            if (waiting) stateDescription = waitingLabel
        },
        shape = CircleShape,
        color = if (shown) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 2.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painterResource(if (shown && !waiting) R.drawable.ic_my_location else R.drawable.ic_location_searching),
                contentDescription = null,
            )
        }
    }
}

/** Zoom and fit as one control, for those who can't pinch. */
@Composable
internal fun MapZoomControls(controller: MapController, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 2.dp,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = { controller.zoomIn() }) {
                Icon(Icons.Default.Add, stringResource(R.string.map_zoom_in))
            }
            HorizontalDivider(Modifier.width(24.dp))
            IconButton(onClick = { controller.zoomOut() }) {
                Icon(painterResource(R.drawable.ic_remove), stringResource(R.string.map_zoom_out))
            }
            HorizontalDivider(Modifier.width(24.dp))
            IconButton(onClick = { controller.showAllTracks() }) {
                Icon(painterResource(R.drawable.ic_fit), stringResource(R.string.map_show_all))
            }
        }
    }
}
