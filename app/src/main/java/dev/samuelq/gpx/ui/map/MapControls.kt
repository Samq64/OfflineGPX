package dev.samuelq.gpx.ui.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.samuelq.gpx.R
import dev.samuelq.gpx.core.model.Waypoint
import dev.samuelq.gpx.ui.PointTooltip
import dev.samuelq.gpx.ui.TooltipSecondary
import dev.samuelq.gpx.ui.format.LocalFormatters
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * A tapped waypoint's time and note, beside its pin tip at [tipAt]. With [onEdit], the whole
 * bubble edits, marked by a pencil: a target as small as the pencil alone would miss to the map.
 */
@Composable
internal fun WaypointTooltip(waypoint: Waypoint, tipAt: () -> Offset, onEdit: (() -> Unit)? = null) {
    val density = LocalDensity.current
    val headRadius = with(density) { WaypointPinHeadRadius.toPx() }
    val height = with(density) { (WaypointPinHeadRadius + PIN_TIP_LENGTH_DP.dp).toPx() }
    // Shown on the pencil, as a ripple over the bubble's corners and caret would spill.
    val presses = remember { MutableInteractionSource() }
    PointTooltip(
        anchorAt = {
            val tip = tipAt()
            IntOffset((tip.x - headRadius).roundToInt(), (tip.y - height).roundToInt())
        },
        anchorSize = DpSize(WaypointPinHeadRadius * 2, WaypointPinHeadRadius + PIN_TIP_LENGTH_DP.dp),
        modifier = onEdit?.let {
            val label = stringResource(R.string.waypoint_edit)
            Modifier
                .clickable(interactionSource = presses, indication = null, role = Role.Button, onClick = it)
                .semantics { contentDescription = label }
        } ?: Modifier,
    ) { caretOnLeft ->
        val formatters = LocalFormatters.current
        val name = waypoint.name?.takeIf(String::isNotBlank)
        val time = waypoint.point.time
        val timeLine: @Composable () -> Unit = {
            Text(
                text = formatters.time(time),
                style = MaterialTheme.typography.labelMedium,
                color = TooltipSecondary,
            )
        }
        // Said when it can be edited, so the bubble's tap has something to fill in.
        val note = name ?: onEdit?.let { stringResource(R.string.waypoint_no_note) }
        // Announced as it opens, since a screen reader action can open it with focus elsewhere.
        NoteLayout(
            // The placeholder dash only when there's nothing else to show.
            above = timeLine.takeIf { note != null && time != null },
            last = note?.let { { NoteText(it, placeholder = name == null) } } ?: timeLine,
            presses = presses.takeIf { onEdit != null },
            caretOnLeft = caretOnLeft,
            modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/**
 * [above] over [last], with a pencil if [presses], centred on [last]: on the side away from the caret,
 * so the caret's side stays the text's edge.
 */
@Composable
private fun NoteLayout(
    above: (@Composable () -> Unit)?,
    last: @Composable () -> Unit,
    presses: InteractionSource?,
    caretOnLeft: () -> Boolean,
    modifier: Modifier,
) {
    Layout(
        contents = listOf(above ?: {}, last, { if (presses != null) EditPencil(presses) }),
        modifier = modifier,
    ) { (aboveMeasurables, lastMeasurables, editMeasurables), constraints ->
        val pencil = editMeasurables.firstOrNull()?.measure(Constraints())
        val gap = if (pencil != null) EditGap.roundToPx() else 0
        val textConstraints = constraints.copy(
            minWidth = 0,
            maxWidth = (constraints.maxWidth - (pencil?.width ?: 0) - gap).coerceAtLeast(0),
        )
        val top = aboveMeasurables.firstOrNull()?.measure(textConstraints)
        val bottom = lastMeasurables.single().measure(textConstraints)
        val textWidth = maxOf(top?.width ?: 0, bottom.width)
        val topHeight = top?.height ?: 0
        val lastHeight = maxOf(bottom.height, pencil?.height ?: 0)
        layout(textWidth + gap + (pencil?.width ?: 0), topHeight + lastHeight) {
            val onLeft = caretOnLeft()
            val textX = if (pencil != null && !onLeft) pencil.width + gap else 0
            top?.place(textX, 0)
            bottom.place(textX, topHeight + (lastHeight - bottom.height) / 2)
            pencil?.place(if (onLeft) textWidth + gap else 0, topHeight + (lastHeight - pencil.height) / 2)
        }
    }
}

/** As wide as its widest line: wrapped text otherwise keeps all the width it was offered. */
@Composable
private fun NoteText(text: String, placeholder: Boolean) {
    var lines: TextLayoutResult? = null
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (placeholder) TooltipSecondary else Color.Unspecified,
        onTextLayout = { lines = it },
        modifier = Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val result = lines?.takeIf { it.lineCount > 1 }
                ?: return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            val left = (0 until result.lineCount).minOf { floor(result.getLineLeft(it)) }
            val right = (0 until result.lineCount).maxOf { ceil(result.getLineRight(it)) }
            layout((right - left).toInt(), placeable.height) { placeable.place(-left.toInt(), 0) }
        },
    )
}

/** The glyph alone, so it insets like the text; the bubble takes the click and its label. */
@Composable
private fun EditPencil(presses: InteractionSource) {
    Icon(
        painterResource(R.drawable.ic_edit),
        contentDescription = null,
        modifier = Modifier
            .size(EditSize)
            .indication(presses, ripple(bounded = false, radius = EditRipple)),
    )
}

private val EditSize = 12.dp
private val EditGap = 8.dp
private val EditRipple = 16.dp

/** Starts a recording; once running, it lives in the sheet. */
@Composable
internal fun RecordButton(onStart: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.record_start)
    ExtendedFloatingActionButton(
        onClick = onStart,
        modifier = modifier,
        // The label goes on the icon: this overload hides its text from accessibility.
        icon = { Icon(painterResource(R.drawable.ic_play_arrow), contentDescription = label) },
        text = { Text(label) },
    )
}

/**
 * Filled while [following], with the found-position icon once there's a fix; [waiting] until
 * then. Says what a tap does: follow, stop following, or, outside a recording, stop showing the position.
 */
@Composable
internal fun LocationButton(
    following: Boolean,
    waiting: Boolean,
    recording: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(
        when {
            !following -> R.string.map_follow_location
            recording -> R.string.map_stop_following
            else -> R.string.map_stop_location
        },
    )
    val waitingLabel = stringResource(R.string.record_waiting_for_fix)
    val icon = if (following && !waiting) R.drawable.ic_my_location else R.drawable.ic_location_searching
    Surface(
        onClick = onClick,
        modifier = modifier.size(48.dp).semantics {
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
