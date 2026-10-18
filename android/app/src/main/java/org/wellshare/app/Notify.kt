package org.wellshare.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** The always-visible "WellShare is sharing" notification, with a quick SOS button. */
object Notify {
    private const val CHANNEL = "sharing"
    private const val ID = 1

    fun allowed(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true

    fun showSharing(context: Context) {
        if (!Prefs(context).isRegistered || !allowed(context)) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Sharing status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows that WellShare is sharing with your mentor, with a quick SOS button"
                setShowBadge(false)
            }
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val sos = PendingIntent.getActivity(
            context, 1, Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_SOS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_share)
            .setContentTitle("Sharing with your mentor")
            .setContentText("Screen time, app timers and websites · Tap SOS in an emergency")
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(Notification.Action.Builder(null, "SOS", sos).build())
            .build()
        try { nm.notify(ID, n) } catch (_: SecurityException) {}
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(ID)
    }
}
