package dev.samuelq.gpx.data.record

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.TtsSpan
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import dev.samuelq.gpx.MainActivity
import dev.samuelq.gpx.R
import dev.samuelq.gpx.core.model.UnitSystem
import dev.samuelq.gpx.ui.format.Formatters
import dev.samuelq.gpx.ui.format.spokenDuration

internal class NotificationContent(
    val status: RecordingStatus,
    /** Elapsed time is counting, so the notification's chronometer can show it. */
    val timing: Boolean,
    val distanceMeters: Double,
    val totalSeconds: Double,
    val units: UnitSystem,
)

internal class RecordingNotifications(private val service: Service) {

    private var notifiedAt = 0L
    private var shown: Shown? = null

    /** What a notification says, beyond the chronometer's own ticking. */
    private data class Shown(val status: RecordingStatus, val timing: Boolean, val text: String)

    /** False if the system refused: location permission gone, or started from the background. */
    fun startForeground(content: NotificationContent): Boolean {
        createChannel()
        try {
            ServiceCompat.startForeground(
                service,
                NOTIFICATION_ID,
                build(content),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Not allowed to record", e)
            return false
        } catch (e: IllegalStateException) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                e !is ForegroundServiceStartNotAllowedException
            ) {
                throw e
            }
            Log.w(TAG, "Not allowed to record", e)
            return false
        }
        return true
    }

    /** When what it says changes; a new distance at most every [MIN_INTERVAL_MILLIS], as it can each fix. */
    fun update(content: NotificationContent) {
        val now = SystemClock.elapsedRealtime()
        val last = shown
        val next = shownOf(content)
        if (next == last) return
        if (last != null && last.copy(text = next.text) == next && now - notifiedAt < MIN_INTERVAL_MILLIS) return
        service.getSystemService<NotificationManager>()?.notify(NOTIFICATION_ID, build(content))
    }

    fun remove() = ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)

    private fun shownOf(content: NotificationContent): Shown {
        val distance = Formatters(content.units).distance(content.distanceMeters)
        // The chronometer shows a running time, so only a stopped one is in the text.
        val text = if (content.timing) distance else "$distance${SEPARATOR}${Formatters.duration(content.totalSeconds)}"
        return Shown(content.status, content.timing, text)
    }

    private fun build(content: NotificationContent): Notification {
        val open = activity(null)
        val paused = content.status == RecordingStatus.PAUSED
        val distance = Formatters(content.units).distance(content.distanceMeters)
        val text = if (content.timing) {
            distance
        } else {
            spokenText(
                distance,
                Formatters.duration(content.totalSeconds),
                service.resources.spokenDuration(content.totalSeconds),
            )
        }
        shown = shownOf(content)
        notifiedAt = SystemClock.elapsedRealtime()

        return NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            // As the sheet says it.
            .setContentTitle(
                service.getString(
                    when (content.status) {
                        RecordingStatus.WAITING -> R.string.record_waiting_for_fix
                        RecordingStatus.RECORDING -> R.string.record_notification_active
                        RecordingStatus.LOCATION_OFF -> R.string.record_location_is_off
                        RecordingStatus.PAUSED -> R.string.record_notification_paused
                    },
                ),
            )
            .setContentText(text)
            .setUsesChronometer(content.timing)
            .setShowWhen(content.timing)
            .setWhen(System.currentTimeMillis() - (content.totalSeconds * 1000).toLong())
            // Recording starts on a tap, so the notification shouldn't wait out the usual delay.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
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
            service.getString(R.string.record_notification_active),
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

        const val TAG = "RecordingNotifications"
    }
}

/** "12.3 km  ·  1:02:03", read with the duration in words and the dot as a pause. */
private fun spokenText(distance: String, duration: String, spokenDuration: String): CharSequence =
    SpannableStringBuilder(distance).apply {
        append(SEPARATOR, TtsSpan.TextBuilder(", ").build(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        append(duration, TtsSpan.TextBuilder(spokenDuration).build(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

private const val SEPARATOR = "  ·  "
