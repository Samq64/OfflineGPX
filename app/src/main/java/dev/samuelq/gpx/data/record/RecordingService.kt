package dev.samuelq.gpx.data.record

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.annotation.StringRes
import dev.samuelq.gpx.GpxApplication
import dev.samuelq.gpx.R
import dev.samuelq.gpx.core.analysis.TrackAnalyzer
import dev.samuelq.gpx.core.model.TrackPoint
import dev.samuelq.gpx.core.model.TrackPoints
import dev.samuelq.gpx.data.track.TrackLabel
import java.io.IOException
import java.time.Instant
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

/** Foreground service owning a recording; commands arrive as intents from [RecordingController]. */
class RecordingService : Service() {

    private val container get() = (application as GpxApplication).container
    private val controller get() = container.recordingController

    /** All fields below and the WAL are confined to this; IO since each fix is a flushed write. */
    private val recorder = Dispatchers.IO.limitedParallelism(1)

    /** A failed write ends the recording rather than the process. */
    private val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + recorder + CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "Recording failed", e)
            scope.launch { fail() }
        },
    )

    /** Commands suspend and must not interleave. */
    private val commands = Mutex()

    private val notifications = RecordingNotifications(this)

    /** Non-null while paused too, so a START then can't reopen the WAL over the ride. */
    private var session: RecordingSession? = null
    private var collection: Job? = null
    private var wal: RecordingWal? = null

    /** Keeps the duration moving between fixes. */
    private var ticker: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        // Immediately: a START queued behind a save would miss the startForegroundService deadline.
        if (action == ACTION_START && !notifications.startForeground(notificationContent())) {
            scope.launch {
                commands.withLock {
                    if (session == null) {
                        controller.emit(RecordingEvent.Failed(R.string.record_location_denied))
                        stopSelf(startId)
                    }
                }
            }
            return START_NOT_STICKY
        }
        scope.launch {
            commands.withLock {
                when (action) {
                    ACTION_START -> start()
                    ACTION_PAUSE -> pause()
                    ACTION_RESUME -> resume()
                    ACTION_HOLD -> hold()
                    ACTION_RELEASE -> release()
                    ACTION_STOP -> stop(save = true, label = intent.label())
                    ACTION_DISCARD -> stop(save = false, label = intent.label())
                    ACTION_WAYPOINT -> addWaypoint(intent.getStringExtra(EXTRA_WAYPOINT_NAME) ?: "")
                }
                // By id, so a START queued behind a stop still gets its recording.
                if (session == null) stopSelf(startId)
            }
        }
        // Never resume location sampling unasked.
        return START_NOT_STICKY
    }

    private suspend fun start() {
        if (session != null) return
        val settings = container.settingsRepository.settings.value
        session = RecordingSession(
            maxAccuracyMeters = settings.maxAccuracyMeters,
            clock = SystemClock::elapsedRealtime,
        )

        // Again: a preceding stop's finish() may have removed the first one.
        if (!notifications.startForeground(notificationContent())) {
            abandon(R.string.record_location_denied)
            return
        }

        val source = container.locationSource
        // With location off, requestLocationUpdates succeeds but never calls back.
        if (!source.isGpsEnabled) {
            abandon(R.string.record_location_off)
            return
        }

        // Set aside any unsaved ride for recovery so this one starts from an empty log.
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
            .fixes(onAvailable = ::onLocationAvailable)
            .onEach(::onFix)
            .launchIn(scope)
    }

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
        controller.updateTrace(TrackPoints.EMPTY)
        notifications.remove()
    }

    /** Keeps recording, but says so since it otherwise looks like standing still. On the main thread. */
    private fun onLocationAvailable(available: Boolean) {
        scope.launch {
            val session = session ?: return@launch
            if (session.locationOff == !available) return@launch
            session.locationOff = !available
            if (!available) controller.emit(RecordingEvent.Failed(R.string.record_location_lost))
            publish()
            notifications.update(notificationContent())
        }
    }

    private fun onFix(fix: TrackPoint) {
        val session = session ?: return
        session.onFix(fix)?.let { point ->
            wal?.append(point)
            notifications.update(notificationContent())
            if (session.traceDue) {
                publishTrace(session)
                // With the line, so the first fix flips the status as the puck appears.
                publish()
            }
        }
        // Otherwise the ticker publishes: fixes come about as often, but out of step with it.
    }

    /** While Stop's dialog is up, so the ride ends when Stop was tapped, not answered. */
    private fun hold() {
        val session = session ?: return
        session.hold(Instant.now())?.let { wal?.append(it) }
        publish()
        notifications.update(notificationContent())
    }

    private fun release() {
        session?.release()
        publish()
        notifications.update(notificationContent())
    }

    private fun addWaypoint(name: String) {
        val waypoint = session?.addWaypoint(name, Instant.now()) ?: return
        wal?.appendWaypoint(waypoint)
        publish()
    }

    /** The one regular publish: elapsed time and each fix's numbers alike. */
    private fun startTicker() {
        ticker = scope.launch {
            while (true) {
                delay(TICK_INTERVAL_MILLIS)
                publish()
            }
        }
    }

    /** Stops sampling to save battery and writes a real `<trkseg>` boundary. */
    private suspend fun pause() {
        val session = session ?: return
        if (session.paused) return
        session.pause(Instant.now())?.let { wal?.append(it) }

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

        val source = container.locationSource
        // Stay paused and say so rather than resuming into silence.
        if (!source.isGpsEnabled) {
            controller.emit(RecordingEvent.Failed(R.string.record_location_off))
            return
        }

        session.resume()
        session.locationOff = false
        collectFixes(source)
        publish()
        notifications.update(notificationContent())
    }

    private fun Intent.label() =
        TrackLabel(getStringExtra(EXTRA_NAME).orEmpty(), getStringExtra(EXTRA_CATEGORY).orEmpty())

    /** A blank name uses the default; a discard keeps [label] for an undo. */
    private suspend fun stop(save: Boolean, label: TrackLabel) {
        if (session == null) return

        // Joined, not just cancelled: a fix mid-append must land before the log closes.
        collection?.cancelAndJoin()
        collection = null

        val log = wal ?: return finish()
        wal = null

        try {
            log.close()
            if (!save) {
                val aside = container.recordingRecovery.setAside(log.file, label)
                controller.emit(RecordingEvent.Discarded(aside))
                return
            }
            // What was logged, analysed as it will be saved, rather than the running numbers.
            val track = RecordingWal.recover(log.file)
            val profile = track?.let(TrackAnalyzer::analyze)
            if (track == null || profile == null || !RecordingRecovery.isSaveable(profile)) {
                log.file.delete()
                controller.emit(
                    RecordingEvent.Failed(
                        if (track == null) R.string.record_nothing_recorded else R.string.record_no_distance,
                    ),
                )
                return
            }
            container.trackRepository.saveRecording(track.labelled(label), profile).fold(
                onSuccess = {
                    log.file.delete()
                    controller.emit(RecordingEvent.Saved(it))
                },
                onFailure = { keepForRecovery() },
            )
        } catch (e: IOException) {
            Log.e(TAG, "Could not read back the recording", e)
            keepForRecovery()
        } finally {
            finish()
        }
    }

    /** Claimed as a crash's log would be, so recovery offers it without waiting for a relaunch. */
    private suspend fun keepForRecovery() {
        container.recordingRecovery.claim()
        controller.emit(RecordingEvent.Unsaved)
    }

    private fun publishTrace(session: RecordingSession) = controller.updateTrace(session.trace())

    private fun publish() {
        session?.let { controller.update(it.state()) }
    }

    private fun notificationContent() = NotificationContent(
        status = session?.state()?.status ?: RecordingStatus.WAITING,
        timing = session?.timing == true,
        distanceMeters = session?.distanceMeters ?: 0.0,
        totalSeconds = session?.totalSeconds ?: 0.0,
        units = container.settingsRepository.settings.value.units,
    )

    override fun onDestroy() {
        scope.cancel()
        // Not in scope, which is cancelled, nor blocking, as this is the main thread. On the
        // recorder, so it queues behind an append still running. Lost if the process dies
        // first, which costs nothing: every line is already flushed.
        CoroutineScope(recorder).launch { wal?.let { runCatching(it::close) } }
        super.onDestroy()
    }

    companion object {
        internal const val ACTION_START = "dev.samuelq.gpx.RECORD_START"
        internal const val ACTION_PAUSE = "dev.samuelq.gpx.RECORD_PAUSE"
        internal const val ACTION_RESUME = "dev.samuelq.gpx.RECORD_RESUME"
        internal const val ACTION_HOLD = "dev.samuelq.gpx.RECORD_HOLD"
        internal const val ACTION_RELEASE = "dev.samuelq.gpx.RECORD_RELEASE"
        internal const val ACTION_STOP = "dev.samuelq.gpx.RECORD_STOP"
        internal const val ACTION_DISCARD = "dev.samuelq.gpx.RECORD_DISCARD"
        internal const val ACTION_WAYPOINT = "dev.samuelq.gpx.RECORD_WAYPOINT"
        internal const val EXTRA_NAME = "name"
        internal const val EXTRA_CATEGORY = "category"
        internal const val EXTRA_WAYPOINT_NAME = "waypoint_name"

        private const val TAG = "RecordingService"

        private const val TICK_INTERVAL_MILLIS = 1_000L
    }
}
