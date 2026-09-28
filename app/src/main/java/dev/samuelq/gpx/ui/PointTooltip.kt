package dev.samuelq.gpx.ui

import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset

/**
 * A tooltip beside a box that moves: a scrubbed chart point, a tapped map pin. [anchorAt] is
 * the box's top-left in the parent's coordinates, read during layout so a drag or pan moves
 * the tooltip without recomposing. Shown while composed and the box is mostly on screen - a
 * popup isn't clipped, so a chart under a collapsed sheet would otherwise show it anyway.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PointTooltip(
    anchorAt: () -> IntOffset,
    anchorSize: DpSize,
    content: @Composable () -> Unit,
) {
    // Its own mutex: the shared default lets only one tooltip show, and both charts label the scrub.
    val state = rememberTooltipState(isPersistent = true, mutatorMutex = remember { MutatorMutex() })
    var onScreen by remember { mutableStateOf(false) }
    // Cancelling show() hides it again.
    LaunchedEffect(onScreen) { if (onScreen) state.show() }

    // Offset here, not on TooltipBox: the popup anchors to TooltipBox's outer bounds.
    Box(Modifier.offset { anchorAt() }) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.End),
            tooltip = { PlainTooltip(caretShape = TooltipDefaults.caretShape()) { content() } },
            state = state,
            // A touch elsewhere is a scrub or a pan, not a dismissal.
            onDismissRequest = {},
            enableUserInput = false,
        ) {
            Box(
                Modifier
                    .size(anchorSize)
                    .onGloballyPositioned {
                        // Half, so a pin mostly behind the top bar doesn't label the bar.
                        val shown = it.boundsInWindow()
                        onScreen = shown.width * 2 >= it.size.width && shown.height * 2 >= it.size.height
                    },
            )
        }
    }
}
