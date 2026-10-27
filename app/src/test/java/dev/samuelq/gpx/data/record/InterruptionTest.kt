package dev.samuelq.gpx.data.record

import android.app.ApplicationExitInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class InterruptionTest {

    /** How most battery savers close an app; the active apps list says it was the user. */
    @Test
    fun `a force stop is told apart from a stop in the active apps list`() {
        assertEquals(Interruption.FORCE_STOPPED, Interruption.of(ApplicationExitInfo.REASON_USER_REQUESTED))
        assertEquals(Interruption.STOPPED, Interruption.of(ApplicationExitInfo.REASON_USER_STOPPED))
        assertEquals(Interruption.LOW_MEMORY, Interruption.of(ApplicationExitInfo.REASON_LOW_MEMORY))
    }

    @Test
    fun `unexplained exits say only that the app closed`() {
        for (reason in listOf(
            ApplicationExitInfo.REASON_UNKNOWN,
            ApplicationExitInfo.REASON_EXIT_SELF,
            ApplicationExitInfo.REASON_DEPENDENCY_DIED,
            ApplicationExitInfo.REASON_SIGNALED,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            ApplicationExitInfo.REASON_FREEZER,
            ApplicationExitInfo.REASON_OTHER,
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE,
            99,
        )) {
            assertEquals(Interruption.UNKNOWN, Interruption.of(reason), "reason $reason")
        }
    }

    @Test
    fun `a hang isn't called a crash`() {
        assertEquals(Interruption.CRASH, Interruption.of(ApplicationExitInfo.REASON_CRASH))
        assertEquals(Interruption.ANR, Interruption.of(ApplicationExitInfo.REASON_ANR))
    }

    /** Found at the launch after the death, while its exit record is kept, then read from the name. */
    @Test
    fun `a claimed log's name keeps its cause`() {
        for (cause in Interruption.entries) {
            assertEquals(cause, RecordingRecovery.causeIn(RecordingRecovery.claimName(1_793_060_313_282, cause)))
        }
        // Claimed before causes were kept.
        assertEquals(Interruption.UNKNOWN, RecordingRecovery.causeIn("recovering-1793060313282.wal"))
    }

    @Test
    fun `claimed logs still sort oldest first`() {
        val older = RecordingRecovery.claimName(1_793_060_313_282, Interruption.UNKNOWN)
        val newer = RecordingRecovery.claimName(1_793_060_313_283, Interruption.FORCE_STOPPED)
        assertEquals(listOf(older, newer), listOf(newer, older).sorted())
    }
}
