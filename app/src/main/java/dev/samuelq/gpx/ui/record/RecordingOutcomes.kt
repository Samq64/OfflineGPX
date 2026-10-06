package dev.samuelq.gpx.ui.record

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.samuelq.gpx.R
import dev.samuelq.gpx.data.record.RecordingController
import dev.samuelq.gpx.data.record.RecordingEvent

/**
 * What becomes of a recording: saved, discarded with an undo, failed, or left by a crash and
 * offered again. The only collector of [recorder]'s events, which are delivered once.
 *
 * @param onSaved opens the saved track.
 */
@Composable
fun RecordingOutcomes(
    recorder: RecordingController,
    say: (String) -> Unit,
    offerUndo: (message: String, onUndo: () -> Unit, onCommit: () -> Unit) -> Unit,
    onSaved: (id: Long) -> Unit,
    recovery: RecoveryViewModel = viewModel(factory = RecoveryViewModel.Factory),
) {
    // Not context.getString: a long-lived collector would keep the old locale.
    val resources = LocalResources.current
    // By the name it would have been saved as, if any.
    fun discardedNamed(name: String) =
        if (name.isBlank()) resources.getString(R.string.record_discarded)
        else resources.getString(R.string.record_discarded_named, name.trim())

    LaunchedEffect(recorder) {
        recorder.events.collect { event ->
            when (event) {
                is RecordingEvent.Saved -> onSaved(event.id)
                is RecordingEvent.Discarded -> event.recording?.let { recording ->
                    offerUndo(
                        discardedNamed(recording.name),
                        { recovery.restoreDiscarded(recording) },
                        { recovery.forgetDiscarded(recording) },
                    )
                } ?: say(resources.getString(R.string.record_discarded))
                is RecordingEvent.Failed -> say(resources.getString(event.messageRes))
            }
        }
    }

    LaunchedEffect(recovery) {
        recovery.events.collect { event ->
            when (event) {
                is RecoveryEvent.Saved -> onSaved(event.id)
                RecoveryEvent.Failed -> say(resources.getString(R.string.record_save_failed))
                is RecoveryEvent.AbandonedDiscarded -> offerUndo(
                    discardedNamed(event.name),
                    { recovery.restoreAbandoned(event.recording, event.name) },
                    { recovery.forgetAbandoned(event.recording) },
                )
            }
        }
    }

    val abandoned by recovery.abandoned.collectAsStateWithLifecycle()
    abandoned?.let { recording ->
        RecoveredRecordingDialog(
            recording = recording,
            onSave = recovery::saveAbandoned,
            onDiscard = recovery::discardAbandoned,
        )
    }
}
