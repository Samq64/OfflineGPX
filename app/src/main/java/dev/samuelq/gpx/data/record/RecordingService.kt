package dev.samuelq.gpx.data.record

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import dev.samuelq.gpx.MainActivity
import dev.samuelq.gpx.R
import dev.samuelq.gpx.core.analysis.Fix
import dev.samuelq.gpx.core.analysis.FixFilter
import dev.samuelq.gpx.core.analysis.SpeedWindow
import dev.samuelq.gpx.core.analysis.haversineMeters
import dev.samuelq.gpx.core.model.TrackPoint
import dev.samuelq.gpx.core.model.Waypoint
import dev.samuelq.gpx.data.settings.Settings
import dev.samuelq.gpx.GpxApplication
import dev.samuelq.gpx.ui.format.Formatters
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
import java.io.File
import java.io.IOException
import java.time.Instant

/**
 * Owns a recording for as long as it runs.
 *
 * A foreground service because that is the only honest way to keep sampling with the
 * screen off: the notification is the user's standing reminder that the app has their
 * location, and it is how they stop without hunting for the app.
 */
class RecordingService : Service() {

    private val container get() = (application as GpxApplication).container

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

    private var collection: Job? = null
    private var wal: RecordingWal? = null

    /**
     * True from START until the recording ends, pause included. [collection] is null while
     * paused and so can't answer this - and a START arriving then would reopen the WAL in
     * append mode over the ride already in it.
     */
    private var recording = false

    private var paused = false
    private var pointCount = 0
    private var distanceMeters = 0.0
    private var lastPoint: TrackPoint? = null
    private var currentSpeedMps: Double? = null
    private var lastAccuracyMeters: Double? = null

    /** Dropped by hand so far this ride. The WAL is the record of truth; recovery rebuilds
     *  this same list from it, so this copy exists only to publish without waiting on that. */
    private val waypoints = mutableListOf<Waypoint>()

    /**
     * When the first point was logged, on the clock [totalSeconds] reads. Null while waiting
     * for a fix: the ride starts where its data does, as the saved track's duration does.
     */
    private var startedAtRealtime: Long? = null
    /**
     * Republishes on a plain clock rather than waiting on the next fix, so the duration
     * shown keeps moving through a GPS outage, a stretch of points the filter rejects, or
     * a pause - a fix is not the only thing that means time passed, and neither is motion.
     * Runs for the whole recording, pause included, so it reads the same span [totalSeconds]
     * does: the sheet's "elapsed" is start to finish with no time carved out of it either.
     */
    private var ticker: Job? = null

    /** When the notification last went out, for the throttle in [updateNotification]. */
    private var notifiedAt = 0L

    /**
     * Built from settings as they stood when the recording started - a threshold changed
     * mid-ride would make the two halves of one track mean different things.
     */
    private var filter = FixFilter()
    private val speedWindow = SpeedWindow()

    /** Fixed for the run, for the same reason, and shown so the UI can explain a refusal. */
    private var accuracyLimitMeters = Settings.Defaults.maxAccuracyMeters

    /**
     * The route so far, kept in memory purely so the map can draw it live. The WAL is
     * still the record of truth; this is a copy that dies with the service.
     */
    private val tracePoints = mutableListOf<TrackPoint>()
    private val traceSegmentStarts = mutableListOf<Int>()
    private var traceStartsSegment = true
    private var tracePublishedAt = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        // Now, not once the command gets its turn: a START queued behind a long save would
        // miss the deadline startForegroundService sets, and that is a crash.
        if (action == ACTION_START) startForegroundNotification()
        scope.launch {
            commands.withLock {
                when (action) {
                    ACTION_START -> start()
                    ACTION_PAUSE -> pause()
                    ACTION_RESUME -> resume()
                    ACTION_STOP -> stop(save = true)
                    ACTION_DISCARD -> stop(save = false)
                    ACTION_WAYPOINT -> addWaypoint(intent?.getStringExtra(EXTRA_DESCRIPTION) ?: "")
                }
                // By id, so a START queued behind a stop still gets its recording.
                if (!recording) stopSelf(startId)
            }
        }
        // Not sticky: a restart with no intent cannot know whether the user still wants to
        // be recorded, and resuming location sampling unasked is exactly the wrong default.
        return START_NOT_STICKY
    }

    private suspend fun start() {
        if (recording) return
        recording = true

        paused = false
        pointCount = 0
        distanceMeters = 0.0
        lastPoint = null
        currentSpeedMps = null
        lastAccuracyMeters = null
        waypoints.clear()
        startedAtRealtime = null

        val settings = container.settingsRepository.settings.value
        accuracyLimitMeters = settings.maxAccuracyMeters
        filter = FixFilter(
            maxAccuracyMeters = settings.maxAccuracyMeters,
            minDisplacementMeters = settings.minDisplacementMeters,
        )
        speedWindow.reset()
        tracePoints.clear()
        traceSegmentStarts.clear()
        traceStartsSegment = true
        tracePublishedAt = 0

        // Again, now the state it shows is this ride's. Also covers a START that waited
        // behind a stop, whose finish() took the first one down.
        startForegroundNotification()

        val source = LocationSource(this)
        // The one failure the old code let pass in silence. Location off system-wide means
        // requestLocationUpdates succeeds and then never calls back, which looks exactly
        // like a recording that is waiting for a fix and never gets one.
        if (!source.isGpsEnabled) {
            abandon(R.string.record_location_off)
            return
        }

        // A ride whose save failed is still in the log. Set aside first, for the next
        // launch to ask about, so this one starts from an empty file.
        if (!container.trackRepository.claimAbandonedRecording()) {
            abandon(R.string.record_save_failed)
            return
        }

        wal = RecordingWal.open(File(recordingsDir(this), WAL_NAME))

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
        container.recordingController.emit(RecordingEvent.Failed(messageRes))
        finish()
    }

    /** The log stopped taking writes. Whatever reached it stays on disk for recovery. */
    private fun fail() {
        if (!recording) return
        collection?.cancel()
        collection = null
        wal?.let { runCatching(it::close) }
        wal = null
        container.recordingController.emit(RecordingEvent.Failed(R.string.record_save_failed))
        finish()
        stopSelf()
    }

    private fun finish() {
        recording = false
        ticker?.cancel()
        ticker = null
        container.recordingController.update(RecordingState.Idle)
        container.recordingController.updateTrace(LiveTrace.Empty)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    /**
     * Location switched off mid-ride. The recording stays up - the points already logged
     * are real, and the provider often comes back - but the user is told, because from the
     * outside this is indistinguishable from standing still.
     */
    private fun onLocationUnavailable() {
        container.recordingController.emit(RecordingEvent.Failed(R.string.record_location_lost))
    }

    /**
     * One reading. [FixFilter] alone decides what counts as travel - the duration shown
     * doesn't wait on it, or on this being called at all; see [ticker].
     */
    private fun onFix(fix: Fix) {
        if (paused) return

        val at = fix.point.time ?: return
        lastAccuracyMeters = fix.accuracyMeters

        filter.pointFor(fix)?.let { filtered ->
            // The filter decides distance and displacement off bare position; the accuracy
            // that earned this point its place is stamped on afterwards, purely to write
            // down for whatever reopens the file later.
            val point = filtered.copy(accuracyMeters = fix.accuracyMeters)
            if (startedAtRealtime == null) startedAtRealtime = SystemClock.elapsedRealtime()
            lastPoint?.let { distanceMeters += haversineMeters(it, point) }
            lastPoint = point
            pointCount++
            wal?.append(point)

            if (traceStartsSegment) {
                traceSegmentStarts += tracePoints.size
                traceStartsSegment = false
            }
            tracePoints += point
            if (tracePoints.size - tracePublishedAt >= TRACE_PUBLISH_EVERY) publishTrace()
        }

        speedWindow.add(at.toEpochMilli() / 1000.0, distanceMeters)
        currentSpeedMps = speedWindow.speedMps

        publish()
        // Throttled, unlike `publish`: see updateNotification.
        updateNotification(force = false)
    }

    /**
     * Drops a waypoint at the last known position, stamped with the time this was called
     * rather than the fix's own - a description can take a while to type, and the moment
     * worth marking is when the button was pressed, not whenever the last fix happened to
     * land. Silently does nothing before the first fix: there is nowhere to put it yet.
     */
    private fun addWaypoint(description: String) {
        if (!recording) return
        val point = lastPoint ?: return
        val waypoint = Waypoint(
            point.copy(time = Instant.now()),
            description.trim().takeIf(String::isNotEmpty),
        )
        waypoints += waypoint
        wal?.appendWaypoint(waypoint)
        publish()
    }

    /**
     * Wall-clock seconds since the first point, pauses included - the same span
     * [dev.samuelq.gpx.core.analysis.TrackAnalyzer]'s `totalDurationSeconds` measures from
     * the saved file's first and last points, so the live number and the one on the sheet
     * afterwards read the same thing.
     */
    private fun totalSeconds(): Double =
        startedAtRealtime?.let { (SystemClock.elapsedRealtime() - it) / 1000.0 } ?: 0.0

    /** Keeps the duration moving once a second without waiting on a fix to do it. */
    private fun startTicker() {
        ticker = scope.launch {
            while (true) {
                delay(TICK_INTERVAL_MILLIS)
                publish()
                updateNotification(force = false)
            }
        }
    }

    /**
     * Stops sampling outright, which is the one thing auto-detection (a dismounted break
     * already leaves a silence the analyser splits on) cannot do: save the battery, and
     * write a real `<trkseg>` boundary that travels with the file to whatever reads it next.
     */
    private suspend fun pause() {
        if (paused || !recording) return
        paused = true

        collection?.cancelAndJoin()
        collection = null

        // A pause is a gap in the track, not a straight line across it. Mark it now so
        // the recovered file shows the break even if the app dies while paused.
        wal?.appendBreak()
        traceStartsSegment = true
        lastPoint = null
        currentSpeedMps = null
        // Resuming somewhere else must not read as having travelled there: the next fix
        // starts a new run with nothing to measure against.
        filter.reset()
        speedWindow.reset()
        publishTrace()
        publish()
        updateNotification()
    }

    private fun resume() {
        if (!paused || !recording) return

        val source = LocationSource(this)
        // Location can be switched off during a long pause - it is a quick-settings
        // toggle and a paused recording is exactly when someone would reach for it.
        // Staying paused and saying so beats resuming into silence.
        if (!source.isGpsEnabled) {
            container.recordingController.emit(RecordingEvent.Failed(R.string.record_location_off))
            return
        }

        paused = false
        collectFixes(source)
        publish()
        updateNotification()
    }

    private suspend fun stop(save: Boolean) {
        // A STOP with no recording behind it has nothing to save and nothing to say.
        if (!recording) return

        // Joined, not just cancelled: a fix mid-append must land before the log closes.
        collection?.cancelAndJoin()
        collection = null

        val log = wal ?: return finish()
        wal = null

        try {
            if (!save) {
                log.discard()
                container.recordingController.emit(RecordingEvent.Discarded)
                return
            }
            log.close()
            val track = RecordingWal.recover(log.file, name = null)
            // Nothing in the log, or fixes that never went anywhere - either way a library
            // row of three empty charts, and neither the user's decision, so neither is
            // called "discarded". The log only ever holds this ride; see [start].
            if (track == null || distanceMeters < MIN_SAVEABLE_DISTANCE_METERS) {
                log.file.delete()
                container.recordingController.emit(
                    RecordingEvent.Failed(
                        if (track == null) {
                            R.string.record_nothing_recorded
                        } else {
                            R.string.record_no_distance
                        }
                    )
                )
                return
            }
            container.trackRepository.saveRecording(track).fold(
                onSuccess = {
                    log.file.delete()
                    container.recordingController.emit(RecordingEvent.Saved(it))
                },
                onFailure = {
                    // Keep the log. A failed save that also deleted the ride would be
                    // the worst outcome this class exists to prevent.
                    container.recordingController.emit(
                        RecordingEvent.Failed(R.string.record_save_failed)
                    )
                },
            )
        } catch (e: IOException) {
            Log.e(TAG, "Could not read back the recording", e)
            container.recordingController.emit(RecordingEvent.Failed(R.string.record_save_failed))
        } finally {
            finish()
        }
    }

    /**
     * Hands the map a snapshot of the route so far.
     *
     * Throttled, unlike [publish]: every published trace re-projects every route the map
     * is drawing, and once a second for hours is a lot of work to show a line growing by
     * a pixel.
     */
    private fun publishTrace() {
        tracePublishedAt = tracePoints.size
        container.recordingController.updateTrace(
            LiveTrace(tracePoints.toList(), traceSegmentStarts.toIntArray())
        )
    }

    private fun publish() {
        container.recordingController.update(
            RecordingState.Active(
                paused = paused,
                pointCount = pointCount,
                distanceMeters = distanceMeters,
                totalSeconds = totalSeconds(),
                lastPoint = lastPoint,
                currentSpeedMps = currentSpeedMps,
                accuracyMeters = lastAccuracyMeters,
                accuracyLimitMeters = accuracyLimitMeters,
                waypoints = waypoints.toList(),
            )
        )
    }

    private fun startForegroundNotification() {
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
    }

    /**
     * @param force post regardless of how recently the last one did. True for pause and
     *   resume; false for the per-fix tick, which otherwise rebuilds the whole
     *   Notification once a second for a ride to move a readout by a few metres.
     */
    private fun updateNotification(force: Boolean = true) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - notifiedAt < NOTIFICATION_INTERVAL_MILLIS) return
        notifiedAt = now
        getSystemService<NotificationManager>()?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val title = getString(
            if (paused) R.string.record_notification_paused else R.string.record_notification_active
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(
                // Read per notification rather than cached: the units are a display
                // preference, and a notification still on screen from before the switch
                // should catch up with the rest of the app on its next tick.
                buildString {
                    val formatters = Formatters(container.settingsRepository.settings.value.units)
                    append(formatters.distance(distanceMeters))
                    append("  ·  ")
                    append(Formatters.duration(totalSeconds()))
                }
            )
            .setContentIntent(open)
            .setOngoing(true)
            // The numbers change every second; silence keeps that from being a nuisance.
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                0,
                getString(if (paused) R.string.record_resume else R.string.record_pause),
                command(if (paused) ACTION_RESUME else ACTION_PAUSE),
            )
            .addAction(0, getString(R.string.record_stop), command(ACTION_STOP))
            .build()
    }

    private fun command(action: String): PendingIntent = PendingIntent.getService(
        this,
        action.hashCode(),
        Intent(this, RecordingService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createChannel() {
        val manager = getSystemService<NotificationManager>() ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.record_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.record_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        scope.cancel()
        // Queued behind whatever the recorder is running, so it can't land mid-append.
        CoroutineScope(recorder).launch { wal?.let { runCatching(it::close) } }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "dev.samuelq.gpx.RECORD_START"
        const val ACTION_PAUSE = "dev.samuelq.gpx.RECORD_PAUSE"
        const val ACTION_RESUME = "dev.samuelq.gpx.RECORD_RESUME"
        const val ACTION_STOP = "dev.samuelq.gpx.RECORD_STOP"
        const val ACTION_DISCARD = "dev.samuelq.gpx.RECORD_DISCARD"
        const val ACTION_WAYPOINT = "dev.samuelq.gpx.RECORD_WAYPOINT"

        private const val EXTRA_DESCRIPTION = "description"

        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1

        /** Fixes between live-trace snapshots. At 1 Hz, the map's line grows every 5s. */
        private const val TRACE_PUBLISH_EVERY = 5

        /** The floor on how often the ongoing notification is rebuilt while sampling. */
        private const val NOTIFICATION_INTERVAL_MILLIS = 5_000L

        /** How often the clock ticks on its own, between whatever fixes arrive. */
        private const val TICK_INTERVAL_MILLIS = 1_000L

        /**
         * Below this a recording is not a track - not zero, since a handful of fixes that
         * happened to clear the displacement floor is the same nothing as none at all.
         * Shared with crash recovery, which must not resurrect what a clean stop would toss.
         */
        const val MIN_SAVEABLE_DISTANCE_METERS = 10.0

        /** The in-progress log. Fixed name: there is only ever one recording. */
        const val WAL_NAME = "recording.wal"

        /**
         * Prefix of the names recovery moves an abandoned [WAL_NAME] to before reading it.
         * One per ride, so a claim a failed save left behind is never overwritten. See
         * `GpxTrackRepository.claimAbandonedRecording`.
         */
        const val WAL_RECOVERY_PREFIX = "recovering-"

        /** Where recordings and their logs live. App-private: no permission, and ours to delete. */
        fun recordingsDir(context: Context): File =
            File(context.filesDir, "recordings").apply { mkdirs() }

        fun send(context: Context, action: String) {
            val intent = Intent(context, RecordingService::class.java).setAction(action)
            if (action == ACTION_START) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** [description] may be blank - a waypoint with nothing typed is still one. */
        fun sendWaypoint(context: Context, description: String) {
            val intent = Intent(context, RecordingService::class.java)
                .setAction(ACTION_WAYPOINT)
                .putExtra(EXTRA_DESCRIPTION, description)
            context.startService(intent)
        }
    }
}
