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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import dev.samuelq.gpx.MainActivity
import dev.samuelq.gpx.R
import dev.samuelq.gpx.core.analysis.TrackAnalyzer
import dev.samuelq.gpx.core.analysis.haversineMeters
import dev.samuelq.gpx.core.model.TrackPoint
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

        val file = File(recordingsDir(this), WAL_NAME)
        wal = RecordingWal.open(file)

        startForegroundNotification()
        publish()

        val source = LocationSource(this)
        collection = source.fixes()
            .onEach(::onFix)
            .launchIn(scope)
    }

    private fun onFix(point: TrackPoint) {
        if (paused) return

        val previous = lastPoint
        if (previous != null) {
            val meters = haversineMeters(previous, point)
            val seconds = secondsBetween(previous, point)
            distanceMeters += meters
            if (seconds > 0) {
                currentSpeedMps = meters / seconds
                // The analyzer's own threshold, so the live number and the one on the
                // track screen afterwards cannot disagree.
                if (meters / seconds >= TrackAnalyzer.MOVING_SPEED_THRESHOLD_MPS) {
                    movingSeconds += seconds
                }
            }
        }

        lastPoint = point
        pointCount++
        wal?.append(point)

        publish()
        updateNotification()
    }

    private fun pause() {
        if (paused) return
        paused = true
        // A pause is a gap in the track, not a straight line across it. Mark it now so
        // the recovered file shows the break even if the app dies while paused.
        wal?.appendBreak()
        lastPoint = null
        currentSpeedMps = null
        publish()
        updateNotification()
    }

    private fun resume() {
        if (!paused) return
        paused = false
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
                    if (track == null) {
                        file?.delete()
                        container.recordingController.emit(RecordingEvent.Discarded)
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
                                    RecordingEvent.Failed(it.message ?: "Could not save")
                                )
                            },
                        )
                    }
                }
            } finally {
                container.recordingController.update(RecordingState.Idle)
                ServiceCompat.stopForeground(this@RecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
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
                "${Formatters.distance(distanceMeters)}  ·  ${Formatters.duration(movingSeconds)}"
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

        private fun secondsBetween(from: TrackPoint, to: TrackPoint): Double {
            val a = from.time ?: return 0.0
            val b = to.time ?: return 0.0
            return (b.toEpochMilli() - a.toEpochMilli()) / 1000.0
        }
    }
}
