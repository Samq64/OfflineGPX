package dev.samuelq.gpx.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult

/**
 * Shows [message] with Undo, then calls exactly one of [onUndo] or [onCommit]. Anything but
 * the action commits: a timeout, a swipe, a newer message, or the screen leaving, which
 * cancels the caller. Both run from `finally`, so they must not suspend.
 */
suspend fun SnackbarHostState.showUndo(
    message: String,
    undoLabel: String,
    onUndo: () -> Unit,
    onCommit: () -> Unit,
) {
    var undone = false
    try {
        currentSnackbarData?.dismiss()
        undone = showSnackbar(message, undoLabel, duration = SnackbarDuration.Long) ==
            SnackbarResult.ActionPerformed
    } finally {
        if (undone) onUndo() else onCommit()
    }
}
