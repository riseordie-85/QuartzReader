package com.tim.reader.stats

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.tim.reader.MainActivity
import com.tim.reader.R
import com.tim.reader.appScope
import com.tim.reader.data.LibraryRepository
import com.tim.reader.library.DeviceSettings
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The optional evening nudge. An alarm fires near the chosen time each day, checks today's
 * reading, and only notifies when there's still some to do. Nothing leaves the phone.
 */
object Reminders {
    private const val CHANNEL = "reminders"
    private const val NOTIFICATION_ID = 1001
    private const val REQUEST = 7

    /** Sets the next reminder, or cancels it when reminders are off. */
    fun schedule(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(context)
        alarms.cancel(pending)
        if (!DeviceSettings.reminderOn) return
        val zone = ZoneId.systemDefault()
        val time = LocalTime.of(DeviceSettings.reminderMinutes / 60, DeviceSettings.reminderMinutes % 60)
        var next = LocalDate.now(zone).atTime(time).atZone(zone)
        if (!next.toInstant().isAfter(java.time.Instant.now().plusSeconds(60))) next = next.plusDays(1)
        // Inexact but allowed while the phone is idle, so it arrives near the time and needs no special permission.
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), pending)
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST,
        Intent(context, ReminderReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun canNotify(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    internal fun notify(context: Context, title: String, text: String) {
        if (!canNotify(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Reading reminders", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context,
            0,
            // Brings the app back as it was, without closing a book that's open.
            (context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent(context, MainActivity::class.java))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }
}

/** Fires once a day near the reminder time, and again after the phone restarts to set the next one. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        DeviceSettings.ensureLoaded(app)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Reminders.schedule(app)
            return
        }
        val done = goAsync()
        appScope.launch {
            try {
                if (DeviceSettings.reminderOn) {
                    reminderMessage(LibraryRepository.get(app).readingHistory())?.let { (title, text) -> Reminders.notify(app, title, text) }
                }
            } finally {
                Reminders.schedule(app)
                done.finish()
            }
        }
    }
}
