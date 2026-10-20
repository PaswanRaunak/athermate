package io.ather.pro.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.ather.pro.R
import io.ather.pro.ble.VehicleLinkController
import io.ather.pro.appContainer

/**
 * Foreground host for the vehicle BLE link, mirroring Ather's BLEServerService
 * (manifest: foregroundServiceType="connectedDevice"). The controller lives in
 * the app container; this service keeps the process alive while the link is
 * enabled and re-triggers reconnect after boot.
 */
class VehicleLinkService : Service() {

    inner class LocalBinder : Binder() { val service: VehicleLinkService get() = this@VehicleLinkService }

    private val binder = LocalBinder()
    private val controller: VehicleLinkController by lazy { appContainer.vehicleLink }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Vehicle link active"))
        controller.onServiceStarted()
        return START_STICKY
    }

    fun update(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_vehicle), NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "vehicle_link"
        private const val NOTIFICATION_ID = 47

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, VehicleLinkService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VehicleLinkService::class.java))
        }
    }
}
