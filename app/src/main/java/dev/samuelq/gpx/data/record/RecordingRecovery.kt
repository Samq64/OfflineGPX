package dev.samuelq.gpx.data.record

import android.content.Context
import android.util.Log
import dev.samuelq.gpx.core.analysis.TrackAnalyzer
import dev.samuelq.gpx.core.analysis.TrackProfile
import dev.samuelq.gpx.core.model.Track
import dev.samuelq.gpx.data.runCancellable
import dev.samuelq.gpx.data.track.TrackRepository
import dev.samuelq.gpx.data.track.TrackLabel
import dev.samuelq.gpx.data.track.asTrackName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
)

/** A ride discarded moments ago, kept on disk until its undo lapses. */
class DiscardedRecording internal constructor(internal val file: File, val label: TrackLabel)

/**
 * The live recording log, plus rides never cleanly stopped: those are claimed out of the
 * live log's way and offered to the user until saved or discarded.
 */
class RecordingRecovery(
    context: Context,
    private val tracks: TrackRepository,
    /** Outlives any screen, so deletes after an undo lapses still finish. */
    private val scope: CoroutineScope,
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
        if (track == null || profile == null || !isSaveable(profile)) {
            claimed.delete()
            return null
        }
        return AbandonedRecording(claimed, track, profile)
    }

    /** A blank name leaves it unnamed. */
    suspend fun save(recording: AbandonedRecording, label: TrackLabel): Result<Long> = lock.withLock {
        tracks.saveRecording(recording.track.labelled(label), recording.profile).onSuccess {
            withContext(io) { recording.file.delete() }
        }
    }

    /** Deletes it once its undo lapses. */
    fun forget(recording: AbandonedRecording) = forget(recording.file)

    /**
     * Moves the closed live log out of the next recording's way, for [restore] to save if the
     * discard is undone. Null if it isn't worth saving or couldn't be moved; it's deleted then.
     */
    suspend fun setAside(log: File, label: TrackLabel): DiscardedRecording? =
        lock.withLock {
            withContext(io) {
                val aside = File(dir, "$DISCARD_PREFIX${System.currentTimeMillis()}.wal")
                val profile = RecordingWal.recover(log)?.let(TrackAnalyzer::analyze)
                if (profile != null && isSaveable(profile) && log.renameTo(aside)) {
                    DiscardedRecording(aside, label)
                } else {
                    log.delete()
                    null
                }
            }
        }

    /** Read back as an abandoned one would be, then saved the same way. */
    suspend fun restore(recording: DiscardedRecording): Result<Long> {
        val read = runCancellable { withContext(io) { read(recording.file) } }
            .getOrElse { return Result.failure(it) }
            ?: return Result.failure(IOException("Nothing left in ${recording.file.name}"))
        return save(read, recording.label)
    }

    fun forget(recording: DiscardedRecording) = forget(recording.file)

    private fun forget(file: File) {
        scope.launch(io) { lock.withLock { file.delete() } }
    }

    /** At launch: an undo from a previous process can no longer be taken. */
    suspend fun purgeDiscarded() = lock.withLock {
        withContext(io) {
            dir.listFiles { file -> file.name.startsWith(DISCARD_PREFIX) }.orEmpty().forEach(File::delete)
        }
    }

    companion object {
        private const val TAG = "RecordingRecovery"
        private const val LIVE_LOG = "recording.wal"
        private const val CLAIM_PREFIX = "recovering-"
        private const val DISCARD_PREFIX = "discarded-"

        private const val MIN_SAVEABLE_DISTANCE_METERS = 10.0

        /** Of the analysed log, at stop and recovery alike, so a crash can't resurrect what Stop would discard. */
        fun isSaveable(profile: TrackProfile) = isSaveable(profile.stats.distanceMeters)

        /** For Stop's dialog, from the running distance, before there is a log to analyse. */
        fun isSaveable(distanceMeters: Double) = distanceMeters >= MIN_SAVEABLE_DISTANCE_METERS
    }
}

/** As [label] names and categorises it; blank leaves either out. */
internal fun Track.labelled(label: TrackLabel) = copy(name = label.name.asTrackName(), type = label.category.asTrackName())
