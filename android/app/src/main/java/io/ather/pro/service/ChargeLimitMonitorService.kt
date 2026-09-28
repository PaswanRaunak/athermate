package io.ather.pro.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.ather.pro.data.auth.SecureSessionStore
import io.ather.pro.data.repository.AtherRepository
import io.ather.pro.domain.charging.ChargeLimitController
import io.ather.pro.util.ChargingNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground monitor while phone-app charge limit is enabled.
 * Keeps a persistent notification; does not invent firmware limits.
 */
class ChargeLimitMonitorService : Service() {

    private val notificationManager by lazy {
        ChargingNotificationManager.getInstance(applicationContext)
    }

    private val repository by lazy {
        AtherRepository.getInstance(applicationContext)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager.createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMonitoring()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                startMonitoring()
                return START_STICKY
            }
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        if (!repository.hasCredentials()) {
            SecureSessionStore(applicationContext).current()
                ?.takeIf { it.isComplete }
                ?.let { repository.applyCredentials(it.token, it.vehicleUuid) }
        }
        isRunning = true
        val snap = repository.chargeLimit.value
        promoteForeground(snap)

        observeJob?.cancel()
        observeJob = scope.launch {
            repository.chargeLimit.collectLatest { state ->
                if (!state.enabled) {
                    stopMonitoring()
                    return@collectLatest
                }
                updateNotification(state)
            }
        }
    }

    private fun promoteForeground(snap: ChargeLimitController.Snapshot) {
        val notification = notificationManager.buildChargeLimitNotification(
            percent = snap.percent,
            statusLabel = statusLabel(snap.status),
            detail = snap.message
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                ChargingNotificationManager.NOTIFICATION_ID_CHARGE_LIMIT,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(
                ChargingNotificationManager.NOTIFICATION_ID_CHARGE_LIMIT,
                notification
            )
        }
    }

    private fun updateNotification(snap: ChargeLimitController.Snapshot) {
        val notification = notificationManager.buildChargeLimitNotification(
            percent = snap.percent,
            statusLabel = statusLabel(snap.status),
            detail = snap.message
        )
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(ChargingNotificationManager.NOTIFICATION_ID_CHARGE_LIMIT, notification)
    }

    private fun stopMonitoring() {
        isRunning = false
        observeJob?.cancel()
        observeJob = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        notificationManager.cancelChargeLimitNotification()
        stopSelf()
    }

    override fun onDestroy() {
        isRunning = false
        observeJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "io.ather.pro.action.START_CHARGE_LIMIT_MONITOR"
        const val ACTION_STOP = "io.ather.pro.action.STOP_CHARGE_LIMIT_MONITOR"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, ChargeLimitMonitorService::class.java).apply {
                action = ACTION_START
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {
                // Background start may be restricted; UI still owns settings.
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ChargeLimitMonitorService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {
                // Already stopped
            }
        }

        private fun statusLabel(status: ChargeLimitController.Status): String = when (status) {
            ChargeLimitController.Status.DISABLED -> "Off"
            ChargeLimitController.Status.MONITORING -> "Monitoring"
            ChargeLimitController.Status.PENDING -> "Pending"
            ChargeLimitController.Status.CONFIRMED -> "Confirmed"
            ChargeLimitController.Status.ERROR -> "Error"
        }
    }
}
