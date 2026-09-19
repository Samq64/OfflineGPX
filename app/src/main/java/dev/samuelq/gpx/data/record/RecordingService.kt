package dev.samuelq.gpx.data.record

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import dev.samuelq.gpx.MainActivity
import dev.samuelq.gpx.R
import dev.samuelq.gpx.core.analysis.Fix
import dev.samuelq.gpx.core.analysis.FixFilter
import dev.samuelq.gpx.core.analysis.SpeedWindow
import dev.samuelq.gpx.core.analysis.TrackAnalyzer
import dev.samuelq.gpx.core.analysis.haversineMeters
import dev.samuelq.gpx.core.model.TrackPoint
import dev.samuelq.gpx.data.settings.Settings
import dev.samuelq.gpx.GpxApplication
import dev.samuelq.gpx.ui.format.Formatters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import java.io.File
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var collection: Job? = null
    private var wal: RecordingWal? = null

    private var startedAt: Instant = Instant.EPOCH
    private var paused = false
    private var pointCount = 0
    private var distanceMeters = 0.0
    private var movingSeconds = 0.0
    private var lastPoint: TrackPoint? = null
    private var currentSpeedMps: Double? = null
    private var lastFixAt: Instant? = null
    private var lastAccuracyMeters: Double? = null

    /**
     * What counts as having moved, and how fast. Both live for one recording, and the
     * filter is built from the settings as they stood when that recording started -
     * changing a threshold mid-ride would make the first half and the second half of one
     * track mean different things.
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
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_STOP -> stop(save = true)
            ACTION_DISCARD -> stop(save = false)
            else -> stopSelf()
        }
        // Not sticky: a restart with no intent cannot know whether the user still wants to
        // be recorded, and resuming location sampling unasked is exactly the wrong default.
        return START_NOT_STICKY
    }

    private fun start() {
        if (collection != null) return

        startedAt = Instant.now()
        paused = false
        pointCount = 0
        distanceMeters = 0.0
        movingSeconds = 0.0
        lastPoint = null
        currentSpeedMps = null
        lastFixAt = null
        lastAccuracyMeters = null

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

        // Before anything else: the caller reached us through startForegroundService, so
        // the notification has to go up within seconds whatever happens next - including
        // the refusal below.
        startForegroundNotification()

        val source = LocationSource(this)
        // The one failure the old code let pass in silence. Location off system-wide means
        // requestLocationUpdates succeeds and then never calls back, which looks exactly
        // like a recording that is waiting for a fix and never gets one.
        if (!source.isGpsEnabled) {
            abandon(R.string.record_location_off)
            return
        }

        val file = File(recordingsDir(this), WAL_NAME)
        wal = RecordingWal.open(file)

        publish()
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
        container.recordingController.update(RecordingState.Idle)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
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
     * One reading. Two separate questions: is this a position, and how long has it been?
     *
     * They used to be the same question, which is why a phone on a table recorded a ride -
     * every wander inside the error circle was committed as travel. Only [FixFilter] now
     * decides what is travel; time, speed and the moving clock advance on every reading,
     * believed or not, because a second passed either way. A reading that did not move
     * still comes back as a point - the last position, stamped now - so a stop is written
     * into the file as a stop rather than left as a hole for the analyser to infer.
     */
    private fun onFix(fix: Fix) {
        if (paused) return

        val at = fix.point.time ?: return
        val seconds = lastFixAt?.let { (at.toEpochMilli() - it.toEpochMilli()) / 1000.0 } ?: 0.0
        lastFixAt = at
        lastAccuracyMeters = fix.accuracyMeters

        filter.pointFor(fix)?.let { point ->
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

        // The analyzer's own threshold against the analyzer's own window, so the live
        // moving time and the one on the sheet afterwards cannot disagree.
        if (seconds > 0.0 && (currentSpeedMps ?: 0.0) >= TrackAnalyzer.MOVING_SPEED_THRESHOLD_MPS) {
            movingSeconds += seconds
        }

        publish()
        updateNotification()
    }

    /**
     * What pause is actually for, now that a stop detects itself.
     *
     * [FixFilter] already drops a stationary phone's wander, so a dismounted break leaves
     * a silence in the log that the analyser splits on without being told. Pause is not
     * needed for that any more, and it used to do nothing else: the receiver stayed on at
     * 1 Hz and every fix was thrown away, which is the worst of both outcomes - you lose
     * the data *and* the battery.
     *
     * So it stops sampling outright. That is the thing auto-detection cannot do: a long
     * stop with the GPS off is the difference between a lunch that costs nothing and one
     * that costs an hour of receiver. It also writes a real segment break, which is the
     * other thing an inferred gap is not - a `<trkseg>` boundary travels with the file to
     * whatever reads it next, where our rule about medians does not.
     */
    private fun pause() {
        if (paused) return
        paused = true

        collection?.cancel()
        collection = null

        // A pause is a gap in the track, not a straight line across it. Mark it now so
        // the recovered file shows the break even if the app dies while paused.
        wal?.appendBreak()
        traceStartsSegment = true
        lastPoint = null
        currentSpeedMps = null
        lastFixAt = null
        // Resuming somewhere else must not read as having travelled there: the next fix
        // starts a new run with nothing to measure against.
        filter.reset()
        speedWindow.reset()
        publishTrace()
        publish()
        updateNotification()
    }

    private fun resume() {
        if (!paused) return

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

    private fun stop(save: Boolean) {
        collection?.cancel()
        collection = null

        val log = wal
        wal = null

        scope.launch {
            try {
                if (!save) {
                    log?.discard()
                    container.recordingController.emit(RecordingEvent.Discarded)
                } else {
                    log?.close()
                    val file = log?.file
                    val track = file?.let { RecordingWal.recover(it, name = null) }
                    // Nothing in the log at all, or fixes that never went anywhere. A
                    // library row for either is a track with no route, no speed and no
                    // profile - three empty charts and a name. Both are far more likely
                    // since the fix filter arrived, and neither is the user's decision,
                    // so neither is called "discarded".
                    if (track == null || distanceMeters < MIN_SAVEABLE_DISTANCE_METERS) {
                        file?.delete()
                        container.recordingController.emit(
                            RecordingEvent.Failed(
                                if (track == null) {
                                    R.string.record_nothing_recorded
                                } else {
                                    R.string.record_no_distance
                                }
                            )
                        )
                    } else {
                        val id = container.trackRepository.saveRecording(track, startedAt)
                        id.fold(
                            onSuccess = {
                                file.delete()
                                container.recordingController.emit(RecordingEvent.Saved(it))
                            },
                            onFailure = {
                                // Keep the log. A failed save that also deleted the ride
                                // would be the worst outcome this class exists to prevent.
                                container.recordingController.emit(
                                    RecordingEvent.Failed(R.string.record_save_failed)
                                )
                            },
                        )
                    }
                }
            } finally {
                container.recordingController.update(RecordingState.Idle)
                container.recordingController.updateTrace(LiveTrace.Empty)
                ServiceCompat.stopForeground(this@RecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
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
                startedAt = startedAt,
                pointCount = pointCount,
                distanceMeters = distanceMeters,
                movingSeconds = movingSeconds,
                lastPoint = lastPoint,
                currentSpeedMps = currentSpeedMps,
                accuracyMeters = lastAccuracyMeters,
                accuracyLimitMeters = accuracyLimitMeters,
            )
        )
    }

    private fun startForegroundNotification() {
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            },
        )
    }

    private fun updateNotification() {
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
                    append(Formatters.duration(movingSeconds))
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
        collection?.cancel()
        wal?.close()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "dev.samuelq.gpx.RECORD_START"
        const val ACTION_PAUSE = "dev.samuelq.gpx.RECORD_PAUSE"
        const val ACTION_RESUME = "dev.samuelq.gpx.RECORD_RESUME"
        const val ACTION_STOP = "dev.samuelq.gpx.RECORD_STOP"
        const val ACTION_DISCARD = "dev.samuelq.gpx.RECORD_DISCARD"

        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1

        /** Fixes between live-trace snapshots. At 1 Hz, the map's line grows every 5s. */
        private const val TRACE_PUBLISH_EVERY = 5

        /**
         * Below this a recording is not a track.
         *
         * Not zero, because a handful of accepted fixes that happened to clear the
         * displacement floor is the same nothing as none at all - a few metres of line
         * and three empty charts. Shared with the crash-recovery path, which must not
         * resurrect what a clean stop would have thrown away.
         */
        const val MIN_SAVEABLE_DISTANCE_METERS = 10.0

        /** The in-progress log. Fixed name: there is only ever one recording. */
        const val WAL_NAME = "recording.wal"

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
    }
}
