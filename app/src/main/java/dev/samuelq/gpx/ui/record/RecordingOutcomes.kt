package dev.samuelq.gpx.ui.record

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.samuelq.gpx.R
import dev.samuelq.gpx.data.record.RecordingEvent
import dev.samuelq.gpx.ui.map.openLocationSettings
import kotlinx.coroutines.flow.Flow

/**
 * What becomes of a recording: saved, discarded with an undo, failed, or left by a crash and
 * offered again. The only collector of [recorder]'s events, which are delivered once.
 *
 * @param onSaved opens the saved track.
 * @param onDiscarded follows a recording thrown away, which leaves nothing to open.
 */
@Composable
internal fun RecordingOutcomes(
    events: Flow<RecordingEvent>,
    categories: CategoryChoice,
    /** With a Settings action when there's one to take. */
    say: (message: String, openSettings: (() -> Unit)?) -> Unit,
    offerUndo: (message: String, onUndo: () -> Unit, onCommit: () -> Unit) -> Unit,
    onSaved: (id: Long) -> Unit,
    onDiscarded: () -> Unit,
    recovery: RecoveryViewModel = viewModel(factory = RecoveryViewModel.Factory),
) {
    // Not context.getString: a long-lived collector would keep the old locale.
    val resources = LocalResources.current
    val context = LocalContext.current
    // The collectors outlive compositions, so they call the latest of each.
    val currentSay by rememberUpdatedState(say)
    val currentOfferUndo by rememberUpdatedState(offerUndo)
    val currentOnSaved by rememberUpdatedState(onSaved)
    val currentOnDiscarded by rememberUpdatedState(onDiscarded)

    // By the name it would have been saved as, if any.
    fun discardedNamed(name: String) = if (name.isBlank()) {
        resources.getString(R.string.record_discarded)
    } else {
        resources.getString(R.string.record_discarded_named, name.trim())
    }

    LaunchedEffect(events) {
        events.collect { event ->
            when (event) {
                is RecordingEvent.Saved -> currentOnSaved(event.id)
                is RecordingEvent.Discarded -> {
                    currentOnDiscarded()
                    event.recording?.let { recording ->
                        currentOfferUndo(
                            discardedNamed(recording.label.name),
                            { recovery.restoreDiscarded(recording) },
                            { recovery.forgetDiscarded(recording) },
                        )
                    } ?: currentSay(resources.getString(R.string.record_discarded), null)
                }
                is RecordingEvent.Failed -> currentSay(
                    resources.getString(event.messageRes),
                    { context.openLocationSettings() }.takeIf { event.messageRes == R.string.record_location_off },
                )
            }
        }
    }

    LaunchedEffect(recovery) {
        recovery.events.collect { event ->
            when (event) {
                is RecoveryEvent.Saved -> currentOnSaved(event.id)
                RecoveryEvent.Failed -> currentSay(resources.getString(R.string.record_save_failed), null)
                is RecoveryEvent.AbandonedDiscarded -> currentOfferUndo(
                    discardedNamed(event.label.name),
                    { recovery.restoreAbandoned(event.recording, event.label) },
                    { recovery.forgetAbandoned(event.recording) },
                )
            }
        }
    }

    val abandoned by recovery.abandoned.collectAsStateWithLifecycle()
    abandoned?.let { recording ->
        RecoveredRecordingDialog(
            recording = recording,
            categories = categories,
            onSave = recovery::saveAbandoned,
            onDiscard = recovery::discardAbandoned,
        )
    }
}
