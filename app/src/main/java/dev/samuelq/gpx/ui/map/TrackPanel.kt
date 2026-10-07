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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.samuelq.gpx.R
import dev.samuelq.gpx.core.model.Waypoint
import dev.samuelq.gpx.ui.track.FocusedTrack
import dev.samuelq.gpx.ui.track.TrackActions
import dev.samuelq.gpx.ui.track.TrackSheet
import dev.samuelq.gpx.ui.track.TrackSheetError
import dev.samuelq.gpx.ui.track.TrackSheetLoading
import dev.samuelq.gpx.ui.track.TrimControls

/** A focused track's details, however far it has loaded. Nothing for [FocusedTrack.None]. */
@Composable
internal fun FocusedTrackContent(
    focused: FocusedTrack,
    /** The ready track's, from its row when it has one. */
    title: String,
    routeColor: Color,
    maxHeight: Dp,
    selectedIndex: Int?,
    onSelectedIndexChange: (Int?) -> Unit,
    preferTimeAxis: Boolean,
    onAxisChange: (Boolean) -> Unit,
    actions: TrackActions?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onPeekHeightChange: (Dp) -> Unit,
    onSelectWaypoint: (Waypoint) -> Unit,
    trim: TrimControls? = null,
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
            title = title,
            routeColor = routeColor,
            maxHeight = maxHeight,
            selectedIndex = selectedIndex,
            onSelectedIndexChange = onSelectedIndexChange,
            useTimeAxis = preferTimeAxis && focused.track.profile.hasTime,
            onAxisChange = onAxisChange,
            onPeekHeightChange = onPeekHeightChange,
            // Closable without a drag, as the side panel is.
            onClose = onDismiss,
            actions = actions,
            onSelectWaypoint = onSelectWaypoint,
            trim = trim,
        )
    }
}

/** The landscape side panel. Keeps the last [subject] while sliding away rather than emptying first. */
@Composable
internal fun <T : Any> SidePanel(
    subject: T?,
    visible: Boolean,
    width: Dp,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    val shown = rememberLastNonNull(subject)
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
                shown?.let { content(it) }
            }
        }
    }
}

/** [value], or while it's null the last value that wasn't: content kept while it animates away. */
@Composable
internal fun <T : Any> rememberLastNonNull(value: T?): T? {
    val last = remember { LastValue<T>() }
    // After commit: a discarded composition's value is never shown.
    SideEffect { if (value != null) last.value = value }
    return value ?: last.value
}

private class LastValue<T : Any> {
    var value: T? = null
}

private val SidePanelCornerRadius = 28.dp

/** The handle's own height, counted into the measured peek. */
internal val DragHandleHeight = 20.dp

/** Half the Material handle, which spends 44 of 48dp on padding; the whole sheet drags anyway. */
@Composable
internal fun CompactDragHandle() {
    // Named like Material's; the scaffold adds expand and collapse to it.
    val description = stringResource(R.string.sheet_drag_handle)
    Box(
        Modifier.fillMaxWidth().height(DragHandleHeight).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 32.dp, height = 4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}
