package dev.samuelq.gpx.ui

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * A screen's snackbars, kept across activity recreation so a rotation or locale change doesn't
 * take an undo away. Its scope ends with the screen, which commits what's still offered.
 */
internal class Snackbars : ViewModel() {
    val host = SnackbarHostState()

    /** See [showUndo]. With a screen reader on it waits: reaching Undo takes swipes. */
    fun offerUndo(context: Context, message: String, undoLabel: String, onUndo: () -> Unit, onCommit: () -> Unit) {
        val exploring = context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
        // Dispatched, not immediate, so a newer message queues before a displaced undo resumes.
        viewModelScope.launch(Dispatchers.Main) { host.showUndo(message, undoLabel, exploring, onUndo, onCommit) }
    }
}

/** The screen's [Snackbars]: a view model lookup, so the same one across recreation. */
@Composable
internal fun screenSnackbars(): Snackbars = viewModel()

/**
 * Shows [message] with Undo, then calls exactly one of [onUndo] or [onCommit]. A timeout, a
 * dismissal, a newer undo or the scope ending commits. A message shown with [makeWay] only puts
 * it off until after. Both run from `finally`, so they must not suspend.
 */
internal suspend fun SnackbarHostState.showUndo(
    message: String,
    undoLabel: String,
    indefinite: Boolean,
    onUndo: () -> Unit,
    onCommit: () -> Unit,
) {
    val visuals =
        UndoVisuals(message, undoLabel, if (indefinite) SnackbarDuration.Indefinite else SnackbarDuration.Long)
    var undone = false
    try {
        currentSnackbarData?.dismiss()
        while (true) {
            visuals.displaced = false
            undone = showSnackbar(visuals) == SnackbarResult.ActionPerformed
            if (undone || !visuals.displaced) break
            // The newer message takes the host first.
            yield()
        }
    } finally {
        if (undone) onUndo() else onCommit()
    }
}

/** Dismisses what's shown for a newer message; an undo comes back after it rather than committing. */
fun SnackbarHostState.makeWay() {
    val current = currentSnackbarData ?: return
    (current.visuals as? UndoVisuals)?.displaced = true
    current.dismiss()
}

private class UndoVisuals(
    override val message: String,
    override val actionLabel: String,
    override val duration: SnackbarDuration,
) : SnackbarVisuals {
    override val withDismissAction = false
    var displaced = false
}

/** For a snackbar host: read before the screen it sits over, so Undo is a swipe away. */
fun Modifier.readFirst(): Modifier = semantics {
    isTraversalGroup = true
    traversalIndex = -1f
}
