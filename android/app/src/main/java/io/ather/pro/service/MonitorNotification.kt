package io.ather.pro.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.ather.pro.MainActivity
import io.ather.pro.R

object MonitorNotification {
    const val CHANNEL = "charging_monitor_silent"
    const val ID = 1201

    /**
     * Persistent monitor notification. While the scooter draws current it shows a
     * determinate battery progress bar; otherwise it falls back to plain status text.
     */
    fun build(context: Context, message: String, socPercent: Int? = null, charging: Boolean = false): Notification {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Charging status (silent)", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            })
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(context, 1, Intent(context, ScooterMonitorService::class.java).setAction("io.ather.pro.STOP_MONITORING"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_charging)
            .setContentTitle(if (charging) "AtherMate · Charging" else "AtherMate · Monitor")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        if (charging) {
            builder.setColor(0xFF4ADE80.toInt())
            if (socPercent != null) builder.setProgress(100, socPercent.coerceIn(0, 100), false)
        }
        return builder.addAction(0, "Stop monitoring", stop).build()
    }
}
