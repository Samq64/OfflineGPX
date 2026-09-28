package dev.samuelq.gpx.data.record

import android.content.Context
import android.util.Log
import dev.samuelq.gpx.core.analysis.TrackAnalyzer
import dev.samuelq.gpx.core.analysis.TrackProfile
import dev.samuelq.gpx.core.model.Track
import dev.samuelq.gpx.data.track.TrackRepository
import dev.samuelq.gpx.data.track.defaultTrackName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** A recording left unsaved by a crash, read back and waiting to be saved or discarded. */
class AbandonedRecording(
    internal val file: File,
    val track: Track,
    val profile: TrackProfile,
    val defaultName: String,
)

/**
 * The live recording log, plus rides never cleanly stopped: those are claimed out of the
 * live log's way and offered to the user until saved or discarded.
 */
class RecordingRecovery(
    context: Context,
    private val tracks: TrackRepository,
) {
    private val io = Dispatchers.IO
    private val appContext = context.applicationContext
    // no_backup: a ride in progress on the old phone isn't a crash on the new one.
    private val dir: File get() = File(appContext.noBackupFilesDir, "recording").apply { mkdirs() }

    val liveLog: File get() = File(dir, LIVE_LOG)

    /** Two recoveries at once would both save the same claimed log. */
    private val lock = Mutex()

    /**
     * Moves an unsaved live log aside under a unique name. Returns whether the live log's
     * name is now free. Locks before switching threads, so the launch-time call holds the
     * lock ahead of any recording.
     */
    suspend fun claim(): Boolean = lock.withLock {
        withContext(io) {
            val log = liveLog
            if (log.length() > 0L) {
                log.renameTo(File(dir, "$CLAIM_PREFIX${System.currentTimeMillis()}.wal"))
            } else {
                log.delete()
            }
            !log.exists()
        }
    }

    /** Claimed recordings worth saving, oldest first; the rest are deleted. */
    suspend fun abandoned(): List<AbandonedRecording> = lock.withLock {
        withContext(io) {
            dir.listFiles { file -> file.name.startsWith(CLAIM_PREFIX) }
                .orEmpty()
                .sortedBy(File::getName)
                .mapNotNull { claimed ->
                    try {
                        read(claimed)
                    } catch (e: IOException) {
                        Log.w(TAG, "Could not read ${claimed.name}", e)
                        null
                    }
                }
        }
    }

    private fun read(claimed: File): AbandonedRecording? {
        val track = RecordingWal.recover(claimed)
        val profile = track?.let(TrackAnalyzer::analyze)
        if (track == null || profile == null || !isSaveable(profile.stats.distanceMeters)) {
            claimed.delete()
            return null
        }
        return AbandonedRecording(claimed, track, profile, defaultTrackName(appContext, profile.stats))
    }

    /** A blank [name] uses the default. */
    suspend fun save(recording: AbandonedRecording, name: String): Result<Long> = lock.withLock {
        val named = recording.track.copy(name = name.trim().ifEmpty { null })
        tracks.saveRecording(named, recording.profile).onSuccess {
            withContext(io) { recording.file.delete() }
        }
    }

    suspend fun discard(recording: AbandonedRecording) {
        lock.withLock { withContext(io) { recording.file.delete() } }
    }

    companion object {
        private const val TAG = "RecordingRecovery"
        private const val LIVE_LOG = "recording.wal"
        private const val CLAIM_PREFIX = "recovering-"

        /** Shared by stop and recovery, so a crash can't resurrect what Stop would discard. */
        private const val MIN_SAVEABLE_DISTANCE_METERS = 10.0

        fun isSaveable(distanceMeters: Double) = distanceMeters >= MIN_SAVEABLE_DISTANCE_METERS
    }
}
