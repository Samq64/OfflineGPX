package dev.samuelq.gpx.data.record

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import dev.samuelq.gpx.MainActivity
import dev.samuelq.gpx.R
import dev.samuelq.gpx.core.model.UnitSystem
import dev.samuelq.gpx.ui.format.Formatters

/** What the notification shows. */
internal class NotificationContent(
    val paused: Boolean,
    val distanceMeters: Double,
    val totalSeconds: Double,
    /** Read per post: a notification still up from before a switch catches up on its next. */
    val units: UnitSystem,
)

/**
 * The recording's ongoing notification: the user's standing reminder that the app has
 * their location, and how they stop without hunting for the app.
 */
internal class RecordingNotifications(private val service: Service) {

    /** When the notification last went out, for the throttle in [update]. */
    private var notifiedAt = 0L

    fun startForeground(content: NotificationContent) {
        createChannel()
        ServiceCompat.startForeground(
            service,
            NOTIFICATION_ID,
            build(content),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
    }

    /**
     * @param force post regardless of how recently the last one did. True for pause and
     *   resume; false for the per-second tick, which would otherwise rebuild the whole
     *   Notification every second to move a readout by a few metres.
     */
    fun update(content: NotificationContent, force: Boolean = true) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - notifiedAt < MIN_INTERVAL_MILLIS) return
        notifiedAt = now
        service.getSystemService<NotificationManager>()?.notify(NOTIFICATION_ID, build(content))
    }

    fun remove() = ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)

    private fun build(content: NotificationContent): Notification {
        val open = PendingIntent.getActivity(
            service,
            0,
            Intent(service, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val paused = content.paused
        val text = Formatters(content.units).distance(content.distanceMeters) +
            "  ·  " + Formatters.duration(content.totalSeconds)

        return NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(
                service.getString(
                    if (paused) R.string.record_notification_paused else R.string.record_notification_active
                )
            )
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            // The numbers change every second; silence keeps that from being a nuisance.
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                0,
                service.getString(if (paused) R.string.record_resume else R.string.record_pause),
                command(if (paused) RecordingService.ACTION_RESUME else RecordingService.ACTION_PAUSE),
            )
            .addAction(0, service.getString(R.string.record_stop), command(RecordingService.ACTION_STOP))
            .build()
    }

    private fun command(action: String): PendingIntent = PendingIntent.getService(
        service,
        action.hashCode(),
        Intent(service, RecordingService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createChannel() {
        val manager = service.getSystemService<NotificationManager>() ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            service.getString(R.string.record_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = service.getString(R.string.record_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "recording"
        const val NOTIFICATION_ID = 1

        /** The floor on how often the ongoing notification is rebuilt while sampling. */
        const val MIN_INTERVAL_MILLIS = 5_000L
    }
}
