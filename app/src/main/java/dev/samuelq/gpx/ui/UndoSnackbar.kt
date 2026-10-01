package dev.samuelq.gpx.ui

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex

/**
 * Shows [message] with Undo, then calls exactly one of [onUndo] or [onCommit]. Anything but
 * the action commits: a timeout, a swipe, a newer message, or the screen leaving, which
 * cancels the caller. Both run from `finally`, so they must not suspend.
 *
 * With a screen reader on it waits: reaching Undo takes swipes, and undo is all a delete has.
 */
suspend fun SnackbarHostState.showUndo(
    context: Context,
    message: String,
    undoLabel: String,
    onUndo: () -> Unit,
    onCommit: () -> Unit,
) {
    val exploring = context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
    var undone = false
    try {
        currentSnackbarData?.dismiss()
        val duration = if (exploring) SnackbarDuration.Indefinite else SnackbarDuration.Long
        undone = showSnackbar(message, undoLabel, duration = duration) ==
            SnackbarResult.ActionPerformed
    } finally {
        if (undone) onUndo() else onCommit()
    }
}

/** For a snackbar host: read before the screen it sits over, so Undo is a swipe away. */
fun Modifier.readFirst(): Modifier = semantics {
    isTraversalGroup = true
    traversalIndex = -1f
}
