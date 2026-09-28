package dev.samuelq.gpx.data.record

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.annotation.StringRes
import dev.samuelq.gpx.GpxApplication
import dev.samuelq.gpx.R
import dev.samuelq.gpx.core.model.TrackPoint
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.Instant

/**
 * Owns a recording for as long as it runs.
 *
 * A foreground service because that is the only honest way to keep sampling with the
 * screen off. Commands arrive as intents from [RecordingController]; state goes back
 * through it.
 */
class RecordingService : Service() {

    private val container get() = (application as GpxApplication).container
    private val controller get() = container.recordingController

    /**
     * Every field below, and the WAL, is touched only from here: one fix at a time, never
     * racing a pause or a stop. IO rather than Main because each fix is a flushed write.
     */
    private val recorder = Dispatchers.IO.limitedParallelism(1)

    /** A write that fails ends the recording rather than the process; see [fail]. */
    private val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + recorder + CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "Recording failed", e)
            scope.launch { fail() }
        }
    )

    /** Commands suspend (recovery, saving), and must not interleave with each other. */
    private val commands = Mutex()

    private val notifications = RecordingNotifications(this)

    /**
     * Non-null from START until the recording ends, pause included - a START arriving
     * while paused would otherwise reopen the WAL in append mode over the ride in it.
     */
    private var session: RecordingSession? = null
    private var collection: Job? = null
    private var wal: RecordingWal? = null

    /**
     * Republishes on a plain clock rather than waiting on the next fix, so the duration
     * keeps moving through a GPS outage, rejected fixes, or a pause.
     */
    private var ticker: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        // Now, not once the command gets its turn: a START queued behind a long save would
        // miss the deadline startForegroundService sets, and that is a crash.
        if (action == ACTION_START) notifications.startForeground(notificationContent())
        scope.launch {
            commands.withLock {
                when (action) {
                    ACTION_START -> start()
                    ACTION_PAUSE -> pause()
                    ACTION_RESUME -> resume()
                    ACTION_STOP -> stop(save = true)
                    ACTION_DISCARD -> stop(save = false)
                    ACTION_WAYPOINT -> addWaypoint(intent.getStringExtra(EXTRA_DESCRIPTION) ?: "")
                }
                // By id, so a START queued behind a stop still gets its recording.
                if (session == null) stopSelf(startId)
            }
        }
        // Not sticky: a restart with no intent cannot know whether the user still wants to
        // be recorded, and resuming location sampling unasked is exactly the wrong default.
        return START_NOT_STICKY
    }

    private suspend fun start() {
        if (session != null) return
        val settings = container.settingsRepository.settings.value
        session = RecordingSession(
            maxAccuracyMeters = settings.maxAccuracyMeters,
            minDisplacementMeters = settings.minDisplacementMeters,
            clock = SystemClock::elapsedRealtime,
        )

        // Again, now the state it shows is this ride's. Also covers a START that waited
        // behind a stop, whose finish() took the first one down.
        notifications.startForeground(notificationContent())

        val source = LocationSource(this)
        // Location off system-wide means requestLocationUpdates succeeds and then never
        // calls back, which looks exactly like waiting for a fix that never comes.
        if (!source.isGpsEnabled) {
            abandon(R.string.record_location_off)
            return
        }

        // A ride whose save failed is still in the log. Set aside first, for the next
        // launch to ask about, so this one starts from an empty file.
        if (!container.recordingRecovery.claim()) {
            abandon(R.string.record_save_failed)
            return
        }

        wal = RecordingWal.open(container.recordingRecovery.liveLog)

        publish()
        startTicker()
        collectFixes(source)
    }

    private fun collectFixes(source: LocationSource) {
        collection = source
            .fixes(onUnavailable = ::onLocationUnavailable)
            .onEach(::onFix)
            .launchIn(scope)
    }

    /** Give up before a recording exists: say why, drop the notification, go away. */
    private fun abandon(@StringRes messageRes: Int) {
        controller.emit(RecordingEvent.Failed(messageRes))
        finish()
    }

    /** The log stopped taking writes. Whatever reached it stays on disk for recovery. */
    private fun fail() {
        if (session == null) return
        collection?.cancel()
        collection = null
        wal?.let { runCatching(it::close) }
        wal = null
        controller.emit(RecordingEvent.Failed(R.string.record_save_failed))
        finish()
        stopSelf()
    }

    private fun finish() {
        session = null
        ticker?.cancel()
        ticker = null
        controller.update(RecordingState.Idle)
        controller.updateTrace(LiveTrace.Empty)
        notifications.remove()
    }

    /**
     * Location switched off mid-ride. The recording stays up - the points already logged
     * are real, and the provider often comes back - but the user is told, because from the
     * outside this is indistinguishable from standing still.
     */
    private fun onLocationUnavailable() {
        controller.emit(RecordingEvent.Failed(R.string.record_location_lost))
    }

    private fun onFix(fix: TrackPoint) {
        val session = session ?: return
        session.onFix(fix)?.let { point ->
            wal?.append(point)
            if (session.traceDue) publishTrace(session)
        }
        publish()
        notifications.update(notificationContent(), force = false)
    }

    private fun addWaypoint(description: String) {
        val waypoint = session?.addWaypoint(description, Instant.now()) ?: return
        wal?.appendWaypoint(waypoint)
        publish()
    }

    private fun startTicker() {
        ticker = scope.launch {
            while (true) {
                delay(TICK_INTERVAL_MILLIS)
                publish()
                notifications.update(notificationContent(), force = false)
            }
        }
    }

    /**
     * Stops sampling outright, which is the one thing auto-detection cannot do: save the
     * battery, and write a real `<trkseg>` boundary that travels with the file.
     */
    private suspend fun pause() {
        val session = session ?: return
        if (!session.pause()) return

        collection?.cancelAndJoin()
        collection = null

        // Marked now, so the recovered file shows the break even if the app dies paused.
        wal?.appendBreak()
        publishTrace(session)
        publish()
        notifications.update(notificationContent())
    }

    private fun resume() {
        val session = session ?: return
        if (!session.paused) return

        val source = LocationSource(this)
        // Location is a quick-settings toggle, and a paused recording is exactly when
        // someone would reach for it. Staying paused and saying so beats resuming into silence.
        if (!source.isGpsEnabled) {
            controller.emit(RecordingEvent.Failed(R.string.record_location_off))
            return
        }

        session.resume()
        collectFixes(source)
        publish()
        notifications.update(notificationContent())
    }

    private suspend fun stop(save: Boolean) {
        val session = session ?: return

        // Joined, not just cancelled: a fix mid-append must land before the log closes.
        collection?.cancelAndJoin()
        collection = null

        val log = wal ?: return finish()
        wal = null

        try {
            if (!save) {
                log.discard()
                controller.emit(RecordingEvent.Discarded)
                return
            }
            log.close()
            val track = RecordingWal.recover(log.file)
            // Nothing in the log, or fixes that never went anywhere - either way not the
            // user's decision, so neither is called "discarded".
            if (track == null || !RecordingRecovery.isSaveable(session.distanceMeters)) {
                log.file.delete()
                controller.emit(
                    RecordingEvent.Failed(
                        if (track == null) R.string.record_nothing_recorded else R.string.record_no_distance
                    )
                )
                return
            }
            container.trackRepository.saveRecording(track).fold(
                onSuccess = {
                    log.file.delete()
                    controller.emit(RecordingEvent.Saved(it))
                },
                // Keep the log: a failed save that also deleted the ride would be the
                // worst outcome this class exists to prevent.
                onFailure = { controller.emit(RecordingEvent.Failed(R.string.record_save_failed)) },
            )
        } catch (e: IOException) {
            Log.e(TAG, "Could not read back the recording", e)
            controller.emit(RecordingEvent.Failed(R.string.record_save_failed))
        } finally {
            finish()
        }
    }

    /** Hands the map a snapshot of the route so far. See [RecordingSession.traceDue]. */
    private fun publishTrace(session: RecordingSession) = controller.updateTrace(session.trace())

    private fun publish() {
        session?.let { controller.update(it.state()) }
    }

    private fun notificationContent() = NotificationContent(
        paused = session?.paused == true,
        distanceMeters = session?.distanceMeters ?: 0.0,
        totalSeconds = session?.totalSeconds ?: 0.0,
        units = container.settingsRepository.settings.value.units,
    )

    override fun onDestroy() {
        scope.cancel()
        // Queued behind whatever the recorder is running, so it can't land mid-append.
        CoroutineScope(recorder).launch { wal?.let { runCatching(it::close) } }
        super.onDestroy()
    }

    companion object {
        internal const val ACTION_START = "dev.samuelq.gpx.RECORD_START"
        internal const val ACTION_PAUSE = "dev.samuelq.gpx.RECORD_PAUSE"
        internal const val ACTION_RESUME = "dev.samuelq.gpx.RECORD_RESUME"
        internal const val ACTION_STOP = "dev.samuelq.gpx.RECORD_STOP"
        internal const val ACTION_DISCARD = "dev.samuelq.gpx.RECORD_DISCARD"
        internal const val ACTION_WAYPOINT = "dev.samuelq.gpx.RECORD_WAYPOINT"
        internal const val EXTRA_DESCRIPTION = "description"

        private const val TAG = "RecordingService"

        /** How often the clock ticks on its own, between whatever fixes arrive. */
        private const val TICK_INTERVAL_MILLIS = 1_000L
    }
}
