package io.ather.pro.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.ather.pro.data.repository.AtherRepository
import io.ather.pro.util.ChargingNotificationManager

class ChargingMonitorService : Service() {

    private val notificationManager by lazy {
        ChargingNotificationManager.getInstance(applicationContext)
    }

    private val repository by lazy {
        AtherRepository.getInstance(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager.createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_MONITORING -> {
                stopMonitoring()
                return START_NOT_STICKY
            }
            ACTION_START_MONITORING, null -> {
                startMonitoring()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startMonitoring() {
        isRunning = true

        val currentTelemetry = repository.dashboard.value.telemetry
        val initialNotification = if (currentTelemetry != null && currentTelemetry.batterySoc != null) {
            notificationManager.buildProgressNotification(
                soc = currentTelemetry.batterySoc,
                timeToEighty = currentTelemetry.timeToEightyChargeMin,
                timeToFull = currentTelemetry.timeToFullChargeMin
            )
        } else {
            notificationManager.buildProgressNotification()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                ChargingNotificationManager.NOTIFICATION_ID_PROGRESS,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(
                ChargingNotificationManager.NOTIFICATION_ID_PROGRESS,
                initialNotification
            )
        }
    }

    private fun stopMonitoring() {
        isRunning = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_MONITORING = "io.ather.pro.action.START_CHARGING_MONITOR"
        const val ACTION_STOP_MONITORING = "io.ather.pro.action.STOP_CHARGING_MONITOR"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            if (isRunning) return
            val intent = Intent(context, ChargingMonitorService::class.java).apply {
                action = ACTION_START_MONITORING
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                // Ignore if background start restriction prevents launch
            }
        }

        fun stop(context: Context) {
            if (!isRunning) return
            val intent = Intent(context, ChargingMonitorService::class.java).apply {
                action = ACTION_STOP_MONITORING
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                // Ignore if service is already stopped
            }
        }
    }
}
