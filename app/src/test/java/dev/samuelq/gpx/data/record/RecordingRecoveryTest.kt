package dev.samuelq.gpx.data.record

import dev.samuelq.gpx.core.analysis.TrackAnalyzer
import dev.samuelq.gpx.core.model.TrackPoint
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The rest needs a Context; see the instrumented tests. */
class RecordingRecoveryTest {

    @Test
    fun `a ride under ten metres isn't worth saving`() {
        assertFalse(RecordingRecovery.isSaveable(9.9))
        assertTrue(RecordingRecovery.isSaveable(10.0))
    }

    /** Stop and a crash recovery judge the same analysed log, so neither keeps what the other drops. */
    @Test
    fun `a recovered log is judged on its analysed distance`() {
        val at = Instant.parse("2026-09-18T09:00:00Z")
        fun logOf(metres: Double) = File.createTempFile("ride", ".wal").also { file ->
            file.deleteOnExit()
            file.delete()
            RecordingWal.open(file).use { wal ->
                for (s in 0..10) {
                    wal.append(TrackPoint(51.5 + s * metres / 10 / 111_320.0, -0.1, time = at.plusSeconds(s * 5L)))
                }
            }
        }
        val short = assertNotNull(RecordingWal.recover(logOf(5.0)))
        assertFalse(RecordingRecovery.isSaveable(TrackAnalyzer.analyze(short)))
        val long = assertNotNull(RecordingWal.recover(logOf(200.0)))
        assertTrue(RecordingRecovery.isSaveable(TrackAnalyzer.analyze(long)))
    }
}
