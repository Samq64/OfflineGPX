package dev.samuelq.gpx.ui.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.samuelq.gpx.ui.theme.slot
import dev.samuelq.gpx.ui.track.FocusedTrack
import dev.samuelq.gpx.ui.track.TrackActions
import dev.samuelq.gpx.ui.track.TrackSheet
import dev.samuelq.gpx.ui.track.TrackSheetError
import dev.samuelq.gpx.ui.track.TrackSheetLoading

/** A focused track's details, however far it has loaded. Nothing for [FocusedTrack.None]. */
@Composable
internal fun FocusedTrackContent(
    focused: FocusedTrack,
    palette: List<Color>,
    maxHeight: Dp,
    selectedIndex: Int?,
    onSelectedIndexChange: (Int?) -> Unit,
    preferTimeAxis: Boolean,
    onAxisChange: (Boolean) -> Unit,
    actions: TrackActions?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onClose: (() -> Unit)?,
    onPeekHeightChange: (Dp) -> Unit,
) {
    when (focused) {
        FocusedTrack.None -> Unit
        FocusedTrack.Loading -> TrackSheetLoading()
        is FocusedTrack.Failed -> TrackSheetError(
            messageRes = focused.messageRes,
            onRetry = onRetry,
            onClose = onDismiss,
        )
        is FocusedTrack.Ready -> TrackSheet(
            loaded = focused.track,
            routeColor = palette.slot(focused.track.colorIndex),
            maxHeight = maxHeight,
            selectedIndex = selectedIndex,
            onSelectedIndexChange = onSelectedIndexChange,
            useTimeAxis = preferTimeAxis && focused.track.profile.hasTime,
            onAxisChange = onAxisChange,
            onPeekHeightChange = onPeekHeightChange,
            onClose = onClose,
            actions = actions,
        )
    }
}

/**
 * The track down the start edge, for a window wider than tall. Keeps showing the last track
 * while it slides away, rather than emptying before it has gone.
 */
@Composable
internal fun TrackSidePanel(
    visible: Boolean,
    focused: FocusedTrack,
    width: Dp,
    modifier: Modifier = Modifier,
    content: @Composable (FocusedTrack) -> Unit,
) {
    var shown by remember { mutableStateOf<FocusedTrack>(FocusedTrack.None) }
    LaunchedEffect(focused) { if (focused != FocusedTrack.None) shown = focused }
    val fromStart = if (LocalLayoutDirection.current == LayoutDirection.Ltr) -1 else 1
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally { fromStart * it },
        exit = slideOutHorizontally { fromStart * it },
        modifier = modifier.fillMaxHeight().width(width),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(topEnd = SidePanelCornerRadius),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start))
                    .padding(top = 16.dp),
            ) {
                content(if (focused != FocusedTrack.None) focused else shown)
            }
        }
    }
}

private val SidePanelCornerRadius = 28.dp

/** The handle's own height, counted into the measured peek. */
internal val DragHandleHeight = 20.dp

/**
 * Half the height of the Material handle, which spends 44 of its 48dp on padding. Every
 * one of those is a dp of map, and the sheet is dragged by its whole surface anyway.
 */
@Composable
internal fun CompactDragHandle() {
    Box(
        Modifier.fillMaxWidth().height(DragHandleHeight),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 32.dp, height = 4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
    }
}
