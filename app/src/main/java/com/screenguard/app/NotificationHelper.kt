package com.screenguard.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** The persistent, non-dismissible monitoring disclosure notification. */
object NotificationHelper {

    const val CHANNEL_ID = "monitoring"
    const val NOTIFICATION_ID = 42

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(
            CHANNEL_ID,
            "Screen monitoring status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps you informed that ScreenGuard is actively monitoring this device."
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    fun monitoringNotification(ctx: Context): Notification {
        ensureChannel(ctx)
        val intent = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            ctx, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(ctx.getString(R.string.notif_title))
            .setContentText(ctx.getString(R.string.notif_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .build()
    }
}
