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

internal class NotificationContent(
    val paused: Boolean,
    val distanceMeters: Double,
    val totalSeconds: Double,
    val units: UnitSystem,
)

internal class RecordingNotifications(private val service: Service) {

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

    /** @param force bypass the throttle; false for the per-second tick. */
    fun update(content: NotificationContent, force: Boolean = true) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - notifiedAt < MIN_INTERVAL_MILLIS) return
        notifiedAt = now
        service.getSystemService<NotificationManager>()?.notify(NOTIFICATION_ID, build(content))
    }

    fun remove() = ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)

    private fun build(content: NotificationContent): Notification {
        val open = activity(null)
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
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                0,
                service.getString(if (paused) R.string.record_resume else R.string.record_pause),
                command(if (paused) RecordingService.ACTION_RESUME else RecordingService.ACTION_PAUSE),
            )
            // Opens the app to ask save or discard, rather than stopping unasked.
            .addAction(0, service.getString(R.string.record_stop), activity(MainActivity.ACTION_REQUEST_STOP))
            .build()
    }

    /** Single-top, so it reaches a running app through onNewIntent. */
    private fun activity(action: String?): PendingIntent = PendingIntent.getActivity(
        service,
        action.hashCode(),
        Intent(service, MainActivity::class.java)
            .setAction(action)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

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

        const val MIN_INTERVAL_MILLIS = 5_000L
    }
}
