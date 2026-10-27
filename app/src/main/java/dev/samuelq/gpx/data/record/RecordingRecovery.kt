package dev.samuelq.gpx.data.record

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.core.content.getSystemService
import dev.samuelq.gpx.core.analysis.TrackAnalyzer
import dev.samuelq.gpx.core.analysis.TrackProfile
import dev.samuelq.gpx.core.model.Track
import dev.samuelq.gpx.data.runCancellable
import dev.samuelq.gpx.data.track.TrackLabel
import dev.samuelq.gpx.data.track.TrackRepository
import dev.samuelq.gpx.data.track.asTrackName
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A recording left unsaved by a crash, read back and waiting to be saved, resumed or discarded. */
class AbandonedRecording(
    internal val file: File,
    val track: Track,
    val profile: TrackProfile,
    val cause: Interruption,
) {
    /** When the last fix was logged; every fix has a time. */
    val lastFix: Instant get() = track.points.let { Instant.ofEpochMilli(it.timeMillis(it.size - 1)) }

    /** Soon enough after it stopped that carrying on still makes one outing. */
    fun resumable(): Boolean = Duration.between(lastFix, Instant.now()) <= RecordingRecovery.RESUME_WINDOW
}

/** What [RecordingRecovery.reopen] found. */
sealed interface Reopened {
    /** Back in the live log, to be appended to. */
    class Resumed(val recording: AbandonedRecording) : Reopened

    /** Left where it was, past [RecordingRecovery.RESUME_WINDOW]. */
    class TooOld(val recording: AbandonedRecording) : Reopened

    /** Saved, discarded or unreadable since. */
    data object Gone : Reopened
}

/** A recording discarded moments ago, kept on disk until its undo lapses. */
class DiscardedRecording internal constructor(internal val file: File, val label: TrackLabel)

/**
 * The live recording log, plus recordings never cleanly stopped: those are claimed out of the
 * live log's way and offered to the user until saved or discarded.
 */
class RecordingRecovery(
    context: Context,
    private val tracks: TrackRepository,
    /** Outlives any screen, so deletes after an undo lapses still finish. */
    private val scope: CoroutineScope,
    /** For file work; a parameter so tests can substitute one. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val appContext = context.applicationContext

    // no_backup: a recording in progress on the old phone isn't a crash on the new one.
    private val dir: File get() = File(appContext.noBackupFilesDir, "recording").apply { mkdirs() }

    val liveLog: File get() = File(dir, LIVE_LOG)

    /** Two recoveries at once would both save the same claimed log. */
    private val lock = Mutex()

    /** What the launch claim moved: a recording the previous process died running. */
    private var claimedAtLaunch: File? = null

    /**
     * Moves an unsaved live log aside under a unique name. Returns whether the live log's
     * name is now free. Locks before switching threads, so the launch-time call holds the
     * lock ahead of any recording.
     */
    suspend fun claim(): Boolean = lock.withLock {
        withContext(io) {
            // With the app running, so nothing died: a save or a write failed.
            moveAside { Interruption.UNSAVED }
            !liveLog.exists()
        }
    }

    /**
     * [claim] in the first process after one died recording, while its exit record is sure to
     * be kept, remembering what it moved for [interrupted].
     */
    suspend fun claimAtLaunch() = lock.withLock { withContext(io) { claimedAtLaunch = moveAside(::causeOf) } }

    /** The claimed file, named with its [cause], or null if there was nothing to move or it couldn't be. */
    private fun moveAside(cause: (File) -> Interruption): File? {
        val log = liveLog
        if (log.length() == 0L) {
            log.delete()
            return null
        }
        val claimed = File(dir, claimName(System.currentTimeMillis(), cause(log)))
        return claimed.takeIf { log.renameTo(it) }
    }

    /** The app is offering a recording itself, so the notification about one is done with. */
    fun dismissNotice() = appContext.cancelInterrupted()

    /** The recording the previous process died running, if it's still worth saving. */
    suspend fun interrupted(): AbandonedRecording? = lock.withLock {
        withContext(io) { claimedAtLaunch?.takeIf(File::exists)?.let(::read) }
    }

    /**
     * Moves the claimed log [name] back to the live log, for a recording to carry on, if it
     * stopped within [RESUME_WINDOW]. Anything in the live log is claimed first.
     */
    suspend fun reopen(name: String): Reopened = lock.withLock {
        withContext(io) {
            val claimed = File(dir, name).takeIf(File::exists) ?: return@withContext Reopened.Gone
            val recording = read(claimed) ?: return@withContext Reopened.Gone
            if (!recording.resumable()) return@withContext Reopened.TooOld(recording)
            moveAside { Interruption.UNSAVED }
            if (liveLog.exists() || !claimed.renameTo(liveLog)) return@withContext Reopened.Gone
            Reopened.Resumed(recording)
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

    private fun read(claimed: File): AbandonedRecording? = saveable(claimed) ?: null.also { claimed.delete() }

    private fun saveable(log: File): AbandonedRecording? {
        val track = RecordingWal.recover(log) ?: return null
        val profile = TrackAnalyzer.analyze(track).takeIf(::isSaveable) ?: return null
        return AbandonedRecording(log, track, profile, causeIn(log.name))
    }

    /**
     * The first exit of the app's process after the log's last write: the one that died with
     * the recording. A shutdown leaves none, so a log older than the boot is from before one.
     */
    private fun causeOf(log: File): Interruption {
        val lastWrite = log.lastModified()
        val exit = appContext.getSystemService<ActivityManager>()
            ?.getHistoricalProcessExitReasons(appContext.packageName, 0, 0)
            ?.filter { it.processName == appContext.packageName && it.timestamp >= lastWrite }
            ?.minByOrNull { it.timestamp }
        val bootedAt = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        return when {
            exit != null -> Interruption.of(exit.reason)
            lastWrite < bootedAt -> Interruption.REBOOT
            else -> Interruption.UNKNOWN
        }
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
    suspend fun setAside(log: File, label: TrackLabel): DiscardedRecording? = lock.withLock {
        withContext(io) {
            val aside = File(dir, "$DISCARD_PREFIX${System.currentTimeMillis()}.wal")
            if (saveable(log) != null && log.renameTo(aside)) {
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
        private const val CLAIM_SUFFIX = ".wal"
        private const val DISCARD_PREFIX = "discarded-"

        private const val MIN_SAVEABLE_DISTANCE_METERS = 10.0

        /** Since the last fix; longer and carrying on is a new outing. */
        val RESUME_WINDOW: Duration = Duration.ofHours(1)

        /** `recovering-<epoch millis>-<cause>.wal`, sorting oldest first. */
        internal fun claimName(millis: Long, cause: Interruption) = "$CLAIM_PREFIX$millis-${cause.name}$CLAIM_SUFFIX"

        /** Unknown for a name without one, as claimed before causes were kept. */
        internal fun causeIn(name: String): Interruption {
            val cause = name.removePrefix(CLAIM_PREFIX).removeSuffix(CLAIM_SUFFIX).substringAfter('-', "")
            return Interruption.entries.firstOrNull { it.name == cause } ?: Interruption.UNKNOWN
        }

        /** Of the analysed log, at stop and recovery alike, so a crash can't resurrect what Stop would discard. */
        fun isSaveable(profile: TrackProfile) = isSaveable(profile.stats.distanceMeters)

        /** For Stop's dialog, from the running distance, before there is a log to analyse. */
        fun isSaveable(distanceMeters: Double) = distanceMeters >= MIN_SAVEABLE_DISTANCE_METERS
    }
}

/** As [label] names and categorises it; blank leaves either out. */
internal fun Track.labelled(label: TrackLabel) =
    copy(name = label.name.asTrackName(), type = label.category.asTrackName())
